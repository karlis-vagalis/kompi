//! Read-only projection of a phone-associated Pi v3 JSONL transcript.
use anyhow::{Context, Result, ensure};
use serde::Deserialize;
use serde_json::value::RawValue;
use std::{fs::OpenOptions, io::Read, os::unix::fs::OpenOptionsExt, path::Path};

const MAX_ARCHIVE_BYTES: u64 = 8 * 1024 * 1024;

#[derive(Deserialize)]
struct Record<'a> {
    #[serde(rename = "type")]
    kind: &'a str,
    id: &'a str,
    version: Option<u32>,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct Snapshot<'a> {
    entries: Vec<&'a RawValue>,
    leaf_id: Option<&'a str>,
}

/// Reconstruct the saved branch just as Pi does when opening JSONL: the last
/// saved entry is the leaf, not necessarily a child of the previous file row.
/// No Pi subprocess, migration, file creation or runtime acquisition occurs.
pub(crate) fn snapshot(path: &Path, session_id: &str) -> Result<String> {
    ensure!(
        path.is_absolute() && path.extension().is_some_and(|ext| ext == "jsonl"),
        "invalid transcript path"
    );
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(path)?;
    let metadata = file.metadata()?;
    ensure!(
        metadata.is_file() && metadata.len() <= MAX_ARCHIVE_BYTES,
        "archive is not a bounded regular file"
    );
    // Read only the length observed at open; a terminal appending later must
    // not extend this snapshot. Reject an incomplete write instead of mixing it in.
    let mut raw = String::new();
    file.take(metadata.len()).read_to_string(&mut raw)?;
    ensure!(
        raw.len() as u64 == metadata.len() && raw.ends_with('\n'),
        "incomplete transcript"
    );
    let mut records = raw.lines().filter(|line| !line.trim().is_empty());
    let header: Record<'_> =
        serde_json::from_str(records.next().context("missing Pi session header")?)?;
    ensure!(
        header.kind == "session" && header.id == session_id && header.version == Some(3),
        "Pi session identity or format mismatch"
    );
    let mut snapshot = Snapshot {
        entries: Vec::new(),
        leaf_id: None,
    };
    let mut seen = std::collections::HashSet::new();
    for line in records {
        let entry: Record<'_> = serde_json::from_str(line)?;
        ensure!(
            entry.kind != "session" && !entry.id.is_empty() && seen.insert(entry.id),
            "invalid Pi archive entry"
        );
        snapshot.leaf_id = Some(entry.id);
        snapshot.entries.push(serde_json::from_str(line)?);
    }
    let json = serde_json::to_string(&snapshot)?;
    ensure!(
        json.len() as u64 <= MAX_ARCHIVE_BYTES,
        "archive exceeds display capacity"
    );
    Ok(json)
}
