/** Tiny DOM helpers. Glass renders with plain DOM so pages stay testable under tests/helpers/mini-dom. */
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

export function button(label: string, className = "btn"): HTMLButtonElement {
  const node = el("button", className, label);
  node.type = "button";
  return node;
}

export function link(label: string, href: string, className?: string): HTMLAnchorElement {
  const node = el("a", className, label);
  node.href = href;
  return node;
}

export function setChildren(parent: HTMLElement, ...children: Array<Node | null | undefined | false>): void {
  parent.replaceChildren(...children.filter((child): child is Node => Boolean(child)));
}
