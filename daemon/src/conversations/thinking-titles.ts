/** Extract only standalone bold headings, never reasoning paragraphs or signatures.
 * Keep at most one short line in memory, even for an unbounded thinking stream.
 */
export class ThinkingTitles {
  private line = "";
  private discarded = false;
  private readonly emitted = new Set<string>();

  append(delta: string): string[] {
    const titles: string[] = [];
    for (const character of delta) {
      if (character === "\n") {
        const title = this.finishLine();
        if (title) titles.push(title);
      } else if (!this.discarded) {
        this.line += character;
        if (this.line.length > 200) {
          this.line = "";
          this.discarded = true;
        }
      }
    }
    return titles;
  }

  end(finalContent?: string): string[] {
    const title = this.finishLine();
    const titles = title ? [title] : [];
    // Some providers supply the summary only on thinking_end, or revise it there.
    // Parse that authoritative content with the same heading-only rules, deduplicated.
    if (finalContent !== undefined) {
      const final = new ThinkingTitles();
      for (const candidate of [...final.append(finalContent), ...final.end()]) {
        if (this.emitted.size < 32 && !this.emitted.has(candidate)) {
          this.emitted.add(candidate);
          titles.push(candidate);
        }
      }
    }
    return titles;
  }

  private finishLine(): string | undefined {
    const match = this.discarded ? null : /^\s*\*\*([^*\r\n]{1,160})\*\*\s*$/.exec(this.line);
    this.line = "";
    this.discarded = false;
    const title = match?.[1]?.replace(/[\u0000-\u001f\u007f]/g, " ").trim();
    if (!title || this.emitted.size >= 32 || this.emitted.has(title)) return undefined;
    this.emitted.add(title);
    return title;
  }
}
