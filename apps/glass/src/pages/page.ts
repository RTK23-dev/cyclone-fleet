/** Every page is an element plus a teardown. The shell mounts one page at a time. */
export interface GlassPage {
  element: HTMLElement;
  destroy(): void;
  /** Pages with live local state (a connect in progress) take new device lists instead of being re-mounted. */
  update?(ctx: import("../app.js").GlassContext): void;
}
