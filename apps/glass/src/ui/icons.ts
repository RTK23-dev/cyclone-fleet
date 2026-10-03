/** Stroke icons (24px grid, currentColor). Paths only, no external assets, so the strict CSP stays `self`. */
const SVG_NS = "http://www.w3.org/2000/svg";

const PATHS = {
  home: ["M4 11l8-7 8 7", "M6 10v10h12V10", "M10 20v-6h4v6"],
  apps: ["M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h6v6h-6z"],
  phone: ["M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M11 18h2"],
  settings: ["M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z", "M4 12h2M18 12h2M12 4v2M12 18v2M6.3 6.3l1.4 1.4M16.3 16.3l1.4 1.4M6.3 17.7l1.4-1.4M16.3 7.7l1.4-1.4"],
  map: ["M9 4 3 6v14l6-2 6 2 6-2V4l-6 2-6-2z", "M9 4v14M15 6v14"],
  search: ["M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z", "M20 20l-4-4"],
  refresh: ["M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7"],
  play: ["M7 4l13 8-13 8z"],
  pause: ["M7 4h3v16H7zM14 4h3v16h-3z"],
  stop: ["M6 6h12v12H6z"],
  back: ["M15 18l-6-6 6-6"],
  chevron: ["M9 6l6 6-6 6"],
  alert: ["M12 3l10 18H2z", "M12 10v4M12 17v.5"],
  close: ["M6 6l12 12M18 6L6 18"],
  help: ["M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6v.6", "M12 17v.5"],
  plug: ["M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0z", "M12 17v4"],
  hand: ["M8 13V5a1.5 1.5 0 0 1 3 0v6", "M11 11V4a1.5 1.5 0 0 1 3 0v7", "M14 11V6a1.5 1.5 0 0 1 3 0v8a7 7 0 0 1-7 7h-1a6 6 0 0 1-5-3l-2-4a1.5 1.5 0 0 1 2.5-1.5L8 15"],
  send: ["M4 12l16-8-6 16-3-7z"],
  runs: ["M4 6h16M4 12h10M4 18h13", "M18 10l3 2-3 2"],
  store: ["M4 9l1.5-5h13L20 9", "M4 9h16v2a2.7 2.7 0 0 1-5.3 0 2.7 2.7 0 0 1-5.4 0A2.7 2.7 0 0 1 4 11z", "M5 12v8h14v-8", "M10 20v-5h4v5"],
  flask: ["M9 3h6", "M10 3v6L4.5 18.5A1.7 1.7 0 0 0 6 21h12a1.7 1.7 0 0 0 1.5-2.5L14 9V3", "M7 15h10"],
  download: ["M12 4v11", "M7 10l5 5 5-5", "M5 20h14"],
  book: ["M5 4h9a4 4 0 0 1 4 4v12H9a4 4 0 0 1-4-4z", "M5 16a4 4 0 0 1 4-4h9"],
  lock: ["M6 11h12v9H6z", "M8 11V8a4 4 0 0 1 8 0v3"],
  shield: ["M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z", "M9 12l2 2 4-4"],
  user: ["M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z", "M4 21a8 8 0 0 1 16 0"],
  bell: ["M6 17v-6a6 6 0 0 1 12 0v6l2 2H4z", "M10 21h4"],
  chat: ["M4 5h16v11H9l-5 4z"],
  camera: ["M4 8h4l2-3h4l2 3h4v11H4z", "M12 17a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z"],
  layers: ["M12 3l9 5-9 5-9-5z", "M3 13l9 5 9-5"],
  clock: ["M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M12 7v5l3 2"],
  bag: ["M5 8h14l-1 12H6z", "M9 8a3 3 0 0 1 6 0"],
  star: ["M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z"],
  // Plan 48 (Ports): a hub with four links, the two directions, keys, checks.
  port: ["M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z", "M12 3v6M12 15v6M3 12h6M15 12h6", "M12 3h.01M12 21h.01M3 12h.01M21 12h.01"],
  out: ["M7 17L17 7", "M9 7h8v8"],
  in: ["M17 7L7 17", "M15 17H7V9"],
  key: ["M8 15a4 4 0 1 0 0-8 4 4 0 0 0 0 8z", "M11.5 11H21M18 11v3M15 11v2"],
  check: ["M5 12.5l4.5 4.5L19 7.5"],
  copy: ["M9 9h11v11H9z", "M5 15H4V4h11v1"],
  pulse: ["M3 12h4l2-6 4 12 2-6h6"],
  trash: ["M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13"],
  plus: ["M12 5v14M5 12h14"],
} as const;

export type IconName = keyof typeof PATHS;

export function icon(name: IconName, className = "icon"): SVGSVGElement {
  const svg = document.createElementNS(SVG_NS, "svg") as SVGSVGElement;
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "1.8");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("class", className);
  for (const d of PATHS[name]) {
    const path = document.createElementNS(SVG_NS, "path");
    path.setAttribute("d", d);
    svg.append(path);
  }
  return svg;
}
