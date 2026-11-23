use super::*;
use std::{
    mem::MaybeUninit,
    os::windows::{
        fs::{MetadataExt, OpenOptionsExt},
        io::AsRawHandle,
    },
    path::{Component, Prefix},
};
use windows_sys::Win32::{Foundation::*, Storage::FileSystem::*};

#[cfg(test)]
#[path = "windows_tests.rs"]
mod tests;

fn invalid() -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, "unsafe Pi history path")
}
pub(crate) fn valid_name(name: &OsStr) -> bool {
    name.to_str().is_some_and(|s| {
        !s.is_empty()
            && !s.ends_with(['.', ' '])
            && !s.contains([':', '/', '\\', '<', '>', '"', '|', '?', '*'])
            && !s.chars().any(char::is_control)
            && {
                let base = s
                    .split('.')
                    .next()
                    .unwrap_or("")
                    .trim_end()
                    .to_ascii_uppercase();
                !matches!(
                    base.as_str(),
                    "AUX" | "CON" | "NUL" | "PRN" | "CONIN$" | "CONOUT$"
                ) && !["LPT", "COM"].iter().any(|p| {
                    base.strip_prefix(p).is_some_and(|n| {
                        n.len() == 1 && n.as_bytes()[0].is_ascii_digit()
                            || matches!(n, "¹" | "²" | "³")
                    })
                })
            }
    })
}
fn plain(file: &File, dir: bool) -> io::Result<()> {
    let m = file.metadata()?;
    if m.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0
        || (if dir { !m.is_dir() } else { !m.is_file() })
    {
        Err(invalid())
    } else {
        Ok(())
    }
}
/// Keep every component immovable and non-reparse for subsequent path-based opens.
pub(crate) fn pin_directory(path: &Path) -> io::Result<Vec<File>> {
    let mut parts = path.components();
    let Some(Component::Prefix(p)) = parts.next() else {
        return Err(invalid());
    };
    if !matches!(p.kind(), Prefix::Disk(_) | Prefix::VerbatimDisk(_))
        || parts.next() != Some(Component::RootDir)
    {
        return Err(invalid());
    }
    let mut prefix = PathBuf::from(p.as_os_str());
    prefix.push("\\");
    let mut pins = Vec::new();
    loop {
        let f = OpenOptions::new()
            // A metadata-only handle is exempt from Windows sharing checks. Request
            // directory read access so a later DELETE/write open genuinely conflicts.
            .access_mode(FILE_LIST_DIRECTORY | FILE_READ_ATTRIBUTES)
            .share_mode(FILE_SHARE_READ)
            .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS)
            .open(&prefix)?;
        plain(&f, true)?;
        pins.push(f);
        match parts.next() {
            None => break,
            Some(Component::Normal(n)) if valid_name(n) => prefix.push(n),
            _ => return Err(invalid()),
        }
    }
    Ok(pins)
}
fn parent(path: &Path) -> io::Result<Vec<File>> {
    if !path.file_name().is_some_and(valid_name) {
        return Err(invalid());
    }
    pin_directory(path.parent().ok_or_else(invalid)?)
}
fn info(f: &File) -> io::Result<(FileIdentity, u32)> {
    let mut data = MaybeUninit::uninit();
    if unsafe { GetFileInformationByHandle(f.as_raw_handle(), data.as_mut_ptr()) } == 0 {
        return Err(io::Error::last_os_error());
    }
    let data = unsafe { data.assume_init() };
    Ok((
        FileIdentity {
            device: data.dwVolumeSerialNumber.into(),
            inode: (u64::from(data.nFileIndexHigh) << 32) | u64::from(data.nFileIndexLow),
        },
        data.nNumberOfLinks,
    ))
}
pub(crate) fn read_file(path: &Path) -> io::Result<File> {
    let _pins = parent(path)?;
    let f = OpenOptions::new()
        .read(true)
        .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT)
        .open(path)?;
    plain(&f, false)?;
    if info(&f)?.1 != 1 {
        return Err(invalid());
    }
    Ok(f)
}
impl Catalog {
    pub fn scan(&mut self, roots: &SessionRoots) -> Result<Vec<Transcript>> {
        let mut seen = HashSet::new();
        let mut result = HashMap::new();
        let mut duplicates = HashSet::new();
        for (base, flat) in std::iter::once((&roots.standard, false))
            .chain(roots.configured.iter().map(|p| (p, true)))
        {
            let _base = match pin_directory(base) {
                Ok(p) => p,
                Err(e) if e.kind() == io::ErrorKind::NotFound => continue,
                Err(e) => return Err(e.into()),
            };
            let mut dirs = Vec::new();
            if flat {
                dirs.push(base.clone())
            } else {
                for e in fs::read_dir(base)? {
                    let e = e?;
                    if e.file_type()?.is_dir() {
                        dirs.push(e.path())
                    }
                }
            }
            for dir in dirs {
                let Ok(_dir) = pin_directory(&dir) else {
                    continue;
                };
                for e in fs::read_dir(&dir)? {
                    let path = e?.path();
                    if path.extension() != Some(OsStr::new("jsonl")) || !seen.insert(path.clone()) {
                        continue;
                    }
                    let Ok(f) = read_file(&path) else { continue };
                    let metadata = f.metadata()?;
                    let (identity, _) = info(&f)?;
                    let mut basic = FILE_BASIC_INFO::default();
                    if unsafe {
                        GetFileInformationByHandleEx(
                            f.as_raw_handle(),
                            FileBasicInfo,
                            (&mut basic as *mut FILE_BASIC_INFO).cast(),
                            size_of_val(&basic) as u32,
                        )
                    } == 0
                    {
                        return Err(io::Error::last_os_error().into());
                    }
                    let revision = Revision {
                        identity,
                        length: metadata.len(),
                        modified: (basic.LastWriteTime, 0),
                        changed: (basic.ChangeTime, 0),
                    };
                    let summary = match self.entries.get(&path).filter(|(old, _)| old == &revision)
                    {
                        Some((_, t)) => t.clone(),
                        None => {
                            let t = summarize(f, &path, &metadata, identity)?;
                            self.entries.insert(path, (revision, t.clone()));
                            t
                        }
                    };
                    if let Some(t) = summary {
                        let id = t.id;
                        if result.insert(id, t).is_some() {
                            duplicates.insert(id);
                        }
                    }
                }
            }
        }
        self.entries.retain(|p, _| seen.contains(p));
        result.retain(|id, _| !duplicates.contains(id));
        Ok(result.into_values().collect())
    }
}
fn conflict(e: io::Error) -> LockError {
    if e.raw_os_error() == Some(ERROR_SHARING_VIOLATION as i32) {
        LockError::InUse
    } else {
        e.into()
    }
}
fn sidecar(path: &Path) -> io::Result<File> {
    let mut name = path.as_os_str().to_owned();
    name.push(LOCK_SUFFIX);
    let f = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .share_mode(0)
        .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT)
        .open(name)?;
    plain(&f, false)?;
    if info(&f)?.1 != 1 {
        return Err(invalid());
    }
    Ok(f)
}
/// Install non-inheritable leases in the Pi process itself. They survive worker/helper death
/// and Windows releases them only when that writer exits.
pub fn worker_lease() -> Result<()> {
    use std::os::windows::io::{FromRawHandle, OwnedHandle};
    use windows_sys::Win32::System::Threading::{
        GetCurrentProcess, OpenProcess, PROCESS_DUP_HANDLE,
    };
    let mut args = std::env::args_os().skip(2);
    let pid: u32 = args
        .next()
        .and_then(|n| n.to_str()?.parse().ok())
        .context("missing owner")?;
    let path = PathBuf::from(args.next().context("missing transcript")?);
    ensure!(
        path.extension() == Some(OsStr::new("jsonl")),
        "invalid transcript"
    );
    let mut handles = parent(&path)?;
    handles.push(sidecar(&path)?);
    let cwd = match args.next().as_deref().and_then(OsStr::to_str) {
        Some("fresh") => {
            ensure!(
                fs::symlink_metadata(&path).is_err_and(|e| e.kind() == io::ErrorKind::NotFound),
                "fresh transcript exists"
            );
            None
        }
        Some("resume") => {
            let f = OpenOptions::new()
                .read(true)
                .share_mode(FILE_SHARE_READ | FILE_SHARE_WRITE)
                .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT)
                .open(&path)?;
            plain(&f, false)?;
            ensure!(info(&f)?.1 == 1, "aliased transcript");
            let mut line = Vec::new();
            ensure!(
                record(&mut BufReader::new(&f), &mut line, MAX_HEADER)?,
                "missing header"
            );
            let h = Header::parse(&line).context("invalid header")?;
            if let Some(id) = args.next() {
                ensure!(id.to_str() == Some(&h.id.to_string()), "session mismatch")
            };
            handles.push(f);
            Some(h.cwd)
        }
        _ => anyhow::bail!("invalid lease mode"),
    };
    let target = unsafe { OpenProcess(PROCESS_DUP_HANDLE, 0, pid) };
    if target.is_null() {
        return Err(io::Error::last_os_error().into());
    }
    let target = unsafe { OwnedHandle::from_raw_handle(target) };
    for h in handles {
        let mut remote = std::ptr::null_mut();
        if unsafe {
            DuplicateHandle(
                GetCurrentProcess(),
                h.as_raw_handle(),
                target.as_raw_handle(),
                &mut remote,
                0,
                0,
                DUPLICATE_SAME_ACCESS,
            )
        } == 0
        {
            return Err(io::Error::last_os_error().into());
        }
    }
    println!("{}", serde_json::to_string(&cwd)?);
    Ok(())
}
pub(crate) struct LockedTranscript {
    _pins: Vec<File>,
    _lease: File,
    file: File,
    pub identity: FileIdentity,
}
impl LockedTranscript {
    pub fn discover(root: &SessionRoots, t: &Transcript) -> Result<Self, LockError> {
        Self::open(root, &t.path, t.id, t.identity)
    }
    pub fn open(
        root: &SessionRoots,
        path: &Path,
        id: Uuid,
        expected: FileIdentity,
    ) -> Result<Self, LockError> {
        let dir = path.parent().ok_or(LockError::IdentityChanged)?;
        if path.extension() != Some(OsStr::new("jsonl"))
            || !(root.configured.as_deref() == Some(dir) || dir.parent() == Some(&root.standard))
        {
            return Err(LockError::IdentityChanged);
        }
        let mut pins = parent(path)?;
        let lease = sidecar(path).map_err(conflict)?;
        let file = match OpenOptions::new()
            .access_mode(GENERIC_READ | DELETE)
            .share_mode(0)
            .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT)
            .open(path)
        {
            Ok(f) => f,
            Err(e) if e.kind() == io::ErrorKind::NotFound => {
                return Err(LockError::Missing(pins.pop().unwrap()));
            }
            Err(e) => return Err(conflict(e)),
        };
        plain(&file, false)?;
        let (identity, links) = info(&file)?;
        let mut line = Vec::new();
        if links != 1
            || identity != expected
            || !record(&mut BufReader::new(&file), &mut line, MAX_HEADER)?
            || Header::parse(&line).is_none_or(|h| h.id != id)
        {
            return Err(LockError::IdentityChanged);
        }
        Ok(Self {
            _pins: pins,
            _lease: lease,
            file,
            identity,
        })
    }
    pub fn unlink(self) -> Result<()> {
        let data = FILE_DISPOSITION_INFO { DeleteFile: true };
        if unsafe {
            SetFileInformationByHandle(
                self.file.as_raw_handle(),
                FileDispositionInfo,
                (&data as *const FILE_DISPOSITION_INFO).cast(),
                size_of_val(&data) as u32,
            )
        } == 0
        {
            return Err(io::Error::last_os_error().into());
        }
        Ok(())
    }
}
