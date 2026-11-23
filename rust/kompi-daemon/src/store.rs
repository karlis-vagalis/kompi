use anyhow::{Context, Result, bail};
use kompi_protocol::{ConversationId, IdleTimeoutSeconds};
use rand::RngCore;
use std::{
    fs::{File, OpenOptions},
    io::{Read, Write},
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
    _state_directory: crate::private_fs::PrivateDirectory,
}

impl HostIdentity {
    pub fn open(directory: impl AsRef<Path>) -> Result<(Self, InstanceLock)> {
        let directory = directory.as_ref().to_path_buf();
        let private = crate::private_fs::private_dir(&directory)
            .with_context(|| format!("create state directory {}", directory.display()))?;
        let lock = acquire_lock(&directory.join("instance.lock"), private)?;
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
        crate::private_fs::open_file(&self.identity_path, OpenOptions::new().read(true))?
            .read_exact(&mut bytes)?;
        Ok(bytes)
    }

    fn ensure_identity(&self) -> Result<()> {
        match crate::private_fs::open_file(&self.identity_path, OpenOptions::new().read(true)) {
            Ok(file) => {
                let metadata = file.metadata()?;
                if metadata.len() != 32 {
                    bail!("identity.key must be exactly 32 bytes; refusing to replace it");
                }
                crate::private_fs::private_file(&self.identity_path)?;
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                let mut key = [0u8; 32];
                rand::rng().fill_bytes(&mut key);
                let mut file = crate::private_fs::open_file(
                    &self.identity_path,
                    OpenOptions::new().write(true).create_new(true),
                )
                .context("create identity.key")?;
                crate::private_fs::private_file(&self.identity_path)?;
                file.write_all(&key)?;
                file.sync_all()?;
            }
            Err(error) => return Err(error.into()),
        }
        Ok(())
    }
}

#[cfg(test)]
mod identity_tests {
    use super::*;

    #[test]
    fn identity_survives_restart_and_lock_excludes_second_daemon() {
        let dir = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        let (identity, lease) = HostIdentity::open(&dir).unwrap();
        let key = identity.identity_seed().unwrap();
        assert!(HostIdentity::open(&dir).is_err());
        drop(lease);
        let (identity, lease) = HostIdentity::open(&dir).unwrap();
        assert_eq!(identity.identity_seed().unwrap(), key);
        drop(lease);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[cfg(windows)]
    #[test]
    fn state_path_must_not_cross_junction() {
        let base = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        let target = base.join("outside");
        let junction = base.join("junction");
        std::fs::create_dir_all(&target).unwrap();
        let status = std::process::Command::new("cmd.exe")
            .current_dir(&base)
            .args(["/d", "/c", "mklink", "/j", "junction", "outside"])
            .status()
            .unwrap();
        assert!(status.success());
        assert!(HostIdentity::open(junction.join("state")).is_err());
        assert!(!target.join("state").exists());
        std::fs::remove_dir(junction).unwrap();
        std::fs::remove_dir_all(base).unwrap();
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
    #[cfg(windows)]
    return crate::local_ipc::pipe_path(state_dir);
    #[cfg(unix)]
    state_dir.join("control.sock")
}

fn acquire_lock(
    path: &Path,
    state_directory: crate::private_fs::PrivateDirectory,
) -> Result<InstanceLock> {
    let mut file = crate::private_fs::open_file(
        path,
        OpenOptions::new().write(true).create(true).truncate(false),
    )
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
    crate::private_fs::private_file(path)?;
    file.set_len(0)?;
    writeln!(
        file,
        "{{\"owner\":\"kompi-rust-daemon\",\"pid\":{}}}",
        std::process::id()
    )?;
    file.sync_all()?;
    Ok(InstanceLock {
        _file: file,
        _state_directory: state_directory,
    })
}
