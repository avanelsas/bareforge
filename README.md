# Bareforge

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="public/assets/bareforge_darkmode.png">
    <source media="(prefers-color-scheme: light)" srcset="public/assets/bareforge_lightmode.png">
    <img alt="Project Logo" src="public/assets/bareforge_lightmode.png" width="200">
  </picture>
</p>

A visual landing-page builder for [BareDOM](https://github.com/avanelsas/baredom).
This project offers a drag and drop interface to build a web component based static page and using bindings, records and events can export to a fully interactive, functional extensible codebase in either ClojureScript or JavaScript.

![CI](https://github.com/avanelsas/bareforge/actions/workflows/ci.yml/badge.svg)

Bareforge is built in ClojureScript on top of BareDOM's 90 stateless web
components. The editor chrome itself is made from BareDOM elements —
palette, inspector, toolbars, layers, modals — dogfooding the library
inside a real end-user application. The canvas uses a hand-written DOM
reconciler (no virtual DOM) to stay philosophically aligned with BareDOM.

## Why Bareforge?

Like most Clojure/ClojureScript developers starting out with UIs, I went through the common phases of using Reagent and Re-frame—which are great utilities in their own right. However, as my UIs became larger and more complex, bundle sizes increased, and I found myself spending too much time rebuilding generic, reusable components from scratch.

I started looking for a different approach and discovered Web Components. I built a few, but didn't have the spare time to develop a comprehensive set that could be used in any project. Then AI arrived. While experimenting with Claude Code, I realised that 1 + 1 could be 3. That is how BareDOM, my first open-source project, was born.

I then turned to another aspect of web development and thought about how I could make web components easier in use. I often found myself taking a Figma design and translating it into UI components and code.

I wondered if I could automate some of that and with that the idea for Bareforge was born. I wanted something that would not just deliver a static page. Instead it should allow exporting a complete and interactive CLJS or JS project that can be used for further development. It is still rough around the edges but I hope it brings you joy and usefulness when designing and developing web/landing pages.

## Features

- **Drag-drop canvas** with before / inside / after drop indicators
- **Inspector** with type-aware editors for every BareDOM component —
  enums as dropdowns, colors as pickers, booleans as switches, URLs,
  numbers, and a per-instance CSS-variable editor
- **Free-form positioning** for decoratives (absolute x/y/w/h) with an
  8-handle resize overlay and keyboard nudging (arrow keys, 10 px with
  Shift, coalesced into a single undo step)
- **Flow resize** handles on non-free elements (E / S / SE only, updating
  `:width` / `:height` as CSS length strings)
- **Component-aware snap** on drop — an `x-navbar` lands at the top of
  the root at full width; an `x-sidebar` does the same; easily extensible
  via `meta/placement.cljs`
- **Theme editor** with 8 built-in presets and full per-token overrides
- **Autosave** to IndexedDB (debounced) plus explicit project files
  (`.json`) via File Download / File Upload
- **Project files** are spec-validated on load — malformed payloads are
  refused rather than silently installed
- **Four export modes**:
  - **CDN export** (static snapshot) — one HTML file, loads BareDOM
    from jsDelivr at runtime (requires internet). Markup only; no
    reactive state.
  - **Bundle export** (static snapshot) — a `.zip` with the HTML +
    a local `vendor/baredom/` folder containing every module the
    document uses; serve from any static HTTP server offline.
    Markup only; same contract as CDN export.
  - **ClojureScript export** (interactive) — a full shadow-cljs
    project that ships a minimal re-frame subset plus the
    declarative data-binding layer. Buttons fire actions, fields
    update state, computed subs recompute. See
    [`docs/recipes.md`](./docs/recipes.md) for how to build one
    end-to-end.
  - **Vanilla JavaScript export** (interactive) — a `.zip` with a
    tiny reactive store, a hand-written reconciler, and per-group
    view modules. Same feature parity as the ClojureScript export
    (template groups, collection fields, the seven computed
    operations, bindings, triggers, raw-HTML icons) — no
    framework dependency, plain DOM + ES modules.
- **Pluggable export system** — the four targets above are
  built-in plugins under `src/bareforge/export/<name>/`; adding a
  new one (React, Svelte, your in-house framework) is a manifest
  + a single `generate` fn. See [`docs/plugins.md`](./docs/plugins.md).
- **Undo / redo** with 100-step history, including coalesced keyboard
  nudges (hold arrow → one undo step)
- **Preview mode** toggle — drops the editor chrome interactions so you
  can click through the page as a user
- **Escape to deselect** + full keyboard shortcuts (Cmd-Z / Cmd-Shift-Z /
  Delete / arrow-key nudge)

## Docs

The deep-dive content lives in [`docs/`](./docs/) so this README
stays scannable:

- [`docs/recipes.md`](./docs/recipes.md) — end-to-end walkthrough
  that builds a filterable product feed with an add-to-cart flow,
  plus a quick-reference index.
- [`docs/adding-components.md`](./docs/adding-components.md) —
  scaffolder recipe for onboarding a new BareDOM component into
  the palette.
- [`docs/architecture.md`](./docs/architecture.md) — architecture,
  data model, rendering pipeline, project layout, and per-component
  notes for new contributors.
- [`docs/plugins.md`](./docs/plugins.md) — export plugin authoring
  guide.
- [`docs/repl.md`](./docs/repl.md) — editor-connected REPL setup
  (Calva / CIDER / Cursive) and the recommended dev loop.
- [`docs/dev-setup.md`](./docs/dev-setup.md) — first-time contributor
  walkthrough: toolchain, `./scripts/check.sh`, common pitfalls.

## Authoring shortcuts

A set of power-user gestures that make daily editing faster. Every
keyboard shortcut lives in a press-`?` cheat sheet; every action is
reachable through a Cmd-K command palette.

### Multi-select & bulk ops

- **Shift-click** any node (canvas or Layers panel) to extend the
  selection. **Drag from empty canvas** for marquee select; hold
  **Shift** to extend.
- With multiple nodes selected, the Inspector shows the **shared
  attributes** across them. Edit a row once, every selected node
  updates in a single undo step. Mixed values render with a `Mixed`
  placeholder.
- **Cmd-D** duplicates (deep-clone with fresh ids); **Cmd-G** wraps
  the selection in an `x-container` (**Cmd-Shift-G** prompts for
  `x-grid` / `x-card` / `x-navbar`); **Delete** removes the whole
  set in one commit.
- **Cmd-Opt-C / Cmd-Opt-V** copy attributes from the selection and
  paste them onto another node, filtered to the target tag's
  supported attrs — a paste from `x-button` onto an `x-card`
  silently drops `variant` instead of stamping an unknown attr.

### Inspector ergonomics

- **Drag-to-scrub** numeric labels — the `min` / `max` / `step` rows
  and the free-coord `:layout :x / :y / :w / :h` rows. Drag the
  label horizontally; hold Shift for ×10 steps. The whole drag is
  one undo entry.
- **`var(--x-…)` autocomplete.** Type `var(` into a colour or length
  field and a native `<datalist>` surfaces every BareDOM theme
  token — `--x-color-primary`, `--x-space-md`, `--x-radius-lg`,
  and so on, sourced from `x-theme` and resolved live by the
  active preset.

### Discoverability

- **`?`** opens a cheat sheet with every keyboard shortcut and
  gesture, grouped by category. Built on `x-modal` + `x-typography`
  so it inherits the live theme preset.
- **Cmd-K** opens a fuzzy command palette built on
  `x-command-palette`. Insert any of the 90 BareDOM tags by typing
  a fragment of the name; toggle the theme editor, the templates
  panel, preview mode, or the cheat sheet itself — every toolbar
  action is one keystroke away.
- **Layers keyboard nav.** Focus the Layers tree, then ↑ / ↓ walk
  siblings, ← / → step to parent / first child, **Alt+↑ / Alt+↓**
  reorder within the parent slot.
- **Palette pattern flyout.** Components with curated variants —
  `x-button` (primary / secondary / ghost / danger / loading),
  `x-typography` (h1–h3 / body / caption / code), `x-alert`,
  `x-badge`, `x-card`, `x-grid` (2-col / 3-col / 4-col / sidebar),
  and more — show a `▾` caret next to their palette tile. Expand,
  pick a chip, the component lands pre-styled.
- **Empty-slot hints.** Empty containers in edit mode show a per-tag
  prompt — `Drop nav links / actions` inside an empty `x-navbar`,
  `Drop tiles into the grid` inside an `x-grid`, `Drop x-tab here`
  inside `x-tabs` — so the next move is always obvious.

## Fields & bindings

Give a container a **name** and it becomes a component group with its
own reactive state. Declare typed **fields** on the group (e.g.
`:cart-count` → number, default `0`); any attribute of any descendant
can then be **bound** to a field — a `:read` binding tracks it live
(the `:text` of an `x-badge` mirrors `:cart-count`), a `:write`
binding lets an event trigger update it, and event triggers pick an
operation from `:increment`, `:decrement`, `:toggle`, `:set`,
`:clear`. On export, fields become the group's `default-db`, reads
become `rf/reg-sub` + `rf/query`, and event triggers become
`rf/reg-event` + `rf/dispatch` — a minimal re-frame shape, no React,
no Reagent.

## Templates

Bareforge ships with 8 starter templates that showcase BareDOM's component
library and modern web design patterns:

| Template | Description |
|----------|-------------|
| **SaaS Hero** | Gaussian blur background, navbar, kinetic typography headline, particle button CTAs, and a stats row |
| **Bento Features** | Section heading with a bento grid of feature cards, including a multi-span highlighted card |
| **Our Story** | Narrative cards separated by organic dividers — a vertical storytelling layout |
| **Pricing Table** | Three-tier pricing cards (Starter / Pro / Enterprise) with badges, dividers, and feature lists |
| **Testimonials** | Grid of customer quote cards with avatars and attributions |
| **How It Works** | Four-step timeline with labels, titles, and descriptive content |
| **Contact** | Two-column layout with a form (wrapped in `x-form`) and a contact info card |
| **Full Landing Page** | Complete multi-section page combining navbar, hero, stats, feature grid, testimonial, and CTA footer with a gaussian blur background |

Pick a template from the Templates panel to start with a pre-built
structure, then customise content, theme, and layout in the editor.

## Status

**Early alpha.** Feature-complete: 90 BareDOM components in the
palette, four export plugins at full feature parity (HTML, bundle,
CLJS, vanilla-JS), nine starter templates, first-run welcome tour,
doc-level XSS sanitiser, CSP + SRI on every export. 571 tests / 0
release-build warnings under Closure Advanced. Expect rough edges
on less-common BareDOM components until their augment entries are
hand-tuned. See [`CHANGELOG.md`](./CHANGELOG.md) for what's in
each release.

## Relationship to BareDOM

Bareforge is a **consumer** of BareDOM, pulled from Clojars as
`com.github.avanelsas/baredom`. This repository does not modify BareDOM
— version bumps happen via `deps.edn`.

## Quick start

```bash
# Install dev dependencies (jszip for bundle export)
npm install

# Start dev server on http://localhost:8765
# Also exposes an nREPL on port 7888 — see docs/repl.md.
npx shadow-cljs watch app

# Release build (Closure :advanced)
npx shadow-cljs release app

# Tests
npx shadow-cljs watch test

# All four PR-readiness gates in one go
./scripts/check.sh
```

Requirements: JDK 11+, Node 18+. Contributors should match CI exactly
(Java 21, Node 20) — see [`docs/dev-setup.md`](./docs/dev-setup.md).

## Philosophy (brief)

Every edit flows through a pure transform (`doc.ops/*`) before being
committed through the single atom (`bareforge.state/app-state`). The
reconciler is a one-way function `DOM = f(state)`. Pure zone
(`doc/`, `meta/`, `export/` serialization, `storage/` serialization)
is side-effect-free and tested without a browser; effectful zone
(`render/`, `dnd/`, `ui/`, `state.cljs`) is the only place with DOM
access or atom writes. `CLAUDE.md` has the full rule list.

## Contributing

First-time contributor? Start with [`docs/dev-setup.md`](./docs/dev-setup.md)
— toolchain pins, `./scripts/check.sh`, and common pitfalls.

Read `CLAUDE.md` before writing code. It encodes the pure / effectful
zone boundary, the one-atom rule, spec usage at boundaries, Closure
Advanced safety, and the runtime-dependency policy (only BareDOM and
JSZip).

## License

MIT. See `LICENSE` for the full text.
