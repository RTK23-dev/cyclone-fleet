export function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  className?: string,
  text?: string,
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

export function button(label: string, className = "button secondary"): HTMLButtonElement {
  const node = el("button", className, label);
  node.type = "button";
  return node;
}

export function setChildren(parent: HTMLElement, ...children: Array<Node | null | undefined>): void {
  parent.replaceChildren(...children.filter((child): child is Node => child != null));
}

export function icon(symbol: string): HTMLSpanElement {
  const node = el("span", "icon", symbol);
  node.setAttribute("aria-hidden", "true");
  return node;
}
