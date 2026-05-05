# Architecture & Development Rules

> Internal maintainer guidance for Bareforge. User-facing docs live under [`docs/`](./docs/). The filename `CLAUDE.md` is historical — these rules apply to every contributor, not only AI-assisted edits.

## What this project is
Bareforge is a ClojureScript/shadow-cljs **application** — a visual landing-page builder. It consumes BareDOM (`com.github.avanelsas/baredom`) from Clojars and uses its 83 custom elements both for editor chrome and for the pages users build. Architecture overview and document model: [`docs/architecture.md`](./docs/architecture.md).

Bareforge is NOT a web component library. Rules from BareDOM's `CLAUDE.md` do not apply here — this is an app, with one state atom and a hand-written DOM reconciler.

## Commands
- Dev:     `npx shadow-cljs watch app` (http://localhost:8765)
- Tests:   `npx shadow-cljs watch test`
- Release: `npx shadow-cljs release app` — must compile clean, zero warnings
- Lint:    `clj-kondo --lint src test scripts`
- Format:  `cljfmt check` / `cljfmt fix`

Run `release` regularly, not just at the end. Closure Advanced renames bite late.

## PR readiness gate
All four MUST pass locally before opening a PR — including AI-assisted edits. CI re-runs them (`.github/workflows/ci.yml`); a green CI shouldn't be the maintainer's first look.

1. `clj-kondo --lint src test scripts` — **0 errors, 0 warnings**. Annotate with `:clj-kondo/ignore` only when lint genuinely doesn't apply.
2. `cljfmt check` — clean. Run `cljfmt fix` if drift exists.
3. `npx shadow-cljs compile test` — `0 failures, 0 errors`.
4. `npx shadow-cljs release app` — `0 warnings`. The only signal that catches Closure Advanced renames.

## NEVER open a pull request without explicit approval
Most-violated rule by AI contributors. **Do not run `gh pr create` (or equivalent) until the maintainer has explicitly said "open the PR" / "push and open the pr" / "go ahead with the PR" or similar.**

Things that are NOT PR approval:
- **Plan approval.** `ExitPlanMode` approves *writing the code*, not opening the PR.
- **All four gates green.** Branch is ready to be reviewed, not that the request is wanted now.
- **A previous PR was opened on similar instruction.** Each PR needs its own go-ahead.
- **Auto mode being on.** Auto mode covers routine local work; PR creation is shared-state and excluded.

When ready: push the branch (`git push -u origin <branch>`), summarise the work + gate results in chat, ask "Ready to open the PR?", and wait for a yes. PR creation kicks shared CI and is publicly visible — the maintainer wants the final call on timing, title, and body.

## After a PR is merged
Confirm the squash commit is on `origin/main` (`git fetch origin && git log origin/main` — squash messages end with `(#<pr>)`), then `git branch -D feature/<name>` locally (squash creates a commit with no shared history, so `-d` refuses) and `git push origin --delete feature/<name>`. Don't pre-emptively delete branches — a revert can strand in-flight work.

## Onboarding a new BareDOM component
Bump the BareDOM version in **both** `deps.edn` and `src/bareforge/meta/versions.cljs` (lockstep is test-enforced — `test/bareforge/meta/versions_test.cljs` fails if either side moves alone), then run `clojure -X:scaffold :tag x-new-thing :category :layout`. If the component is interactive, also add it to `src/bareforge/meta/events.cljs` — the inspector's Events section reads from that list and a missing entry leaves the tag silently non-interactive. Full recipe (slots, placement, enum choices, CSS variables): [`docs/adding-components.md`](./docs/adding-components.md).

## Core philosophy
Model the program as transformations over immutable data. Push side effects to the edges. Prefer clarity over cleverness. Two zones with different rules:

**Pure zone** — `doc/`, `meta/`, `export/`, `storage/` (serialization), `util/`:
- Deterministic: same input → same output. No side effects, no DOM, no atom reads.
- Data-oriented: plain maps, vectors, sets. No records or protocols unless there is a concrete reason. No classes.
- Prefer `map` / `filter` / `reduce` / `into` over loops; threading (`->`, `->>`, `some->`, `cond->`) for clarity.
- Use `clojure.core` first; reach for a dependency only when core genuinely cannot do it.
- Every public fn in `doc/ops.cljs` returns a new document; never mutates.

**Effectful zone** — `state.cljs`, `render/`, `dnd/`, `ui/`, `storage/` (IO):
- Where the atom lives, where `requestAnimationFrame` is scheduled, where `document.createElement` is called, where pointer events are handled.
- Effectful fns stay thin: call into the pure zone, apply the result. No business logic buried inside a DOM handler.
- Imperative DOM is allowed here *and only here*. Use `baredom.utils.dom` helpers (`set-attr!`, `setv!`, `set-bool-attr!`) over raw interop where they fit.

**Rule of thumb:** if a function touches the atom or the DOM, it lives in the effectful zone. Otherwise it must be pure and testable without a browser.

## Design lens (Hickey-style)
Vocabulary used to evaluate new code, review PRs, and audit modules:

- **Incidental complexity** — accidents of how it's built (glue, re-derivations, fanout). Distinct from **essential complexity**, the problem itself.
- **Simple vs. easy** — *simple* is one role, no braiding; *easy* is familiar / near at hand. Choose simple even when easy looks cheaper.
- **Complecting / braided** — intertwining concerns that should be separable. Most refactors are un-braiding.

Four pillars:

1. **De-complecting & orthogonality** — each function does one job: data transformation OR I/O OR DOM OR business logic. When mixed (e.g., a DOM handler running domain logic), pull the pure transformation out and leave only orchestration at the effectful site (`dnd.resolve` is the canonical example: pure planner + four-line `commit-*!` orchestrators).
2. **Information as data** — plain maps, vectors, sets. A node, a binding, an action, a project file: all maps with documented keys, manipulable by `assoc` / `update` / `get-in`. Don't hide data behind methods (the **"totem"** anti-pattern). Prefer a `:kind` key plus `case`/multimethod over type-dispatched polymorphism. Generic functions on generic data outlive bespoke type hierarchies.
3. **Epochal time model** — state is a succession of immutable values, not a place that mutates. There is exactly one atom; `doc.ops/*` produces a new document value (a **calculation**) and `state/commit!` is the single **action** that flips the atom. Calculations and actions are strictly isolated. Any mutable cell outside `state/app-state` is **place-oriented programming (PLOP)** and a red flag.
4. **Language over plumbing** — domain logic as composable data + small primitives, not bespoke imperative code. Specs in `doc/spec.cljs`, ops in `doc/ops.cljs`, tag-level rules in `meta/*.cljs` as plain data (`augment`, `placement`, `events`, `slots`). Existing examples: `clj-form`'s hiccup-shaped intermediate, the export plugin registry/manifest contract, `lower-document` as the canonical model for consumers, the inspector's field-as-data row builders.

Audit checklist for new work:
- **Complected?** Could a reviewer name one concern this fn addresses, or does it span several?
- **Data behind a wall?** Could a future caller `get-in` the value, or is it locked behind a method/protocol?
- **State as values?** Or is something mutating in place where a `commit!` should run?
- **DSL or plumbing?** Could the same logic be data + a small interpreter?

If a review surfaces "braided" or "PLOP," refactor along the offended pillar — don't defend the existing shape because it's familiar.

## State management
- **One atom**, `bareforge.state/app-state`, with top-level keys `:document :selection :history :mode :theme :ui :dirty? :project-file`. No other atoms. No `volatile!`, no mutable JS fields on records.
- All `:document` writes go through `doc.ops/*` (pure) then `state/commit!` (effectful). UI never `swap!`s the document directly.
- `state/commit!` is the only place that pushes history and clears redo. Downstream effects (rAF render, autosave debouncing) live in watchers — `render/canvas/install-watch!`, `storage/indexeddb/install-autosave!` — not inside `commit!`.
- Non-document UI changes use `state/assoc-ui!` and skip history.
- Each subsystem registers **at most one** `add-watch`, keyed by a namespaced sentinel (`::render`, `::inspector`, `::autosave`, …). The handler's first line MUST be an early-exit guard on the slice the subsystem cares about (`(when (not= (get-in old-state path) (get-in new-state path)) …)`). Watches install once on the mount path from `bareforge.main/init`; never `add-watch` from inside business logic.

## Data validation — `clojure.spec.alpha`
- Specs live in `src/bareforge/doc/spec.cljs` and `src/bareforge/meta/spec.cljs` — node, document, and project-file schemas.
- `doc.ops/*` is covered by `test/bareforge/doc/ops_test.cljs` and `actions_test.cljs` — unit tests are the doc-op safety net, not runtime `s/assert`. Specs are a development reference and the load-boundary check below. No `s/valid?` / `s/assert` on `doc.ops/*` in the hot path.
- Loading a `.json` project file validates with `s/explain-data` (see `storage/project_file/validate-project`) and refuses on failure — never silently drop fields.

## Closure Advanced safety
- `shadow-cljs release app` must compile with zero warnings.
- `^js` type hint on every parameter that is a DOM node, custom element instance, event, or other JS object.
- Never `(.-foo obj)` on an unknown JS shape — use `goog.object/get` or destructure through a typed binding.
- Only `^:export` the entry point `bareforge.main/init`. Everything else is free to rename.
- Keyword-to-string conversion for `baredom.utils.dom/setv!` property names must match BareDOM's convention exactly; test on the release build, not just dev.

## JavaScript interop discipline
- Interop at the edges only. Pure code must not see `js/*` or `.-foo`.
- Wrap repeated DOM APIs in small helpers in `util/dom.cljs`.
- Convert JS objects to persistent maps when crossing into the pure zone.

## Tests
- Pure zone: `:node-test` (no browser). Reconciler / rendering: `:browser-test` (real DOM).
- HTML export: golden-file tests — three reference docs, stable serialization, diff-on-change.
- A meta-coverage test enumerates BareDOM tags lacking rich augmentation, so coverage gaps are visible.
- When fixing a bug, add the failing test first.

## Dependencies
- Runtime: BareDOM (Clojars) + JSZip. Dev: shadow-cljs, clj-kondo, cljfmt. That is it.
- Do not add React, Reagent, re-frame, Lit, Alpine, hiccup renderers, core.async, reitit or any other router, or any vdom library. Bareforge stays philosophically aligned with BareDOM: `DOM = f(state)` via a hand-written reconciler.
- No TypeScript, no JSX, no build-time codegen beyond shadow-cljs. Single-view app — no routing in v1.

## Code style
- Files map 1:1 to namespaces. Short aliases (`[bareforge.doc.ops :as ops]`).
- `defn-` for internal helpers — public surface stays obvious.
- Functions over ~30 lines split. Threading over nested calls; `cond->` for conditional assoc chains.
- Anonymous `#(...)` only for one-liners; longer gets a named fn.
- No macros unless a function genuinely cannot do the job.
- Comments explain *why* (constraint, invariant, workaround), never *what*. No dead code, no `_unused` vars, no "removed" placeholders.

## Anti-patterns
- Mutable state outside `state/app-state` — **PLOP**.
- Hidden side effects inside pure-zone fns — **calculation/action complecting**.
- Business logic inside a pointer or DOM event handler — **UI/domain complecting**.
- Deep JS interop leaking into `doc/` or `meta/`.
- Records or protocols used as classes, method dispatch by type — the **"totem"** pattern.
- Monolithic functions parsing + transforming + rendering + committing — **braided** code.
- Re-implementing `clojure.core`.

## Exported ClojureScript code style
Rules governing the CLJS code emitted by `src/bareforge/export/*`, distinct from the project's own `Code style` above. Full numbered set (1–19): [`docs/export-cljs-spec.md`](./docs/export-cljs-spec.md). In-source comments cite rule numbers. Three concept-level invariants:

- **Canonical source + inline (rule 1).** Exported framework and renderer are slurped from `src/bareforge/export/framework.cljs` and `renderer.cljs` via `shadow.resource/inline`, with one `str/replace-first` rewriting the `ns` prefix. New features land in the canonical `.cljs` — *never* as a string-literal copy in `cljs_project.cljs`. Byte-level parity is test-pinned in `cljs_project_test`.
- **Field / Action / Trigger (rule 9).** Three named, separable concepts: a **Field** is a reactive state slot on a group, an **Action** is a named handler that mutates a field on the *same* group, a **Trigger** is a DOM event-to-action wiring on an interactive descendant. If a reviewer can't put a finger on which a chunk of generated code is, the codegen has braided them — fix the generator, not the output.
- **Test-first for codegen bugs (rule 18).** Add a failing test in `test/bareforge/export/` against a known-good fixture before fixing the generator. Generated output drifts silently otherwise.

## Export plugins
The export system is plugin-shaped: `bareforge.export.model` owns the target-agnostic semantic lowering, `bareforge.export.plugin` and `registry` own the plugin contract, and each plugin under `src/bareforge/export/<name>/` consumes the model and emits files. Two non-negotiables:

- **Semantic interpretation lives in the model, not the plugin.** New questions about a document (which group owns a collection, how a trigger's implicit payload resolves, what a `:filter-by` targets) get a helper in `bareforge.export.model` with matching tests. Plugins consume; they don't re-derive.
- **`generate` is pure; `download!` is the only effectful surface.** `(generate doc opts) → {file-path → string}`. DOM, filesystem, and JSZip live in `download!`.

Full contract (manifest shape, runtime-shim placement, NYI escape hatch, pre-1.0/post-1.0 stability pledge, hello-world walk-through): [`docs/plugins.md`](./docs/plugins.md).

## When in doubt
Ask: *can this be expressed as a pure data transformation?* If yes, put it in the pure zone with a unit test. If no, isolate the effect in the smallest possible wrapper and keep the logic it calls pure.
