#[tokio::main]
async fn main() {
    if let Err(error) = kompi_daemon::cli::run().await {
        eprintln!("kompi: {error:#}");
        std::process::exit(1);
    }
}
