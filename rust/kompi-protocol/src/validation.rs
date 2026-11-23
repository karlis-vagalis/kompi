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
            jsonschema::validator_for(&schema).map_err(|e| e.to_string())
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
        if let Message::AttachmentChunk { data, .. } | Message::ArtifactData { data } = self {
            if data.as_bytes().is_empty() || data.as_bytes().len() > ATTACHMENT_CHUNK_MAX_BYTES {
                return Err(ProtocolError::Invalid("attachment chunk data length"));
            }
            return Ok(());
        }
        static VALIDATOR: OnceLock<Result<Validator, String>> = OnceLock::new();
        validate_from_schema(self, &VALIDATOR)?;
        if let Message::ArtifactReady { artifact } = self {
            if !is_safe_artifact_name(&artifact.file_name) {
                return Err(ProtocolError::Invalid("artifact file name"));
            }
        }
        if let Message::Prompt { text, attachments, .. } | Message::TimedPrompt { text, attachments, .. } | Message::ConversationPrompt { text, attachments, .. } = self {
            if text.trim().is_empty() && attachments.as_ref().is_none_or(Vec::is_empty) {
                return Err(ProtocolError::Invalid("prompt requires text or attachments"));
            }
        }
        if let Message::AndroidToolRequest { tool, action_json, .. } = self {
            validate_android_tool(*tool, action_json.as_deref())?;
        }
        if let Message::AndroidToolResponse { success, result_json, error_code, error_message, .. } = self {
            let shape = if *success { result_json.is_some() && error_code.is_none() && error_message.is_none() }
                else { error_code.as_ref().is_some_and(|code| !code.is_empty()) && error_message.is_some() };
            if !shape { return Err(ProtocolError::Invalid("android tool response shape")); }
        }
        Ok(())
    }
}
impl_schema_validation!(ControlMessage);
impl ValidateProtocol for WorkerRequest {
    fn validate(&self) -> Result<(), ProtocolError> {
        static VALIDATOR: OnceLock<Result<Validator, String>> = OnceLock::new();
        validate_from_schema(self, &VALIDATOR)?;
        if let WorkerRequest::Prompt { text, attachments, .. } | WorkerRequest::FollowUpAdd { text, attachments, .. } = self {
            if text.trim().is_empty() && attachments.as_ref().is_none_or(Vec::is_empty) {
                return Err(ProtocolError::Invalid("worker prompt requires text or attachments"));
            }
        }
        if let WorkerRequest::AndroidToolResponse { success, result_json, error_code, error_message, .. } = self {
            let shape = if *success { result_json.is_some() && error_code.is_none() && error_message.is_none() }
                else { error_code.as_ref().is_some_and(|code| !code.is_empty()) && error_message.is_some() };
            if !shape { return Err(ProtocolError::Invalid("worker android tool response shape")); }
        }
        Ok(())
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
