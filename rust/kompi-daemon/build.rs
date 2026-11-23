use std::{
    env, fs,
    path::{Path, PathBuf},
};

// Generate a fixed manifest, not an archive parser. Only the locked worker's
// compiled JS and explicit metadata may enter the distribution executable.
fn compiled(root: &Path, directory: &Path, files: &mut Vec<(String, PathBuf)>) {
    assert!(directory.symlink_metadata().unwrap().is_dir());
    println!("cargo:rerun-if-changed={}", directory.display());
    for entry in directory.read_dir().unwrap() {
        let entry = entry.unwrap();
        let path = entry.path();
        let name = entry.file_name();
        let name = name.to_str().unwrap();
        assert!(
            !name.starts_with('.')
                && name
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b)),
            "invalid compiled worker filename"
        );
        if entry.file_type().unwrap().is_dir() {
            compiled(root, &path, files);
        } else {
            assert!(
                entry.file_type().unwrap().is_file()
                    && path.extension().is_some_and(|ext| ext == "js"),
                "unexpected compiled worker entry"
            );
            files.push((
                path.strip_prefix(root)
                    .unwrap()
                    .to_str()
                    .unwrap()
                    .replace('\\', "/"),
                path,
            ));
        }
    }
}
fn main() {
    println!("cargo:rerun-if-env-changed=KOMPI_PACKAGE_INPUTS");
    println!("cargo:rerun-if-changed=build.rs");
    let out = PathBuf::from(env::var_os("OUT_DIR").unwrap());
    let mut manifest = String::from("&[\n");
    if let Some(root) = env::var_os("KOMPI_PACKAGE_INPUTS") {
        let root = PathBuf::from(root);
        assert!(root.is_absolute(), "KOMPI_PACKAGE_INPUTS must be absolute");
        let mut files = vec![(
            "LICENSE".into(),
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../LICENSE"),
        )];
        for name in ["notices.tar.gz", "worker/package.json", "worker/bun.lock", "worker/typebox-LICENSE", "worker/zod-LICENSE"] {
            files.push((name.into(), root.join(name)));
        }
        compiled(&root, &root.join("worker/dist-worker"), &mut files);
        assert!(
            files
                .iter()
                .any(|(name, _)| name == "worker/dist-worker/daemon/src/worker.js"),
            "missing compiled worker entry"
        );
        files.sort();
        for (i, (name, path)) in files.into_iter().enumerate() {
            assert!(
                path.symlink_metadata().unwrap().is_file() && path.metadata().unwrap().len() > 0
            );
            println!("cargo:rerun-if-changed={}", path.display());
            fs::copy(path, out.join(format!("embedded-{i}"))).unwrap();
            manifest.push_str(&format!("({name:?}, include_bytes!(\"embedded-{i}\")),\n"));
        }
    }
    manifest.push(']');
    fs::write(out.join("runtime.rs"), manifest).unwrap();
}
