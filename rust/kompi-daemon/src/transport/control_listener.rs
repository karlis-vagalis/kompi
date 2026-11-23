use std::{io, time::Duration};

fn recoverable(error: &io::Error) -> bool {
    #[cfg(unix)]
    if matches!(
        error.raw_os_error(),
        Some(libc::EMFILE | libc::ENFILE | libc::ENOBUFS | libc::ENOMEM)
    ) {
        return true;
    }
    matches!(
        error.kind(),
        io::ErrorKind::Interrupted | io::ErrorKind::WouldBlock | io::ErrorKind::ConnectionAborted
    )
}

pub(super) async fn accept_with_retry<T>(
    mut accept: impl AsyncFnMut() -> io::Result<T>,
) -> io::Result<T> {
    let mut delay = Duration::from_millis(10);
    loop {
        match accept().await {
            Err(error) if recoverable(&error) => {
                if delay == Duration::from_millis(10) {
                    eprintln!("Local control accept failed; retrying: {error}");
                }
                tokio::time::sleep(delay).await;
                delay = (delay * 2).min(Duration::from_secs(1));
            }
            result => return result,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn accept_recovers_then_delivers_next_stream() {
        let mut attempts = [
            Err(io::ErrorKind::Interrupted.into()),
            Err(io::ErrorKind::ConnectionAborted.into()),
            Ok(42),
        ]
        .into_iter();
        let start = tokio::time::Instant::now();
        assert_eq!(
            accept_with_retry(async || attempts.next().unwrap())
                .await
                .unwrap(),
            42
        );
        assert!(start.elapsed() >= Duration::from_millis(30));
    }

    #[tokio::test]
    async fn fatal_accept_stops_daemon_and_releases_control_address() -> anyhow::Result<()> {
        use super::super::{Daemon, HostIdentity, SqliteStore};
        let directory = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        let (identity, lock) = HostIdentity::open(&directory)?;
        let sqlite = SqliteStore::open(&directory).await?;
        let (running, state) = tokio::sync::watch::channel(true);
        let daemon = Daemon::new(identity, sqlite.clone(), running).await?;
        let path = daemon.control_path.clone();
        let error = daemon
            .run_with_accept(async |_| Err(io::Error::other("injected terminal failure")))
            .await
            .unwrap_err();
        assert!(
            format!("{error:#}")
                .contains("local control listener failed: injected terminal failure")
        );
        assert!(!*state.borrow());
        #[cfg(unix)]
        assert!(!path.exists());
        drop(crate::local_ipc::Listener::bind(&path)?);
        drop(lock);
        drop(sqlite);
        // SQLite closes its file handles asynchronously on Windows.
        for attempt in 0..50 {
            match std::fs::remove_dir_all(&directory) {
                Ok(()) => break,
                Err(_) if attempt < 49 => tokio::time::sleep(Duration::from_millis(20)).await,
                Err(error) => return Err(error.into()),
            }
        }
        Ok(())
    }

    #[tokio::test]
    async fn accept_returns_terminal_error_instead_of_retrying() {
        let mut attempts = [Err::<(), _>(io::Error::from(
            io::ErrorKind::PermissionDenied,
        ))]
        .into_iter();
        assert_eq!(
            accept_with_retry(async || attempts.next().unwrap())
                .await
                .unwrap_err()
                .kind(),
            io::ErrorKind::PermissionDenied
        );
    }
}
