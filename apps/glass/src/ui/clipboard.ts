/** Copy text with the browser's clipboard. False when the browser refuses (no permission, no secure context). */
export async function copyText(text: string): Promise<boolean> {
  const clipboard = (globalThis as { navigator?: { clipboard?: { writeText(value: string): Promise<void> } } }).navigator?.clipboard;
  if (!clipboard) return false;
  try {
    await clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}
