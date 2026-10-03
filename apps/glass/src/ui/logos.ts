/**
 * The two marks in the top-left switcher: Cyclone (the phone app's seven-petal vortex) and the Command Center (a page
 * with a plan on it, in Glass's indigo). Built with DOM APIs, like every icon in Glass.
 */
import { CYCLONE_PETALS } from "./cycloneMark.js";

const SVG_NS = "http://www.w3.org/2000/svg";
let serial = 0;

function svgEl<K extends string>(tag: K, attrs: Record<string, string>): SVGElement {
  const node = document.createElementNS(SVG_NS, tag) as SVGElement;
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  return node;
}

function gradient(id: string, stops: Array<[string, string]>, x1 = "0", y1 = "0", x2 = "1", y2 = "1"): SVGElement {
  const g = svgEl("linearGradient", { id, x1, y1, x2, y2 });
  for (const [offset, color] of stops) g.append(svgEl("stop", { offset, "stop-color": color }));
  return g;
}

/**
 * Cyclone: seven petals spiralling into a void (brand/logo-wormhole), on the dark teal tile of the phone's app icon.
 * A lit funnel (radial gradient), a void shadow under the petals and a soft top-left sheen, as in the brand file.
 */
export function cycloneLogo(className = "logo logo-cyclone"): SVGSVGElement {
  const n = (serial += 1);
  const svg = svgEl("svg", { viewBox: "0 0 128 128", class: className, "aria-hidden": "true" }) as SVGSVGElement;
  const defs = svgEl("defs", {});
  const radial = (id: string, r: string, stops: Array<[string, string, string?]>) => {
    const g = svgEl("radialGradient", { id, gradientUnits: "userSpaceOnUse", cx: "100", cy: "100", r });
    for (const [offset, color, opacity] of stops) {
      g.append(svgEl("stop", { offset, "stop-color": color, ...(opacity ? { "stop-opacity": opacity } : {}) }));
    }
    return g;
  };
  defs.append(
    radial(`cy-petal-${n}`, "91", [["0", "#000405"], ["0.1", "#021316"], ["0.3", "#0B5458"], ["0.58", "#2EC2B9"], ["0.78", "#5BE3D6"], ["1", "#2AA9A3"]]),
    radial(`cy-void-${n}`, "52", [["0", "#000405"], ["0.35", "#011012"], ["0.7", "#0A4D50", "0.9"], ["1", "#1C9E98", "0"]]),
  );
  const sheen = svgEl("linearGradient", { id: `cy-sheen-${n}`, gradientUnits: "userSpaceOnUse", x1: "25", y1: "18", x2: "175", y2: "182" });
  for (const [offset, color, opacity] of [["0", "#FFFFFF", "0.28"], ["0.45", "#FFFFFF", "0"], ["0.62", "#000000", "0"], ["1", "#001214", "0.3"]]) {
    sheen.append(svgEl("stop", { offset, "stop-color": color, "stop-opacity": opacity }));
  }
  defs.append(sheen);
  const mark = svgEl("g", { transform: "translate(64 64) scale(0.5) translate(-100 -100)" });
  mark.append(
    svgEl("circle", { cx: "100", cy: "100", r: "52", fill: `url(#cy-void-${n})` }),
    svgEl("path", { d: CYCLONE_PETALS, "fill-rule": "evenodd", fill: `url(#cy-petal-${n})` }),
    svgEl("path", { d: CYCLONE_PETALS, "fill-rule": "evenodd", fill: `url(#cy-sheen-${n})` }),
  );
  svg.append(svgEl("rect", { x: "0", y: "0", width: "128", height: "128", rx: "30", class: "logo-tile" }), defs, mark);
  return svg;
}

/** Command Center: a page with a heading line, two text lines and a ticked plan card, on an indigo tile. */
export function commandLogo(className = "logo logo-command"): SVGSVGElement {
  const id = `cc-tile-${(serial += 1)}`;
  const svg = svgEl("svg", { viewBox: "0 0 128 128", class: className, "aria-hidden": "true" }) as SVGSVGElement;
  const defs = svgEl("defs", {});
  defs.append(gradient(id, [["0", "#7A7AF2"], ["1", "#3F3FB8"]]));
  svg.append(defs, svgEl("rect", { x: "0", y: "0", width: "128", height: "128", rx: "30", fill: `url(#${id})` }));
  svg.append(
    svgEl("rect", { x: "30", y: "26", width: "68", height: "78", rx: "10", fill: "#FFFFFF", opacity: "0.96" }),
    svgEl("rect", { x: "41", y: "38", width: "34", height: "8", rx: "4", fill: "#3F3FB8" }),
    svgEl("rect", { x: "41", y: "53", width: "46", height: "5", rx: "2.5", fill: "#B9B9EE" }),
    svgEl("rect", { x: "41", y: "63", width: "38", height: "5", rx: "2.5", fill: "#B9B9EE" }),
    svgEl("rect", { x: "41", y: "76", width: "46", height: "17", rx: "5", fill: "#EDEDFC" }),
    svgEl("path", { d: "M47 84.5l4 4 8-8", fill: "none", stroke: "#1F9D5C", "stroke-width": "4", "stroke-linecap": "round", "stroke-linejoin": "round" }),
    svgEl("rect", { x: "64", y: "82.5", width: "17", height: "4", rx: "2", fill: "#8C8CE0" }),
  );
  return svg;
}
