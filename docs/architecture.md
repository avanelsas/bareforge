# Architecture

Bareforge is a ClojureScript single-page app on top of
[BareDOM](https://github.com/avanelsas/baredom)'s 90 stateless web
components. The editor chrome itself is composed of BareDOM elements
— palette, inspector, toolbars, layers, modals — dogfooding the
library. The canvas is rendered by a hand-written DOM reconciler
(no virtual DOM) to stay philosophically aligned with BareDOM's
`DOM = f(state)` model.

This document orients new contributors. For the day-to-day rules
(Closure-Advanced safety, JS interop, dependency policy) see
[`CLAUDE.md`](../CLAUDE.md). For plugin authoring see
[`docs/plugins.md`](./plugins.md).

## Two zones, hard line between them

The codebase has two compartments with different rules:

**Pure zone** — `doc/`, `meta/`, `export/`, `storage/`
serialisation, `util/`. Functions are deterministic; same input,
same output. No side effects, no DOM, no atom reads. Everything
is plain Clojure data: maps, vectors, sets — no records, no
protocols. Reach for a dependency only when `clojure.core`
genuinely cannot do it.

**Effectful zone** — `state.cljs`, `render/`, `dnd/`, `ui/`,
`storage/` IO. This is where the atom lives, where
`requestAnimationFrame` is scheduled, where `document.createElement`
is called. Effectful functions stay thin: they call into the pure
zone, then apply the result. No business logic buried inside a
DOM handler.

If a function touches the atom or the DOM it lives in the
effectful zone. If it doesn't, it's pure and testable without a
browser.

## State

**One atom**: `bareforge.state/app-state`. Top-level keys
`:document :selection :history :mode :theme :ui :dirty?
:project-file`. No other atoms anywhere. No `volatile!`, no
mutable JS fields on records.

**All `:document` writes go through `doc.ops/*`** (pure) **then
`state/commit!`** (effectful). UI code never `swap!`s the
document directly. `commit!` is the only place that pushes
history and clears redo. Downstream effects (rAF-scheduling a
render, autosave debouncing) live in their own watchers on the
atom — `render/canvas/install-watch!`,
`storage/indexeddb/install-autosave!` — not inside `commit!`.

**Watches** are installed once on the mount path (rooted at
`bareforge.main/init`), keyed by namespaced sentinels
(`::render`, `::inspector`, `::autosave` etc.). Each handler's
first line is an early-exit guard on the slice it cares about,
so unrelated commits cost nothing.

## Document model

A Bareforge document is a tree of nodes. Each node has a tag, an
attrs map, props (BareDOM property settings), text, optional
inner-html (for raw-HTML components like `x-icon`), and slots
(named child collections — most components have just `default`).

Nodes can carry interactivity:

- **Groups**: a node with a non-empty `:name` becomes a *group*
  — a slice of state the design owns.
- **Fields** on a group: stored fields (numbers, strings, lists)
  + computed fields that derive from other fields via seven
  v1 operations (`:count-of`, `:sum-of`, `:empty-of`,
  `:negation`, `:any-of`, `:filter-by`, `:join-on`).
- **Records**: when a list field has `:of-group "<other-group>"`,
  each item in the list takes the *record shape* of that other
  group — its fields define what an item looks like. The
  referencing list owns the data; the target group is a template
  for one item's shape and view.
- **Bindings**: per-attribute connections between a component
  attribute and a field. Direction is `:read`, `:write`, or
  `:read-write`.
- **Events** (triggers): a DOM event (`press`, `change`, …) on
  an interactive node fires an *action* on a field. Actions
  mutate fields with simple operations (`:set`, `:toggle`,
  `:increment`, `:decrement`, `:clear`, `:add`, `:remove`).

The model is target-agnostic. Every export plugin lowers the
document via `bareforge.export.model` into the same intermediate
shape, then emits target-specific source.

## Rendering

`bareforge.render.canvas` patches the canvas DOM whenever
`:document` changes. The reconciler is plain ClojureScript and
walks the document tree against the live DOM, producing
minimal-diff updates. There's no virtual DOM intermediate; the
diff target is the live tree.

Selection, drop hints, and the slot-strips overlay live in
`bareforge.render.selection` / `slot_strips`. They listen to
their own slices of state and paint over the canvas with plain
SVG / DOM.

## Export plugins

Every export plugin lives under `src/bareforge/export/<name>/`
and conforms to the contract in `bareforge.export.plugin`:

- A `manifest` map (id, label, extension, interactive?,
  description, order, download! fn).
- A pure `(generate doc opts) → {file-path → content}` entry
  point.
- An effectful `download!` that wraps `generate` and triggers a
  browser download.

Four plugins ship today (HTML, bundle, CLJS-project,
vanilla-JS), all consuming the same lowered model. Adding a new
plugin is a subdir + one append to `bareforge.export.registry`.
See [`docs/plugins.md`](./plugins.md) for the authoring guide
and a hello-world walkthrough.

## Security boundary

User-supplied content (`:inner-html` raw HTML / SVG, `:attrs`
URL values) is sanitised at every trust boundary by
`bareforge.doc.sanitize`:

- **Load**: `storage/project-file/validate-project` rejects a
  `.json` payload whose `unsafe-findings` flags any node.
- **Commit**: `ops/set-inner-html` and `ops/set-attr` strip /
  reject unsafe writes silently.
- **Export**: each plugin re-runs `sanitize-svg-fragment` at
  codegen as defence-in-depth.

Every export also embeds a `<meta http-equiv="Content-Security-
Policy">` block; CDN-mode whitelists `cdn.jsdelivr.net`, others
narrow to `'self'`. Subresource Integrity hashes from BareDOM's
published `dist/integrity.json` are emitted as
`<link rel=modulepreload integrity=…>` on every CDN-mode
export, binding the dynamic `import()` to the bytes BareDOM
shipped.

The full audit trail (open + closed findings, remediation
references) lives at [`audit/`](../audit/).

## Build + test

- Dev:    `npx shadow-cljs watch app`  (http://localhost:8765)
- Tests:  `npx shadow-cljs compile test`  (Node, autoruns the suite)
- Release:`npx shadow-cljs release app`  (must compile clean, zero
  warnings — Closure Advanced renames bite late)
- Lint:   `clj-kondo --lint src test scripts`
- Format: `cljfmt check` / `cljfmt fix`

CI runs all of these on every push and PR. See
[`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

## Dependencies

Runtime: BareDOM (Clojars) + JSZip. That's it.
Dev: shadow-cljs, clj-kondo, cljfmt. That's it.

No React, Reagent, re-frame, Lit, Alpine, hiccup renderer,
core.async, router, vdom library, TypeScript, JSX, or
build-time codegen beyond shadow-cljs. The design stays
philosophically aligned with BareDOM: `DOM = f(state)` applied
by a hand-written reconciler.

## Project layout

```
bareforge/
├── CLAUDE.md                  Architecture & development rules
├── docs/architecture.md       Architecture overview for new contributors
├── docs/plugins.md            Export plugin authoring guide
├── docs/recipes.md            End-to-end walkthrough + quick reference
├── docs/adding-components.md  Onboarding a new BareDOM component
├── deps.edn                   Clojure dependencies + :scaffold alias
├── shadow-cljs.edn            Build config
├── public/
│   ├── index.html             Static shell + editor CSS
│   ├── assets/                Logo + static assets
│   └── js/                    Compiled output (git-ignored)
├── scripts/
│   └── scaffold_component.clj The new-component scaffolder
└── src/bareforge/
    ├── main.cljs              Entry point
    ├── state.cljs             Single app-state atom + history
    ├── doc/                   Pure document model + ops + spec
    ├── meta/                  Component metadata
    │   ├── public_api.cljs    Tag → observed attrs (from BareDOM)
    │   ├── augment.cljs       Hand-curated property kinds + CSS vars
    │   ├── categories.cljs    Palette grouping
    │   ├── slots.cljs         Container slot descriptors
    │   ├── placement.cljs     Snap hints
    │   ├── heuristics.cljc    Shared label / kind inference
    │   └── registry.cljs      Merged get-meta lookup
    ├── render/                Hand-written DOM reconciler + selection overlay
    ├── ui/                    Editor chrome (palette, layers, inspector, …)
    ├── dnd/                   Drag-drop state machine
    ├── storage/               IndexedDB autosave + project files
    └── export/                Pluggable exports — HTML, bundle, CLJS, vanilla-JS
```

## Where to read next

- [`docs/plugins.md`](./plugins.md) — export plugin authoring.
- [`CLAUDE.md`](../CLAUDE.md) — exhaustive development rules
  (the day-to-day "do this, don't do that" guide for both
  human and AI-assisted contributors).
- [`audit/`](../audit/) — code & security audit history with
  resolution references.
- [`CONTRIBUTING.md`](../CONTRIBUTING.md) — how to send a PR.
