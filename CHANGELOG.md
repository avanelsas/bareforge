# Changelog

All notable changes to Bareforge are documented here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [SemVer](https://semver.org/spec/v2.0.0.html). The
leading `0` in `0.x.y` versions encodes "pre-1.0, breaking changes
possible" — I won't promise API stability until `1.0.0` lands.

## [Unreleased]

Nothing yet.

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
