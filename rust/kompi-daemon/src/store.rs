use anyhow::{Context, Result, bail};
use kompi_protocol::{ConversationId, IdleTimeoutSeconds};
use rand::RngCore;
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
};

#[derive(Clone, Debug)]
pub struct Device {
    pub endpoint: String,
    pub name: String,
    pub paired_at: i64,
    pub last_seen: Option<i64>,
    pub idle_timeout_seconds: Option<IdleTimeoutSeconds>,
}

#[derive(Clone, Debug)]
pub struct ConversationRecord {
    pub id: ConversationId,
    pub endpoint: String,
    pub project: String,
    pub project_session_id: String,
    pub session_id: Option<String>,
    pub session_file: String,
    pub session_name: Option<String>,
    pub idle_timeout_seconds: Option<IdleTimeoutSeconds>,
    pub created_at: i64,
    pub last_user_prompt_at: i64,
    pub state: ConversationState,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ConversationState {
    Idle,
    Busy,
    Closed,
    Expired,
    Unavailable,
}

/// Private daemon identity and filesystem paths. All mutable host state belongs in
/// `SqliteStore`; this type deliberately has no JSON state-file reader or writer.
#[derive(Clone)]
pub struct HostIdentity {
    directory: PathBuf,
    identity_path: PathBuf,
}

/// Proof of exclusive ownership of a state directory for this process lifetime.
/// Closing the file releases the kernel lock, including after an abrupt exit.
/// Never unlink the file: another opener must lock the same inode.
pub struct InstanceLock {
    _file: File,
}

impl HostIdentity {
    pub fn open(directory: impl AsRef<Path>) -> Result<(Self, InstanceLock)> {
        let directory = directory.as_ref().to_path_buf();
        fs::create_dir_all(&directory)
            .with_context(|| format!("create state directory {}", directory.display()))?;
        fs::set_permissions(&directory, fs::Permissions::from_mode(0o700))?;
        let lock = acquire_lock(&directory.join("instance.lock"))?;
        let store = Self {
            directory: directory.clone(),
            identity_path: directory.join("identity.key"),
        };
        store.ensure_identity()?;
        Ok((store, lock))
    }

    pub fn directory(&self) -> &Path {
        &self.directory
    }

    pub fn identity_seed(&self) -> Result<[u8; 32]> {
        let mut bytes = [0u8; 32];
        File::open(&self.identity_path)?.read_exact(&mut bytes)?;
        Ok(bytes)
    }

    fn ensure_identity(&self) -> Result<()> {
        match OpenOptions::new().read(true).open(&self.identity_path) {
            Ok(file) => {
                let metadata = file.metadata()?;
                if metadata.len() != 32 {
                    bail!("identity.key must be exactly 32 bytes; refusing to replace it");
                }
                file.set_permissions(fs::Permissions::from_mode(0o600))?;
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                let mut key = [0u8; 32];
                rand::rng().fill_bytes(&mut key);
                let mut file = OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .mode(0o600)
                    .open(&self.identity_path)
                    .context("create identity.key")?;
                file.write_all(&key)?;
                file.sync_all()?;
            }
            Err(error) => return Err(error.into()),
        }
        Ok(())
    }
}

pub fn validate_endpoint(value: &str) -> Result<()> {
    if value.len() != 64
        || !value
            .bytes()
            .all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())
    {
        bail!("endpoint must be 64 lowercase hexadecimal characters");
    }
    Ok(())
}

pub fn now_ms() -> i64 {
    jiff::Timestamp::now().as_millisecond()
}

pub fn control_socket_path(state_dir: &Path) -> PathBuf {
    state_dir.join("control.sock")
}

fn acquire_lock(path: &Path) -> Result<InstanceLock> {
    let mut file = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)
        .context("open instance.lock")?;
    match file.try_lock() {
        Ok(()) => {}
        Err(std::fs::TryLockError::WouldBlock) => {
            bail!("another Kompi daemon holds the state directory lock");
        }
        Err(std::fs::TryLockError::Error(error)) => {
            return Err(error).context("acquire exclusive instance.lock");
        }
    }
    // Metadata is diagnostic only. Write it after locking so a rejected second
    // daemon cannot truncate the owner's metadata. No PID probing is involved.
    file.set_permissions(fs::Permissions::from_mode(0o600))?;
    file.set_len(0)?;
    writeln!(
        file,
        "{{\"owner\":\"kompi-rust-daemon\",\"pid\":{}}}",
        std::process::id()
    )?;
    file.sync_all()?;
    Ok(InstanceLock { _file: file })
}
