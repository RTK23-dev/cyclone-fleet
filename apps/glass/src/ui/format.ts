/** Human time: "just now", "12 min ago", "3 days ago", then a date. */
export function relativeTime(value: number | string | null | undefined, now = Date.now()): string {
  if (value == null || value === "") return "never";
  const at = typeof value === "number" ? value : Date.parse(value);
  if (!Number.isFinite(at) || at <= 0) return "never";
  const seconds = Math.round((now - at) / 1000);
  if (seconds < 45) return "just now";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days} ${days === 1 ? "day" : "days"} ago`;
  return new Date(at).toISOString().slice(0, 10);
}

export function plural(count: number, one: string, many = `${one}s`): string {
  return `${count} ${count === 1 ? one : many}`;
}

/** A time that may be in the future: "in 12 min", "in 3 h", "Tue 09:00"; past times read like [relativeTime]. */
export function whenLabel(value: number | null | undefined, now = Date.now()): string {
  if (value == null || !Number.isFinite(value)) return "—";
  if (value <= now) return relativeTime(value, now);
  const minutes = Math.round((value - now) / 60_000);
  if (minutes < 1) return "in a moment";
  if (minutes < 60) return `in ${minutes} min`;
  const hours = Math.round(minutes / 60);
  if (hours < 12) return `in ${hours} h`;
  return new Date(value).toLocaleString(undefined, { weekday: "short", hour: "2-digit", minute: "2-digit" });
}
