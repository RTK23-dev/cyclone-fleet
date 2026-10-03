/** Coalesces bursty ADB topology events into at most one follow-up inventory request. */
export class TopologyRefreshGate {
  private active = false;
  private queued = false;
  private queuedForceRender = false;

  begin(forceRender: boolean): boolean {
    if (this.active) {
      this.queued = true;
      this.queuedForceRender ||= forceRender;
      return false;
    }
    this.active = true;
    return true;
  }

  finish(): boolean | null {
    this.active = false;
    if (!this.queued) return null;
    const forceRender = this.queuedForceRender;
    this.queued = false;
    this.queuedForceRender = false;
    return forceRender;
  }
}
