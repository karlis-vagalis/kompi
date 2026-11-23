use std::{
    ffi::CString,
    os::unix::ffi::OsStrExt,
    path::Path,
};

/// Minimum headroom needed for a maximum-sized v3 frame and its framing prefix.
/// This is a protocol-derived transient working-set floor, not a worker/queue cap.
const MIN_FRAME_HEADROOM_BYTES: u64 = 65_540;

#[derive(Debug)]
pub enum AdmissionError {
    Pressure,
    Unavailable(String),
}

/// Reject when a usable Linux resource metric reports pressure. Memory PSI and
/// meminfo are independent signals: an unavailable signal is not evidence of
/// pressure, and does not invalidate the other signal. Call immediately before
/// durable request acceptance; callers should return a retryable preacceptance
/// rejection and must not create a claim/session on either error.
pub fn check(state_dir: &Path) -> Result<(), AdmissionError> {
    let available_memory = available_memory_bytes();
    let memory_pressure = memory_full_stall_active();
    let available_storage = available_storage_bytes(state_dir);

    // Any reliable reading that demonstrates pressure wins, even if another
    // metric is unavailable.
    if available_memory.as_ref().is_ok_and(|bytes| *bytes < MIN_FRAME_HEADROOM_BYTES)
        || memory_pressure.as_ref().is_ok_and(|active| *active)
        || available_storage.as_ref().is_ok_and(|bytes| *bytes < MIN_FRAME_HEADROOM_BYTES)
    {
        return Err(AdmissionError::Pressure);
    }

    // If neither memory signal is readable, memory headroom is unknown rather
    // than pressured. Keep that distinct from a pressure rejection; usable
    // metrics above still determine pressure independently.
    if available_memory.is_err() && memory_pressure.is_err() {
        return Err(AdmissionError::Unavailable(format!(
            "memory headroom and PSI are unavailable (meminfo: {}; PSI: {})",
            available_memory.unwrap_err(),
            memory_pressure.unwrap_err(),
        )));
    }
    if let Err(error) = available_storage {
        return Err(AdmissionError::Unavailable(error.to_string()));
    }
    Ok(())
}

fn available_memory_bytes() -> std::io::Result<u64> {
    let contents = std::fs::read_to_string("/proc/meminfo")?;
    let kib = contents.lines().find_map(|line| {
        let (key, rest) = line.split_once(':')?;
        if key != "MemAvailable" { return None; }
        rest.split_whitespace().next()?.parse::<u64>().ok()
    }).ok_or_else(|| std::io::Error::other("MemAvailable is unavailable"))?;
    kib.checked_mul(1024).ok_or_else(|| std::io::Error::other("MemAvailable overflow"))
}

fn memory_full_stall_active() -> std::io::Result<bool> {
    let contents = std::fs::read_to_string("/proc/pressure/memory")?;
    let full = contents.lines().find(|line| line.starts_with("full "))
        .ok_or_else(|| std::io::Error::other("memory PSI full metric is unavailable"))?;
    let avg10 = full.split_whitespace().find_map(|field| field.strip_prefix("avg10="))
        .ok_or_else(|| std::io::Error::other("memory PSI avg10 is unavailable"))?
        .parse::<f64>().map_err(|_| std::io::Error::other("memory PSI avg10 is invalid"))?;
    if !avg10.is_finite() || avg10 < 0.0 {
        return Err(std::io::Error::other("memory PSI avg10 is invalid"));
    }
    Ok(avg10 > 0.0)
}

fn available_storage_bytes(path: &Path) -> std::io::Result<u64> {
    let path = CString::new(path.as_os_str().as_bytes())
        .map_err(|_| std::io::Error::other("state directory path contains NUL"))?;
    let mut stats = std::mem::MaybeUninit::<libc::statvfs>::uninit();
    // SAFETY: path is a valid NUL-terminated path and stats points to writable storage.
    let result = unsafe { libc::statvfs(path.as_ptr(), stats.as_mut_ptr()) };
    if result != 0 { return Err(std::io::Error::last_os_error()); }
    // SAFETY: statvfs initialized stats after returning success.
    let stats = unsafe { stats.assume_init() };
    u64::from(stats.f_bavail).checked_mul(u64::from(stats.f_frsize))
        .ok_or_else(|| std::io::Error::other("available state storage size overflow"))
}
