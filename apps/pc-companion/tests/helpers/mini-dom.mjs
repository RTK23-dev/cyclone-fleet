const SVG_NS = "http://www.w3.org/2000/svg";

class MiniClassList {
  constructor(node) {
    this.node = node;
  }
  _tokens() {
    return this.node.className.split(/\s+/).filter(Boolean);
  }
  _set(tokens) {
    this.node.className = [...new Set(tokens)].join(" ");
  }
  add(...names) {
    this._set([...this._tokens(), ...names]);
  }
  remove(...names) {
    const drop = new Set(names);
    this._set(this._tokens().filter((name) => !drop.has(name)));
  }
  toggle(name, force) {
    const has = this.contains(name);
    const should = force == null ? !has : Boolean(force);
    if (should) this.add(name);
    else this.remove(name);
    return should;
  }
  contains(name) {
    return this._tokens().includes(name);
  }
}

class MiniNode {
  constructor(tagName, namespaceURI = null) {
    this.tagName = String(tagName || "div").toUpperCase();
    this.namespaceURI = namespaceURI;
    this.children = [];
    this.childNodes = this.children;
    this.parentNode = null;
    this.parentElement = null;
    this.attributes = {};
    this.style = {};
    this.listeners = {};
    this._text = "";
    this._className = "";
    this._id = "";
    this.disabled = false;
    this.hidden = false;
    this.title = "";
    this.type = "";
    this.tabIndex = 0;
    this.value = "";
    this.placeholder = "";
    this.href = "";
    this.rel = "";
    this.classList = new MiniClassList(this);
    this.ownerDocument = null;
    this.dataset = new Proxy(this, {
      get: (target, prop) => {
        if (typeof prop !== "string") return undefined;
        return target.getAttribute(`data-${toKebab(prop)}`);
      },
      set: (target, prop, value) => {
        if (typeof prop === "string") target.setAttribute(`data-${toKebab(prop)}`, String(value));
        return true;
      },
    });
  }
  get className() {
    return this._className;
  }
  set className(value) {
    this._className = String(value ?? "");
    this.attributes.class = this._className;
  }
  get id() {
    return this._id;
  }
  set id(value) {
    this._id = String(value ?? "");
    this.attributes.id = this._id;
  }
  get textContent() {
    if (this.children.length === 0) return this._text;
    return this.children.map((child) => child.textContent).join("");
  }
  set textContent(value) {
    this.children.splice(0).forEach((child) => {
      child.parentNode = null;
      child.parentElement = null;
    });
    this._text = value == null ? "" : String(value);
  }
  setAttribute(name, value) {
    const key = String(name);
    this.attributes[key] = String(value);
    if (key === "class") this._className = String(value);
    if (key === "id") this._id = String(value);
    if (key === "hidden") this.hidden = true;
  }
  getAttribute(name) {
    const key = String(name);
    if (key === "class") return this._className || null;
    if (key === "id") return this._id || null;
    return Object.prototype.hasOwnProperty.call(this.attributes, key) ? this.attributes[key] : null;
  }
  append(...nodes) {
    for (const node of nodes) this.appendChild(typeof node === "string" ? textNode(node) : node);
  }
  appendChild(node) {
    if (!node) return node;
    node.parentNode = this;
    node.parentElement = this;
    node.ownerDocument = this.ownerDocument;
    this.children.push(node);
    return node;
  }
  replaceChildren(...nodes) {
    this.children.splice(0).forEach((child) => {
      child.parentNode = null;
      child.parentElement = null;
    });
    this._text = "";
    this.append(...nodes);
  }
  remove() {
    const parent = this.parentNode;
    if (!parent) return;
    parent.children.splice(parent.children.indexOf(this), 1);
    this.parentNode = null;
    this.parentElement = null;
  }
  addEventListener(type, fn) {
    (this.listeners[type] ??= []).push(fn);
  }
  removeEventListener(type, fn) {
    this.listeners[type] = (this.listeners[type] ?? []).filter((item) => item !== fn);
  }
  dispatchEvent(event) {
    const payload = event && typeof event === "object" ? event : { type: String(event) };
    payload.target ??= this;
    payload.currentTarget = this;
    payload.preventDefault ??= () => {};
    payload.stopPropagation ??= () => {};
    for (const fn of this.listeners[payload.type] ?? []) fn(payload);
    return true;
  }
  click() {
    this.dispatchEvent({ type: "click", button: 0, clientX: 0, clientY: 0 });
  }
  closest(selector) {
    let node = this;
    while (node) {
      if (matches(node, selector)) return node;
      node = node.parentElement;
    }
    return null;
  }
  querySelector(selector) {
    return this.querySelectorAll(selector)[0] ?? null;
  }
  querySelectorAll(selector) {
    const out = [];
    walk(this, (node) => {
      if (node !== this && matchesComplex(node, selector, this)) out.push(node);
    });
    return out;
  }
  getBoundingClientRect() {
    return { x: 0, y: 0, left: 0, top: 0, width: 960, height: 640, right: 960, bottom: 640, toJSON() {} };
  }
  setPointerCapture() {}
  releasePointerCapture() {}
}

function textNode(value) {
  const node = new MiniNode("#text");
  node.textContent = value;
  return node;
}

function walk(node, visit) {
  visit(node);
  for (const child of node.children) walk(child, visit);
}

function toKebab(value) {
  return value.replace(/[A-Z]/g, (char) => `-${char.toLowerCase()}`);
}

function matches(node, selector) {
  const trimmed = selector.trim();
  if (!trimmed) return false;
  if (trimmed === "*") return true;
  let rest = trimmed;
  const tag = rest.match(/^[a-z][\w-]*/i);
  if (tag) {
    if (node.tagName !== tag[0].toUpperCase()) return false;
    rest = rest.slice(tag[0].length);
  }
  while (rest) {
    if (rest[0] === ".") {
      const match = rest.match(/^\.([a-zA-Z0-9_-]+)/);
      if (!match || !node.classList.contains(match[1])) return false;
      rest = rest.slice(match[0].length);
      continue;
    }
    if (rest[0] === "#") {
      const match = rest.match(/^#([a-zA-Z0-9_-]+)/);
      if (!match || node.id !== match[1]) return false;
      rest = rest.slice(match[0].length);
      continue;
    }
    if (rest[0] === "[") {
      const match = rest.match(/^\[([^\]]+)\]/);
      if (!match) return false;
      const body = match[1];
      const eq = body.split("=");
      const name = eq[0].trim();
      if (eq.length === 1) {
        if (node.getAttribute(name) == null) return false;
      } else {
        const expected = eq.slice(1).join("=").replace(/^["']|["']$/g, "");
        if (node.getAttribute(name) !== expected) return false;
      }
      rest = rest.slice(match[0].length);
      continue;
    }
    return false;
  }
  return true;
}

function matchesComplex(node, selector, root) {
  const parts = selector.trim().split(/\s+/);
  if (parts.length === 1) return matches(node, parts[0]);
  if (!matches(node, parts[parts.length - 1])) return false;
  let current = node.parentElement;
  for (let i = parts.length - 2; i >= 0; i -= 1) {
    while (current && current !== root) {
      if (matches(current, parts[i])) break;
      current = current.parentElement;
    }
    if (!current || current === root) return false;
    current = current.parentElement;
  }
  return true;
}

export function installMiniDom(global = globalThis) {
  if (global.document?.__miniDom) return global.document;
  const document = new MiniNode("#document");
  document.__miniDom = true;
  document.ownerDocument = document;
  const html = new MiniNode("html");
  const head = new MiniNode("head");
  const body = new MiniNode("body");
  html.ownerDocument = document;
  head.ownerDocument = document;
  body.ownerDocument = document;
  html.append(head, body);
  document.append(html);
  document.documentElement = html;
  document.head = head;
  document.body = body;
  document.createElement = (tag) => {
    const node = new MiniNode(tag);
    node.ownerDocument = document;
    return node;
  };
  document.createElementNS = (ns, tag) => {
    const node = new MiniNode(tag, ns || SVG_NS);
    node.ownerDocument = document;
    return node;
  };
  document.getElementById = (id) => {
    let found = null;
    walk(document, (node) => {
      if (!found && node.id === id) found = node;
    });
    return found;
  };
  document.querySelector = (selector) => document.documentElement.querySelector(selector);
  document.querySelectorAll = (selector) => document.documentElement.querySelectorAll(selector);
  global.document = document;
  global.HTMLElement = MiniNode;
  global.SVGElement = MiniNode;
  global.Element = MiniNode;
  global.Node = MiniNode;
  global.window = global;
  global.PointerEvent = function PointerEvent(type, init = {}) {
    return { type, button: 0, clientX: 0, clientY: 0, pointerId: 1, ...init };
  };
  global.WheelEvent = function WheelEvent(type, init = {}) {
    return { type, deltaY: 0, clientX: 0, clientY: 0, preventDefault() {}, ...init };
  };
  return document;
}
