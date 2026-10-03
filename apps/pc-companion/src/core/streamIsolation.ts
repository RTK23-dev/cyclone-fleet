import type { StreamUiState } from "../services/types.js";

export class StreamIsolationRegistry {
  private readonly states = new Map<string, StreamUiState>();

  set(deviceId: string, state: StreamUiState): void {
    this.states.set(deviceId, state);
  }

  get(deviceId: string): StreamUiState {
    return this.states.get(deviceId) ?? "CONNECTING";
  }

  snapshot(): ReadonlyMap<string, StreamUiState> {
    return new Map(this.states);
  }
}
