export interface VirtualRange {
  startIndex: number;
  endIndexExclusive: number;
  topSpacerPx: number;
  bottomSpacerPx: number;
  totalRows: number;
}

export function fleetColumnCount(deviceCount: number, viewportWidth: number): number {
  if (deviceCount <= 1) return 1;
  if (deviceCount === 2) return 2;
  if (deviceCount <= 4) return 2;
  if (deviceCount <= 6) return 3;

  const widthLimited = Math.max(1, Math.floor(viewportWidth / 250));
  if (deviceCount <= 12) return Math.min(4, widthLimited);
  return Math.max(1, Math.min(6, widthLimited));
}

export function computeVirtualRange(
  totalItems: number,
  columns: number,
  scrollTop: number,
  viewportHeight: number,
  rowHeight: number,
  overscanRows = 2,
): VirtualRange {
  if (totalItems <= 0) {
    return { startIndex: 0, endIndexExclusive: 0, topSpacerPx: 0, bottomSpacerPx: 0, totalRows: 0 };
  }
  const safeColumns = Math.max(1, columns);
  const safeRowHeight = Math.max(1, rowHeight);
  const totalRows = Math.ceil(totalItems / safeColumns);
  const firstVisibleRow = Math.max(0, Math.floor(scrollTop / safeRowHeight));
  const visibleRows = Math.max(1, Math.ceil(viewportHeight / safeRowHeight));
  const startRow = Math.max(0, firstVisibleRow - overscanRows);
  const endRowExclusive = Math.min(totalRows, firstVisibleRow + visibleRows + overscanRows);
  return {
    startIndex: startRow * safeColumns,
    endIndexExclusive: Math.min(totalItems, endRowExclusive * safeColumns),
    topSpacerPx: startRow * safeRowHeight,
    bottomSpacerPx: Math.max(0, (totalRows - endRowExclusive) * safeRowHeight),
    totalRows,
  };
}
