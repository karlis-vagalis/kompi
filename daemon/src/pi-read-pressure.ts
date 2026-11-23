type PauseReader = (paused: boolean) => void;

/** Queue bytes belong to the shared writer, not to any individual Pi reader. */
export class PiReadPressure {
  bytes = 0;
  private paused = new Map<string, PauseReader>();

  enqueue(bytes: number, epoch: string, pause?: PauseReader): void {
    this.bytes += bytes;
    if (this.bytes >= 1024 * 1024 && pause && !this.paused.has(epoch)) {
      this.paused.set(epoch, pause);
      pause(true);
    }
  }

  complete(bytes: number): void {
    this.bytes -= bytes;
    if (this.bytes <= 512 * 1024) this.resumeAll();
  }

  resume(epoch: string): void {
    const reader = this.paused.get(epoch);
    this.paused.delete(epoch);
    reader?.(false);
  }

  resumeAll(): void {
    for (const epoch of this.paused.keys()) this.resume(epoch);
  }
}
