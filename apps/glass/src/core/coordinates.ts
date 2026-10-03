export interface RectLike {
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface NormalizedPoint {
  x: number;
  y: number;
}

export type NormalizedPointerGesture =
  | { type: "tap"; x: number; y: number }
  | { type: "swipe"; x1: number; y1: number; x2: number; y2: number; durationMs: number };

export function mapPointerToNormalized(
  clientX: number,
  clientY: number,
  viewport: RectLike,
  sourceWidth: number,
  sourceHeight: number,
  rotationDegrees: 0 | 90 | 180 | 270,
): NormalizedPoint | null {
  if (viewport.width <= 0 || viewport.height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return null;

  const displayedWidth = rotationDegrees === 90 || rotationDegrees === 270 ? sourceHeight : sourceWidth;
  const displayedHeight = rotationDegrees === 90 || rotationDegrees === 270 ? sourceWidth : sourceHeight;
  const scale = Math.min(viewport.width / displayedWidth, viewport.height / displayedHeight);
  const contentWidth = displayedWidth * scale;
  const contentHeight = displayedHeight * scale;
  const offsetX = (viewport.width - contentWidth) / 2;
  const offsetY = (viewport.height - contentHeight) / 2;
  const localX = clientX - viewport.left - offsetX;
  const localY = clientY - viewport.top - offsetY;

  if (localX < 0 || localY < 0 || localX > contentWidth || localY > contentHeight) return null;

  const displayX = clamp01(localX / contentWidth);
  const displayY = clamp01(localY / contentHeight);
  return unrotate(displayX, displayY, rotationDegrees);
}

export function mapPointerGesture(
  start: { clientX: number; clientY: number; startedAtMs: number },
  end: { clientX: number; clientY: number; endedAtMs: number },
  viewport: RectLike,
  sourceWidth: number,
  sourceHeight: number,
  rotationDegrees: 0 | 90 | 180 | 270,
  dragThresholdPx = 8,
): NormalizedPointerGesture | null {
  const from = mapPointerToNormalized(start.clientX, start.clientY, viewport, sourceWidth, sourceHeight, rotationDegrees);
  const to = mapPointerToNormalized(end.clientX, end.clientY, viewport, sourceWidth, sourceHeight, rotationDegrees);
  if (!from || !to) return null;
  const distance = Math.hypot(end.clientX - start.clientX, end.clientY - start.clientY);
  if (distance < dragThresholdPx) return { type: "tap", x: to.x, y: to.y };
  return {
    type: "swipe",
    x1: from.x,
    y1: from.y,
    x2: to.x,
    y2: to.y,
    durationMs: Math.max(120, Math.min(1200, Math.round(end.endedAtMs - start.startedAtMs))),
  };
}

function unrotate(x: number, y: number, rotation: 0 | 90 | 180 | 270): NormalizedPoint {
  switch (rotation) {
    case 90:
      return { x: y, y: 1 - x };
    case 180:
      return { x: 1 - x, y: 1 - y };
    case 270:
      return { x: 1 - y, y: x };
    default:
      return { x, y };
  }
}

function clamp01(value: number): number {
  return Math.max(0, Math.min(1, value));
}
