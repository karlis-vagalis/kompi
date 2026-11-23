use super::*;

#[test]
fn scan_and_delete_under_exclusive_lease() -> Result<()> {
    let temp = std::env::temp_dir().join(Uuid::new_v4().to_string());
    let project = temp.join("sessions").join("project & (example)");
    fs::create_dir_all(&project)?;
    let path = project.join("sample.jsonl");
    let id = Uuid::new_v4();
    fs::write(
        &path,
        format!(
            "{}\n",
            serde_json::json!({"type":"session","id":id,"version":3,"timestamp":"2026-05-01T12:00:00Z","cwd":&temp})
        ),
    )?;
    let roots = SessionRoots {
        standard: temp.join("sessions"),
        configured: None,
    };
    let mut catalog = Catalog::default();
    let found = catalog.scan(&roots)?;
    assert_eq!(found.len(), 1);
    let entry = &found[0];
    assert_eq!(entry.id, id);
    let writer = sidecar(&path)?;
    assert!(matches!(
        LockedTranscript::discover(&roots, entry),
        Err(LockError::InUse)
    ));
    drop(writer);
    let guard = LockedTranscript::discover(&roots, entry).unwrap();
    assert!(fs::rename(&project, temp.join("moved")).is_err());
    assert!(sidecar(&path).is_err());
    guard.unlink()?;
    assert!(!path.exists());
    fs::remove_dir_all(temp)?;
    Ok(())
}

#[test]
fn rejects_junction_before_read_or_lease_creation() -> Result<()> {
    let temp = std::env::temp_dir().join(Uuid::new_v4().to_string());
    fs::create_dir_all(temp.join("outside"))?;
    let status = std::process::Command::new("cmd.exe")
        .args(["/d", "/c", "mklink", "/J", "link", "outside"])
        .current_dir(&temp)
        .status()?;
    assert!(status.success());
    let path = temp.join("link").join("file.jsonl");
    fs::write(temp.join("outside/file.jsonl"), "not history")?;
    assert!(parent(&path).is_err());
    assert!(read_file(&path).is_err());
    assert!(crate::pi_archive::snapshot(&path, "invalid").is_err());
    fs::remove_dir(temp.join("link"))?;
    fs::remove_dir_all(temp)?;
    Ok(())
}
