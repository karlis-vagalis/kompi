use kompi_protocol::{
    ANDROID_TOOL_RESULT_MAX_BYTES, ATTACHMENT_CHUNK_MAX_BYTES, AttachmentChunkData, Message,
    SyncCursor, ValidateProtocol,
};
use serde_json::json;

#[test]
fn cursor_roundtrips_only_canonical_sqlite_integers() {
    for n in [0, 1, i64::MAX - 1, i64::MAX] {
        let value = json!(n.to_string());
        let cursor: SyncCursor = serde_json::from_value(value.clone()).unwrap();
        assert_eq!(cursor.as_i64(), n);
        assert_eq!(serde_json::to_value(cursor).unwrap(), value);
    }
    for invalid in [
        "-1",
        "+1",
        "01",
        "9223372036854775808",
        "18446744073709551615",
    ] {
        assert!(serde_json::from_value::<SyncCursor>(json!(invalid)).is_err());
    }
}

#[test]
fn chunk_size_and_representation_hold_at_deserialization() {
    for size in [
        0,
        1,
        ATTACHMENT_CHUNK_MAX_BYTES,
        ATTACHMENT_CHUNK_MAX_BYTES + 1,
    ] {
        use base64::Engine;
        let bytes = vec![255; size];
        let encoded = json!(base64::engine::general_purpose::STANDARD.encode(&bytes));
        let valid = (1..=ATTACHMENT_CHUNK_MAX_BYTES).contains(&size);
        let parsed = serde_json::from_value::<AttachmentChunkData>(encoded.clone());
        assert_eq!(parsed.is_ok(), valid);
        let mut binary = Vec::new();
        ciborium::into_writer(serde_bytes::Bytes::new(&bytes), &mut binary).unwrap();
        assert_eq!(
            ciborium::from_reader::<AttachmentChunkData, _>(&binary[..]).is_ok(),
            valid
        );
        if let Ok(chunk) = parsed {
            assert_eq!(serde_json::to_value(&chunk).unwrap(), encoded);
            let mut actual = Vec::new();
            ciborium::into_writer(&chunk, &mut actual).unwrap();
            assert_eq!(actual, binary);
        }
    }
    for invalid in [json!("not base64"), json!([42])] {
        assert!(serde_json::from_value::<AttachmentChunkData>(invalid).is_err());
    }
}

#[test]
fn chunk_payload_before_tag_still_requires_cbor_bytes() {
    use ciborium::Value;
    for (data, valid) in [
        (Value::Bytes(vec![255]), true),
        (Value::Text("/w==".into()), false),
    ] {
        let wire = Value::Map(vec![
            ("v".into(), kompi_protocol::VERSION.into()),
            ("id".into(), "example".into()),
            (
                "message".into(),
                Value::Map(vec![
                    ("payload".into(), Value::Map(vec![("data".into(), data)])),
                    ("type".into(), "artifact-data".into()),
                ]),
            ),
        ]);
        let mut bytes = Vec::new();
        ciborium::into_writer(&wire, &mut bytes).unwrap();
        assert_eq!(
            kompi_protocol::decode_cbor::<Message>(&bytes).is_ok(),
            valid
        );
    }
}

#[test]
fn tool_results_use_utf8_bytes_and_failure_can_carry_details() {
    let boundary = "é".repeat(ANDROID_TOOL_RESULT_MAX_BYTES / 2);
    for (success, result, code, error, valid) in [
        (true, Some(boundary.as_str()), None, None, true),
        (true, Some(&format!("{boundary}a")), None, None, false),
        (true, None, None, None, false),
        (true, Some("ok"), Some("denied"), None, false),
        (false, Some("details"), Some("denied"), Some(""), true),
        (false, None, Some(""), Some(""), false),
    ] {
        let message: Message = serde_json::from_value(json!({"type":"android-tool-response","payload":{
            "conversationId":"12345678-1234-4123-8123-123456789abc","requestId":"example","success":success,
            "resultJson":result,"errorCode":code,"errorMessage":error
        }})).unwrap();
        assert_eq!(message.validate().is_ok(), valid);
    }
}
