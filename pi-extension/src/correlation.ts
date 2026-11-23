/** Correlates Pi's text-only prompt API with the user message that actually entered the run. */
export class RunCorrelation {
  private beforeAgentStartSeen = false;
  private initialMessageSeen = false;
  private contamination = false;

  constructor(private readonly exactSubmittedText: string) {}

  observeBeforeAgentStart(prompt: string): void {
    if (prompt === this.exactSubmittedText) this.beforeAgentStartSeen = true;
    else this.contamination = true;
  }

  observeUserMessage(text: string): void {
    if (!this.initialMessageSeen && text === this.exactSubmittedText) {
      this.initialMessageSeen = true;
      return;
    }
    this.contamination = true;
  }

  get canAttributeResult(): boolean { return this.beforeAgentStartSeen && !this.contamination; }
  get contaminated(): boolean { return this.contamination; }
}

export type AttributedText = { kind: "attributed"; text: string } | { kind: "withheld"; reason: string };

export function attributeAssistantText(correlation: RunCorrelation | undefined, text: string): AttributedText {
  if (correlation?.canAttributeResult) return { kind: "attributed", text };
  return {
    kind: "withheld",
    reason: correlation?.contaminated
      ? "Terminal input joined this Pi run; result withheld to avoid misattribution. See the Pi session."
      : "The submitted input could not be independently correlated; result withheld. See the Pi session.",
  };
}
