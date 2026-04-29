# Architecture & Development Rules

> Internal maintainer guidance for Bareforge's codebase. Third-party
> contributors may find it useful for understanding how the project
> hangs together; the user-facing docs live under [`docs/`](./docs/).
> The filename `CLAUDE.md` is a historical artefact — these rules
> apply to every contributor, not only AI-assisted edits.

## What this project is
Bareforge is a ClojureScript/shadow-cljs **application** — a visual landing-page
builder. It consumes BareDOM (`com.github.avanelsas/baredom`) from Clojars and
uses its 83 custom elements both for the editor chrome and for the pages users
build. See [`docs/architecture.md`](./docs/architecture.md) for the
architecture overview and the document model.

Bareforge is NOT a web component library. Rules from BareDOM's CLAUDE.md do not
apply here — this is an app, with one state atom and a hand-written DOM
reconciler.

## Commands
- Dev server:   `npx shadow-cljs watch app`   (http://localhost:8765)
- Tests (node): `npx shadow-cljs watch test`
- Release:      `npx shadow-cljs release app` (must compile clean, zero warnings)
- Lint:         `clj-kondo --lint src test scripts`
- Format:       `cljfmt check` / `cljfmt fix`

Run `release` regularly, not just at the end. Closure Advanced renames bite late.

## PR readiness gate

Before opening a pull request — including AI-assisted edits — every
one of the following MUST pass locally. CI re-runs them, but a green
CI shouldn't be the first time the maintainer sees the result.

1. `clj-kondo --lint src test scripts` — **0 errors, 0 warnings**.
   Don't open a PR with warnings; clean them or annotate with
   `:clj-kondo/ignore` where the lint genuinely doesn't apply.
2. `cljfmt check` — clean. Run `cljfmt fix` if drift exists.
3. `npx shadow-cljs compile test` — `0 failures, 0 errors`.
4. `npx shadow-cljs release app` — `0 warnings`. Closure Advanced
   renaming bites late; this is the only signal that catches it.

Skipping any of these in a PR wastes a CI run and the
maintainer's review time. The CI workflow at
`.github/workflows/ci.yml` enforces the same four gates;
matching them locally first is the contract.

**AI assistants: ALWAYS ask for explicit permission before
opening a pull request.** Even when the four gates above are
green and the work looks ready, do not run `gh pr create`
without the maintainer's go-ahead. PR creation is a public,
shared-state action (notifies reviewers, kicks CI, lands in the
PR list); the maintainer wants the final call on timing, title,
and body. Push the feature branch if needed, summarise the
gate results, and wait for a "yes" before opening the PR.

## Onboarding a new BareDOM component

Two-step recipe:

1. Bump the BareDOM version in **both** places:
   - `deps.edn` — the `com.github.avanelsas/baredom` entry, which
     pins Bareforge's own runtime jar.
   - `src/bareforge/meta/versions.cljs` — the single `def
     baredom-version`, consumed by every export path (HTML export,
     bundle-zip export, CLJS-project export). Changing it here
     updates the CDN URL in HTML export, the vendored jar URL in
     bundle export, and the emitted `deps.edn` inside exported CLJS
     projects, all at once.

   These two files must stay in lockstep — `deps.edn` is EDN so it
   cannot import the CLJS `def`. The lockstep is enforced by
   `test/bareforge/meta/versions_test.cljs` — `npx shadow-cljs
   compile test` fails loudly if either side moves without the
   other, so a half-finished bump never reaches a green test gate.
   No manual grep needed.

   Skipping half of this pair means Bareforge itself recognises the
   new component but the exported artefacts pull an older jar where
   the component (or its newly-added attributes, like
   `x-popover.portal` in 2.3) doesn't exist.
2. Run the scaffolder:
   ```
   clojure -X:scaffold :tag x-new-thing :category :layout
   ```
   (Add `:dry-run true` to preview without writing.)

This edits `src/bareforge/meta/public_api.cljs`,
`src/bareforge/meta/augment.cljs`, and
`src/bareforge/meta/categories.cljs` in place. The new component
lands in the palette on the next `shadow-cljs` reload, with
heuristic property kinds inferred from each observed attribute's
name (see `src/bareforge/meta/heuristics.cljc`).

Optional follow-ups (only when the defaults aren't enough):
- Add a `slots.cljs` entry if it's a container with non-default
  slots (containers need explicit slot registration; `registry/container?`
  only returns true for explicitly registered tags).
- Add a `placement.cljs` hint for snap behaviour on drop.
- Hand-edit the scaffolded augment entry for enum choices and
  CSS variable editors.
- If the component is interactive, add a `src/bareforge/meta/events.cljs`
  entry mapping the tag to its supported DOM events (e.g.
  `"x-popover" ["x-popover-toggle" "mouseenter" "mouseleave"]`). The
  inspector's Events section reads from this list — no entry means
  the tag isn't exposed as interactive.

See `scripts/scaffold_component.clj` for the full implementation.

---

## Core philosophy

Model the program as transformations over immutable data. Push side effects to
the edges. Prefer clarity over cleverness.

Two zones exist in this codebase, with different rules:

### Pure zone — `doc/`, `meta/`, `export/`, `storage/` (serialization), `util/`
- Functions are deterministic: same input → same output.
- No side effects, no DOM, no atom reads.
- Think input → transformation → output; prefer `map` / `filter` / `reduce` /
  `into` over loops. Use threading (`->`, `->>`, `some->`, `cond->`) for clarity.
- Data-oriented: plain maps, vectors, sets. No records, no protocols unless
  there is a concrete reason. No classes.
- Use `clojure.core` first. Reach for a dependency only when core genuinely
  cannot do it.
- Every public fn in `doc/ops.cljs` returns a new document; never mutates.

### Effectful zone — `state.cljs`, `render/`, `dnd/`, `ui/`, `storage/` (IO)
- This is where the atom lives, where `requestAnimationFrame` is scheduled,
  where `document.createElement` is called, where pointer events are handled.
- Keep effectful functions thin: they call into the pure zone, then apply the
  result. No business logic buried inside a DOM handler.
- Imperative DOM code is allowed here *and only here*. Use `baredom.utils.dom`
  helpers (`set-attr!`, `setv!`, `set-bool-attr!`) where they fit instead of
  raw interop.

**Rule of thumb:** if a function touches the atom or the DOM, it lives in the
effectful zone. If it does not, it must be pure and testable without a browser.

---

## State management
- **One atom**, `bareforge.state/app-state`, with top-level keys
  `:document :selection :history :mode :theme :ui :dirty? :project-file`.
  No other atoms. No `volatile!`, no mutable JS fields on records.
- **All `:document` writes go through `doc.ops/*`** (pure) then
  `state/commit!` (effectful). UI code must never `swap!` the document
  directly.
- `state/commit!` is the only place that pushes history and clears
  redo. Do not duplicate that logic elsewhere. Downstream effects
  (rAF-scheduling of a render, autosave debouncing) live in their
  own watchers on the atom — `render/canvas/install-watch!`,
  `storage/indexeddb/install-autosave!` — not inside `commit!`.
- Non-document UI changes use `state/assoc-ui!` and skip history.
- Each subsystem may register **at most one** `add-watch` on
  `state/app-state`, keyed by a namespaced sentinel (e.g.
  `::render`, `::inspector`, `::autosave`). The handler's first
  line MUST be an early-exit guard on the slice the subsystem
  cares about —
  `(when (not= (get-in old-state path) (get-in new-state path)) …)`
  — so unrelated commits are free. Watches are installed once
  during the initial mount path (rooted at `bareforge.main/init`,
  which calls `ui.app/mount!` and `idb/install-autosave!` —
  panel-owned watches are installed transitively from each panel's
  own `install-watch!` / `create` fn). Never call `add-watch`
  from inside a business-logic function; install sites belong on
  the mount path only.

---

## Data validation — `clojure.spec.alpha`
- Specs live in `src/bareforge/doc/spec.cljs` and
  `src/bareforge/meta/spec.cljs`.
- Spec the node schema, the document schema, and the project-file schema.
- `doc.ops/*` is covered by `test/bareforge/doc/ops_test.cljs` and
  `test/bareforge/doc/actions_test.cljs` — the unit tests are the
  doc-op safety net, not runtime `s/assert`. Specs remain a
  development reference (read them to see the shape an op should
  return) and the load-boundary check below. No `s/valid?` /
  `s/assert` on `doc.ops/*` in the hot path.
- When loading a `.json` project file, validate with `s/explain-data`
  (see `storage/project_file/validate-project`) and refuse to load
  on failure — never silently drop fields.
- Spec is a development and boundary tool, not a runtime type system. Do not
  sprinkle `s/valid?` inside hot rendering paths.

---

## Closure Advanced safety
- `shadow-cljs release app` must compile with zero warnings.
- Put `^js` type hints on every parameter that is a DOM node, custom element
  instance, event, or other JS object.
- Never use `(.-foo obj)` on an unknown JS shape. Use `goog.object/get` or
  destructure through a typed binding.
- Only `^:export` the single entry point `bareforge.main/init`. Everything
  else is free to rename.
- Keyword-to-string conversion for property names passed to
  `baredom.utils.dom/setv!` must match BareDOM's convention exactly. Test on
  the release build, not just dev.

---

## JavaScript interop discipline
- Keep interop at the edges. Pure code must not see `js/*` or `.-foo`.
- Wrap DOM APIs in small idiomatic helpers in `util/dom.cljs` when a call
  shows up more than twice.
- Do not leak JS mutability inward. If you read from a JS object into the
  pure zone, convert to a persistent map at the boundary.

---

## Tests
- Unit tests for everything in the pure zone using shadow's `:node-test`
  build — no browser needed.
- Reconciler and rendering tests use `:browser-test` (real DOM).
- Golden-file tests for HTML export: three reference documents, stable
  serialization, diff-on-change.
- A meta-coverage test enumerates BareDOM tags without rich augmentation, so
  coverage gaps are visible, not silent.
- When fixing a bug, add the failing test first.

---

## Dependencies
- Runtime: BareDOM (Clojars) + JSZip. That is it.
- Dev: shadow-cljs, clj-kondo, cljfmt. That is it.
- Do not add React, Reagent, re-frame, Lit, Alpine, hiccup renderers,
  core.async, reitit or any other router, or any vdom library. Bareforge
  stays philosophically aligned with BareDOM: `DOM = f(state)` applied by a
  hand-written reconciler.
- No TypeScript, no JSX, no build-time code generation beyond shadow-cljs.
- Bareforge is a single-view app. There is no routing and there will be no
  routing library in v1.

---

## Code style
- Files map 1:1 to namespaces. Short aliases: `[bareforge.doc.ops :as ops]`.
- `defn-` for internal helpers so each namespace's public surface is obvious.
- Small, composable functions. If a fn is over ~30 lines, split it.
- Threading macros over nested calls. `cond->` for conditional assoc chains.
- Anonymous `#(...)` only for one-liners; anything longer gets a named fn.
- No macros unless a function genuinely cannot do the job.
- Comments explain *why* (constraint, invariant, workaround), never *what*.
- No dead code, no `_unused` vars left behind, no "removed" placeholders.

---

## Anti-patterns
- Mutable state outside `state/app-state`.
- Hidden side effects inside pure-zone functions.
- Business logic buried inside a pointer or DOM event handler.
- Deep JS interop leaking into `doc/` or `meta/`.
- Object-oriented design (records or protocols used as classes, method
  dispatch by type).
- Large monolithic functions that parse, transform, render, and commit all
  at once.
- Re-implementing `clojure.core`.

---

## Exported ClojureScript code style

These rules govern the ClojureScript code emitted by the export pipeline
(`src/bareforge/export/*`). They are distinct from the `Code style` section
above, which covers Bareforge's own source.

1. The exported `app.framework` is a minimal re-frame subset. It MUST
   support three `reg-sub` arities:
   - `(reg-sub id handler-fn)`                 — free-form handler
   - `(reg-sub id :-> key)`                    — direct extraction from root db
   - `(reg-sub id :<- [source] :-> key)`       — derived from another sub
   Any generator feature that requires a new arity must be added to the
   framework first, with a node test covering it.

   **Canonical source + inline.** `app.framework` and `app.renderer` are
   both slurped from their canonical files at build time —
   `src/bareforge/export/framework.cljs` and
   `src/bareforge/export/renderer.cljs` — via
   `shadow.resource/inline`, with one `str/replace-first` rewriting the
   `ns` prefix to `<app-ns>.framework` / `<app-ns>.renderer`. New
   features land in the canonical `.cljs` file (with normal syntax
   highlighting, compile-time checking, and namespace-level tests), not
   in a string literal inside `cljs_project.cljs`. Byte-level parity is
   pinned by two tests in `cljs_project_test` that reproduce the
   `str/replace-first` and assert equality against the emitted output;
   a re-introduced manual copy will fail them with a clean diff.

2. Generated subs prefer the shortened shapes:
   - Direct extraction (most common):
     `(rf/reg-sub ::cart-count :-> ::cart.db/cart-count)`
   - Derived from another sub (only when truly derived):
     `(rf/reg-sub ::foo :<- [::bar] :-> ::ns/field)`
   Only fall back to `(fn [db] ...)` when the logic cannot be expressed
   with `:<-` / `:->`.

3. No forward `declare` in the generated `app.framework`. Order defs so each
   is defined before it is referenced. A blanket `#"\(declare "` regex
   assertion in `cljs_project_test` enforces this across the emitted
   framework. The `app.renderer` (adapted from bare-demo) is exempt: its
   mutual recursion between `create-element`/`create-node` and
   `patch-node!`/`patch-children!` is genuine, and one side of each pair
   needs a `declare`.

4. The root `app.db` is a FLAT merge of each group's `default-db`. Keys are
   fully namespaced by their group's db namespace (e.g.
   `:app.cart.db/cart-count`), so collisions are prevented without nesting.
   Do not introduce tier-1 slice subs or an `app.core.subs` namespace.

   **Every named group auto-gets a locked `::id int? 0` field at the head
   of its `:fields`.** `doc.ops/set-name` inserts it when a name becomes
   non-blank and strips it when the name is cleared. The inspector
   renders the row as read-only and `remove-field` refuses to drop it.
   `db.cljs` therefore always emits `::id` first in the `s/keys :req`
   vector, and template groups' `::record` spec includes it.

5. Hiccup prop maps on custom elements follow a strict shape:
   - 0 props: omit the map entirely.
   - 1 prop: inline — `[:x-button {:variant "primary"} ...]`.
   - 2+ props: one prop per line, vertically aligned after the opening `{`.

6. `:require` vectors in generated files use dot-notation aliases that
   mirror the tail of the namespace (`[app.cart.db :as cart.db]`,
   `[app.cart.subs :as cart.subs]`). No single-segment aliases for
   multi-segment namespaces. Only emit requires the file actually uses:
   `[app.framework :as rf]` is dropped from a view's `:require` list when
   the view has no let-bindings, no triggers, and no collection sub-groups
   (i.e. nothing that reaches for `rf/query` or `rf/dispatch`). A view with
   nothing to require emits bare `(ns app.<group>.views)` with no
   `:require` clause at all.

7. Generated views are hiccup vectors consumed by the hand-written
   reconciler in `app.renderer`. Never emit raw `js/document.createElement`
   interop or any JSX-style DSL.

8. Files with nothing to emit are omitted, not written as empty
   placeholders. A group with no readable fields gets no `subs.cljs`; a
   group with no events gets no `events.cljs`.

9. Event handling splits cleanly into three concepts. Keep them
   distinct:
   - **Field** (`:fields` on a group node): a reactive state slot.
   - **Action** (`:actions` on a group node): a named event handler
     that mutates a field in the SAME group. `{:name :operation
     :target-field}`. Operations: `:set :toggle :increment :decrement
     :clear :add :remove` (`:add` = `conj`, `:remove` = `filterv`
     not-equal; both work on vector fields).
   - **Trigger** (`:events` on an interactive descendant): fires an
     action on a DOM event. `{:trigger :action-ref}`, with `:payload`
     optional. `:action-ref` is a fully qualified keyword like
     `:app.cart.events/add-to-cart`. When `:payload` is absent (the
     v1 default), the generator walks up from the trigger node to the
     nearest enclosing template-instance record and dispatches that
     record as the single positional arg. Triggers outside any template
     dispatch with no args. An explicit `:payload` vector is still
     honoured for legacy docs and special cases — see rule 17.

10. Every declared field gets an auto-generated setter event
    `::<field>-changed` in its group's `events.cljs`. Both auto
    setters and declared actions use `trim-v` + `path`:
    ```clojure
    (rf/reg-event
     ::cart-count-changed
     [rf/trim-v
      (rf/path ::cart.db/cart-count)]
     (fn [_ [new-cart-count]]
       new-cart-count))

    (rf/reg-event
     ::add-to-cart
     [rf/trim-v
      (rf/path ::cart.db/cart-items)]
     (fn [v [x]]
       (conj v x)))
    ```
    The framework MUST:
    - expose `trim-v` as a public var and `path` as a public fn
      (variadic on key path segments),
    - support the 3-arity `(reg-event id interceptors handler)` form,
    - implement interceptors with `:before` / `:after` phases running
      over a context `{:db :event}`, with `:after` in reverse order
      (classic interceptor-chain semantics — `path`'s `:after` writes
      the handler's return value back into the wider db).
    If a **declared action** uses the same name as an auto setter,
    the declared action wins (auto setter skipped).

    **Payload re-keying for of-group targets.** When a declared
    action's target field is `:of-group G`, the generated handler
    wraps its incoming payload with
    `(rf/qualify-map x "<app-ns>.<G>.db")` so the record's keys match
    the target group's spec. Affects every op that consumes the
    dispatched payload — `:set`, `:add`, `:remove`. `qualify-map` is
    idempotent: payloads already in the right shape pass through
    unchanged. Example: `cart.add-to-cart` targeting
    `cart-items :of-group "cart-item"` dispatches a product record;
    the handler re-keys it to `::cart-item.db/*` before `conj`:

    ```clojure
    (rf/reg-event
     ::add-to-cart
     [rf/trim-v
      (rf/path ::cart.db/cart-items)]
     (fn [v [x]]
       (conj v (rf/qualify-map x "app.cart-item.db"))))
    ```

    Scalar-target actions (`:toggle`, `:increment`, `:decrement`,
    `:clear`, or `:set`/`:add`/`:remove` on a non-`:of-group` field)
    are unchanged — no qualification needed.

11. Triggers dispatch cross-group. The generated view:
    - adds the action-ref's `:app.<owner>.events` namespace to its
      `:require` list,
    - for the **implicit-payload** default (no `:payload` on the
      trigger): dispatches the enclosing template-group record as the
      single arg. Template group view fns destructure their record
      arg as `{:keys [...] :as record}` so `record` is always in scope.
    - for the **explicit-payload** legacy path: binds every payload
      field via `(rf/query [::<owner-subs>/<f>])` in the enclosing
      `let` (routing each field through the subs namespace of its
      declaring group via the field-owner index), and emits
      `#(rf/dispatch [::<owner>.events/<action> <arg>…])` where args
      are the let-bound symbols.

12. **Computed fields** (`:computed {:operation :source-field}` on a
    `::field-def`) are derived, not stored. They have NO entry in
    `default-db` and NO auto `<field>-changed` setter. Their
    subscription is emitted as a derived re-frame sub:
    ```clojure
    (rf/reg-sub ::cart-count
      :<- [::cart-items]
      :-> count)
    ```

    **v1 operation set** — `:count-of`, `:sum-of`, `:empty-of`,
    `:negation`, `:join-on`, `:any-of`, `:filter-by`. `:first-of` /
    `:last-of` / `:lookup-in` are **not** v1 ops — any of them in an
    older doc is a migration error.

    Simple-op extractors:
    `:count-of` → `count`, `:empty-of` → `empty?`,
    `:negation` → `not`. `:sum-of` over a collection of numbers →
    `#(reduce + 0 %)`. `:sum-of` with a `:project-field` (the source
    holds records) → `#(transduce (map ::<of-group>.db/<field>) + 0 %)`.

    Simple ops (`:count-of`, `:sum-of`, `:empty-of`, `:negation`)
    reference only fields on their **own group**; cross-group reach
    is `:join-on`'s job. A computed field may reference another
    computed field on the same group (chained computeds — e.g.
    `has-items = (negation is-empty)`).

    `:any-of` is a multi-signal boolean OR over `:source-fields`;
    see rule 15.

    `:filter-by` is a multi-signal derived collection: combines the
    filtered `:source-field` (a local `:of-group` collection) with a
    scalar `:filter-spec :search-field` on the same group and
    returns items whose `:match-field` on the template record
    contains the search term. v1 supports one `:match-kind`
    (`:contains-ci`, case-insensitive substring); blank term is a
    pass-through. The generator emits a multi-signal `reg-sub` that
    reads via `::<template>.db/<match-field>`, so the filter-by
    field's `:of-group` must match the source collection's
    `:of-group`. Like other computed fields, `:filter-by` has no
    `default-db` entry and no auto setter.

    Because `:->` is applied as an arbitrary function, the generated
    framework's 3-arity and 5-arity `reg-sub` arms MUST call
    `(extract-fn source)`, not `(get source extract-fn)`. Keyword
    extractors still work (keywords are functions).

    Writes to a computed field are rejected at the inspector UI
    level: action target-field pickers exclude computed fields; the
    bindings UI disallows `:write` / `:read-write` directions on
    them.

**x-grid `columns`** is a **CSS track list**, not a column count. Pass
it as a real `grid-template-columns` value — `"1fr auto auto"`,
`"repeat(3, 1fr)"`, etc. — not as the number `"3"`. BareDOM passes
the attribute through verbatim; a bare integer produces an invalid
template and each child ends up on its own row.

As a safety net, the generator coerces any `x-grid` `columns` value that
is a bare integer string (e.g. `"3"`) to `"repeat(N, 1fr)"` at emission
time. Documents should still carry valid track lists — the coercion
exists for legacy docs and errant inspector input, not as a shortcut.

**Wrappers are transparent.** Only nodes that carry a non-empty `:name`
become groups. Unnamed containers (`x-container`, `x-card`,
`x-gaussian-blur`, `x-grid`, …) are decorative / layout wrappers. They
render as inline hiccup inside their enclosing group's view, or
directly inside `app.core/app` if they sit at root. The generator
walks recursively through such wrappers; wherever it meets a named
descendant, it emits `(<group>.views/<group>)` at that position and
stops descending — the named group owns its own subtree. Being a
direct child of root does **not** implicitly make a node a group.

13. **Template groups and collection fields.** Vectors of records are
    modelled by two explicit declarations:

    - A **template group** has `:fields` describing a record shape (plus
      a view template). It owns no state of its own. A group becomes a
      template group iff some other group's collection field points at
      it via `:of-group "<this-group>"`. There is no explicit `:kind`
      flag; the template/stateful distinction is determined by
      `:of-group` references alone.
    - A **collection field** is a `:vector`-typed field-def carrying
      `:of-group "<template-group>"`. It lives on a stateful group and
      owns the vector of records. Seed records come from the field's
      `:default` — there is NO pluralisation of group names and NO
      harvesting of seed data from duplicate canvas nodes.

    **At design time, the canvas iterates seeds** — a template-instance
    node with a `:source-field` pointing at a seed-backed collection
    renders one DOM clone per seed, with `:text-field`-bound descendants
    pre-substituted from each seed record. `:source-sub` instances stay
    as single placeholders (subs are runtime-only). See
    `src/bareforge/render/canvas.cljs` / `expand-templates`.

    A **template instance** (a canvas node that belongs to a template
    group) names its data source with one of:
    - `:source-field :<field-name>` — resolved via the field-owner
      index to a sub on the owning stateful group's subs ns.
    - `:source-sub :<ns>/<name>` — a qualified sub keyword, typically
      a computed join (`:app.cart.subs/cart-with-products`).

    Export output:
    - Template group `db.cljs`: specs only — per-field `s/def` plus a
      `::record` key spec. No `default-db`; not merged into the root.
    - Template group `subs.cljs` and `events.cljs`: NOT emitted.
    - Template group `views.cljs`: view fn takes a record arg,
      destructured with fully-qualified `:keys` (rule 14). Nodes with
      `:text-field :foo` emit the plain `foo` symbol. Trigger payload
      entries whose owner matches the template group read directly off
      the destructured record via the same symbol.
    - Stateful group with a collection field `:F :of-group "G"`:
      `db.cljs` emits `(s/def ::F (s/coll-of ::G.db/record))`, seeds
      `{::F <:default>}` in `default-db`, and requires the template
      group's `db.cljs` (for the `::record` spec). `subs.cljs` emits
      `(rf/reg-sub ::F :-> ::<owner.db>/F)`. `events.cljs` emits an
      auto setter `::F-changed` (vector replace) alongside any
      declared `:add` / `:remove` actions.
    - Parent of the template instance iterates with
      `(for [p (rf/query [<sub-ref>])] (<ns>.views/<ns> p))` — where
      `<sub-ref>` comes from `:source-sub` or is built from
      `:source-field` + the field-owner index.

14. **Destructure collection records with fully-qualified `:keys`
    and keep the whole map via `:as record`.** The generated view
    MUST destructure the record arg as
    `{:keys [::<db-alias>/<field> ::<db-alias>/<field> …] :as record}`,
    NOT the `{::<db-alias>/keys [field …]}` shorthand:

    ```clojure
    ;; GOOD — explicit keys, plus :as record for implicit payload
    (defn cart-item
      [{:keys [::cart-item.db/id ::cart-item.db/title ::cart-item.db/price]
        :as record}]
      …)

    ;; BAD — shorthand form
    (defn cart-item [{::cart-item.db/keys [id title price]}]
      …)
    ```
    Rationale: records carry fully namespaced keys (per rule 4), so
    the destructure should mirror that exactly rather than rely on the
    `::alias/keys` expansion sugar. `:as record` is required so
    triggers with implicit payload can dispatch the whole map (see
    rule 11).

15. **Multi-signal subs** are supported by the generated `app.framework`.
    `reg-sub` is variadic; any number of `:<-` inputs may precede
    either a `:-> extract-fn` or a plain handler fn. Single-input
    `:->` unwraps the value for backward compatibility; multi-input
    paths pass a vector:
    ```clojure
    (rf/reg-sub ::joined
      :<- [::a]
      :<- [::b]
      (fn [[a b] _] (merge a b)))
    ```

16. **`:join-on` computed fields** emit a multi-signal sub that joins
    a local vector-of-ids (`:source-field`) against a template
    group's records (`:join-target {:group-name :match-field
    :of-group}`). The records live on the stateful group that owns a
    collection field pointing at the template via `:of-group` — the
    generator resolves that automatically. When `:of-group` is set on
    the join-target, matching records are passed through
    `rf/qualify-map` to re-key them into the target group's db
    namespace. Required aliases: the owning group's `<owner>.subs`
    (for the `:<-` input) and the template's `<target>.db` (for the
    `::<target>.db/<match-field>` keyword used inside the handler).

17. **Explicit payload variants (legacy / advanced).** The v1 inspector
    does not offer payload customisation — triggers it creates have no
    `:payload` key and rely on the implicit record dispatch (rule 9).
    The generator still honours an explicit `:payload` vector in the
    doc for docs authored before v1 or hand-edited via JSON. Entries
    come in three shapes:
    - `{:field :owner}` — field reference (let-bound or destructured).
    - `{:literal <value>}` — an EDN literal dispatched verbatim
      (e.g. `true`/`false` for hover/open flags).
    - `{:event-detail :key}` — reads `(.. e -detail -key)` from the
      DOM event. When any entry is `:event-detail`, the generated
      handler wraps as `(fn [^js e] (rf/dispatch …))` instead of
      `#(rf/dispatch …)`.

    A trigger also accepts a `:prevent-default? true` sibling flag.
    When set, the handler is wrapped as
    `(fn [^js e] (.preventDefault e) (rf/dispatch …))` so the
    component's built-in behaviour for the event is skipped — useful
    when db-driven state would otherwise race with the component's
    internal state mutation (e.g. `x-popover-toggle` where the
    component would toggle its own `open` attribute after our
    dispatch, racing our render pass).

18. When a bug is found in the generated output, add a failing test in
    `test/bareforge/export/` against a known-good fixture before fixing
    the generator.

19. **Every template-group iteration emits a `display: contents`
    wrapper.** The generator MUST emit:
    ```clojure
    [:div {:style "display: contents"}
     (for [p (rf/query [::owner.subs/<field>])]
       (<tpl>.views/<tpl> p))]
    ```
    …never a bare `(for ...)` form at the hiccup children position.
    Rationale: the emitted `app.renderer` diffs children positionally.
    Without a wrapper, a shrinking list's tail shifts into trigger-
    slotted siblings (x-icon, x-badge, etc.) and replace-branch fallout
    strands stale DOM — especially visible with
    `<x-popover portal="true">` and friends, which physically relocate
    their default-slot children on open and never re-snapshot. The
    wrapper gives the iteration its own stable position in the parent's
    children array and its own isolated child-space in which every
    element has the same tag (the template group's root), so the
    positional diff stays sound. `display: contents` keeps the wrapper
    visually transparent: grid / flex / popover default-slot all lay
    out as if the wrapper weren't there. A node test in
    `cljs_project_test` pins the emitted shape.

    The partner half of this contract lives in the reconciler. See
    `src/bareforge/export/renderer.cljs` for why it tracks children in
    a `__bd_children` array on each parent (instead of reading live
    `.childNodes`) and anchors `replaceChild` / `removeChild` on
    `node.parentNode`: components that relocate their own children
    (popover portals, future teleports) reconcile correctly because
    our array follows the nodes wherever they currently live. Do not
    simplify back to `.childNodes`-based diffing — Bug 3 in the audit
    branch is the cautionary tale.

---

## Export plugins

Bareforge's export system is plugin-shaped. `bareforge.export.model`
owns the target-agnostic semantic lowering of a document;
`bareforge.export.plugin` and `bareforge.export.registry` own the
plugin contract; each plugin under `src/bareforge/export/<name>/`
consumes the model and emits a target artefact. Rules:

1. **Every exporter is a plugin.** Built-in (`html`, `bundle`,
   `cljs-project`, `vanilla-js`) and third-party plugins share the
   same contract. Adding a new plugin is a single subdir plus one
   append to `registry/plugins`. Do not hardwire exports in
   `toolbar.cljs` — the File menu is registry-driven.

2. **Semantic interpretation lives in the model, not the plugin.**
   Anything a plugin needs to know about a document beyond raw
   tree-walking (which fields are computed, what a `:filter-by`
   targets, which group owns a collection, how a trigger's
   implicit payload resolves) comes from
   `bareforge.export.model`'s public helpers. Plugins don't
   re-derive semantics; they consume them. If a new helper is
   needed, it goes in the model with matching tests — never
   inside a plugin.

3. **Plugin `generate` is pure.** `(generate doc opts) →
   {file-path → string}` must be a pure function. The
   `download!` entry point is the only effectful surface and
   should be a thin wrapper that calls `generate`, packages the
   result (usually via JSZip), and triggers the browser
   download.

4. **Manifest shape is enforced.** Every plugin manifest must
   carry `:id :label :extension :interactive? :description :order
   :download!`. `registry/validated-plugins` silently drops
   manifests missing any required key; `registry_test.cljs`
   asserts completeness for every built-in plugin. Add your new
   plugin to the registry test.

5. **Runtime shims live inside the plugin.** If your target
   needs its own reactive store / reconciler / event bus (the
   CLJS plugin's `framework.cljs` + `renderer.cljs`, the
   vanilla-JS plugin's `runtime.js` + `renderer.js`), those files
   live in the plugin's subdir and get slurp-inlined into the
   emitted output via `shadow.resource/inline`. One source of
   truth per plugin; no string-literal copies inside codegen.

6. **NYI is explicit.** If a plugin only supports a subset of
   features, throw `(ex-info "…" {:error :nyi})` at generate
   time with a human-readable message naming the specific
   feature. The download layer surfaces the message so the user
   sees the gap instead of broken output. See
   `bareforge.export.vanilla-js.codegen/assert-supported!` for
   the pattern.

7. **Model stability pledge — pre-1.0 unstable; post-1.0
   additive-only.** Until Bareforge hits 1.0, the model shape
   and plugin contract may change in any minor version. Plugin
   authors pin to a specific commit / tag. Post-1.0, `:id` /
   `:label` / `:extension` / `:interactive?` / `:description` /
   `:order` / `:download!` are guaranteed to stay; helpers may
   be added to the model but never removed without a
   one-minor-version deprecation warning.

`docs/plugins.md` has the contributor-facing authoring guide
(contract details, a hello-world markdown plugin walk-through,
testing recipe, FAQ).

---

## When in doubt
Ask: *can this be expressed as a pure data transformation?* If yes, put it in
the pure zone and write a unit test. If no, isolate the effect in the smallest
possible wrapper and keep the logic it calls pure.
