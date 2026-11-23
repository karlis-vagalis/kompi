//! Read-only Pi v3 catalog, with a separate locked capability for deletion.
//! Pi's default picker discovers `getAgentDir()/sessions/<encoded cwd>/*.jsonl`.
use anyhow::{Context, Result, ensure};
use kompi_protocol::ConversationId;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    ffi::{CStr, CString, OsStr},
    fs::{self, File, Metadata, OpenOptions},
    io::{self, BufRead, BufReader},
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::{
            ffi::OsStrExt,
            fs::{MetadataExt, OpenOptionsExt},
        },
    },
    path::{Path, PathBuf},
};
use uuid::Uuid;

const MAX_HEADER: usize = 64 * 1024;
// Shared with the Pi RPC adapter and the documented terminal wrapper.
const LOCK_SUFFIX: &str = ".kompi-lock";
const MAX_RECORD: usize = 256 * 1024;
const TITLE_CHARS: usize = 160;

#[derive(Clone, Debug)]
pub(crate) struct Transcript {
    pub id: Uuid,
    pub path: PathBuf,
    pub cwd: PathBuf,
    pub title: Option<String>,
    pub created: i64,
    pub activity: i64,
    identity: FileIdentity,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct FileIdentity {
    pub device: u64,
    pub inode: u64,
}

impl From<&Metadata> for FileIdentity {
    fn from(metadata: &Metadata) -> Self {
        Self {
            device: metadata.dev(),
            inode: metadata.ino(),
        }
    }
}

/// Phone conversation keys are peer-scoped; the Pi header UUID itself is never
/// changed. Switching from unassociated to associated keeps the same wire key.
pub(crate) fn conversation_id(peer: &str, id: Uuid) -> ConversationId {
    let mut hash = Sha256::new();
    hash.update(b"kompi-host-history-v1\0");
    hash.update(peer);
    hash.update(id.as_bytes());
    uuid::Builder::from_custom_bytes(hash.finalize()[..16].try_into().expect("UUID length"))
        .into_uuid()
        .into()
}

pub(crate) struct SessionRoots {
    standard: PathBuf,
    configured: Option<PathBuf>,
}

pub(crate) fn sessions_root(home: &Path) -> Result<SessionRoots> {
    use std::io::Read;
    fn expanded(home: &Path, path: &Path) -> PathBuf {
        home.join(path.strip_prefix("~").unwrap_or(path))
    }
    fn resolved(path: PathBuf) -> Result<PathBuf> {
        match fs::canonicalize(&path) {
            Ok(path) => Ok(path),
            Err(err) if err.kind() == io::ErrorKind::NotFound => Ok(path),
            Err(err) => Err(err.into()),
        }
    }
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct PiSettings {
        session_dir: Option<PathBuf>,
    }
    let agent = std::env::var_os("PI_CODING_AGENT_DIR")
        .filter(|path| !path.is_empty())
        .map(|path| expanded(home, Path::new(&path)))
        .unwrap_or_else(|| home.join(".pi/agent"));
    // The ordinary Pi picker accepts a sessionDir override, a flat JSONL
    // directory. Read only the host's configured location, never a client path.
    let custom = std::env::var_os("PI_CODING_AGENT_SESSION_DIR")
        .filter(|path| !path.is_empty())
        .map(PathBuf::from)
        .or_else(|| {
            let settings = OpenOptions::new()
                .read(true)
                .custom_flags(libc::O_NONBLOCK)
                .open(agent.join("settings.json"))
                .ok()?;
            if !settings.metadata().ok()?.is_file() {
                return None;
            }
            serde_json::from_reader::<_, PiSettings>(settings.take(1024 * 1024))
                .ok()?
                .session_dir
        });
    let configured = custom
        .filter(|path| !path.as_os_str().is_empty())
        .map(|path| resolved(expanded(home, &path)))
        .transpose()?;
    Ok(SessionRoots {
        standard: resolved(agent.join("sessions"))?,
        configured,
    })
}

#[derive(Debug, PartialEq, Eq)]
struct Revision {
    identity: FileIdentity,
    length: u64,
    modified: (i64, i64),
    changed: (i64, i64),
}

impl From<&Metadata> for Revision {
    fn from(metadata: &Metadata) -> Self {
        Self {
            identity: metadata.into(),
            length: metadata.len(),
            modified: (metadata.mtime(), metadata.mtime_nsec()),
            changed: (metadata.ctime(), metadata.ctime_nsec()),
        }
    }
}

#[derive(Default)]
pub(crate) struct Catalog {
    entries: HashMap<PathBuf, (Revision, Option<Transcript>)>,
}

impl Catalog {
    pub fn scan(&mut self, roots: &SessionRoots) -> Result<Vec<Transcript>> {
        fn present(path: &Path) -> io::Result<Option<File>> {
            match open_directory(path) {
                Ok(dir) => Ok(Some(dir)),
                Err(err) if err.kind() == io::ErrorKind::NotFound => Ok(None),
                Err(err) => Err(err),
            }
        }
        let mut visited = HashSet::new();
        let mut found = Vec::new();
        if let Some(dir) = present(&roots.standard)? {
            for project in fs::read_dir(fd_path(&dir))? {
                let project = project?;
                if !project.file_type()?.is_dir() {
                    continue;
                }
                let name = project.file_name();
                let project_dir = match open_at(&dir, &name, libc::O_DIRECTORY) {
                    Ok(dir) => dir,
                    Err(err) if ephemeral(&err) => continue,
                    Err(err) => return Err(err.into()),
                };
                self.summaries(
                    &project_dir,
                    &roots.standard.join(name),
                    &mut visited,
                    &mut found,
                )?;
            }
        }
        if let Some(configured) = &roots.configured {
            if let Some(dir) = present(configured)? {
                self.summaries(&dir, configured, &mut visited, &mut found)?;
            }
        }
        self.entries.retain(|path, _| visited.contains(path));
        let mut transcripts = HashMap::new();
        let mut duplicates = HashSet::new();
        for transcript in found {
            let id = transcript.id;
            if transcripts.insert(id, transcript).is_some() {
                duplicates.insert(id);
            }
        }
        transcripts.retain(|id, _| !duplicates.contains(id));
        Ok(transcripts.into_values().collect())
    }

    fn summaries(
        &mut self,
        dir: &File,
        directory: &Path,
        visited: &mut HashSet<PathBuf>,
        found: &mut Vec<Transcript>,
    ) -> Result<()> {
        for entry in fs::read_dir(fd_path(dir))? {
            let entry = entry?;
            let name = entry.file_name();
            if Path::new(&name).extension() != Some(OsStr::new("jsonl"))
                || !entry.file_type()?.is_file()
            {
                continue;
            }
            let path = directory.join(&name);
            if !visited.insert(path.clone()) {
                continue;
            }
            let file = match open_at(dir, &name, 0) {
                Ok(file) => file,
                Err(err) if ephemeral(&err) => continue,
                Err(err) => return Err(err.into()),
            };
            let metadata = file.metadata()?;
            if !metadata.is_file() {
                continue;
            }
            let revision = Revision::from(&metadata);
            let transcript = match self.entries.get(&path).filter(|(old, _)| old == &revision) {
                Some((_, cached)) => cached.clone(),
                None => {
                    let parsed = summarize(file, &path, &metadata)?;
                    self.entries.insert(path, (revision, parsed.clone()));
                    parsed
                }
            };
            found.extend(transcript);
        }
        Ok(())
    }
}

#[derive(Deserialize)]
struct Header {
    #[serde(rename = "type")]
    kind: String,
    id: Uuid,
    version: u32,
    cwd: PathBuf,
    timestamp: jiff::Timestamp,
}

impl Header {
    fn parse(line: &[u8]) -> Option<Self> {
        let header: Self = serde_json::from_slice(line).ok()?;
        (header.kind == "session" && header.version == 3 && header.cwd.is_absolute())
            .then_some(header)
    }
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum Record {
    SessionInfo {
        name: Option<String>,
    },
    Message {
        message: Message,
        timestamp: Option<jiff::Timestamp>,
    },
    #[serde(other)]
    Other,
}

#[derive(Deserialize)]
struct Message {
    role: String,
    #[serde(default)]
    content: Content,
    timestamp: Option<i64>,
}

#[derive(Deserialize, Default)]
#[serde(untagged)]
enum Content {
    Text(String),
    Blocks(Vec<Block>),
    #[default]
    Other,
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum Block {
    Text {
        text: String,
    },
    #[serde(other)]
    Other,
}

fn compact_title(text: &str) -> Option<String> {
    let compact = text
        .split_whitespace()
        .filter(|part| part.chars().any(|c| !c.is_control()))
        .collect::<Vec<_>>()
        .join(" ");
    let mut kept = compact.chars().filter(|c| !c.is_control());
    let mut title: String = kept.by_ref().take(TITLE_CHARS).collect();
    if !title.is_empty() {
        if kept.next().is_some() {
            title.push('…');
        }
        Some(title)
    } else {
        None
    }
}

fn summarize(file: File, path: &Path, metadata: &Metadata) -> Result<Option<Transcript>> {
    use std::io::Read;
    // Don't chase a writer appending during the scan. A trailing partial record
    // is ignored; the next stat revision invalidates the cached summary.
    let mut reader = BufReader::new(file.take(metadata.len()));
    let mut line = Vec::new();
    if !record(&mut reader, &mut line, MAX_HEADER)? {
        return Ok(None);
    }
    let Some(header) = Header::parse(&line) else {
        return Ok(None);
    };
    let mut name = None;
    let mut first_user = None;
    let created = header.timestamp.as_millisecond();
    let mut activity = created;
    while record(&mut reader, &mut line, MAX_RECORD)? {
        match serde_json::from_slice::<Record>(&line) {
            Ok(Record::SessionInfo { name: next }) => {
                name = next
                    .as_deref()
                    .map(str::trim)
                    .filter(|value| !value.is_empty())
                    .map(|value| value.chars().take(512).collect())
            }
            Ok(Record::Message { message, timestamp }) => {
                if let Some(at) = message
                    .timestamp
                    .or_else(|| timestamp.map(|t| t.as_millisecond()))
                {
                    if jiff::Timestamp::from_millisecond(at).is_ok() {
                        activity = activity.max(at);
                    }
                }
                if first_user.is_none() && message.role == "user" {
                    first_user = match message.content {
                        Content::Text(text) => compact_title(&text),
                        Content::Blocks(blocks) => compact_title(
                            &blocks
                                .into_iter()
                                .filter_map(|block| match block {
                                    Block::Text { text } => Some(text),
                                    Block::Other => None,
                                })
                                .collect::<Vec<_>>()
                                .join(" "),
                        ),
                        Content::Other => None,
                    };
                }
            }
            _ => {}
        }
    }
    Ok(Some(Transcript {
        id: header.id,
        path: path.to_owned(),
        cwd: header.cwd,
        title: name.or(first_user),
        created,
        activity,
        identity: metadata.into(),
    }))
}

/// Discard an oversized row without allocating its full contents. A later
/// session_info row can still name a transcript with large tool/image records.
fn record(reader: &mut impl BufRead, line: &mut Vec<u8>, limit: usize) -> io::Result<bool> {
    line.clear();
    let mut oversized = false;
    loop {
        let available = reader.fill_buf()?;
        if available.is_empty() {
            return Ok(false);
        }
        let newline = available.iter().position(|byte| *byte == b'\n');
        let count = newline.map_or(available.len(), |index| index + 1);
        oversized |= line.len().saturating_add(count) > limit;
        if !oversized {
            line.extend_from_slice(&available[..count]);
        }
        reader.consume(count);
        if newline.is_some() {
            if oversized {
                line.clear();
            }
            return Ok(true);
        }
    }
}

fn fd_path(file: &File) -> PathBuf {
    PathBuf::from(format!("/proc/self/fd/{}", file.as_raw_fd()))
}

fn open_directory(path: &Path) -> io::Result<File> {
    OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(path)
}

fn open_at(parent: &File, name: &OsStr, flags: libc::c_int) -> io::Result<File> {
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

fn ephemeral(error: &io::Error) -> bool {
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

#[derive(Debug)]
pub(crate) enum LockError {
    Missing(File),
    InUse,
    IdentityChanged,
    Other(anyhow::Error),
}

impl From<io::Error> for LockError {
    fn from(error: io::Error) -> Self {
        Self::Other(error.into())
    }
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
