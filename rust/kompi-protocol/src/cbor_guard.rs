use crate::{
    CBOR_MAX_ARRAY_ENTRIES, CBOR_MAX_DEPTH, CBOR_MAX_MAP_ENTRIES, MAX_PAYLOAD, ProtocolError,
};
use std::collections::HashSet;

/// Structural CBOR preflight prevents oversized/deep containers and duplicate text keys.
pub struct CborGuard<'a> {
    bytes: &'a [u8],
    at: usize,
}
impl<'a> CborGuard<'a> {
    pub fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, at: 0 }
    }
    pub fn verify(&mut self) -> Result<(), ProtocolError> {
        self.value(0)?;
        if self.at != self.bytes.len() {
            return Err(ProtocolError::Invalid("CBOR trailing data"));
        }
        Ok(())
    }
    fn byte(&mut self) -> Result<u8, ProtocolError> {
        let b = *self
            .bytes
            .get(self.at)
            .ok_or(ProtocolError::Invalid("truncated CBOR"))?;
        self.at += 1;
        Ok(b)
    }
    fn head(&mut self) -> Result<(u8, u64), ProtocolError> {
        let first = self.byte()?;
        let major = first >> 5;
        let ai = first & 31;
        let n = match ai {
            0..=23 => ai as u64,
            24 => self.byte()? as u64,
            25 => u16::from_be_bytes([self.byte()?, self.byte()?]) as u64,
            26 => {
                u32::from_be_bytes([self.byte()?, self.byte()?, self.byte()?, self.byte()?]) as u64
            }
            27 => u64::from_be_bytes([
                self.byte()?,
                self.byte()?,
                self.byte()?,
                self.byte()?,
                self.byte()?,
                self.byte()?,
                self.byte()?,
                self.byte()?,
            ]),
            _ => return Err(ProtocolError::Invalid("indefinite CBOR")),
        };
        Ok((major, n))
    }
    fn text(&mut self, n: u64) -> Result<String, ProtocolError> {
        if n > MAX_PAYLOAD as u64 || n as usize > self.bytes.len().saturating_sub(self.at) {
            return Err(ProtocolError::Invalid("CBOR text length"));
        }
        let s = std::str::from_utf8(&self.bytes[self.at..self.at + n as usize])
            .map_err(|_| ProtocolError::Invalid("CBOR UTF-8"))?
            .to_owned();
        self.at += n as usize;
        Ok(s)
    }
    fn value(&mut self, depth: u8) -> Result<(), ProtocolError> {
        if depth as usize > CBOR_MAX_DEPTH {
            return Err(ProtocolError::Invalid("CBOR depth"));
        }
        let initial = *self
            .bytes
            .get(self.at)
            .ok_or(ProtocolError::Invalid("truncated CBOR"))?;
        if initial >> 5 == 7 {
            self.at += 1;
            return if matches!(initial, 0xf4 | 0xf5 | 0xf6) {
                Ok(())
            } else {
                Err(ProtocolError::Invalid("CBOR simple value"))
            };
        }
        let (major, n) = self.head()?;
        match major {
            0 | 1 => Ok(()),
            2 => {
                if n as usize > self.bytes.len().saturating_sub(self.at) {
                    return Err(ProtocolError::Invalid("CBOR bytes length"));
                }
                self.at += n as usize;
                Ok(())
            }
            3 => {
                self.text(n)?;
                Ok(())
            }
            4 => {
                if n > CBOR_MAX_ARRAY_ENTRIES as u64 {
                    return Err(ProtocolError::Invalid("CBOR array limit"));
                }
                for _ in 0..n {
                    self.value(depth + 1)?;
                }
                Ok(())
            }
            5 => {
                if n > CBOR_MAX_MAP_ENTRIES as u64 {
                    return Err(ProtocolError::Invalid("CBOR map limit"));
                }
                let mut keys = HashSet::new();
                for _ in 0..n {
                    let (key_major, key_len) = self.head()?;
                    if key_major != 3 {
                        return Err(ProtocolError::Invalid("CBOR map key"));
                    }
                    if !keys.insert(self.text(key_len)?) {
                        return Err(ProtocolError::Invalid("duplicate CBOR key"));
                    }
                    self.value(depth + 1)?;
                }
                Ok(())
            }
            _ => Err(ProtocolError::Invalid("unsupported CBOR type")),
        }
    }
}
