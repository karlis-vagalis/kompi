use sha2::{Digest, Sha256};
use std::{
    io,
    mem::size_of,
    os::windows::{
        ffi::OsStrExt,
        io::{AsRawHandle, FromRawHandle, OwnedHandle},
    },
    path::{Path, PathBuf},
    ptr::{null, null_mut},
};
use tokio::net::windows::named_pipe::{
    ClientOptions, NamedPipeClient, NamedPipeServer, ServerOptions,
};
use windows_sys::Win32::{
    Foundation::LocalFree,
    Security::{Authorization::*, *},
    System::Threading::{GetCurrentProcess, OpenProcessToken},
};

/// Install before spawning any workers. Children inherit membership, not the
/// job handle, so forced daemon termination also retires Bun, Pi and helpers.
pub(crate) fn contain_daemon_tree() -> io::Result<()> {
    use windows_sys::Win32::System::JobObjects::*;
    unsafe {
        let raw = CreateJobObjectW(null(), null());
        if raw.is_null() {
            return Err(io::Error::last_os_error());
        }
        let job = OwnedHandle::from_raw_handle(raw);
        let mut limits = JOBOBJECT_EXTENDED_LIMIT_INFORMATION::default();
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        checked(SetInformationJobObject(
            raw,
            JobObjectExtendedLimitInformation,
            (&limits as *const JOBOBJECT_EXTENDED_LIMIT_INFORMATION).cast(),
            size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
        ))?;
        checked(AssignProcessToJobObject(raw, GetCurrentProcess()))?;
        // The daemon itself is in this job. Keep its sole non-inheritable
        // handle until process exit, including through graceful shutdown.
        std::mem::forget(job);
    }
    Ok(())
}
pub type ServerStream = NamedPipeServer;
struct Local(*mut std::ffi::c_void);
impl Drop for Local {
    fn drop(&mut self) {
        unsafe {
            LocalFree(self.0);
        }
    }
}
fn checked(ok: i32) -> io::Result<()> {
    if ok == 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(())
    }
}
unsafe fn sid_string(sid: PSID) -> io::Result<String> {
    let mut out = null_mut();
    unsafe {
        checked(ConvertSidToStringSidW(sid, &mut out))?;
    }
    let _allocation = Local(out.cast());
    let mut len = 0;
    unsafe {
        while *out.add(len) != 0 {
            len += 1;
        }
        Ok(String::from_utf16_lossy(std::slice::from_raw_parts(
            out, len,
        )))
    }
}
pub fn current_user_sid() -> io::Result<String> {
    unsafe {
        let mut token = null_mut();
        checked(OpenProcessToken(
            GetCurrentProcess(),
            TOKEN_QUERY,
            &mut token,
        ))?;
        let _token = OwnedHandle::from_raw_handle(token);
        let mut needed = 0;
        GetTokenInformation(token, TokenUser, null_mut(), 0, &mut needed);
        if needed == 0 {
            return Err(io::Error::last_os_error());
        }
        let mut aligned = vec![0usize; (needed as usize).div_ceil(size_of::<usize>())];
        checked(GetTokenInformation(
            token,
            TokenUser,
            aligned.as_mut_ptr().cast(),
            needed,
            &mut needed,
        ))?;
        sid_string((*aligned.as_ptr().cast::<TOKEN_USER>()).User.Sid)
    }
}
pub fn pipe_path(state: &Path) -> PathBuf {
    let state = state.canonicalize().unwrap_or_else(|_| state.to_path_buf());
    let mut hash = Sha256::new();
    for unit in state.as_os_str().encode_wide() {
        hash.update(unit.to_le_bytes());
    }
    format!(r"\\.\pipe\kompi-control-{}", hex::encode(hash.finalize())).into()
}
fn create(path: &Path, first: bool) -> io::Result<ServerStream> {
    let sid = current_user_sid()?;
    let sddl: Vec<u16> = format!("O:{sid}D:P(A;;GA;;;{sid})\0")
        .encode_utf16()
        .collect();
    unsafe {
        let mut sd = null_mut();
        checked(ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl.as_ptr(),
            SDDL_REVISION_1,
            &mut sd,
            null_mut(),
        ))?;
        let _allocation = Local(sd);
        let mut attrs = SECURITY_ATTRIBUTES {
            nLength: size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: sd,
            bInheritHandle: 0,
        };
        ServerOptions::new()
            .first_pipe_instance(first)
            .reject_remote_clients(true)
            .create_with_security_attributes_raw(
                path,
                (&mut attrs as *mut SECURITY_ATTRIBUTES).cast(),
            )
    }
}
pub struct Listener {
    path: PathBuf,
    next: ServerStream,
}
impl Listener {
    pub fn bind(path: &Path) -> io::Result<Self> {
        Ok(Self {
            path: path.into(),
            next: create(path, true)?,
        })
    }
    pub async fn accept(&mut self) -> io::Result<(ServerStream, ())> {
        self.next.connect().await?;
        let next = create(&self.path, false)?;
        Ok((std::mem::replace(&mut self.next, next), ()))
    }
}
fn verify(pipe: &NamedPipeClient) -> io::Result<()> {
    unsafe {
        let mut owner = null_mut();
        let mut sd = null_mut();
        let error = GetSecurityInfo(
            pipe.as_raw_handle(),
            SE_KERNEL_OBJECT,
            OWNER_SECURITY_INFORMATION,
            &mut owner,
            null_mut(),
            null_mut(),
            null_mut(),
            &mut sd,
        );
        if error != 0 {
            return Err(io::Error::from_raw_os_error(error as i32));
        }
        let _allocation = Local(sd);
        if owner.is_null() || sid_string(owner)? != current_user_sid()? {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "control pipe owner mismatch",
            ));
        }
    }
    Ok(())
}
#[cfg(test)]
#[tokio::test]
async fn pipe_has_only_a_user_ace_and_rejects_duplicate_listeners() -> io::Result<()> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let path = PathBuf::from(format!(r"\\.\pipe\kompi-test-{}", uuid::Uuid::new_v4()));
    let mut listener = Listener::bind(&path)?;
    unsafe {
        let mut acl = null_mut();
        let mut sd = null_mut();
        let err = GetSecurityInfo(
            listener.next.as_raw_handle(),
            SE_KERNEL_OBJECT,
            DACL_SECURITY_INFORMATION,
            null_mut(),
            null_mut(),
            &mut acl,
            null_mut(),
            &mut sd,
        );
        assert_eq!(err, 0);
        let _allocation = Local(sd);
        assert!(!acl.is_null());
        assert_eq!((*acl).AceCount, 1);
        let mut ace = null_mut();
        checked(GetAce(acl, 0, &mut ace))?;
        let ace = &*ace.cast::<ACCESS_ALLOWED_ACE>();
        assert_eq!(ace.Header.AceType, 0); // ACCESS_ALLOWED_ACE_TYPE
        assert_eq!(
            sid_string(std::ptr::addr_of!(ace.SidStart).cast_mut().cast())?,
            current_user_sid()?
        );
    }
    for expected in [1u8, 2] {
        let mut client = connect(&path).await?;
        let (mut server, _) = listener.accept().await?;
        client.write_u8(expected).await?;
        assert_eq!(server.read_u8().await?, expected);
    }
    assert!(Listener::bind(&path).is_err());
    drop(listener);
    assert!(connect(&path).await.is_err());
    Ok(())
}
pub async fn connect(path: &Path) -> io::Result<NamedPipeClient> {
    loop {
        match ClientOptions::new()
            .security_qos_flags(windows_sys::Win32::Storage::FileSystem::SECURITY_IDENTIFICATION)
            .open(path)
        {
            Ok(pipe) => {
                verify(&pipe)?;
                return Ok(pipe);
            }
            Err(e) if e.raw_os_error() == Some(231) => {
                tokio::time::sleep(std::time::Duration::from_millis(10)).await
            }
            Err(e) => return Err(e),
        }
    }
}
