import type { DesktopDevice, DesktopService, PairBeginResult } from "../services/types.js";
import { button, el } from "./dom.js";

export class PairingModal {
  private readonly backdrop = el("div", "modal-backdrop");
  private readonly dialog = el("section", "pairing-modal");
  private closed = false;
  private trustPollTimer: number | null = null;
  private polling = false;
  private pairing: PairBeginResult | null = null;
  private input: HTMLInputElement | null = null;
  private message = el("div", "pair-message");

  constructor(
    private readonly service: DesktopService,
    private readonly device: DesktopDevice,
    private readonly onPaired: (device: DesktopDevice) => void,
    private readonly onClose: () => void,
  ) {
    this.backdrop.append(this.dialog);
    this.backdrop.addEventListener("click", (event) => {
      if (event.target === this.backdrop) this.close();
    });
  }

  async open(): Promise<HTMLElement> {
    this.closed = false;
    this.renderTrust();
    void this.beginTrust();
    return this.backdrop;
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.stopTrustPolling();
    this.backdrop.remove();
    this.onClose();
  }

  private header(title: string): HTMLElement[] {
    const top = el("div", "pair-header");
    const close = button("×", "icon-button");
    close.setAttribute("aria-label", "Close connection setup");
    close.addEventListener("click", () => this.close());
    top.append(el("div", "pair-device-name", this.device.name), close);
    return [top, el("h2", "pair-title", title)];
  }

  private renderTrust(): void {
    this.stopTrustPolling();
    this.message = el("div", "pair-message", "Preparing secure PC trust…");
    const copy = el(
      "p",
      "pair-copy",
      "Your screen can connect with normal USB debugging. For AI and Codex access, Cyclone will ask you once on the phone to Allow this PC.",
    );
    const trustNote = el("div", "pair-countdown", "No local gateway token or pairing code is needed for the normal USB setup.");
    const retry = button("Try again", "button primary wide");
    retry.hidden = true;
    retry.addEventListener("click", () => void this.beginTrust(retry));
    const fallback = button("Use manual pairing fallback", "button ghost wide");
    fallback.addEventListener("click", () => void this.renderManualFallback());
    this.dialog.replaceChildren(...this.header("Allow AI control"), copy, this.message, trustNote, retry, fallback);
    (this.dialog as HTMLElement & { trustRetry?: HTMLButtonElement }).trustRetry = retry;
  }

  private async beginTrust(retry?: HTMLButtonElement): Promise<void> {
    if (this.closed) return;
    if (!this.service.trustBegin || !this.service.trustComplete) {
      this.message.textContent = "This Companion build cannot start one-time trust yet. Use the manual fallback below.";
      this.message.className = "pair-message error";
      if (retry) retry.hidden = true;
      return;
    }
    this.stopTrustPolling();
    this.message.textContent = "Starting one-time trust…";
    this.message.className = "pair-message";
    if (retry) retry.disabled = true;
    try {
      const result = await this.service.trustBegin(this.device.id);
      if (this.closed) return;
      if (result.sessionReady && result.trusted) {
        await this.finishWithLatestDevice("AI/Codex access restored automatically");
        return;
      }
      if (result.confirmationRequired) {
        this.message.textContent = "On your phone, tap Allow this PC. Cyclone will finish automatically.";
        this.message.className = "pair-message success";
        this.startTrustPolling();
        return;
      }
      this.message.textContent = result.lastSafeError || "Cyclone is waiting for the phone trust confirmation.";
      this.startTrustPolling();
    } catch (error) {
      if (this.closed) return;
      this.message.textContent = friendlyTrustError(error);
      this.message.className = "pair-message error";
      const buttonNode = retry ?? (this.dialog as HTMLElement & { trustRetry?: HTMLButtonElement }).trustRetry;
      if (buttonNode) buttonNode.hidden = false;
    } finally {
      if (retry) retry.disabled = false;
    }
  }

  private startTrustPolling(): void {
    this.stopTrustPolling();
    const poll = async () => {
      if (this.closed || this.polling || !this.service.trustComplete) return;
      this.polling = true;
      try {
        const result = await this.service.trustComplete(this.device.id);
        if (this.closed) return;
        if (result.sessionReady && result.trusted) {
          this.stopTrustPolling();
          await this.finishWithLatestDevice("Phone trusted · AI/Codex access ready");
          return;
        }
        if (result.confirmationRequired) {
          this.message.textContent = "Waiting for Allow this PC on the phone…";
        } else if (result.lastSafeError) {
          this.message.textContent = result.lastSafeError;
        }
      } catch (error) {
        if (!this.closed) {
          this.stopTrustPolling();
          this.message.textContent = friendlyTrustError(error);
          this.message.className = "pair-message error";
          const retry = button("Start again", "button primary wide");
          retry.addEventListener("click", () => {
            retry.remove();
            void this.beginTrust();
          });
          this.dialog.append(retry);
        }
      } finally {
        this.polling = false;
      }
    };
    this.trustPollTimer = window.setInterval(() => void poll(), 850);
    void poll();
  }

  private stopTrustPolling(): void {
    if (this.trustPollTimer != null) window.clearInterval(this.trustPollTimer);
    this.trustPollTimer = null;
  }

  private async renderManualFallback(): Promise<void> {
    this.stopTrustPolling();
    this.message = el("div", "pair-message", "Requesting a temporary phone code…");
    const copy = el(
      "p",
      "pair-copy",
      "Manual fallback is for recovery or transition devices. Enter the temporary 4-letter code shown by Cyclone Mobile. AI actions still require one-time Allow this PC trust.",
    );
    this.input = el("input", "pair-code-input") as HTMLInputElement;
    this.input.maxLength = 8;
    this.input.autocomplete = "one-time-code";
    this.input.autocapitalize = "characters";
    this.input.spellcheck = false;
    this.input.placeholder = "ABCD";
    this.input.setAttribute("aria-label", "Manual four-letter fallback code");
    const submit = button("Use fallback code", "button primary wide");
    submit.disabled = true;
    const update = () => {
      if (!this.input) return;
      const normalized = this.input.value.toUpperCase().replace(/[^A-Z]/g, "").slice(0, 4);
      this.input.value = normalized;
      submit.disabled = normalized.length !== 4 || !this.pairing;
    };
    this.input.addEventListener("input", update);
    submit.addEventListener("click", () => void this.confirmManualFallback(submit));
    const normal = button("Back to Allow this PC", "button ghost wide");
    normal.addEventListener("click", () => {
      this.pairing = null;
      this.renderTrust();
      void this.beginTrust();
    });
    this.dialog.replaceChildren(...this.header("Manual pairing fallback"), copy, this.input, this.message, submit, normal);
    try {
      this.pairing = await this.service.pairBegin(this.device.id);
      if (this.closed) return;
      this.message.textContent = "Enter the temporary 4-letter code shown on the phone.";
      this.message.className = "pair-message";
      update();
      this.input.focus();
    } catch (error) {
      this.message.textContent = error instanceof Error ? error.message : "Manual fallback is unavailable.";
      this.message.className = "pair-message error";
    }
  }

  private async confirmManualFallback(submit: HTMLButtonElement): Promise<void> {
    const pairing = this.pairing;
    const code = this.input?.value ?? "";
    if (!pairing || code.length !== 4) return;
    submit.disabled = true;
    submit.textContent = "Checking…";
    try {
      const result = await this.service.pairConfirm(this.device.id, pairing.pairingId, code);
      if (!result.ok) {
        this.message.textContent = result.message || (result.reason === "INVALID_CODE" ? "That code does not match." : "Manual fallback could not finish.");
        this.message.className = "pair-message error";
        return;
      }
      this.message.textContent = "Fallback connection ready. Complete Allow this PC to enable trusted AI actions.";
      this.message.className = "pair-message success";
      window.setTimeout(() => {
        if (this.closed) return;
        this.renderTrust();
        void this.beginTrust();
      }, 500);
    } catch {
      this.message.textContent = "Manual fallback could not finish. Check USB debugging and try again.";
      this.message.className = "pair-message error";
    } finally {
      submit.disabled = false;
      submit.textContent = "Use fallback code";
    }
  }

  private async finishWithLatestDevice(message: string): Promise<void> {
    this.message.textContent = message;
    this.message.className = "pair-message success";
    const devices = await this.service.listDevices();
    const latest = devices.find((candidate) => candidate.id === this.device.id) ?? this.device;
    window.setTimeout(() => {
      if (this.closed) return;
      this.onPaired(latest);
      this.close();
    }, 320);
  }
}

function friendlyTrustError(error: unknown): string {
  const message = error instanceof Error ? error.message.trim() : "";
  if (!message) return "Cyclone could not start one-time trust. Unlock the phone and try again.";
  if (/expired/i.test(message)) return "The phone confirmation expired. Start again and tap Allow this PC.";
  if (/locked/i.test(message)) return "Unlock the phone, then try Allow this PC again.";
  if (/protocol|version/i.test(message)) return "Cyclone Mobile and PC Companion need compatible trust versions.";
  return message.slice(0, 220);
}
