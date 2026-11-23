use anyhow::{Context, Result, bail};
use base64::Engine as _;
use clap::{Parser, Subcommand};
use kompi_protocol::{Envelope, RequestId, control::ControlMessage};
use std::{path::PathBuf, time::Duration};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    time::timeout,
};

#[derive(Debug, Parser)]
#[command(name = "kompi", version, about = "Kompi native daemon")]
pub struct Args {
    #[arg(long, global = true, env = "KOMPI_STATE_DIR")]
    pub state_dir: Option<PathBuf>,
    #[command(subcommand)]
    pub command: CommandKind,
}

#[derive(Debug, Subcommand)]
pub enum CommandKind {
    Serve {},
    /// Install the bundled runtime beside this executable and print its worker directory.
    SetupWorker {},
    Status {
        #[arg(long)]
        json: bool,
    },
    Pair {
        #[arg(long)]
        token: bool,
        #[arg(long)]
        json: bool,
        /// Computer name shown during pairing (defaults to this daemon machine's hostname).
        name: Option<String>,
    },
    Devices {
        #[command(subcommand)]
        command: DeviceCommand,
    },
    Push {
        #[command(subcommand)]
        command: PushCommand,
    },
}
#[derive(Debug, Subcommand)]
pub enum DeviceCommand {
    List {
        #[arg(long)]
        json: bool,
    },
    Revoke {
        endpoint: String,
        #[arg(long)]
        json: bool,
    },
    Notify {
        /// Exact paired Android endpoint ID from `kompi devices list`.
        endpoint: String,
        /// Read one bounded JSON object {"title":"...","body":"..."} from stdin through EOF.
        #[arg(long, required = true)]
        payload_stdin: bool,
        /// Stable caller id for deduplication across retries (defaults to a generated id).
        #[arg(long)]
        request_id: Option<String>,
        #[arg(long)]
        json: bool,
    },
}

#[derive(Debug, Subcommand)]
pub enum PushCommand {
    AllowOrigin { origin: String, #[arg(long)] json: bool },
    ListOrigins { #[arg(long)] json: bool },
    RevokeOrigin { origin: String, #[arg(long)] json: bool },
}

type NotificationPayload = kompi_protocol::AgentNotification;

fn parse_notification_payload(bytes:&[u8]) -> Result<NotificationPayload> {
    const MAX_BYTES: usize = kompi_protocol::NOTIFICATION_INPUT_MAX_BYTES;
    anyhow::ensure!(bytes.len()<=MAX_BYTES, "notification payload exceeds the stdin size limit");
    let payload:NotificationPayload=serde_json::from_slice(bytes).context("stdin must contain one JSON notification object and EOF")?;
    payload.validate()?;
    Ok(payload)
}

async fn read_notification_payload() -> Result<NotificationPayload> {
    const MAX_BYTES: usize = kompi_protocol::NOTIFICATION_INPUT_MAX_BYTES;
    let mut bytes=Vec::with_capacity(1024);
    let mut stdin=tokio::io::stdin().take((MAX_BYTES+1) as u64);
    stdin.read_to_end(&mut bytes).await.context("read notification payload from stdin")?;
    parse_notification_payload(&bytes)
}

pub fn resolve_state_dir(configured: Option<PathBuf>) -> Result<PathBuf> {
    if let Some(path) = configured {
        return Ok(path);
    }
    if let Some(path) = std::env::var_os("KOMPI_STATE_DIR") {
        return Ok(PathBuf::from(path));
    }
    Ok(dirs::home_dir()
        .context("cannot determine home directory")?
        .join(".pi/agent/kompi-native"))
}

pub async fn run() -> Result<()> {
    let args = Args::parse();
    let state_dir = resolve_state_dir(args.state_dir)?;
    match args.command {
        CommandKind::SetupWorker {} => {
            println!("{}", crate::package_worker::setup()?.display());
            Ok(())
        }
        CommandKind::Serve {} => {
            if std::env::var_os("KOMPI_SDK_WORKER_DIR").is_none() {
                crate::package_worker::default_dir()?;
            }
            crate::transport::run_daemon(state_dir).await
        }
        CommandKind::Status { json } => {
            ensure_running(&state_dir).await?;
            let result = control(&state_dir, ControlMessage::Status {}).await?;
            print_control(result, json)
        }
        CommandKind::Pair { token, json, name } => {
            ensure_running(&state_dir).await?;
            let result = control(&state_dir, ControlMessage::PairingCreate { name }).await?;
            let pairing = match result {
                ControlMessage::PairingResult {
                    qr,
                    token: pair_token,
                    expires_at,
                } => (qr, pair_token, expires_at),
                ControlMessage::Error { code, message } => bail!("{code}: {message}"),
                _ => bail!("daemon returned an unexpected pairing response"),
            };
            let qr = kompi_protocol::PairingPayload::parse_json(pairing.0.as_bytes())
                .context("daemon returned invalid pairing QR JSON")?;
            let terminal_qr = render_qr(&pairing.0)?;
            let png_base64 = render_png(&pairing.0)?;
            let requested_token = if token {
                pairing
                    .1
                    .clone()
                    .context("daemon omitted requested pairing token")?
            } else {
                String::new()
            };
            if token && !json {
                println!("{requested_token}");
            } else if json {
                let mut value = serde_json::json!({
                    "ok": true,
                    "pairing": {
                        "qr": pairing.0,
                        "terminalQr": terminal_qr,
                        "pngBase64": png_base64,
                        "name": qr.name,
                        "expiresAt": pairing.2
                    }
                });
                if token {
                    value["pairing"]["token"] = serde_json::Value::String(requested_token);
                }
                println!("{}", serde_json::to_string(&value)?);
            } else {
                println!("{terminal_qr}");
                println!(
                    "Pair Kompi Android with this QR; expires at {}.",
                    pairing.2.as_jiff().to_string()
                );
            }
            Ok(())
        }
        CommandKind::Push { command: PushCommand::AllowOrigin { origin, json } } => {
            ensure_running(&state_dir).await?;
            print_control(control(&state_dir, ControlMessage::PushAllowOrigin { origin }).await?, json)
        }
        CommandKind::Push { command: PushCommand::ListOrigins { json } } => {
            ensure_running(&state_dir).await?;
            print_control(control(&state_dir, ControlMessage::PushAllowedOriginsList {}).await?, json)
        }
        CommandKind::Push { command: PushCommand::RevokeOrigin { origin, json } } => {
            ensure_running(&state_dir).await?;
            print_control(control(&state_dir, ControlMessage::PushAllowedOriginRevoke { origin }).await?, json)
        }
        CommandKind::Devices {
            command: DeviceCommand::List { json },
        } => {
            ensure_running(&state_dir).await?;
            let result = control(&state_dir, ControlMessage::DevicesList {}).await?;
            print_control(result, json)
        }
        CommandKind::Devices {
            command: DeviceCommand::Revoke { endpoint, json },
        } => {
            ensure_running(&state_dir).await?;
            let result = control(&state_dir, ControlMessage::DeviceRevoke { endpoint }).await?;
            print_control(result, json)
        }
        CommandKind::Devices {
            command: DeviceCommand::Notify { endpoint, payload_stdin: _, request_id, json },
        } => {
            if !valid_endpoint(&endpoint) {
                return print_control(ControlMessage::DeviceNotificationResult {
                    endpoint,
                    status: kompi_protocol::DeviceNotificationStatus::NotPosted,
                    code: Some("invalid-endpoint".into()),
                    message: Some("Endpoint must be the exact 64-character lowercase paired-device ID; no request was dispatched.".into()),
                }, json);
            }
            let payload=read_notification_payload().await?;
            let notification=payload;
            if ensure_running(&state_dir).await.is_err() {
                return print_control(ControlMessage::DeviceNotificationResult {
                    endpoint,
                    status: kompi_protocol::DeviceNotificationStatus::NotPosted,
                    code: Some("service-unavailable".into()),
                    message: Some("The native service was unavailable before dispatch; no phone request was sent.".into()),
                }, json);
            }
            let request_id = request_id.map(RequestId::parse).transpose()?.unwrap_or(RequestId::parse(format!("notify-{}", uuid::Uuid::new_v4()))?);
            let result = match control_with_timeout(
                &state_dir,
                if notification.rich.is_some() {
                    ControlMessage::DeviceRichNotification { endpoint: endpoint.clone(), request_id, notification }
                } else {
                    ControlMessage::DeviceNotification { endpoint: endpoint.clone(), request_id, title: notification.title, body: notification.body }
                },
                Duration::from_secs(35),
            ).await {
                Ok(result) => result,
                Err(_) => ControlMessage::DeviceNotificationResult {
                    endpoint,
                    status: kompi_protocol::DeviceNotificationStatus::Unknown,
                    code: Some("control-response-lost".into()),
                    message: Some("The native response was lost after attempting dispatch. The phone may have posted the notification; do not retry automatically.".into()),
                },
            };
            print_control(result, json)
        }
    }
}

fn valid_endpoint(endpoint: &str) -> bool {
    endpoint.len() == 64
        && endpoint.bytes().all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}

async fn ensure_running(state_dir: &PathBuf) -> Result<()> {
    if matches!(timeout(Duration::from_secs(3), crate::local_ipc::connect(&crate::store::control_socket_path(state_dir))).await, Ok(Ok(_))) {
        return Ok(());
    }
    bail!(
        "Kompi service is unavailable at {}; start it under your process supervisor with `kompi serve --state-dir {}`",
        crate::store::control_socket_path(state_dir).display(),
        state_dir.display()
    )
}

async fn control(state_dir: &PathBuf, message: ControlMessage) -> Result<ControlMessage> {
    control_with_timeout(state_dir, message, Duration::from_secs(10)).await
}

async fn control_with_timeout(state_dir: &PathBuf, message: ControlMessage, response_timeout: Duration) -> Result<ControlMessage> {
    let path = crate::store::control_socket_path(state_dir);
    let mut stream = timeout(Duration::from_secs(3), crate::local_ipc::connect(&path)).await??;
    let id = RequestId::parse(format!("cli-{}", uuid::Uuid::new_v4()))?;
    let request = Envelope::new(id.clone(), message);
    let bytes = kompi_protocol::encode_cbor(&request)?;
    let framed = kompi_protocol::frame(&bytes)?;
    stream.write_all(&framed).await?;
    let mut header = [0u8; 4];
    timeout(response_timeout, stream.read_exact(&mut header)).await??;
    let length = u32::from_be_bytes(header) as usize;
    if length == 0 || length > kompi_protocol::MAX_PAYLOAD {
        bail!("invalid control response size");
    }
    let mut payload = vec![0; length];
    timeout(response_timeout, stream.read_exact(&mut payload)).await??;
    let response: Envelope<ControlMessage> = kompi_protocol::decode_cbor(&payload)?;
    if response.id != id {
        bail!("control response correlation ID mismatch");
    }
    Ok(response.message)
}

fn print_control(message: ControlMessage, json: bool) -> Result<()> {
    match message {
        ControlMessage::Error { code, message } => {
            println!(
                "{}",
                serde_json::json!({"ok":false,"error":{"code":code,"message":message}})
            );
            bail!("daemon control request failed")
        }
        ControlMessage::StatusResult {
            running,
            endpoint,
            control_socket,
            paired_devices,
        } => {
            if json {
                println!(
                    "{}",
                    serde_json::json!({"ok":true,"running":running,"endpoint":endpoint,"controlSocket":control_socket,"pairedDevices":paired_devices})
                );
            } else {
                println!(
                    "Kompi daemon: {}",
                    if running { "running" } else { "stopped" }
                );
                if let Some(endpoint) = endpoint {
                    println!("Endpoint: {endpoint}");
                }
                println!(
                    "Paired devices: {}",
                    paired_devices.len()
                );
            }
        }
        ControlMessage::PushAllowedOriginsResult { origins } => {
            if json { println!("{}", serde_json::json!({"ok":true,"origins":origins})); }
            else if origins.is_empty() { println!("No UnifiedPush HTTPS origins are allowed."); }
            else { for origin in origins { println!("{origin}"); } }
        }
        ControlMessage::DevicesResult { devices } => {
            if json {
                println!("{}", serde_json::json!({"ok":true,"devices":devices}));
            } else if devices.is_empty() {
                println!("No paired devices.");
            } else {
                for device in devices {
                    println!(
                        "{}\t{}\t{}",
                        device.endpoint,
                        device.name,
                        device.paired_at.as_jiff()
                    );
                }
            }
        }
        ControlMessage::DeviceNotificationResult { endpoint, status, code, message } => {
            let status = match status {
                kompi_protocol::DeviceNotificationStatus::Queued => "queued",
                kompi_protocol::DeviceNotificationStatus::PushAccepted => "push-accepted",
                kompi_protocol::DeviceNotificationStatus::Posted => "posted",
                kompi_protocol::DeviceNotificationStatus::NotPosted => "not-posted",
                kompi_protocol::DeviceNotificationStatus::Unknown => "unknown",
                kompi_protocol::DeviceNotificationStatus::Offline => "offline",
                kompi_protocol::DeviceNotificationStatus::Unavailable => "unavailable",
            };
            if json {
                println!("{}", serde_json::json!({"ok":true,"endpoint":endpoint,"status":status,"code":code,"message":message}));
            } else {
                println!("Notification to {endpoint}: {status}");
                if let Some(message) = message { println!("{message}"); }
                if let Some(code) = code { println!("Code: {code}"); }
            }
        }
        ControlMessage::Ok { operation } => {
            if json {
                println!("{}", serde_json::json!({"ok":true,"operation":operation}));
            } else {
                println!("Kompi {operation}");
            }
        }
        other => bail!("unexpected response: {other:?}"),
    }
    Ok(())
}

fn render_qr(payload: &str) -> Result<String> {
    let code = qrcode::QrCode::new(payload.as_bytes()).context("encode pairing QR")?;
    Ok(code
        .render::<qrcode::render::unicode::Dense1x2>()
        .dark_color(qrcode::render::unicode::Dense1x2::Dark)
        .light_color(qrcode::render::unicode::Dense1x2::Light)
        .build())
}
fn render_png(payload: &str) -> Result<String> {
    let code = qrcode::QrCode::new(payload.as_bytes()).context("encode pairing QR image")?;
    let scale = 8usize;
    let margin = 4usize;
    let modules = code.width();
    let dimension = (modules + margin * 2) * scale;
    let mut pixels = vec![255u8; dimension * dimension];
    for y in 0..modules {
        for x in 0..modules {
            if code[(x, y)] == qrcode::Color::Dark {
                for py in (y + margin) * scale..(y + margin + 1) * scale {
                    for px in (x + margin) * scale..(x + margin + 1) * scale {
                        pixels[py * dimension + px] = 0;
                    }
                }
            }
        }
    }
    let mut bytes = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut bytes, dimension as u32, dimension as u32);
        encoder.set_color(png::ColorType::Grayscale);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header().context("encode QR PNG header")?;
        writer
            .write_image_data(&pixels)
            .context("encode QR PNG pixels")?;
    }
    Ok(base64::engine::general_purpose::STANDARD.encode(bytes))
}

#[cfg(test)]
mod notification_cli_tests {
    use super::*;

    #[test]
    fn notification_content_is_stdin_only_and_strictly_bounded() {
        let endpoint = "a".repeat(64);
        let args = Args::try_parse_from(["kompi", "devices", "notify", &endpoint, "--payload-stdin", "--request-id", "stable-call-1", "--json"])
            .expect("stdin payload interface should parse");
        assert!(matches!(args.command, CommandKind::Devices { command: DeviceCommand::Notify { .. } }));
        assert!(Args::try_parse_from(["kompi", "devices", "notify", &endpoint, "--title", "secret", "--body", "secret", "--json"]).is_err());
        let parsed = parse_notification_payload(br#"{"title":"--help","body":"--state-dir"}"#).unwrap();
        assert_eq!(parsed.title, "--help");
        assert_eq!(parsed.body, "--state-dir");
        assert!(parse_notification_payload(br#"{"title":"x","body":"y","extra":"no"}"#).is_err());
        assert!(parse_notification_payload(&vec![b'x'; 8193]).is_err());
    }
}
