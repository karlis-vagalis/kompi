//! Private state and no-follow file opens shared by storage and artifact handling.
#[cfg(unix)]
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
#[cfg(windows)]
use std::os::windows::{
    fs::{MetadataExt, OpenOptionsExt},
    io::AsRawHandle,
};
#[cfg(any(unix, test))]
use std::fs;
use std::{
    fs::{File, OpenOptions},
    io,
    path::Path,
};
#[cfg(windows)]
use windows_sys::Win32::Storage::FileSystem::*;

#[cfg(windows)]
fn reject_reparse(file: &File) -> io::Result<()> {
    if file.metadata()?.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0 {
        Err(io::Error::other("refusing reparse point"))
    } else {
        Ok(())
    }
}

/// Hold every ancestor against rename while resolving the final component.
#[cfg(windows)]
fn pin_dirs(path: &Path, create: bool) -> io::Result<Vec<File>> {
    use crate::pi_history::windows::{pin_directory, valid_name};
    let path = std::path::absolute(path)?;
    match pin_directory(&path) {
        Err(e) if create && e.kind() == io::ErrorKind::NotFound => {
            if !path.file_name().is_some_and(valid_name) {
                return Err(io::Error::other("invalid directory name"));
            }
            let _parents = pin_dirs(path.parent().ok_or(e)?, true)?;
            match create_private_dir(&path) {
                Err(e) if e.kind() != io::ErrorKind::AlreadyExists => return Err(e),
                _ => {}
            }
            pin_directory(&path)
        }
        result => result,
    }
}

pub fn open_file(path: &Path, options: &mut OpenOptions) -> io::Result<File> {
    #[cfg(unix)]
    options
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK);
    #[cfg(windows)]
    let _parents = pin_dirs(
        std::path::absolute(path)?
            .parent()
            .ok_or_else(|| io::Error::other("missing parent"))?,
        false,
    )?;
    #[cfg(windows)]
    {
        if !path
            .file_name()
            .is_some_and(crate::pi_history::windows::valid_name)
        {
            return Err(io::Error::other("invalid file name"));
        }
        options.custom_flags(FILE_FLAG_OPEN_REPARSE_POINT);
    }
    let file = options.open(path)?;
    #[cfg(windows)]
    reject_reparse(&file)?;
    if !file.metadata()?.is_file() {
        return Err(io::Error::other("expected regular file"));
    }
    Ok(file)
}

pub struct PrivateDirectory {
    #[cfg(windows)]
    _pins: Vec<File>,
}

pub fn private_dir(path: &Path) -> io::Result<PrivateDirectory> {
    #[cfg(unix)]
    {
        fs::create_dir_all(path)?;
        if !fs::symlink_metadata(path)?.is_dir() {
            return Err(io::Error::other("expected real directory"));
        }
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
        Ok(PrivateDirectory {})
    }
    #[cfg(windows)]
    {
        let pins = pin_dirs(path, true)?;
        private_acl(path, true)?;
        Ok(PrivateDirectory { _pins: pins })
    }
}

pub fn private_file(path: &Path) -> io::Result<()> {
    #[cfg(unix)]
    {
        open_file(path, OpenOptions::new().read(true))?
            .set_permissions(fs::Permissions::from_mode(0o600))
    }
    #[cfg(windows)]
    {
        let _held = pin_dirs(
            path.parent()
                .ok_or_else(|| io::Error::other("missing parent"))?,
            false,
        )?;
        private_acl(path, false)
    }
}

#[cfg(windows)]
fn private_acl(path: &Path, directory: bool) -> io::Result<()> {
    use windows_sys::Win32::Security::*;
    let file = OpenOptions::new()
        .access_mode(READ_CONTROL | WRITE_DAC | WRITE_OWNER | FILE_READ_ATTRIBUTES)
        .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS)
        .open(path)?;
    reject_reparse(&file)?;
    if file.metadata()?.is_dir() != directory {
        return Err(io::Error::other("unexpected state entry type"));
    }
    if !directory {
        let mut info = BY_HANDLE_FILE_INFORMATION::default();
        if unsafe { GetFileInformationByHandle(file.as_raw_handle(), &mut info) } == 0 {
            return Err(io::Error::last_os_error());
        }
        if info.nNumberOfLinks != 1 {
            return Err(io::Error::other("refusing aliased private state file"));
        }
    }
    with_descriptor(directory, |sd| unsafe {
        SetKernelObjectSecurity(
            file.as_raw_handle(),
            OWNER_SECURITY_INFORMATION
                | PROTECTED_DACL_SECURITY_INFORMATION
                | DACL_SECURITY_INFORMATION,
            sd,
        )
    })?;
    verify_private_acl(&file, directory)
}

/// Some volumes accept security descriptor writes without storing them. Never
/// put identity or database bytes there, even when the write reported success.
#[cfg(windows)]
fn verify_private_acl(file: &File, directory: bool) -> io::Result<()> {
    use std::ptr::null_mut;
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::{Authorization::*, *},
    };
    struct Allocation(*mut std::ffi::c_void);
    impl Drop for Allocation {
        fn drop(&mut self) {
            unsafe {
                LocalFree(self.0);
            }
        }
    }
    let user: Vec<_> = crate::local_ipc::current_user_sid()?
        .encode_utf16()
        .chain(Some(0))
        .collect();
    unsafe {
        let (mut sd, mut owner, mut acl, mut sid, mut ace) =
            (null_mut(), null_mut(), null_mut(), null_mut(), null_mut());
        let status = GetSecurityInfo(
            file.as_raw_handle(),
            SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION | OWNER_SECURITY_INFORMATION,
            &mut owner,
            null_mut(),
            &mut acl,
            null_mut(),
            &mut sd,
        );
        if status != 0 {
            return Err(io::Error::from_raw_os_error(status as i32));
        }
        let _descriptor = Allocation(sd);
        if ConvertStringSidToSidW(user.as_ptr(), &mut sid) == 0 {
            return Err(io::Error::last_os_error());
        }
        let _sid = Allocation(sid);
        let denied = || {
            io::Error::new(
                io::ErrorKind::PermissionDenied,
                "state volume did not preserve the user-only ACL",
            )
        };
        if owner.is_null()
            || EqualSid(owner, sid) == 0
            || acl.is_null()
            || (*acl).AceCount != 1
            || GetAce(acl, 0, &mut ace) == 0
        {
            return Err(denied());
        }
        let ace = ace.cast::<ACCESS_ALLOWED_ACE>();
        let inheritance = if directory {
            (OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE) as u8
        } else {
            0
        };
        if (*ace).Header.AceType != 0
            || (*ace).Header.AceFlags != inheritance
            || (*ace).Mask != FILE_ALL_ACCESS
            || EqualSid(sid, (&raw mut (*ace).SidStart).cast()) == 0
        {
            return Err(denied());
        }
    }
    Ok(())
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;
    fn assert_only_user_can_access(path: &Path) {
        let file = OpenOptions::new()
            .access_mode(READ_CONTROL)
            .custom_flags(FILE_FLAG_BACKUP_SEMANTICS)
            .open(path)
            .unwrap();
        verify_private_acl(&file, path.is_dir()).unwrap();
    }

    #[test]
    fn state_and_new_files_have_only_the_user_ace() {
        let dir = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        let (_, lease) = crate::HostIdentity::open(&dir).unwrap();
        assert_only_user_can_access(&dir);
        assert_only_user_can_access(&dir.join("identity.key"));
        drop(lease);
        fs::remove_dir_all(dir).unwrap();
    }
}

#[cfg(windows)]
fn create_private_dir(path: &Path) -> io::Result<()> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Security::SECURITY_ATTRIBUTES;
    let wide: Vec<_> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    with_descriptor(true, |sd| {
        let attrs = SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: sd,
            bInheritHandle: 0,
        };
        unsafe { CreateDirectoryW(wide.as_ptr(), &attrs) }
    })
}

#[cfg(windows)]
fn with_descriptor(
    directory: bool,
    apply: impl FnOnce(windows_sys::Win32::Security::PSECURITY_DESCRIPTOR) -> i32,
) -> io::Result<()> {
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::Authorization::ConvertStringSecurityDescriptorToSecurityDescriptorW,
    };
    let sid = crate::local_ipc::current_user_sid()?;
    let inheritance = if directory { "OICI" } else { "" };
    let descriptor: Vec<_> = format!("O:{sid}D:P(A;{inheritance};FA;;;{sid})\0")
        .encode_utf16()
        .collect();
    let mut sd = std::ptr::null_mut();
    // Conversion allocates a self-relative descriptor; the kernel copies it before LocalFree.
    unsafe {
        if ConvertStringSecurityDescriptorToSecurityDescriptorW(
            descriptor.as_ptr(),
            1,
            &mut sd,
            std::ptr::null_mut(),
        ) == 0
        {
            return Err(io::Error::last_os_error());
        }
        let result = apply(sd);
        let error = (result == 0).then(io::Error::last_os_error);
        LocalFree(sd);
        error.map_or(Ok(()), Err)
    }
}
