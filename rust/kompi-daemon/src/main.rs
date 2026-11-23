#[tokio::main]
async fn main() {
    #[cfg(windows)]
    if std::env::args_os().nth(1).as_deref() == Some(std::ffi::OsStr::new("__pi-lease")) {
        match kompi_daemon::worker_lease() {
            Ok(()) => return,
            Err(e)
                if e.downcast_ref::<std::io::Error>()
                    .and_then(|e| e.raw_os_error())
                    == Some(32) =>
            {
                std::process::exit(73)
            }
            Err(_) => std::process::exit(1),
        }
    }
    if let Err(error) = kompi_daemon::cli::run().await {
        eprintln!("kompi: {error:#}");
        std::process::exit(1);
    }
}
