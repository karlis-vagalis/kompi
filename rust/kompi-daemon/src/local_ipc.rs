//! Local control channel. Windows clients verify the pipe object's owner before sending data.
#[cfg(unix)]
pub use tokio::net::{UnixListener as Listener, UnixStream as ServerStream};
#[cfg(unix)]
pub async fn connect(path: &std::path::Path) -> std::io::Result<ServerStream> {
    ServerStream::connect(path).await
}
#[cfg(windows)]
mod windows;
#[cfg(windows)]
pub use windows::*;
