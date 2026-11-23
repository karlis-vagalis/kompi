use kompi_protocol::{Message, decode_cbor};
use serde_json::Value;

#[test]
fn decode_typescript_vectors() {
    let corpus: Value =
        serde_json::from_str(include_str!("../../../protocol/test-vectors/v4.json")).unwrap();
    for vector in ["valid", "invalid"]
        .iter()
        .flat_map(|key| corpus[*key].as_array().unwrap())
    {
        let bytes: Vec<_> = vector["payloadHex"]
            .as_str()
            .unwrap()
            .as_bytes()
            .chunks_exact(2)
            .map(|s| u8::from_str_radix(std::str::from_utf8(s).unwrap(), 16).unwrap())
            .collect();
        let decoded = decode_cbor::<Message>(&bytes);
        if vector.get("message").is_none() {
            assert!(decoded.is_err(), "{}", vector["name"]);
            continue;
        }
        let decoded = decoded.unwrap();
        assert_eq!(
            serde_json::to_value(decoded).unwrap(),
            vector["message"],
            "{}",
            vector["name"]
        );
    }
}
