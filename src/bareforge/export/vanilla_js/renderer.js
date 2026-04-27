// Bareforge vanilla-JS renderer. Mirrors the CLJS
// `bareforge.export.renderer` namespace: a hiccup-style array format
// ([tag, props?, ...children]) consumed by a hand-written DOM
// reconciler. Keeps parity with the CLJS renderer's stored-children
// + parent-anchored-mutations contract so components that relocate
// their own DOM (x-popover[portal], x-modal when rendered into the
// top-layer, future teleports) reconcile correctly.
//
// Shipped verbatim inside every vanilla-JS export — canonical source
// lives at src/bareforge/export/vanilla_js/renderer.js in the
// Bareforge repo and is slurp-inlined at generate time.

const CHILDREN_KEY = "__bd_children";
const PROPS_KEY    = "__bd_props";

const SVG_NS    = "http://www.w3.org/2000/svg";
const SVG_TAGS  = new Set([
  "svg", "path", "circle", "ellipse", "line", "polygon", "polyline",
  "rect", "g", "defs", "use", "text", "tspan", "clipPath", "mask",
  "pattern", "linearGradient", "radialGradient", "stop", "filter",
  "feGaussianBlur", "feColorMatrix", "feComposite", "feOffset",
  "feMerge", "feMergeNode", "foreignObject", "marker", "symbol",
]);

function parseVnode(v) {
  const [tag, maybeProps, ...rest] = v;
  const hasProps =
    maybeProps !== null &&
    typeof maybeProps === "object" &&
    !Array.isArray(maybeProps);
  return [tag, hasProps ? maybeProps : {}, hasProps ? rest : [maybeProps, ...rest].filter((x) => x !== undefined)];
}

function flattenChildren(children) {
  const acc = [];
  const go = (x) => {
    if (x === null || x === undefined || x === false) return;
    if (typeof x === "string" || typeof x === "number") { acc.push(String(x)); return; }
    if (Array.isArray(x)) {
      // Hiccup vectors start with a tag (string). Anything else is a
      // fragment / iteration and gets flattened.
      if (typeof x[0] === "string" && !Array.isArray(x[0])) { acc.push(x); return; }
      for (const child of x) go(child);
      return;
    }
  };
  for (const c of children) go(c);
  return acc;
}

function isOnKey(k) { return k.startsWith("on-"); }
function eventName(k) { return k.slice(3); }

function setListener(el, k, handler) {
  const ename = eventName(k);
  if (!el.__bd_listeners) el.__bd_listeners = {};
  const prev = el.__bd_listeners[ename];
  if (prev) el.removeEventListener(ename, prev);
  el.addEventListener(ename, handler);
  el.__bd_listeners[ename] = handler;
}

function removeListener(el, k) {
  const ename = eventName(k);
  if (el.__bd_listeners && el.__bd_listeners[ename]) {
    el.removeEventListener(ename, el.__bd_listeners[ename]);
    delete el.__bd_listeners[ename];
  }
}

function setProp(el, k, v) {
  if (isOnKey(k)) { setListener(el, k, v); return; }
  if (v === null || v === undefined || v === false) { el.removeAttribute(k); return; }
  if (v === true) { el.setAttribute(k, ""); return; }
  el.setAttribute(k, String(v));
}

function patchProps(el, oldProps, newProps) {
  for (const k of Object.keys(oldProps)) {
    if (!(k in newProps)) {
      if (isOnKey(k)) removeListener(el, k); else el.removeAttribute(k);
    }
  }
  for (const [k, v] of Object.entries(newProps)) {
    if (oldProps[k] !== v) setProp(el, k, v);
  }
}

function createElement(v) {
  const [tag, props, children] = parseVnode(v);
  const el = SVG_TAGS.has(tag)
    ? document.createElementNS(SVG_NS, tag)
    : document.createElement(tag);
  for (const [k, val] of Object.entries(props)) setProp(el, k, val);
  el[PROPS_KEY] = props;
  const kids = flattenChildren(children).map(createNode);
  for (const child of kids) el.appendChild(child);
  el[CHILDREN_KEY] = kids;
  return el;
}

function createNode(x) {
  if (typeof x === "string") return document.createTextNode(x);
  if (Array.isArray(x)) return createElement(x);
  return document.createTextNode("");
}

function sameTag(domNode, vnode) {
  return domNode.nodeType === 1
      && Array.isArray(vnode)
      && domNode.tagName.toUpperCase() === vnode[0].toUpperCase();
}

function textNode(node) { return node.nodeType === 3; }

function patchNode(parent, oldNode, newVnode) {
  // text → text
  if (typeof newVnode === "string" && textNode(oldNode)) {
    if (oldNode.nodeValue !== newVnode) oldNode.nodeValue = newVnode;
    return oldNode;
  }
  // element → same-tag element: patch in place.
  if (sameTag(oldNode, newVnode)) {
    const [, newProps, newChildren] = parseVnode(newVnode);
    patchProps(oldNode, oldNode[PROPS_KEY] || {}, newProps);
    oldNode[PROPS_KEY] = newProps;
    patchChildren(oldNode, flattenChildren(newChildren));
    return oldNode;
  }
  // replace — anchor on the node's CURRENT parent (portals/teleports).
  const targetParent = oldNode.parentNode || parent;
  const freshNode = createNode(newVnode);
  targetParent.replaceChild(freshNode, oldNode);
  return freshNode;
}

function patchChildren(parent, newVnodes) {
  const old = parent[CHILDREN_KEY] || [];
  const oldCount = old.length;
  const newCount = newVnodes.length;
  const minCount = Math.min(oldCount, newCount);
  const kept = [];
  for (let i = 0; i < minCount; i++) {
    kept.push(patchNode(parent, old[i], newVnodes[i]));
  }
  const appended = [];
  for (let i = oldCount; i < newCount; i++) {
    const n = createNode(newVnodes[i]);
    parent.appendChild(n);
    appended.push(n);
  }
  if (oldCount > newCount) {
    for (let i = newCount; i < oldCount; i++) {
      const n = old[i];
      if (n && n.parentNode) n.parentNode.removeChild(n);
    }
  }
  parent[CHILDREN_KEY] = kept.concat(appended);
}

export function render(container, viewFn) {
  patchChildren(container, flattenChildren([viewFn()]));
}

export function mount(container, viewFn, subscribeToStore) {
  render(container, viewFn);
  subscribeToStore(() => render(container, viewFn));
}
