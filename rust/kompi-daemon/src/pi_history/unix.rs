use super::*;
use std::ffi::{CStr, CString};
use std::os::{
    fd::{AsRawFd, FromRawFd},
    unix::{
        ffi::OsStrExt,
        fs::{MetadataExt, OpenOptionsExt},
    },
};
pub(super) fn fd_path(file: &File) -> PathBuf {
    PathBuf::from(format!("/proc/self/fd/{}", file.as_raw_fd()))
}

pub(super) fn open_directory(path: &Path) -> io::Result<File> {
    OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(path)
}

pub(super) fn open_at(parent: &File, name: &OsStr, flags: libc::c_int) -> io::Result<File> {
    let name = CString::new(name.as_bytes())?;
    // SAFETY: `parent` owns a live directory fd and `name` is NUL-terminated.
    let fd = unsafe {
        libc::openat(
            parent.as_raw_fd(),
            name.as_ptr(),
            libc::O_CLOEXEC | libc::O_NOFOLLOW | libc::O_NONBLOCK | flags,
            0o600 as libc::mode_t,
        )
    };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: openat returned a fresh fd, transferred to File exactly once.
    Ok(unsafe { File::from_raw_fd(fd) })
}

pub(super) fn ephemeral(error: &io::Error) -> bool {
    matches!(
        error.raw_os_error(),
        Some(libc::ENOENT | libc::ELOOP | libc::ENOTDIR)
    )
}

pub(crate) struct LockedTranscript {
    _lease: File,
    file: File,
    parent: File,
    name: CString,
    pub identity: FileIdentity,
}

fn lock_file(file: &File) -> Result<(), LockError> {
    // SAFETY: `file` retains the live fd through the syscall and operation.
    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0 {
        return Ok(());
    }
    let error = io::Error::last_os_error();
    Err(if error.kind() == io::ErrorKind::WouldBlock {
        LockError::InUse
    } else {
        error.into()
    })
}

impl LockedTranscript {
    pub fn discover(root: &SessionRoots, transcript: &Transcript) -> Result<Self, LockError> {
        Self::open(root, &transcript.path, transcript.id, transcript.identity)
    }

    pub fn open(
        root: &SessionRoots,
        path: &Path,
        id: Uuid,
        expected: FileIdentity,
    ) -> Result<Self, LockError> {
        use std::path::Component::Normal;
        let checked_dir = |error: io::Error| {
            if ephemeral(&error) {
                LockError::IdentityChanged
            } else {
                error.into()
            }
        };
        let (parent, name) = if let Some(flat) = root
            .configured
            .as_ref()
            .filter(|flat| path.parent() == Some(flat))
        {
            let name = path.file_name().ok_or(LockError::IdentityChanged)?;
            (open_directory(flat).map_err(checked_dir)?, name)
        } else {
            let relative = path
                .strip_prefix(&root.standard)
                .map_err(|_| LockError::IdentityChanged)?;
            let mut parts = relative.components();
            let (Some(Normal(project)), Some(Normal(name)), None) =
                (parts.next(), parts.next(), parts.next())
            else {
                return Err(LockError::IdentityChanged);
            };
            let base = open_directory(&root.standard).map_err(checked_dir)?;
            (
                open_at(&base, project, libc::O_DIRECTORY).map_err(checked_dir)?,
                name,
            )
        };
        if Path::new(name).extension() != Some(OsStr::new("jsonl")) {
            return Err(LockError::IdentityChanged);
        }
        let file = match open_at(&parent, name, 0) {
            Ok(file) => file,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                return Err(LockError::Missing(parent));
            }
            Err(error) => return Err(error.into()),
        };
        let mut lease_name = name.to_os_string();
        lease_name.push(LOCK_SUFFIX);
        let lease = open_at(&parent, &lease_name, libc::O_RDWR | libc::O_CREAT)?;
        let lease_meta = lease.metadata()?;
        if !lease_meta.is_file() || lease_meta.nlink() != 1 {
            return Err(LockError::IdentityChanged);
        }
        lock_file(&lease)?;
        if FileIdentity::from(&open_at(&parent, &lease_name, 0)?.metadata()?)
            != FileIdentity::from(&lease_meta)
        {
            return Err(LockError::IdentityChanged);
        }
        lock_file(&file)?;
        let metadata = file.metadata()?;
        let identity = FileIdentity::from(&metadata);
        if identity != expected || !metadata.is_file() || metadata.nlink() != 1 {
            return Err(LockError::IdentityChanged);
        }
        let mut line = Vec::new();
        if !record(&mut BufReader::new(&file), &mut line, MAX_HEADER)?
            || Header::parse(&line).is_none_or(|header| header.id != id)
        {
            return Err(LockError::IdentityChanged);
        }
        Ok(Self {
            _lease: lease,
            file,
            parent,
            name: CString::new(name.as_bytes()).map_err(|_| LockError::IdentityChanged)?,
            identity,
        })
    }

    pub fn unlink(self) -> Result<()> {
        // Keep the locked inode alive, and re-open relative to the same pinned
        // parent to detect a stale name before the only destructive syscall.
        let current = open_at(
            &self.parent,
            OsStr::from_bytes(CStr::to_bytes(&self.name)),
            0,
        )?;
        ensure!(
            FileIdentity::from(&current.metadata()?) == self.identity
                && self.file.metadata()?.nlink() == 1,
            "transcript changed before removal"
        );
        // SAFETY: live parent fd and NUL-terminated single-component name.
        if unsafe { libc::unlinkat(self.parent.as_raw_fd(), self.name.as_ptr(), 0) } != 0 {
            return Err(io::Error::last_os_error()).context("remove Pi transcript");
        }
        self.parent.sync_all().context("sync Pi session directory")
    }
}
