// Keep the suffix in sync with Rust's pi_history::LOCK_SUFFIX and the terminal wrapper.
export const transcriptLockPath = (transcript: string): string => `${transcript}.kompi-lock`;
// util-linux flock exits with this status without starting Pi on contention.
export const TRANSCRIPT_LOCK_CONFLICT = 73;
