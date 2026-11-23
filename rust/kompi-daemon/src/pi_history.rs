//! Read-only Pi v3 catalog, with a separate locked capability for deletion.
//! Pi's default picker discovers `getAgentDir()/sessions/<encoded cwd>/*.jsonl`.
use anyhow::{Context, Result, ensure};
use kompi_protocol::ConversationId;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    ffi::OsStr,
    fs::{self, File, Metadata, OpenOptions},
    io::{self, BufRead, BufReader},
    path::{Path, PathBuf},
};
use uuid::Uuid;
#[cfg(unix)]
mod unix;
#[cfg(unix)]
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
#[cfg(unix)]
pub(crate) use unix::LockedTranscript;
#[cfg(unix)]
use unix::*;
#[cfg(windows)]
pub(crate) mod windows;
#[cfg(windows)]
pub(crate) use windows::LockedTranscript;

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

#[cfg(unix)]
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
        #[cfg(windows)]
        return Ok(path);
        #[cfg(unix)]
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
            #[cfg(windows)]
            let settings = windows::read_file(&agent.join("settings.json")).ok()?;
            #[cfg(unix)]
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

#[cfg(unix)]
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

#[cfg(unix)]
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
                    let parsed = summarize(file, &path, &metadata, revision.identity)?;
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

fn summarize(
    file: File,
    path: &Path,
    metadata: &Metadata,
    identity: FileIdentity,
) -> Result<Option<Transcript>> {
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
        identity,
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
