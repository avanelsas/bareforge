// Bareforge vanilla-JS runtime. A minimal re-frame-style reactive
// store: dispatch mutates a single central object; subscriptions
// recompute derived values; views subscribe to the subs they render
// and re-run when their inputs change. Shipped verbatim inside every
// vanilla-JS export — the canonical source lives at
// src/bareforge/export/vanilla_js/runtime.js in the Bareforge repo
// and is slurp-inlined at generate time.
//
// Design mirrors the CLJS `bareforge.export.framework` namespace's
// re-frame subset. Keywords become plain string identifiers
// formatted "<ns>/<name>" (e.g. "app.cart.events/add-to-cart") —
// string equality is the dispatch key. Records carry the same
// convention: { "app.cart-item.db/id": 1, ... }.

const store = { state: null };
const subRegistry = new Map();   // id → { inputs, extract, handler }
const subCache = new Map();       // id → last computed value
const subListeners = new Map();   // id → Set<(value) => void>
const eventRegistry = new Map();  // id → handler with interceptors baked in
const fxRegistry = new Map();     // id → effect handler
const watchers = new Set();       // fn(db) called after every dispatch

export function initStore(initial) {
  if (store.state !== null) return;
  store.state = initial;
}

export function getStore() {
  return store.state;
}

export function resetAll() {
  store.state = null;
  subRegistry.clear();
  subCache.clear();
  subListeners.clear();
  eventRegistry.clear();
  fxRegistry.clear();
  watchers.clear();
}

// General-purpose change watcher — fires AFTER every dispatch that
// mutates state. The renderer wires its re-render through this;
// sub-specific subscribers still use `subscribe(id, callback)` for
// finer-grained updates.
export function addWatcher(fn) {
  watchers.add(fn);
  return () => watchers.delete(fn);
}

// --- Subs ----------------------------------------------------------------

export function regSubDirect(id, key) {
  subRegistry.set(id, { kind: "direct", key });
}

export function regSubDerived(id, inputs, extract) {
  subRegistry.set(id, { kind: "derived", inputs, extract });
}

export function regSubMulti(id, inputs, handler) {
  subRegistry.set(id, { kind: "multi", inputs, handler });
}

function computeSub(id, db) {
  const entry = subRegistry.get(id);
  if (!entry) return undefined;
  if (entry.kind === "direct") {
    return db[entry.key];
  }
  if (entry.kind === "derived") {
    const inputVals = entry.inputs.map((inId) => computeSub(inId, db));
    const source = inputVals.length === 1 ? inputVals[0] : inputVals;
    return entry.extract(source);
  }
  if (entry.kind === "multi") {
    const inputVals = entry.inputs.map((inId) => computeSub(inId, db));
    return entry.handler(inputVals);
  }
  return undefined;
}

export function query(id) {
  return computeSub(id, store.state);
}

export function subscribe(id, callback) {
  if (!subListeners.has(id)) subListeners.set(id, new Set());
  subListeners.get(id).add(callback);
  const initial = query(id);
  subCache.set(id, initial);
  callback(initial);
  return () => {
    const ls = subListeners.get(id);
    if (ls) ls.delete(callback);
  };
}

function notifyListeners(db) {
  for (const [id, listeners] of subListeners.entries()) {
    const next = computeSub(id, db);
    const prev = subCache.get(id);
    if (!deepEqual(prev, next)) {
      subCache.set(id, next);
      for (const cb of listeners) cb(next);
    }
  }
}

export function deepEqual(a, b) {
  if (a === b) return true;
  if (a === null || b === null) return false;
  if (typeof a !== typeof b) return false;
  if (Array.isArray(a)) {
    if (!Array.isArray(b) || a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) if (!deepEqual(a[i], b[i])) return false;
    return true;
  }
  if (typeof a === "object") {
    const ka = Object.keys(a);
    const kb = Object.keys(b);
    if (ka.length !== kb.length) return false;
    for (const k of ka) if (!deepEqual(a[k], b[k])) return false;
    return true;
  }
  return false;
}

// --- Interceptors --------------------------------------------------------

// trim-v strips the event id from the event vector, like re-frame.
export const trimV = {
  id: "trim-v",
  before(ctx) {
    return { ...ctx, event: ctx.event.slice(1) };
  },
};

// path narrows db to the value at `key` before the handler runs, then
// re-assembles after. Single-level only in v0.1 — nested paths are
// a future extension.
export function path(key) {
  return {
    id: "path",
    before(ctx) {
      return { ...ctx, originalDb: ctx.db, db: ctx.db[key] };
    },
    after(ctx) {
      return { ...ctx, db: { ...ctx.originalDb, [key]: ctx.db }, originalDb: undefined };
    },
  };
}

function runChain(ctx, interceptors, phase) {
  const ordered = phase === "after" ? [...interceptors].reverse() : interceptors;
  let c = ctx;
  for (const i of ordered) {
    const f = i[phase];
    if (f) c = f(c);
  }
  return c;
}

// --- Events --------------------------------------------------------------

export function regEvent(id, interceptors, handlerFn) {
  if (typeof interceptors === "function") {
    // plain form: regEvent(id, handlerFn) — no interceptors
    handlerFn = interceptors;
    interceptors = [];
  }
  eventRegistry.set(id, (db, eventVec) => {
    const ctx0 = { db, event: eventVec };
    const before = runChain(ctx0, interceptors, "before");
    const handlerDb = handlerFn(before.db, before.event);
    const after = runChain({ ...before, db: handlerDb }, interceptors, "after");
    return after.db;
  });
}

export function regEventFx(id, handlerFn) {
  eventRegistry.set(id, (db, eventVec) => {
    const fx = handlerFn(db, eventVec) || {};
    for (const [fxId, fxArg] of Object.entries(fx)) {
      if (fxId === "db") continue;
      const fxHandler = fxRegistry.get(fxId);
      if (fxHandler) fxHandler(fxArg);
    }
    return Object.prototype.hasOwnProperty.call(fx, "db") ? fx.db : db;
  });
}

export function regFx(id, handler) {
  fxRegistry.set(id, handler);
}

export function dispatch(eventVec) {
  const [id] = eventVec;
  const handler = eventRegistry.get(id);
  if (!handler) return;
  const nextDb = handler(store.state, eventVec);
  if (nextDb !== store.state) {
    store.state = nextDb;
    notifyListeners(nextDb);
    for (const w of watchers) w(nextDb);
  }
}

// --- Record helpers ------------------------------------------------------

// Recursively re-key a record (and nested maps / arrays of records)
// under `ns` so dispatches from one group land with the right key
// prefix — mirrors qualify-map in the CLJS runtime. Idempotent.
export function qualifyMap(m, ns) {
  if (m === null || typeof m !== "object") return m;
  if (Array.isArray(m)) return m.map((v) => qualifyMap(v, ns));
  const out = {};
  for (const [k, v] of Object.entries(m)) {
    const localName = k.includes("/") ? k.split("/")[1] : k;
    const newKey = `${ns}/${localName}`;
    if (v !== null && typeof v === "object" && !Array.isArray(v)) {
      out[newKey] = qualifyMap(v, ns);
    } else if (Array.isArray(v) && v.length && typeof v[0] === "object" && v[0] !== null) {
      out[newKey] = v.map((item) => qualifyMap(item, ns));
    } else {
      out[newKey] = v;
    }
  }
  return out;
}
