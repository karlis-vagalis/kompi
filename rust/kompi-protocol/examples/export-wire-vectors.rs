//! Emit real Rust-encoded payloads for the TypeScript decoder.
use kompi_protocol::{Envelope, Message, VERSION, encode_cbor};
use serde_json::json;

fn main() {
    let uuid = "12345678-1234-4123-8123-123456789abc";
    let mut valid = Vec::new();
    for (name, kind, payload) in [
        (
            "attachment",
            "attachment-chunk",
            json!({"uploadId":uuid,"data":"AP+A","offset":1}),
        ),
        (
            "attachment-only",
            "prompt",
            json!({"text":"", "attachments":[uuid], "configuration":{"provider":"test","modelId":"test","effort":"off"},"delivery":"followUp"}),
        ),
        ("artifact", "artifact-data", json!({"data":"/w=="})),
        (
            "tool-success",
            "android-tool-response",
            json!({"conversationId":uuid,"requestId":"req","success":true,"resultJson":"{}"}),
        ),
        (
            "tool-failure",
            "android-tool-response",
            json!({"conversationId":uuid,"requestId":"req","success":false,"errorCode":"failed","errorMessage":"test","resultJson":"{}"}),
        ),
    ] {
        let message: Envelope<Message> = serde_json::from_value(
            json!({"v":VERSION,"id":"example","message":{"type":kind,"payload":payload}}),
        )
        .unwrap();
        let bytes = encode_cbor(&message).unwrap();
        let hex: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
        valid.push(json!({"name":name,"message":message,"payloadHex":hex}));
    }
    println!(
        "{}",
        serde_json::to_string_pretty(&json!({"valid":valid})).unwrap()
    );
}
