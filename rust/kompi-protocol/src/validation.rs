use crate::{
    control::ControlMessage,
    worker::{WorkerEvent, WorkerRequest},
    *,
};
use jsonschema::Validator;
use schemars::JsonSchema;
use serde::Serialize;
use std::sync::OnceLock;

fn validate_from_schema<T: Serialize + JsonSchema>(
    value: &T,
    cache: &'static OnceLock<Result<Validator, String>>,
) -> Result<(), ProtocolError> {
    let validator = cache
        .get_or_init(|| {
            let schema =
                serde_json::to_value(schemars::schema_for!(T)).map_err(|e| e.to_string())?;
            jsonschema::options().with_format("android-tool-result", |s: &str| s.len() <= ANDROID_TOOL_RESULT_MAX_BYTES).should_validate_formats(true).build(&schema).map_err(|e| e.to_string())
        })
        .as_ref()
        .map_err(|_| ProtocolError::Invalid("protocol schema"))?;
    let instance = serde_json::to_value(value).map_err(|e| ProtocolError::Codec(e.to_string()))?;
    validator
        .validate(&instance)
        .map_err(|_| ProtocolError::Invalid("protocol schema"))
}

macro_rules! impl_schema_validation {
    ($ty:ty) => {
        impl ValidateProtocol for $ty {
            fn validate(&self) -> Result<(), ProtocolError> {
                static VALIDATOR: OnceLock<Result<Validator, String>> = OnceLock::new();
                validate_from_schema(self, &VALIDATOR)
            }
        }
    };
}

fn validate_android_tool(tool: AndroidToolKind, action_json: Option<&str>) -> Result<(), ProtocolError> {
    let valid = match (tool, action_json) {
        (AndroidToolKind::ScreenContext, None) => true,
        (AndroidToolKind::PhoneAction | AndroidToolKind::SendNotification, Some(json)) => {
            !json.is_empty() && json.len() <= ANDROID_TOOL_ACTION_MAX_BYTES
        }
        _ => false,
    };
    if valid { Ok(()) } else { Err(ProtocolError::Invalid("android tool payload")) }
}

impl ValidateProtocol for Message {
    fn normalize(&mut self) {
        if let Message::Prompt { text, .. } | Message::TimedPrompt { text, .. } | Message::ConversationPrompt { text, .. } = self {
            *text = text.trim().to_owned();
        }
    }
    fn validate(&self) -> Result<(), ProtocolError> {
        // Chunk size is proven by AttachmentChunkData. Avoid base64-encoding
        // binary transfers just to recheck that invariant in the JSON schema.
        if matches!(self, Message::AttachmentChunk { .. } | Message::ArtifactData { .. }) {
            return Ok(());
        }
        static VALIDATOR: OnceLock<Result<Validator, String>> = OnceLock::new();
        validate_from_schema(self, &VALIDATOR)?;
        if let Message::ArtifactReady { artifact } = self {
            if !is_safe_artifact_name(&artifact.file_name) {
                return Err(ProtocolError::Invalid("artifact file name"));
            }
        }
        if let Message::AndroidToolRequest { tool, action_json, .. } = self {
            validate_android_tool(*tool, action_json.as_deref())?;
        }
        Ok(())
    }
}
impl_schema_validation!(ControlMessage);
impl ValidateProtocol for WorkerRequest {
    fn normalize(&mut self) {
        if let Self::Prompt { text, .. } | Self::FollowUpAdd { text, .. } = self {
            *text = text.trim().to_owned();
        }
    }
    fn validate(&self) -> Result<(), ProtocolError> {
        static VALIDATOR: OnceLock<Result<Validator, String>> = OnceLock::new();
        validate_from_schema(self, &VALIDATOR)
    }
}
impl ValidateProtocol for WorkerEvent {
    fn validate(&self) -> Result<(), ProtocolError> {
        static VALIDATOR: OnceLock<Result<Validator, String>> = OnceLock::new();
        validate_from_schema(self, &VALIDATOR)?;
        if let WorkerEvent::AndroidToolRequest { tool, action_json, .. } = self {
            validate_android_tool(*tool, action_json.as_deref())?;
        }
        Ok(())
    }
}
impl ValidateProtocol for PairingPayload {
    fn validate(&self) -> Result<(), ProtocolError> {
        static VALIDATOR: OnceLock<Result<Validator, String>> = OnceLock::new();
        validate_from_schema(self, &VALIDATOR)
    }
}
impl ValidateProtocol for crate::control::DeviceSummary {
    fn validate(&self) -> Result<(), ProtocolError> {
        static VALIDATOR: OnceLock<Result<Validator, String>> = OnceLock::new();
        validate_from_schema(self, &VALIDATOR)
    }
}
impl ValidateProtocol for ConversationMetadata {
    fn validate(&self) -> Result<(), ProtocolError> {
        static VALIDATOR: OnceLock<Result<Validator, String>> = OnceLock::new();
        validate_from_schema(self, &VALIDATOR)
    }
}

impl PairingPayload {
    pub fn parse_json(input: &[u8]) -> Result<Self, ProtocolError> {
        if input.is_empty() || input.len() > 8192 {
            return Err(ProtocolError::TooLarge);
        }
        let value: Self =
            serde_json::from_slice(input).map_err(|e| ProtocolError::Codec(e.to_string()))?;
        value.validate()?;
        Ok(value)
    }
}
