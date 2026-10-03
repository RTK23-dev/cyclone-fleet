/**
 * Tracks only which device owns keyboard capture. Typed characters are deliberately never stored.
 */
export class KeyboardCapture {
  private deviceId: string | null = null;

  start(deviceId: string): void {
    if (!deviceId) throw new Error("Keyboard capture requires a device");
    this.deviceId = deviceId;
  }

  stop(): void {
    this.deviceId = null;
  }

  activeDeviceId(): string | null {
    return this.deviceId;
  }

  isActiveFor(deviceId: string): boolean {
    return this.deviceId === deviceId;
  }
}
