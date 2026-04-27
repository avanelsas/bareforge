# Bareforge audit — 2026-04-23

## Summary
Scanned 36 files under `src/bareforge/**` (35 cljs/cljc + 1 cljc), 25 test files, 1 scaffolder script, and `CLAUDE.md`. The architecture is mostly faithful to CLAUDE.md: the pure/effectful split is visible, specs exist at document boundaries, and `doc/ops` + `state/commit!` is the dominant write path. The pressure points are (a) two places in the export tree silently lag the BareDOM version, (b) a few pure-zone namespaces reach for atoms as accumulators, (c) the inspector has one 280-line form-builder that uses atoms as transient UI state, and (d) CLAUDE.md itself has three pieces of stale prose that no longer match the code. Counts: 7 HIGH, 8 MEDIUM, 4 LOW, 5 EXPORTED-CODE, 3 CLAUDE.md-drift items.

## HIGH

### BareDOM version lockstep violated — CDN exporters pin 2.1.1 while the jar is 2.3.1
- File: src/bareforge/export/html.cljs:14 and src/bareforge/export/bundle.cljs:23
- Smell: Both files hard-code `default-cdn-version "2.1.1"`; `deps.edn:5` and `cljs_project.cljs:2082` already ship 2.3.1. Recent BareDOM-bump commits had touched only the jar path and the cljs_project generator, leaving the two CDN exporters behind.
- Fix: Move the version string into a single `def` (e.g. `bareforge.meta.versions/baredom`) referenced by html.cljs, bundle.cljs and cljs_project.cljs; or extend the grep-for-lockstep ritual to include both CDN export sites. At minimum, bump both `default-cdn-version` strings to match.
- Why: CLAUDE.md "Onboarding a new BareDOM component" rule: "Keep the two version strings in lockstep; grep `{:mvn/version "<old>"` across the tree after a bump to catch stragglers." There are in fact four version strings, not two.

### "One add-watch drives rendering" rule vs nine registered watches
- Files: render/canvas.cljs:287, render/selection.cljs:333, storage/indexeddb.cljs:125, ui/inspector.cljs:2004, ui/layers.cljs:80, ui/toolbar.cljs:230, ui/theme_editor.cljs:66, ui/inline_edit.cljs:166, ui/app.cljs:57
- Smell: CLAUDE.md State management: "One `add-watch` drives rendering. Do not add more watches; add logic inside the existing one." Nine distinct `::render / ::autosave / ::inspector / ::layers / ::toolbar / ::theme / ::inline-edit / ::selection-overlay / ::mode` watchers exist, each filtering for changes to the slice it cares about.
- Fix: Either delete this rule from CLAUDE.md in favour of the factual "one watcher per concern, each short-circuits on its own filter" design, or route every subscriber through a single dispatcher in `state.cljs`. The current multi-watch design is fine on merits, but the prose needs to match.
- Why: State management section, lines 108-109. The code is in direct contradiction with the rule as currently written.

### `build-add-field-form` in inspector.cljs — 280 lines, 11 local atoms as UI state
- File: src/bareforge/ui/inspector.cljs:1257-1538
- Smell: One fn creates 11 atoms (`selected-type`, `selected-og`, `computed?`, `selected-op`, `selected-src`, `selected-proj`, `selected-jtg`, `selected-jtm`, `selected-fbm`, `selected-fbs`) to coordinate a multi-select form, plus 3 inner named helpers and a 60-line commit handler. 280 lines vs the ~30-line soft limit. Also violates Anti-pattern "Mutable state outside `state/app-state`."
- Fix: Lift the form's transient state into `state/app-state :ui :add-field-form` (an ordinary map) and `state/assoc-ui!` it on each `select-change`; then the form becomes a `let` over derived values and a commit that reads the UI map. Alternatively extract per-operation form builders (`build-sum-of-subform`, `build-join-on-subform`, `build-filter-by-subform`) so each is ~30 lines.
- Why: CLAUDE.md Code style "If a fn is over ~30 lines, split it" + Anti-patterns "Mutable state outside `state/app-state`" + "Large monolithic functions that parse, transform, render, and commit all at once."

### `generate-core` in cljs_project.cljs — 190 lines, mixed data-prep + string assembly
- File: src/bareforge/export/cljs_project.cljs:1531-1720
- Smell: Single fn collects inline-entry metadata, resolves template subs, gathers four kinds of aliases, assembles the `:require` list, and formats the view body. 190 lines, `let` with >20 locals. Tested in cljs_project_test.cljs, so behaviour coverage exists.
- Fix: Extract two pure helpers: `(core-requires doc groups root-order)` returning the alias set, and `(core-view-entry doc entry inline-index)` returning a single body segment. `generate-core` then threads through those and emits the top-level string. Each piece shrinks below the soft limit.
- Why: CLAUDE.md Code style "Small, composable functions. If a fn is over ~30 lines, split it"; Anti-patterns "Large monolithic functions that parse, transform, render, and commit all at once"; Exported-code rule 18 (bug-first test) already provides safety net.

### `generate-views` in cljs_project.cljs — 156 lines, `let` with ~25 locals
- File: src/bareforge/export/cljs_project.cljs:791-947
- Smell: Same shape as `generate-core`: the function collects sub-groups, read/payload/trigger fields, alias bundles, and the view signature before calling `node->hiccup-with-events`. The `let` reads like a small program.
- Fix: Pull the metadata gathering into `(view-context doc group all-groups app-ns)` that returns a map (`:let-fields :field->sym :requires :fn-sig …`); the remaining `generate-views` body is the require clause + hiccup emission. See HIGH entry above for pattern.
- Why: Same CLAUDE.md rules as `generate-core`.

### Atoms used as accumulators inside pure-zone functions
- File: src/bareforge/export/cljs_project.cljs:89-145 (`detect-groups`, 4 atoms) and src/bareforge/export/html_to_hiccup.cljs:73-95 (`build-tree`, 1 atom)
- Smell: Both live in `export/` (pure zone). `detect-groups` uses `(atom #{})`, `(atom {})`, `(atom #{})`, `(atom [])` as transient scratch; `build-tree` uses `(atom [{…}])` as a parser stack. Both are deterministic externally, but both run `swap!` 1..N times per call which is exactly the side-effect shape CLAUDE.md says pure fns must not contain.
- Fix: `detect-groups` → a pair of `reduce`s, one that builds the `{ns-name → group-entry}` map and one that walks root children collecting `order`. `build-tree` → `reduce` over tokens with a vector stack in the accumulator.
- Why: CLAUDE.md Pure zone: "No side effects, no DOM, no atom reads"; Anti-patterns: "Mutable state outside `state/app-state`"; "Hidden side effects inside pure-zone functions."

### CLAUDE.md says `doc.ops/*` has `s/valid?`/`s/assert` checks — they are absent
- File: src/bareforge/doc/ops.cljs (entire file) vs src/bareforge/doc/spec.cljs
- Smell: spec.cljs defines `::document`, `::node`, `::field-def`, etc. but `grep 's/valid?\|s/assert'` over `src/` returns only one hit — in `storage/project_file.cljs`. No `doc.ops/*` entry guards its output or inputs with spec.
- Fix: Either drop the "in dev" claim from CLAUDE.md and document spec as strictly a load-boundary tool, or add `s/assert` at the tail of each `doc.ops/*` public fn behind `(s/check-asserts true)` flipped by a `goog-define`. The rule as written isn't implemented.
- Why: CLAUDE.md Data validation section, lines 117-118: "`doc.ops/*` functions have explicit `s/valid?` / `s/assert` checks in dev; stripped in release via `goog-define` or `s/check-asserts`." Code does neither.

## MEDIUM

### `toolbar-state-ref` — JS-object callback registry sidesteps the atom
- File: src/bareforge/ui/toolbar.cljs:19-34
- Smell: `#js {:theme-toggle nil :templates-toggle nil}` holds two functions set by `ui/app.cljs` at mount time, read from inside click handlers. It's a mutable JS global acting as a late-binding hook.
- Fix: Put the callbacks on `state/app-state :ui :toolbar-callbacks`, or pass them into `toolbar/create` as arguments and close over them. Either removes the global.
- Why: Anti-patterns: "Mutable state outside `state/app-state`." The justification for `drag-state` and `node-index` (rendering bookkeeping local to one file) doesn't apply here — `toolbar-state-ref` is a cross-namespace coupling table.

### Direct `swap! state/app-state update-in [:ui …]` bypasses `assoc-ui!`
- File: src/bareforge/ui/inspector.cljs:1191-1194 (`toggle-seeds-expanded!`)
- Smell: Mutates `[:ui :expanded-seeds]` via raw `swap!` instead of going through `state/assoc-ui!`. Works, but sets a precedent for UI code to bypass the one-function interface.
- Fix: Either add a `state/update-ui!` helper that takes a key + fn and use it here, or `(state/assoc-ui! :expanded-seeds (if … (disj …) (conj …)))` reading the current value first. The latter matches the pattern of `toggle-section!` two screens up (inspector.cljs:696).
- Why: CLAUDE.md State management: "Non-document UI changes use `state/assoc-ui!` and skip history."

### Palette seeds `x-grid` with invalid CSS `columns "3"`
- File: src/bareforge/ui/palette.cljs:103
- Smell: `seed-for-tag "x-grid"` returns `{:attrs {"columns" "3"}}`. BareDOM passes `columns` through to `grid-template-columns`; a bare integer produces invalid CSS. The generator's `normalize-attr-value` catches it on export, but inside Bareforge the reconciler writes the raw `"3"` to the live element.
- Fix: Seed with `"repeat(3, 1fr)"`. One-liner. The generator's safety net stays as a safety net, which is what CLAUDE.md says it is.
- Why: CLAUDE.md x-grid rule (after rule 12): "Documents should still carry valid track lists — the coercion exists for legacy docs and errant inspector input, not as a shortcut."

### `state/commit!` docstring claims rAF + autosave responsibilities it no longer owns
- File: src/bareforge/state.cljs:84-89
- Smell: The docstring reads "Does not render — rendering is driven by a watcher installed elsewhere" which is fine. But CLAUDE.md line 105-106 says "`state/commit!` is the only place that pushes history, clears redo, schedules rAF, and kicks autosave." In practice rAF lives in `render/canvas/install-watch!` and autosave lives in `storage/indexeddb/install-autosave!`, both of which watch the atom — `commit!` itself is just a `swap! … apply-commit`.
- Fix: Tighten the CLAUDE.md bullet to say "commit! is the only place that pushes history and clears redo; rAF-scheduling and autosave are driven by watchers on the atom installed at mount time." The code is correct; the rule is stale.
- Why: CLAUDE.md State management, line 105-106.

### Atom-per-form state in `build-add-action-form`
- File: src/bareforge/ui/inspector.cljs:1850-1905
- Smell: Same pattern as `build-add-field-form` but smaller: 2 atoms (`selected-op`, `selected-tgt`). 55 lines, just over the soft limit.
- Fix: Same remedy as the HIGH — move transient form state into `:ui`. Smaller footprint, but symmetry with the bigger form makes the refactor low-risk.
- Why: Anti-patterns: "Mutable state outside `state/app-state`."

### `node->hiccup-with-events` at 100 lines + 11-arg signature
- File: src/bareforge/export/cljs_project.cljs:650-767
- Smell: Takes 11 positional args (`doc node slot-name depth field->sym sub-group-ids all-groups template-groups field-owner-ns-map tmpl-record-sym name->ns own-ns-name`), reduces over slot children, and emits a hiccup string. Readable at a crawl because every call site re-passes the same bag.
- Fix: Bundle the context-y args into a single map `{:doc :all-groups :template-groups :field-owner-ns-map :name->ns :own-ns-name}` that's threaded through as one argument plus the node-specific args. Every existing call site already has all those values in scope.
- Why: CLAUDE.md Code style "Small, composable functions"; >30-line soft limit.

### `update-field-in-place!` — 66-line `case` block per widget kind
- File: src/bareforge/ui/inspector.cljs:897-962
- Smell: Big `case` over the `data-prop-kind` attribute stamped on each widget, with a 8-line body per branch re-deriving `(widget-value el)` and calling `.setAttribute`.
- Fix: Extract a pure `(widget-sync-value node kind prop-name transform) -> string` helper; the effectful fn becomes `(when (not= new-v cur-v) (.setAttribute el "value" new-v))` and the case just picks the helper. Individual branches drop to ~2 lines.
- Why: CLAUDE.md Code style + "Business logic buried inside a pointer or DOM event handler" — the logic is in an effectful refresh path.

### Write-path duplication across `commit-attr!` / `commit-prop!` / `commit-text!` / `commit-inner-html!` / `commit-layout!` / `commit-css-var!`
- File: src/bareforge/ui/inspector.cljs:108-131
- Smell: Six 3-5-line commit helpers all fetch `(:document @state/app-state)`, call one `doc.ops/*` fn, and `state/commit!`. The pattern is identical.
- Fix: A single `(commit-with! op-fn & args)` that does `(state/commit! (apply op-fn (:document @state/app-state) args))`; every caller reads `(commit-with! ops/set-attr node-id k v)`.
- Why: CLAUDE.md Code style: "Small, composable functions"; Anti-patterns: "Re-implementing `clojure.core`" by proxy — the helpers are identical boilerplate.

## LOW

### `current-basename` / filename coupling reads through project-file but called from toolbar
- File: src/bareforge/ui/toolbar.cljs:117-126
- Smell: Three tiny exporters (`on-export!`, `on-export-bundle!`, `on-export-cljs!`) each call `(str (current-basename) ".<ext>")`. The extension varies but the pattern is identical.
- Fix: `(fn [ext] (str (current-basename) "." ext))` + one `export-with-extension!` helper. Saves 6 lines, reads at a glance.
- Why: CLAUDE.md Code style: "Small, composable functions."

### `canvas/patch!` returns a side-effectful value (the expanded doc set via mutation)
- File: src/bareforge/render/canvas.cljs:270
- Smell: Last line of `patch!` is `(set-rendered-doc! expanded)`. The sequence is readable, but the function silently owns bookkeeping the caller can't see.
- Fix: Leave it as is and add a single comment line at the top explaining "patch! owns the last-rendered snapshot stored in render-state". The file's ns docstring already says so — one sentence next to the function would make the relationship visible at the call site.
- Why: CLAUDE.md Code style: "Comments explain *why* (constraint, invariant, workaround)." This is a genuine invariant worth marking.

### `computed-op->fn` map missing `:any-of` and `:join-on` lookups produces cryptic `:!unknown-op-…`
- File: src/bareforge/export/cljs_project.cljs:1113-1120 and 1165
- Smell: `:any-of`, `:join-on`, `:filter-by` are deliberately handled by different emitters, so the map is only consulted for the "simple" ops. When the `case` falls through (v1 doc with an unknown op), the extractor string becomes `":!unknown-op-:<op>"` which is read by downstream string concatenation, not caught.
- Fix: Throw from `emit-computed-sub` when `op` is not in the known set, so authoring bugs surface at export time instead of at runtime in the generated project. Add a one-line test against a synthetic bad-op doc in `cljs_project_test.cljs`.
- Why: CLAUDE.md Exported-code rule 18: "add a failing test in `test/bareforge/export/` against a known-good fixture before fixing the generator." This flips that preemptively — fail loudly on unknown ops.

### `escape-hiccup-str` is duplicated by `escape-str` in html-to-hiccup
- File: src/bareforge/export/cljs_project.cljs:212-217 and src/bareforge/export/html_to_hiccup.cljs:13-16
- Smell: Same body: escape `\` then `"`. Two namespaces define it privately.
- Fix: Promote one to a small export-util ns, or `declare` it in both and keep the private. Not urgent.
- Why: CLAUDE.md Anti-patterns: "Re-implementing `clojure.core`" — by proxy, re-implementing itself.

## EXPORTED-CODE (src/bareforge/export/*)

### Embedded framework source in `generate-framework` diverges from canonical `export/framework.cljs`
- File: src/bareforge/export/cljs_project.cljs:1722-1899 vs src/bareforge/export/framework.cljs
- Smell: The canonical `export/framework.cljs` has docstrings, comments, and `reset-all!` (for tests). The embedded string in `generate-framework` strips docstrings and omits `reset-all!`. A `diff` confirms they are structurally different — any future change to the canonical file has to be hand-mirrored into the string, with no compiler to catch the mismatch.
- Fix: Node export path already reads files at write-time via `fs.writeFileSync`; change `generate-framework` to slurp `src/bareforge/export/framework.cljs` and `s/replace` the `ns` prefix. Alternatively, have a release-time task assert the two sources are equivalent AST-wise (a test that parses both with rewrite-clj and compares forms).
- Why: CLAUDE.md Exported-code rule 1: "Any generator feature that requires a new arity must be added to the framework first, with a node test covering it" — but two copies make this ritual silently skippable.

### Rule 3 compliance needs verification — two `declare`s in generated `app.framework`?
- File: src/bareforge/export/cljs_project.cljs:1722-1899
- Smell: Rule 3 says "No forward `declare` in the generated `app.framework`." I did not spot any `declare` in the embedded framework string, but the only safety net is the grep on the embedded string; the canonical `export/framework.cljs` also has none. Worth adding a unit test assertion once to freeze the contract: `(is (not (re-find #"\(declare " emitted-framework)))`.
- Fix: One-line test in `framework_test.cljs` against the generator output.
- Why: Exported-code rule 3.

### `emit-action-event` / `emit-setter-event` emit indentation-sensitive strings that have no unit coverage of the exact shape
- File: src/bareforge/export/cljs_project.cljs:1405-1447
- Smell: Rule 10's handler shape (`[rf/trim-v (rf/path ::…/…)]` two lines down, body indented with two spaces) is hand-built via string concatenation. The test suite checks the overall generated file parses, but a whitespace regression would slip through.
- Fix: Add a string-equality assertion against a golden fragment: "given field `::cart.db/cart-count`, emit exactly `(rf/reg-event\n ::cart-count-changed\n [rf/trim-v\n  (rf/path ::cart.db/cart-count)]\n (fn [_ [new-cart-count]]\n   new-cart-count))`." Freezes the shape so downstream re-frame porting stays byte-exact.
- Why: Exported-code rule 10: auto setter interceptor chain shape is load-bearing; the "must" phrasing in the rule warrants a test.

### x-grid track-list coercion is data-driven in one place and hard-coded in another
- File: src/bareforge/export/cljs_project.cljs:219-231 (`normalize-attr-value`) and src/bareforge/ui/palette.cljs:103
- Smell: The generator coerces bare integers in `columns` at emission time (good safety net). The palette seed plants the bad value in the first place. See the palette-seed MEDIUM for the companion smell.
- Fix: Fix the palette seed; keep the generator's safety net per CLAUDE.md's explicit guidance.
- Why: CLAUDE.md x-grid rule (after rule 12).

### `:payload` handling split across `payload-arg`, `event-aware-payload?`, `trigger->dispatch`, `build-event-row`
- File: src/bareforge/export/cljs_project.cljs:549-616
- Smell: The three payload shapes from rule 17 (`:field`, `:literal`, `:event-detail`) and the `:prevent-default?` flag are implemented across four small fns. Rule 17 is the one place in CLAUDE.md with the most sub-rules; the corresponding code is also the most spread-out.
- Fix: One `(payload->dispatch trigger field->sym tmpl-record-sym)` that returns `{:fn-head :body :event-needed?}` as a map, replacing the ad-hoc triple. Shrinks each call site and puts the shape logic in one place.
- Why: Exported-code rule 17 has the highest surface area; consolidation reduces the chance of rule-17 bugs.

## CLAUDE.md drift

### Lockstep rule names only `cljs_project.cljs`
- File: CLAUDE.md:27-35
- Drift: "Also bump the hard-coded baredom version in `src/bareforge/export/cljs_project.cljs:generate-deps-edn`". But `export/html.cljs:14` and `export/bundle.cljs:23` also hold hard-coded `default-cdn-version` strings, both lagging at 2.1.1 (HIGH finding above).
- Fix: Update the CLAUDE.md onboarding section to list all three callsites, or — better — collapse to "`grep -n '2\.[0-9]\.[0-9]' src` before and after a bump" and remove the file-by-file list.

### "One add-watch" vs nine watches
- File: CLAUDE.md:108-109
- Drift: Rule says "Do not add more watches; add logic inside the existing one." Code has nine. The rule is not the code's actual design contract.
- Fix: Rewrite to match the actual design — e.g. "Each UI subsystem owns at most one watch, each with an early `(when (not= old-<slice> new-<slice>) …)` gate. Do not add watches inside business-logic functions."

### "doc.ops/* have s/valid?/s/assert checks in dev"
- File: CLAUDE.md:117-118
- Drift: See HIGH finding — the code has none of these. Either add them or tighten the rule to "Specs are used at load boundaries (`storage/project-file/validate-project`) and as a development reference; `doc.ops/*` functions are covered by unit tests instead."

## Priority-ordered punch list (top 10)
1. src/bareforge/export/html.cljs:14 and src/bareforge/export/bundle.cljs:23 — bump `default-cdn-version "2.1.1"` → `"2.3.1"` (or centralise).
2. CLAUDE.md:108-109 — reconcile "one add-watch" rule with the nine watches actually in the code.
3. src/bareforge/ui/inspector.cljs:1257-1538 — split `build-add-field-form` and move form state into `:ui`.
4. src/bareforge/export/cljs_project.cljs:1722-1899 — collapse the embedded framework string to a `slurp` of the canonical `export/framework.cljs`, or lock the two files with a parity test.
5. src/bareforge/export/cljs_project.cljs:89-145 — replace the 4 atoms in `detect-groups` with a `reduce`.
6. src/bareforge/export/html_to_hiccup.cljs:73-95 — replace the atom-stack in `build-tree` with a `reduce`.
7. src/bareforge/export/cljs_project.cljs:1531-1720 — extract `core-requires` and `core-view-entry` from `generate-core`.
8. CLAUDE.md:117-118 and src/bareforge/doc/ops.cljs — either add `s/assert` calls or tighten the rule.
9. src/bareforge/ui/palette.cljs:103 — seed `x-grid` with `"repeat(3, 1fr)"` instead of `"3"`.
10. src/bareforge/ui/toolbar.cljs:19-34 — remove `toolbar-state-ref` by passing callbacks into `toolbar/create` from `ui/app`.

---

## Status as of 2026-04-27 — every item resolved or deliberately deferred

All ten priority items are closed. Every HIGH and MEDIUM is either
fixed in code or has shifted to a planned follow-up. Detail:

| # | Item | Status | Resolution |
|---|---|---|---|
| 1 | BareDOM version lockstep (CDN exporters lagged the jar) | **CLOSED** | All call sites read from `bareforge.meta.versions/baredom-version`; `test/bareforge/meta/versions_test.cljs` enforces lockstep with `deps.edn`. |
| 2 | "One add-watch" rule vs nine watches | **CLOSED** | CLAUDE.md rule was the drift, not the code — each subsystem owns one watch with a unique sentinel and an early-exit guard, which is the load-bearing invariant. Rule reworded to describe the actual mount path. |
| 3 | `build-add-field-form` 280-line monster | **CLOSED** | Atoms removed earlier; remaining structural split into stage helpers (`build-add-field-widgets` / `populate-add-field-options!` / `wire-add-field-handlers!` / `assemble-add-field-form`) landed. |
| 4 | Embedded framework string in cljs_project | **CLOSED** | `bareforge.export.cljs-project.framework` lives in its own canonical `.cljs` file, slurp-inlined via `shadow.resource/inline` with one `str/replace-first` ns rewrite. |
| 5 | Atoms in `detect-groups` | **CLOSED** | `detect-groups` now lives in `bareforge.export.model` and is a pure `reduce` over the doc walk — no atoms. |
| 6 | Atom-stack in `html_to_hiccup/build-tree` | **CLOSED** | `build-tree` is now a `reduce` over the token list; no atoms. The whole namespace was promoted from cljs-project subdir to `bareforge.export.html-to-hiccup` and reused by every plugin. |
| 7 | Extract `core-requires` / `core-view-entry` from `generate-core` | **CLOSED** | `generate-core` is a 52-line orchestrator; helpers (`core-requires`, `core-view-body`, `inline-entry`) extracted. |
| 8 | `s/assert` claim in CLAUDE.md vs absent runtime checks | **CLOSED** | CLAUDE.md updated to say "unit tests are the doc-op safety net, not runtime `s/assert`". |
| 9 | Palette `x-grid` columns `"3"` (invalid CSS) | **CLOSED** | The palette + emit pipelines emit `"repeat(3, 1fr)"`. |
| 10 | `toolbar-state-ref` JS-object callback registry | **CLOSED** | Toolbar callbacks now flow as plain map opts into `toolbar/create` from `ui/app`. |

### MEDIUM items (M1–M5)

All five MEDIUM items resolved during the audit-cleanup arc:

- **M1** — `toggle-seeds-expanded!` via `state/update-ui!`
- **M2** — Drop atoms in `build-add-action-form`
- **M3** — `node->hiccup-with-events` takes a ctx map
- **M4** — Split `update-field-in-place!`; canonicalise selection id
- **M5** — Collapse `commit-*` helpers to `commit-with!`

### CLAUDE.md drift items

All three drift items closed by the same edit that reconciled the
add-watch rule and the `s/assert` claim. The "lockstep rule names
only `cljs_project.cljs`" item was closed by the version-lockstep
test, which made the lockstep machine-checked rather than
prose-only.

### EXPORTED-CODE items

The five exported-code items in the original audit have been
absorbed into the broader export-plugin refactor (`feature/export-plugins`,
`feature/plugin-subdir-layout`, `feature/dedupe-field-predicates`).
Each emit path now goes through `bareforge.export.model` for
semantics and a target-specific codegen for output, so embedded
framework strings, indentation-sensitive emitters, and duplicated
classification logic are no longer present.

**Net**: every finding in this audit was resolved before the
v0.1.0 launch. The audit remains in the public repo as a
transparency signal — it shows how Bareforge's review-and-fix
cycle works in practice.
