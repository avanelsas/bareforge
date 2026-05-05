# Exported ClojureScript code style — spec

> Rules governing the ClojureScript source emitted by Bareforge's
> export pipeline (`src/bareforge/export/*`). These rules are
> distinct from Bareforge's own source-code style (see CLAUDE.md
> `Code style` for that). The conceptual model — Field / Action /
> Trigger, the canonical-source-and-inline contract, and the bug-
> first test rule — lives in CLAUDE.md `Exported ClojureScript code
> style`. **This file is the codegen spec proper**, referenced from
> in-source comments by rule number.

## Rules

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

### x-grid `columns` / wrappers transparent (interlude)

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
