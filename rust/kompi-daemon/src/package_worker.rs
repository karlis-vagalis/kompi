use anyhow::{Context, Result, ensure};
use sha2::{Digest, Sha256};
use std::{
    fs,
    path::{Path, PathBuf},
    sync::LazyLock,
};

const FILES: &[(&str, &[u8])] = include!(concat!(env!("OUT_DIR"), "/runtime.rs"));
static FOLDER: LazyLock<String> = LazyLock::new(|| {
    let mut hash = Sha256::new();
    for &(name, bytes) in FILES {
        for part in [name.as_bytes(), bytes] {
            hash.update((part.len() as u64).to_be_bytes());
            hash.update(part);
        }
    }
    format!("kompi-runtime-{}", hex::encode(hash.finalize()))
});

fn require_kind(path: &Path, directory: bool) -> Result<()> {
    let meta = fs::symlink_metadata(path).with_context(|| format!("inspect {}", path.display()))?;
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        ensure!(
            meta.file_attributes() & 0x400 == 0,
            "refusing runtime reparse point {}",
            path.display()
        );
    }
    ensure!(
        if directory {
            meta.is_dir()
        } else {
            meta.is_file()
        },
        "unexpected runtime entry {}",
        path.display()
    );
    Ok(())
}
fn verify(root: &Path) -> Result<()> {
    require_kind(root, true)?;
    for &(name, bytes) in FILES {
        let mut here = root.to_owned();
        let mut components = name.split('/').peekable();
        while let Some(part) = components.next() {
            here.push(part);
            require_kind(&here, components.peek().is_some())?;
        }
        ensure!(
            fs::metadata(&here)?.len() == bytes.len() as u64 && fs::read(&here)? == bytes,
            "modified packaged file {}; reinstall the runtime",
            here.display()
        );
    }
    Ok(())
}
fn install(base: &Path) -> Result<PathBuf> {
    ensure!(
        !FILES.is_empty(),
        "this source build has no embedded runtime; build the worker and set KOMPI_SDK_WORKER_DIR"
    );
    let destination = base.join(&*FOLDER);
    match fs::symlink_metadata(&destination) {
        Ok(_) => verify(&destination)?,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            let stage = base.join(format!(".kompi-stage-{}", uuid::Uuid::new_v4()));
            let builder = fs::DirBuilder::new();
            #[cfg(unix)]
            let builder = {
                use std::os::unix::fs::DirBuilderExt;
                let mut builder = builder;
                builder.mode(0o700);
                builder
            };
            builder
                .create(&stage)
                .context("create staging directory beside installed executable")?;
            let result = (|| -> Result<()> {
                for &(name, bytes) in FILES {
                    let path = stage.join(name);
                    fs::create_dir_all(path.parent().unwrap())?;
                    fs::write(path, bytes)?;
                }
                match fs::rename(&stage, &destination) {
                    Ok(()) => Ok(()),
                    Err(_) if destination.exists() => verify(&destination), // concurrent installation
                    Err(e) => Err(e.into()),
                }
            })();
            let _ = fs::remove_dir_all(stage);
            result?;
        }
        Err(e) => return Err(e.into()),
    }
    Ok(destination.join("worker"))
}
pub(crate) fn setup() -> Result<PathBuf> {
    let exe = std::env::current_exe()?;
    install(exe.parent().context("cannot locate installed executable")?)
}
pub(crate) fn default_dir() -> Result<PathBuf> {
    if !FILES.is_empty() {
        return setup();
    }
    Ok(std::env::current_exe()?
        .parent()
        .context("cannot locate installed executable")?
        .join("worker"))
}
