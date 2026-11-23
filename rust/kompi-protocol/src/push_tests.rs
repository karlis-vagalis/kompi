use super::*;
use base64::Engine as _;
use schemars::JsonSchema;
use serde::Serialize;
use uuid::Uuid;

fn public_key() -> PushPublicKey {
    let mut bytes = [0_u8; 65];
    bytes[0] = 4;
    PushPublicKey::try_from(base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)).unwrap()
}

fn auth_secret() -> PushAuthSecret {
    PushAuthSecret::try_from(
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode([0xA5_u8; 16]),
    )
    .unwrap()
}

fn integrity_key() -> PushIntegrityKey {
    PushIntegrityKey::try_from(
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode([0x5A_u8; 32]),
    )
    .unwrap()
}

fn registration(endpoint: String) -> PushRegistration {
    PushRegistration {
        registration_id: PushRegistrationId::from(
            Uuid::parse_str("b7f6331e-2cd2-4d68-9ab1-3bc824e987d4").unwrap(),
        ),
        endpoint,
        p256dh: public_key(),
        auth: auth_secret(),
        integrity_key: integrity_key(),
    }
}

fn assert_message_cbor_roundtrip(message: Message) {
    let envelope = Envelope::new(RequestId::parse("push-wire-roundtrip").unwrap(), message.clone());
    let bytes = encode_cbor(&envelope).unwrap();
    let decoded = decode_cbor::<Message>(&bytes).unwrap();
    assert_eq!(decoded.v, VERSION);
    assert_eq!(decoded.id, envelope.id);
    assert_eq!(
        serde_json::to_value(decoded.message).unwrap(),
        serde_json::to_value(message).unwrap()
    );
}

fn schema_accepts<T: JsonSchema + Serialize>(value: &T) -> bool {
    let schema = serde_json::to_value(schemars::schema_for!(T)).unwrap();
    let validator = jsonschema::validator_for(&schema).unwrap();
    validator.is_valid(&serde_json::to_value(value).unwrap())
}

#[test]
fn push_registration_messages_roundtrip_over_v4_cbor() {
    assert_message_cbor_roundtrip(Message::PushRegister {
        registration: registration("https://push.example.test/topic/opaque".into()),
    });
    assert_message_cbor_roundtrip(Message::PushUnregister {
        registration_id: PushRegistrationId::from(
            Uuid::parse_str("b7f6331e-2cd2-4d68-9ab1-3bc824e987d4").unwrap(),
        ),
    });
    for (status, code) in [
        (PushRegistrationStatus::Registered, None),
        (PushRegistrationStatus::Unregistered, None),
        (
            PushRegistrationStatus::Rejected,
            Some(PushRegistrationErrorCode::OriginNotAllowed),
        ),
    ] {
        assert_message_cbor_roundtrip(Message::PushRegistrationResult {
            registration_id: PushRegistrationId::from(
                Uuid::parse_str("b7f6331e-2cd2-4d68-9ab1-3bc824e987d4").unwrap(),
            ),
            status,
            code,
        });
    }
}

#[test]
fn push_crypto_key_and_registration_uuid_parsers_reject_malformed_values() {
    let engine = &base64::engine::general_purpose::URL_SAFE_NO_PAD;
    let mut short_public_key = [0_u8; 64];
    short_public_key[0] = 4;
    assert!(PushPublicKey::try_from(engine.encode(short_public_key)).is_err());
    let mut wrong_point_prefix = [0_u8; 65];
    wrong_point_prefix[0] = 3;
    assert!(PushPublicKey::try_from(engine.encode(wrong_point_prefix)).is_err());
    assert!(PushPublicKey::try_from(format!("{}=", public_key().as_str())).is_err());

    assert!(PushAuthSecret::try_from(engine.encode([0_u8; 15])).is_err());
    assert!(PushIntegrityKey::try_from(engine.encode([0_u8; 31])).is_err());
    assert!(PushIntegrityKey::try_from(format!("{}=", integrity_key().expose_secret())).is_err());

    assert!(serde_json::from_str::<PushRegistrationId>(r#""not-a-uuid""#).is_err());
}

#[test]
fn registration_endpoint_size_is_bounded_at_the_protocol_boundary() {
    let at_limit = registration(format!(
        "https://push.example.test/{}",
        "x".repeat(PUSH_ENDPOINT_MAX_BYTES - "https://push.example.test/".len())
    ));
    assert!(Message::PushRegister {
        registration: at_limit,
    }
    .validate()
    .is_ok());

    let over_limit = registration(format!("https://{}", "x".repeat(PUSH_ENDPOINT_MAX_BYTES)));
    assert!(Message::PushRegister {
        registration: over_limit,
    }
    .validate()
    .is_err());
}

#[test]
fn push_payload_schema_enforces_version_and_size_bounds_but_leaves_time_policy_to_sender() {
    let registration_id = PushRegistrationId::from(
        Uuid::parse_str("b7f6331e-2cd2-4d68-9ab1-3bc824e987d4").unwrap(),
    );
    let mut payload = PushNotificationPayload {
        version: 1,
        host_endpoint: "a".repeat(64),
        registration_id,
        notification_id: RequestId::parse("push-payload-boundary").unwrap(),
        issued_at_ms: 0,
        expires_at_ms: 0,
        title: "t".repeat(DEVICE_NOTIFICATION_TITLE_MAX_CHARS),
        body: "b".repeat(DEVICE_NOTIFICATION_BODY_MAX_CHARS),
    };
    assert!(schema_accepts(&payload));

    payload.version = 0;
    assert!(!schema_accepts(&payload));
    payload.version = 1;
    payload.title.push('x');
    assert!(!schema_accepts(&payload));
    payload.title.pop();
    payload.body.push('x');
    assert!(!schema_accepts(&payload));
    payload.body.pop();

    // Protocol DTOs carry signed timestamps but intentionally do not define expiry
    // policy; the authenticated receiver enforces skew and the 24-hour lifetime.
    payload.issued_at_ms = i64::MIN;
    payload.expires_at_ms = i64::MAX;
    assert!(schema_accepts(&payload));
}

#[test]
fn authenticated_wrapper_schema_enforces_version_and_payload_size() {
    let mut wrapper = PushAuthenticatedPayload {
        version: 1,
        payload: "p".repeat(PUSH_PAYLOAD_MAX_BYTES),
        mac: "m".repeat(43),
    };
    assert!(schema_accepts(&wrapper));

    wrapper.version = 0;
    assert!(!schema_accepts(&wrapper));
    wrapper.version = 1;
    wrapper.payload.push('p');
    assert!(!schema_accepts(&wrapper));
}

#[test]
fn older_peer_hello_without_unified_push_capability_remains_valid() {
    let message = Message::Hello {
        capabilities: vec![PHONE_CONVERSATIONS_CAPABILITY.into()],
        endpoint: None,
        session: None,
        idle_timeout_seconds: None,
    };
    assert!(message.validate().is_ok());
    assert_message_cbor_roundtrip(message);
}
