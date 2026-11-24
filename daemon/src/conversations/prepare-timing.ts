// Capture the original sink before worker.ts suppresses arbitrary dependency output.
const write = process.stderr.write.bind(process.stderr);
type PrepareStage = "pi-launch" | "pi-initial-state" | "prompt-baseline" | "prompt-selection";

/** Only fixed stage names and monotonic durations cross the worker log boundary. */
export function preparationTiming(stage: PrepareStage, start: number): void {
  if (process.env.KOMPI_PREPARE_TIMING !== undefined) {
    write(`[pi-worker] prepare ${stage} ${Math.round(performance.now() - start)}\n`);
  }
}
