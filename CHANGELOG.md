# Changelog

All notable changes to Bareforge are documented here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [SemVer](https://semver.org/spec/v2.0.0.html). The
leading `0` in `0.x.y` versions encodes "pre-1.0, breaking changes
possible" — I won't promise API stability until `1.0.0` lands.

## [Unreleased]

Editor authoring quality-of-life. No document-model, project-file,
or export changes — saved projects load identically, every export
target stays at parity. Test count: 571 (up from 486), zero
release-build warnings under Closure Advanced.

### Added

- **Multi-select** on the canvas and Layers panel.
  - Shift-click extends; drag from empty canvas starts a marquee
    rectangle (Shift+drag extends).
  - The selection overlay becomes a pool — one 1px border per
    selected DOM id. Resize handles only when exactly one node is
    selected.
  - Esc clears the selection; Delete / Backspace removes every
    selected node in one commit via `ops/remove-many`.
- **Multi-select inspector edit.** With more than one node
  selected, the Inspector renders the shared-attribute set across
  all of them. Editing a row dispatches a single
  `ops/set-attrs-many` (or `set-props-many`) commit; mixed values
  show with a `Mixed` placeholder and an `is-mixed` class.
- **Cmd-D duplicate** the selection (deep-clone with fresh ids
  throughout the subtree).
- **Cmd-G** wraps the selection in an `x-container`;
  **Cmd-Shift-G** prompts for `x-grid` / `x-card` / `x-navbar`. New
  `ops/wrap-many` keeps a sibling set's document order intact
  inside the new wrapper.
- **Cmd-Opt-C / Cmd-Opt-V** copy and paste attributes between
  nodes. Paste is filtered to the target tag's supported attrs so
  `x-button` → `x-card` silently drops `variant` instead of
  stamping unknown attributes. Macro-OS Option-modified key (`ç`,
  `√`) falls back to `.code` (`KeyC`, `KeyV`) for cross-platform
  parity.
- **Drag-to-scrub** numeric inspector rows. The label of `:number`
  kind editors and free-coord `:layout :x / :y / :w / :h` fields
  becomes a horizontal drag handle; Shift × 10 step. The whole
  drag is one undo entry via `state/commit-coalesced!`.
- **BareDOM theme-token autocomplete.** Colour and length fields
  surface every `--x-color-*` / `--x-space-*` / `--x-radius-*` /
  `--x-font-size-*` / `--x-border-width` token via a native
  `<datalist>` injected into the field's shadow root. New
  `bareforge.meta.design-tokens` mirrors the 50 `tk-*` tokens from
  `baredom.components.x-theme.model`.
- **`?` keyboard cheat sheet.** Lists every shortcut and gesture
  grouped by Editing / Selection / Navigation / File / View. Built
  on `x-modal` + `x-typography` so it inherits the active theme
  preset. Source of truth is the static `shortcut-info` data;
  unit tests assert category coverage.
- **Cmd-K command palette.** Built on the BareDOM
  `x-command-palette` web component — owns its own focus, fuzzy
  filter, scrim, ARIA roles, and theme inheritance. Curated File /
  View / Selection commands plus one entry per registered BareDOM
  tag (`Insert <tag>`) plus the four wrap-in targets. Selection
  flows through a synthetic-id → run-fn dispatch map.
- **Layers panel keyboard navigation.** Focus the Layers tree, then
  ↑ / ↓ walk siblings within the parent slot, ← / → step to parent
  / first child, and **Alt+↑ / Alt+↓** reorder within the slot via
  `ops/move`. The keydown handler `stopPropagation`s so an arrow on
  a free-placed selection no longer simultaneously navigates and
  nudges.
- **Inline component patterns.** New `bareforge.meta.patterns`
  carries pre-styled named configurations per tag: `x-button` →
  primary / secondary / ghost / danger / loading; `x-typography` →
  h1 / h2 / h3 / body / caption / code; `x-alert`, `x-badge`,
  `x-card`, `x-chip`, `x-grid`, `x-divider`, `x-switch`,
  `x-checkbox` covered. Tags with patterns grow a `▾` caret on
  their palette tile that toggles an inline flyout of pattern
  chips. A coverage warning prints uncovered tags in test output.
- **Per-tag empty-slot hints.** New `bareforge.meta.hints` provides
  hint strings (`Drop nav links / actions`, `Drop tiles into the
  grid`, `Drop x-tab here`, etc.) for ~20 container tags. The
  canvas reconciler stamps `data-bareforge-hint` on creation;
  existing CSS reads it via `attr()` to override the generic
  `(empty)` placeholder. Drag-time and preview-mode invisibility
  fall out of existing rules.

### Changed

- `:selection` in `app-state` is now a vector of node ids. New
  pure helpers `state/selected-ids`, `selected?`,
  `single-selected-id`; new effectful `select-one!`, `select-clear!`,
  `select-toggle!`. Internal-only refactor — single-node consumers
  (resize handles, nudge, inspector lookup, inline-edit teardown)
  route through `single-selected-id` and degrade gracefully under
  multi-select. **Saved project files are unchanged.**
- Action helpers in `ui.shortcuts` (`duplicate!`, `wrap-in!`,
  `copy-attrs!`, `paste-attrs!`) become public so the command
  palette reuses them instead of re-implementing the
  selection → commit → reselect flow.
- Wrap-in whitelist updated to `x-container / x-grid / x-card /
  x-navbar`. **`x-flex` is removed** — it isn't a tag in BareDOM
  2.4, so the previous Cmd-Shift-G prompt would have inserted an
  unknown element. Cmd-G default behaviour (`x-container`) is
  unchanged.
- In edit mode, the canvas host gets `user-select: none` so the
  Shift-click and marquee-drag gestures don't paint a native
  text-selection band over the rendered preview. Inline-text
  editing's textarea overlay re-enables `user-select: text`.

## [0.1.1] — 2026-04-28

Patch release. Bundles three user-facing fixes that landed on
`main` after the v0.1.0 launch — the hosted-demo blank-screen
fixes, the BareDOM patch bump — plus a few README polishes.

### Fixed

- **Hosted demo loads**. `public/index.html` and the toolbar
  brand `<picture>` referenced `/js/main.js`, `/favicon.svg`,
  `/assets/bareforge_*.png` with absolute paths. Project
  Pages serves at a sub-path (`/<repo>/`), so those resolved
  to the user-pages root and 404'd — leaving the demo loading
  the HTML but never executing the JS bundle. Switched all
  three to relative paths (matching `shadow-cljs.edn`'s
  `:asset-path "js"` from v0.1.0). The same fix applies to
  the `<script src>` the CLJS-project export plugin emits, so
  user-exported CLJS projects hosted at a sub-path now also
  serve correctly.
- **`bareforge_darkmode.png` 404 in the deployed editor's
  console** — same root cause as above, fixed by the toolbar
  brand path change.

### Changed

- **BareDOM bumped 2.4.0 → 2.4.1** (upstream patch release,
  no API or component-set changes). Propagates via
  `bareforge.meta.versions/baredom-version` to all four export
  paths.
- **Pluggable export system** flagged in the README's Features
  section, with the vanilla-JS export listed alongside HTML,
  bundle, and ClojureScript (it shipped at v0.1.0 but the
  Features list still showed three).
- **README hero / "Why Bareforge?" section** added — a
  centered logo at the top plus a personal motivation
  paragraph between the intro and the Features list.

### Verified

- 486 tests / 1436 assertions / 0 failures / 0 errors.
- `npx shadow-cljs release app` — 0 warnings.
- Hosted demo at <https://avanelsas.github.io/bareforge/>
  loads end-to-end after the deploy fires on this release.

## [0.1.0] — 2026-04-27

First public release. Early-alpha by tone, fully functional by
behaviour: 486 tests, zero release-build warnings, full feature
parity across four export targets.

### Added

- **Visual editor** with palette, layers, canvas, inspector,
  toolbar, theme editor, and templates panels — all dogfooded
  on top of BareDOM 2.4.0 (90 components).
- **Four export plugins**, all at full feature parity:
  - **HTML** — single-file static export, BareDOM via CDN.
  - **Bundle** — self-contained `.zip` with vendored BareDOM.
  - **ClojureScript project** — interactive shadow-cljs project
    with a re-frame-style runtime and hand-written reconciler.
  - **Vanilla JavaScript** — interactive zip with a tiny reactive
    store and reconciler, no framework dependency.
  - Every plugin supports stateful + template groups, collection
    fields, attribute bindings, triggers, the seven computed
    operations (`count-of`, `sum-of`, `empty-of`, `negation`,
    `any-of`, `filter-by`, `join-on`), and `:inner-html` raw
    SVG via codegen-time hiccup parsing.
- **Plugin scaffold** — `clojure -X:new-export :id … :label …`
  generates a new export plugin skeleton.
- **First-run welcome tour** built on BareDOM's `x-welcome-tour`,
  re-launchable from the File menu. Teaches groups, fields,
  records, bindings, events, actions in plain language.
- **Nine starter templates** — eight realistic landing-page
  scaffolds plus one kinetic-launch demo.
- **Project file format** — JSON, round-trips losslessly,
  validated against `clojure.spec` on load with a 5 MB size cap
  and content-sanitiser refusing XSS-shaped payloads.

### Security

- New `bareforge.doc.sanitize` namespace strips
  `<script>` / `<foreignObject>` / `on*=` / `javascript:` /
  `vbscript:` from `:inner-html` and URL-typed attrs at every
  trust boundary (load + commit + codegen).
- Every export embeds a Content-Security-Policy `<meta>` tag —
  CDN-mode allows `cdn.jsdelivr.net`, bundle / vanilla-JS /
  CLJS-project narrow to `'self'`. `object-src 'none'` and
  `base-uri 'self'` everywhere.
- Subresource Integrity hashes via BareDOM's published
  `dist/integrity.json` are embedded as `<link
  rel=modulepreload integrity=…>` in every CDN-mode export, so
  a tampered jsDelivr response can't execute.
- Zip-slip guard at every JSZip emit site refuses absolute,
  traversal, NUL-byte, or Windows-drive paths.

### Verified

- 486 tests / 1436 assertions / 0 failures / 0 errors
- `npx shadow-cljs release app` — 0 warnings under Closure
  Advanced.
- Export round-trip: every starter template exports to all four
  targets and renders in a browser.

[Unreleased]: https://github.com/avanelsas/bareforge/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/avanelsas/bareforge/releases/tag/v0.1.1
[0.1.0]: https://github.com/avanelsas/bareforge/releases/tag/v0.1.0
