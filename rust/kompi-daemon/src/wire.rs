use anyhow::{Result, bail};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const MAX_FRAME: usize = 65_536;

pub async fn read_frame<R: AsyncRead + Unpin>(reader: &mut R) -> Result<Option<Vec<u8>>> {
    let mut header = [0u8; 4];
    if reader.read(&mut header[..1]).await? == 0 {
        return Ok(None);
    }
    read_started_frame(reader, &mut header).await
}

/// An authenticated peer may be receiving replies on secondary streams without
/// sending control messages. Bound only an in-progress frame, not that silence.
pub async fn read_ready_frame<R: AsyncRead + Unpin>(
    reader: &mut R,
    deadline: std::time::Duration,
) -> Result<Option<Vec<u8>>> {
    let mut header = [0; 4];
    if reader.read(&mut header[..1]).await? == 0 {
        return Ok(None);
    }
    tokio::time::timeout(deadline, read_started_frame(reader, &mut header)).await?
}

async fn read_started_frame<R: AsyncRead + Unpin>(
    reader: &mut R,
    header: &mut [u8; 4],
) -> Result<Option<Vec<u8>>> {
    reader.read_exact(&mut header[1..]).await?;
    let length = u32::from_be_bytes(*header) as usize;
    if length == 0 || length > MAX_FRAME {
        bail!("invalid frame length");
    }
    let mut payload = vec![0; length];
    reader.read_exact(&mut payload).await?;
    Ok(Some(payload))
}

pub async fn write_frame<W: AsyncWrite + Unpin>(writer: &mut W, payload: &[u8]) -> Result<()> {
    if payload.is_empty() || payload.len() > MAX_FRAME {
        bail!("invalid frame length");
    }
    writer
        .write_all(&(payload.len() as u32).to_be_bytes())
        .await?;
    writer.write_all(payload).await?;
    writer.flush().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[tokio::test]
    async fn ready_stream_can_idle_but_a_partial_header_times_out() {
        let (mut tx, mut rx) = tokio::io::duplex(64);
        let limit = Duration::from_millis(10);
        {
            let read = read_ready_frame(&mut rx, limit);
            tokio::pin!(read);
            assert!(tokio::time::timeout(limit * 3, &mut read).await.is_err());
            write_frame(&mut tx, b"ping").await.unwrap();
            assert_eq!(read.await.unwrap().unwrap(), b"ping");
        }
        tx.write_all(&[0]).await.unwrap();
        assert!(read_ready_frame(&mut rx, limit).await.is_err());
    }
}
