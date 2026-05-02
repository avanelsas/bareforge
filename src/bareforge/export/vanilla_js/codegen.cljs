(ns bareforge.export.vanilla-js.codegen
  "Per-file JS code generation for the vanilla-JS plugin.

   v0.2 fully covers the demo-store feature surface: template
   groups, collection fields (`:of-group`), vector actions
   (`:add`/`:remove`) with `qualifyMap` re-keying, implicit
   trigger payloads from within template views, template iteration
   (wrapped in a `display: contents` div per CLAUDE.md rule 19),
   `:text-field` substitution, and the seven computed-field
   operations (`:count-of`, `:sum-of`, `:empty-of`, `:negation`,
   `:any-of`, `:filter-by`, `:join-on`).

   `assert-supported!` throws `ex-info {:error :nyi}` only when an
   unknown computed operation is encountered (a pre-v1 op like
   `:first-of` or a typo in a hand-edited doc). Known ops all have
   emitters below.

   NOT meant as a general-purpose JS-codegen library — this is
   Bareforge-specific glue between `bareforge.export.model` and
   the vanilla-JS runtime/renderer."
  (:require [bareforge.doc.actions :as actions]
            [bareforge.doc.model :as m]
            [bareforge.doc.sanitize :as sanitize]
            [bareforge.export.html-to-hiccup :as h2h]
            [bareforge.export.model :as em]
            [bareforge.render.reconcile :as rec]
            [clojure.string :as str]))

;; --- feature gate --------------------------------------------------------

(def ^:private known-computed-ops
  #{:count-of :sum-of :empty-of :negation :any-of :filter-by :join-on})

(defn assert-supported!
  "Throw `ex-info {:error :nyi}` for any feature this plugin doesn't
   handle yet — surfaces a clear error in the File menu instead of
   silently emitting broken or missing output. Today the only gate
   is unknown computed operations (a pre-v1 op like `:first-of`
   or a typo in a hand-edited doc); the seven v1 ops all have
   emitters below. `:inner-html` is supported via `parse-html`
   at codegen — see `node->js-hiccup`.

   Consumes lowered groups so it walks `(:data g)` instead of
   re-deriving via `collect-group-data` per group."
  [groups]
  (doseq [g groups]
    (doseq [fd (:fields (:data g))
            :when (em/computed? fd)
            :let  [op (get-in fd [:computed :operation])]]
      (when-not (contains? known-computed-ops op)
        (throw (ex-info (str "Vanilla-JS plugin doesn't recognise "
                             "computed op " op " on `" (:name fd)
                             "` of group `" (:ns-name g) "`. Known "
                             "ops: " (pr-str known-computed-ops))
                        {:error :nyi :op op :field (:name fd)}))))))

;; --- helpers -------------------------------------------------------------

(defn- js-ident
  "Convert a Bareforge ns-name (kebab-case) into a valid JS
   identifier by replacing hyphens with underscores. Apply to
   every site where ns-name is used as a bare JS identifier
   (import binding name, function name, variable reference) —
   string contexts (object keys, DB keys, path segments) keep
   the kebab form. Nil-tolerant: returns nil so callers can
   short-circuit on stale sub-group lookups."
  [ns-name]
  (when ns-name (str/replace ns-name "-" "_")))

(defn- esc
  "Escape a string for inclusion inside a double-quoted JS literal —
   backslash and double-quote only."
  [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")))

(defn- field-key
  "Fully-qualified DB key used to address a field on the root db —
   `<app-ns>.<group-ns>.db/<field>`."
  [app-ns group-ns-name field-name]
  (str app-ns "." group-ns-name ".db/" (cljs.core/name field-name)))

(defn- record-key
  "Fully-qualified record-key under a TEMPLATE group's db namespace
   — `<app-ns>.<template-ns>.db/<field>`. Distinct name to emphasise
   that collection records use the template's ns, not the owning
   stateful group's."
  [app-ns template-ns-name field-name]
  (str app-ns "." template-ns-name ".db/" (cljs.core/name field-name)))

(defn- seed-record-literal
  "JS object literal for one seed record, with keys re-qualified
   under `<app-ns>.<og-ns>.db/*`. Seeds come in via the field's
   `:default` as CLJS maps with unqualified keys (`:id`/`:title`/…);
   the emitter lifts them to the template's ns."
  [app-ns og-ns record]
  (str "{"
       (str/join ", "
                 (for [[k v] record]
                   (str "\"" (record-key app-ns og-ns (cljs.core/name k)) "\": "
                        (cond
                          (string? v)  (str "\"" (esc v) "\"")
                          (number? v)  (str v)
                          (boolean? v) (str v)
                          (nil? v)     "null"
                          :else        (str "\"" (esc (str v)) "\"")))))
       "}"))

(defn- field-default-literal
  "JS literal for a field-def's default value. Scalars emit their
   native JS equivalent; collection fields emit a JS array of
   seeded records."
  [app-ns fd]
  (let [v  (:default fd)
        og (:of-group fd)]
    (cond
      (em/collection-field? fd)
      (str "["
           (str/join ", "
                     (for [r (or v [])] (seed-record-literal app-ns og r)))
           "]")

      (nil? v)
      (case (:type fd)
        :number  "0"
        :boolean "false"
        :keyword "null"
        :vector  "[]"
        "\"\"")

      (string? v)                (str "\"" (esc v) "\"")
      (number? v)                (str v)
      (boolean? v)               (str v)
      (and (vector? v) (empty? v)) "[]"
      :else                      (pr-str v))))

;; --- db (per stateful group) --------------------------------------------

(defn emit-group-db
  "JS module exporting this group's default-db slice. Template
   groups (no stored state) return nil — the app.js merger skips
   them. Stateful groups contribute one entry per stored field,
   scalar or collection."
  [_doc group _lowered]
  (when-not (:template? group)
    (let [app-ns "app"
          fields (remove em/computed? (:fields (:data group)))]
      (when (seq fields)
        (str "// Auto-generated: db slice for group \"" (:ns-name group) "\".\n"
             "export const defaultDb = {\n"
             (str/join ",\n"
                       (for [fd fields]
                         (str "  \"" (field-key app-ns (:ns-name group) (:name fd)) "\": "
                              (field-default-literal app-ns fd))))
             "\n};\n")))))

;; --- subs (per stateful group) ------------------------------------------

(defn- sub-id
  "Fully-qualified sub identifier for a group's field."
  [app-ns group-ns-name field-name]
  (str app-ns "." group-ns-name ".subs/" (cljs.core/name field-name)))

(defn- emit-direct-sub
  "Stored field → direct sub that extracts its key from the root db."
  [app-ns group fd]
  (let [id  (sub-id app-ns (:ns-name group) (:name fd))
        key (field-key app-ns (:ns-name group) (:name fd))]
    (str "regSubDirect(\"" id "\", \"" key "\");")))

(defn- source-field-of-group
  "For a computed's :source-field, resolve the `:of-group` of the
   underlying COLLECTION field on the owning stateful group. Used
   by `:sum-of` with a `:project-field` and by `:filter-by` to
   qualify the project/match keys."
  [all-groups source-field]
  (some (fn [g]
          (some (fn [f]
                  (when (= source-field (:name f))
                    (:of-group f)))
                (:fields (:data g))))
        all-groups))

(defn- emit-computed-simple
  "Emit a derived sub that applies a single extractor to ONE source
   sub: `regSubDerived(id, [src-sub], extract-fn)`. Covers
   :count-of, :empty-of, :negation, and :sum-of (with or without
   :project-field)."
  [app-ns group fd _doc all-groups]
  (let [id       (sub-id app-ns (:ns-name group) (:name fd))
        src      (:source-field (:computed fd))
        src-sub  (sub-id app-ns (:ns-name group) src)
        op       (:operation (:computed fd))
        proj     (:project-field (:computed fd))
        extract
        (case op
          :count-of "(s) => (s || []).length"
          :empty-of "(s) => (s || []).length === 0"
          :negation "(s) => !s"
          :sum-of
          (if proj
            ;; Records carry fully-namespaced keys under the source
            ;; collection's :of-group; pluck the project field via
            ;; that full key. When :of-group is absent (degenerate
            ;; legacy path), fall back to a bare-key lookup.
            (let [og (source-field-of-group all-groups src)
                  k  (if og
                       (record-key app-ns og (cljs.core/name proj))
                       (cljs.core/name proj))]
              (str "(s) => (s || []).reduce((a, r) => a + (r[\""
                   k "\"] || 0), 0)"))
            "(s) => (s || []).reduce((a, b) => a + b, 0)"))]
    (str "regSubDerived(\"" id "\", [\"" src-sub "\"], " extract ");")))

(defn- emit-computed-any-of
  "Multi-signal OR across same-group source subs."
  [app-ns group fd]
  (let [id      (sub-id app-ns (:ns-name group) (:name fd))
        sources (:source-fields (:computed fd))
        src-ids (str/join ", "
                          (for [s sources]
                            (str "\"" (sub-id app-ns (:ns-name group) s) "\"")))]
    (str "regSubMulti(\"" id "\", [" src-ids
         "], (vs) => vs.some(Boolean));")))

(defn- emit-computed-filter-by
  "Multi-signal :filter-by. Combines the source collection with a
   scalar search-term field on the SAME group; returns records
   whose :match-field (read via `app.<template>.db/<match>`)
   case-insensitively contains the term. Blank term passes through."
  [app-ns group fd doc all-groups]
  (let [id          (sub-id app-ns (:ns-name group) (:name fd))
        {:keys [source-field filter-spec]} (:computed fd)
        {:keys [search-field match-field]} filter-spec
        src-sub     (sub-id app-ns (:ns-name group) source-field)
        search-sub  (sub-id app-ns (:ns-name group) search-field)
        tpl-ns      (em/filter-by-of-group fd doc all-groups)
        match-key   (record-key app-ns tpl-ns match-field)]
    (str "regSubMulti(\"" id "\", [\"" src-sub "\", \"" search-sub "\"], "
         "([items, term]) => {\n"
         "  if (!term) return items || [];\n"
         "  const needle = String(term).toLowerCase();\n"
         "  return (items || []).filter(r => "
         "String(r[\"" match-key "\"] || \"\").toLowerCase().includes(needle));\n"
         "});")))

(defn- emit-computed-join-on
  "Multi-signal :join-on. Reads a vector of ids from :source-field
   on THIS group plus the host collection of records (resolved via
   stateful-host-for-template), and returns records matched by
   :match-field. When :of-group is set on the join-target, matches
   are passed through qualifyMap."
  [app-ns group fd doc all-groups]
  (let [id       (sub-id app-ns (:ns-name group) (:name fd))
        {:keys [source-field join-target]} (:computed fd)
        {:keys [group-name match-field of-group]} join-target
        src-sub  (sub-id app-ns (:ns-name group) source-field)
        host     (em/stateful-host-for-template doc all-groups group-name)
        host-sub (sub-id app-ns (:ns-name host) (:field-name host))
        match-key (record-key app-ns group-name match-field)
        as-ns    (when of-group (str app-ns "." of-group ".db"))
        mapper   (if as-ns
                   (str "      .map(r => qualifyMap(r, \"" as-ns "\"))")
                   "")]
    (str "regSubMulti(\"" id "\", [\"" src-sub "\", \"" host-sub "\"], "
         "([ids, records]) => {\n"
         "  return (ids || [])\n"
         "    .map(id => (records || []).find(r => r[\"" match-key "\"] === id))\n"
         "    .filter(Boolean)"
         (when as-ns (str "\n" mapper))
         ";\n"
         "});")))

(defn- emit-computed-sub
  "Dispatch by :operation to the right emitter."
  [app-ns group fd doc all-groups]
  (let [op (:operation (:computed fd))]
    (case op
      (:count-of :empty-of :negation :sum-of)
      (emit-computed-simple app-ns group fd doc all-groups)

      :any-of    (emit-computed-any-of app-ns group fd)
      :filter-by (emit-computed-filter-by app-ns group fd doc all-groups)
      :join-on   (emit-computed-join-on app-ns group fd doc all-groups))))

(defn- subs-imports-needed
  "Which runtime fns the emitted subs module needs to import.
   Minimises import churn — don't pull qualifyMap in unless a
   :join-on with :of-group is present."
  [fields]
  (let [ops (map #(get-in % [:computed :operation]) (filter em/computed? fields))
        simple? (some #{:count-of :sum-of :empty-of :negation} ops)
        multi?  (some #{:any-of :filter-by :join-on} ops)
        direct? (some (complement em/computed?) fields)
        needs-qualify? (boolean
                        (some (fn [fd]
                                (and (= :join-on (get-in fd [:computed :operation]))
                                     (get-in fd [:computed :join-target :of-group])))
                              fields))]
    (cond-> []
      direct? (conj "regSubDirect")
      simple? (conj "regSubDerived")
      multi?  (conj "regSubMulti")
      needs-qualify? (conj "qualifyMap"))))

(defn emit-group-subs
  "Direct subs for stored fields + derived / multi-signal subs for
   each computed field. Template groups own no state — return nil."
  [doc group lowered]
  (when-not (:template? group)
    (let [app-ns     "app"
          all-groups (:groups lowered)
          fields     (:fields (:data group))]
      (when (seq fields)
        (let [imports (subs-imports-needed fields)]
          (str "// Auto-generated: subs for group \"" (:ns-name group) "\".\n"
               "import { " (str/join ", " imports)
               " } from \"../runtime.js\";\n\n"
               (str/join "\n"
                         (for [fd fields]
                           (if (em/computed? fd)
                             (emit-computed-sub app-ns group fd doc all-groups)
                             (emit-direct-sub app-ns group fd))))
               "\n"))))))

;; --- events (per stateful group) ----------------------------------------

(defn- field-of-group
  "The `:of-group` value on a stateful group's target-field, or nil
   if the target is scalar. Drives `:add` / `:remove` qualifyMap
   re-keying."
  [group target-field]
  (some (fn [fd]
          (when (= target-field (:name fd)) (:of-group fd)))
        (:fields (:data group))))

(defn- qualify-expr
  "JS expression that re-keys `x` under `<app-ns>.<og>.db` when
   `og` is non-nil; bare `x` otherwise."
  [app-ns og x]
  (if og
    (str "qualifyMap(" x ", \"" app-ns "." og ".db\")")
    x))

(defn- emit-auto-setter
  "Auto-generated `::<field>-changed` setter. Path-narrows to the
   field and writes the trim-v'd payload directly — works equally
   for scalar and collection fields (wholesale vector replacement)."
  [app-ns group fd]
  (let [fname (cljs.core/name (:name fd))
        ev-id (str app-ns "." (:ns-name group) ".events/" fname "-changed")
        key   (field-key app-ns (:ns-name group) (:name fd))]
    (str "regEvent(\"" ev-id "\", [trimV, path(\"" key "\")], "
         "(_, [v]) => v);")))

(defn- step-payload-js
  "Resolve a step's payload entry to a JS value expression. v1 honours
   `{:literal v}` only — falls back to `x`, the trimmed trigger arg,
   when no payload override is set. Literals are JSON-encoded so
   Bareforge's primitive payload kinds (booleans, numbers, strings,
   nil/null) all round-trip cleanly."
  [step]
  (let [pe (some-> step :payload first)]
    (cond
      (and pe (contains? pe :literal)) (js/JSON.stringify (clj->js (:literal pe)))
      :else "x")))

(defn- step-mutator-expr
  "JS expression that returns the new db given the prior `db`, with
   one step applied. Used inside the multi-step handler's `(db => …)`
   reducer."
  [app-ns group step prev-db]
  (let [{:keys [operation target-field]} step
        fk    (field-key app-ns (:ns-name group) target-field)
        og    (field-of-group group target-field)
        vexpr (step-payload-js step)
        q-v   (qualify-expr app-ns og vexpr)
        cur   (str prev-db "[\"" fk "\"]")]
    (case operation
      :set       (str "{ ..." prev-db ", \"" fk "\": " q-v " }")
      :toggle    (str "{ ..." prev-db ", \"" fk "\": !" cur " }")
      :increment (str "{ ..." prev-db ", \"" fk "\": (" cur " || 0) + 1 }")
      :decrement (str "{ ..." prev-db ", \"" fk "\": (" cur " || 0) - 1 }")
      :clear     (str "{ ..." prev-db ", \"" fk "\": null }")
      :add       (str "{ ..." prev-db ", \"" fk "\": [..." cur ", " q-v "] }")
      :remove    (str "{ ..." prev-db ", \"" fk "\": ("
                      cur ").filter(item => !deepEqual(item, " q-v ")) }")
      (throw (ex-info (str "unknown action op: " operation)
                      {:op operation})))))

(defn- emit-action
  "Declared action: set / toggle / increment / decrement / clear /
   add / remove. `:add` and `:remove` pass their payload through
   `qualifyMap` when the target field is `:of-group` — same
   re-keying contract the CLJS plugin honours so a payload
   dispatched from one record shape (e.g. a product record) lands
   under the target collection's template ns (e.g. cart-item.db/*).

   Two emission shapes:
   - **Single-step** (1 entry in `:steps`): legacy `regEvent(id,
     [trimV, path(field)], handler)` — value-narrowed handler. Pinned
     by existing tests; bytewise unchanged.
   - **Multi-step** (≥ 2 entries): `regEvent(id, [trimV],
     handler-over-full-db)`. The handler reduces over the steps,
     applying each step's transformation to the rolling db. Each step
     respects its own `:payload` (literal-only in v1) so a step like
     `:set is-popover-open false` lands the literal verbatim while a
     `:add` step still consumes the dispatched record."
  [app-ns group action]
  (let [{:keys [name]} action
        aname (cljs.core/name name)
        ev-id (str app-ns "." (:ns-name group) ".events/" aname)
        steps (actions/step-list action)]
    (if (= 1 (count steps))
      (let [{:keys [operation target-field]} (first steps)
            fk  (field-key app-ns (:ns-name group) target-field)
            og  (field-of-group group target-field)
            q-x (qualify-expr app-ns og "x")]
        (case operation
          :set
          (str "regEvent(\"" ev-id "\", [trimV, path(\"" fk "\")], "
               "(_, [v]) => " (qualify-expr app-ns og "v") ");")
          :toggle
          (str "regEvent(\"" ev-id "\", [trimV, path(\"" fk "\")], "
               "(v) => !v);")
          :increment
          (str "regEvent(\"" ev-id "\", [trimV, path(\"" fk "\")], "
               "(v) => (v || 0) + 1);")
          :decrement
          (str "regEvent(\"" ev-id "\", [trimV, path(\"" fk "\")], "
               "(v) => (v || 0) - 1);")
          :clear
          (str "regEvent(\"" ev-id "\", [trimV, path(\"" fk "\")], "
               "() => null);")
          :add
          (str "regEvent(\"" ev-id "\", [trimV, path(\"" fk "\")], "
               "(v, [x]) => [...v, " q-x "]);")
          :remove
          (str "regEvent(\"" ev-id "\", [trimV, path(\"" fk "\")], "
               "(v, [x]) => { const target = " q-x "; "
               "return v.filter(item => !deepEqual(item, target)); });")
          (throw (ex-info (str "unknown action op: " operation)
                          {:op operation}))))
      ;; Multi-step: thread db through each step's mutator.
      (let [body (reduce
                  (fn [acc step]
                    (str "(db => " (step-mutator-expr app-ns group step "db")
                         ")(" acc ")"))
                  "db0"
                  steps)]
        (str "regEvent(\"" ev-id "\", [trimV], "
             "(db0, [x]) => " body ");")))))

(defn- step-needs-qualify?
  "True when a single step's operation + target combination forces
   `qualifyMap` in the emitted JS."
  [group step]
  (and (contains? #{:add :remove :set} (:operation step))
       (field-of-group group (:target-field step))))

(defn- uses-qualify?
  "Does any declared action need `qualifyMap` — i.e. :add or :remove
   (or :set) on an :of-group target? Iterates step-by-step so
   multi-step actions surface as needing qualifyMap if any one of
   their steps does."
  [group actions]
  (boolean
   (some (fn [a]
           (some (partial step-needs-qualify? group)
                 (actions/step-list a)))
         actions)))

(defn- uses-deep-equal?
  "Does any declared action need the deep-equal helper — i.e. :remove
   anywhere in any step."
  [actions]
  (boolean
   (some (fn [a]
           (some #(= :remove (:operation %)) (actions/step-list a)))
         actions)))

(defn emit-group-events
  "JS module registering every event handler the group owns — auto
   setters for stored fields plus declared actions. Skipped for
   template groups (they own no state)."
  [_doc group _lowered]
  (when-not (:template? group)
    (let [app-ns         "app"
          data           (:data group)
          stored         (remove em/computed? (:fields data))
          declared       (:actions data)
          declared-names (set (map :name declared))
          setter-kw      (fn [fd] (keyword (str (cljs.core/name (:name fd)) "-changed")))
          auto-setters   (remove #(contains? declared-names (setter-kw %)) stored)
          extra-imports  (concat ["regEvent" "trimV" "path"]
                                 (when (uses-qualify? group declared)
                                   ["qualifyMap"])
                                 (when (uses-deep-equal? declared)
                                   ["deepEqual"]))]
      (when (or (seq declared) (seq auto-setters))
        (str "// Auto-generated: events for group \"" (:ns-name group) "\".\n"
             "import { " (str/join ", " extra-imports)
             " } from \"../runtime.js\";\n\n"
             (str/join "\n\n"
                       (concat
                        (for [fd auto-setters] (emit-auto-setter app-ns group fd))
                        (for [a declared] (emit-action app-ns group a))))
             "\n")))))

;; --- views ---------------------------------------------------------------

(declare node->js-hiccup)

(defn- js-prop
  "Serialize one prop key/value pair as a JS object literal entry."
  [k v]
  (cond
    (true? v)     (str "\"" k "\": true")
    (false? v)    (str "\"" k "\": false")
    (nil? v)      (str "\"" k "\": null")
    (number? v)   (str "\"" k "\": " v)
    :else         (str "\"" k "\": \"" (esc (str v)) "\"")))

(defn- binding-lookup-expr
  "For a read/read-write attribute binding, emit the JS expression
   that reads the current value. Inside a template view the binding
   can resolve to a destructured record field symbol — record-bound
   reads don't need a `query`."
  [{:keys [app-ns field->owner template-field-syms]} field]
  (cond
    (contains? template-field-syms field)
    (cljs.core/name field)

    :else
    (let [owner (field->owner field)
          fname (cljs.core/name field)]
      (str "query(\"" app-ns "." owner ".subs/" fname "\")"))))

(def ^:private write-event-names
  {"x-search-field"    "x-search-field-input"
   "x-text-field"      "x-text-field-input"
   "x-text-area"       "x-text-area-input"
   "x-number-field"    "x-number-field-input"
   "x-currency-field"  "x-currency-field-input"
   "x-color-picker"    "x-color-picker-input"
   "x-date-picker"     "x-date-picker-change"
   "x-combobox"        "x-combobox-change"
   "x-slider"          "x-slider-change"
   "x-select"          "x-select-change"
   "x-switch"          "x-switch-change"
   "x-checkbox"        "x-checkbox-change"})

(defn- write-event-name-for [tag] (get write-event-names tag))

(defn- trigger-handler-expr
  "Emit the JS dispatch expression for one trigger.

   With explicit `:payload` (legacy doc path): each entry resolves
   via `field->sym` (records already in scope via template
   destructure) or falls through to a `query` for let-bound stateful
   reads. For v0.2 we honour only `:field` entries — literal /
   event-detail are legacy edges; the inspector doesn't create them.

   Without `:payload`: inside a template view dispatch the record
   symbol as the single arg (implicit payload — CLAUDE.md rule 11).
   Outside any template, dispatch with no args."
  [trigger {:keys [template-record-sym template-field-syms]
            :as   ctx}]
  (let [aref    (:action-ref trigger)
        ;; Canonicalise the action-ref's group segment: an older doc
        ;; can carry `:app.Dashboard.events/tick` (raw user-typed
        ;; name) while the registry id was built from the lowercased
        ;; `:ns-name` — without this, `dispatch` fires on a key the
        ;; registry doesn't know.
        alias   (let [ns        (namespace aref)
                      first-dot (.indexOf ns ".")
                      last-dot  (.lastIndexOf ns ".")
                      app-pref  (subs ns 0 (inc first-dot))
                      grp       (subs ns (inc first-dot) last-dot)
                      suffix    (subs ns last-dot)]
                  (str app-pref (actions/name->ns-segment grp) suffix))
        ename   (cljs.core/name aref)
        payload (:payload trigger)
        args    (cond
                  (seq payload)
                  (for [pe payload
                        :when (:field pe)]
                    (let [f (:field pe)]
                      (if (contains? template-field-syms f)
                        (cljs.core/name f)
                        (str "query(\"" (:app-ns ctx) "."
                             ((:field->owner ctx) f) ".subs/"
                             (cljs.core/name f) "\")"))))

                  template-record-sym
                  [template-record-sym]

                  :else
                  nil)
        args-str (str/join ", " args)
        payload-tail (if (seq args) (str ", " args-str) "")]
    (str "(e) => dispatch([\"" alias "/" ename "\"" payload-tail "])")))

(defn- node-props-js
  "Props-object fragment for a node. Merges slot, static attrs,
   `:props` (component JS properties — booleans / numbers / enum
   strings the inspector commits via setv!), read bindings, auto
   write-event handlers, and `:events` triggers.

   `slot-name` is the name of the slot this node sits in on its
   PARENT — non-default slot names emit `slot=\"…\"` so BareDOM
   places the child in the right named slot (x-navbar's brand /
   actions / start / end / toggle, x-popover's trigger / footer,
   etc.).

   `:props` get the same inclusion gate as the CLJS plugin: skip
   any prop whose key is also in `:attrs` (the attrs entry wins —
   those are the inspector's primary widget) and skip nil values."
  [node slot-name {:keys [app-ns field->owner] :as ctx}]
  (let [slot-prop     (when (and slot-name (not= "default" slot-name))
                        (str "\"slot\": \"" slot-name "\""))
        attr-keys     (set (keys (:attrs node)))
        static-props  (for [[k v] (:attrs node)
                            :when (and k (some? v) (not (contains? (:bindings node) k)))]
                        (js-prop k v))
        prop-pairs    (for [[k v] (:props node)
                            :let  [kn (cljs.core/name k)]
                            :when (and (some? v)
                                       (not (contains? attr-keys kn)))]
                        (js-prop kn v))
        read-bindings (for [[k {:keys [field direction]}] (:bindings node)
                            :when (contains? #{:read :read-write} direction)]
                        (str "\"" k "\": "
                             (binding-lookup-expr ctx field)))
        write-events  (for [[prop-name {:keys [field direction owner]}] (:bindings node)
                            :when (contains? #{:write :read-write} direction)
                            :let  [ename (write-event-name-for (:tag node))]
                            :when ename
                            :let  [owner-ns (or owner (field->owner field))
                                   event-id (str app-ns "." owner-ns ".events/"
                                                 (cljs.core/name field) "-changed")]]
                        (str "\"on-" ename "\": (e) => dispatch([\""
                             event-id "\", e.detail && e.detail." prop-name "])"))
        triggers      (for [t (:events node)]
                        (str "\"on-" (:trigger t) "\": "
                             (trigger-handler-expr t ctx)))
        ;; Layout-derived inline CSS — placement block, x/y/w/h
        ;; (free), :width / :height / :padding / :margin, per-node
        ;; :css-vars, and `:extra-style` verbatim. Same helper the
        ;; CLJS plugin uses, ensuring the export matches what the
        ;; canvas paints in the editor.
        layout-style  (rec/layout->css (:layout node))
        style-prop    (when layout-style
                        (str "\"style\": \"" (esc layout-style) "\""))
        all-props     (vec (concat (when slot-prop [slot-prop])
                                   static-props prop-pairs
                                   read-bindings write-events triggers
                                   (when style-prop [style-prop])))]
    (when (seq all-props)
      (str "{" (str/join ", " all-props) "}"))))

(defn- template-iteration-expr
  "The JS fragment that renders a template-group instance. Wraps
   the for-loop in a `display: contents` div (rule 19) so a
   shrinking list doesn't shift tail items into trigger-slotted
   siblings.

   `slot-name` is the slot this iteration sits in on its parent —
   the `display: contents` wrapper picks it up so all the iterated
   records land in the right named slot (e.g. cart-items inside
   the popover's default slot, vs. avatars inside x-navbar's start
   slot, etc.).

   Source resolution mirrors the CLJS plugin's `node->hiccup-with-
   events` template branch:
     1. Explicit `:source-sub` on the template instance node.
     2. Explicit `:source-field` on the instance — resolved via
        the field-owner index to the owning group's sub.
     3. Implicit fallback: when neither is set, look up the
        STATEFUL HOST that owns the collection field pointing at
        this template via `:of-group` (`em/stateful-host-for-
        template`). The host's :ns-name + :field-name yield the
        sub-ref. This is the common case — the demo-store's
        cart-item template instance has no explicit source; its
        records flow from cart's `:cart-items` collection."
  [sub-group slot-name ctx]
  (let [app-ns (:app-ns ctx)
        src-sub-kw (:source-sub sub-group)
        src-field  (:source-field sub-group)
        host       (when-not (or src-sub-kw src-field)
                     (em/stateful-host-for-template
                      (:doc ctx)
                      (:all-groups ctx)
                      (:ns-name sub-group)))
        sub-ref (cond
                  src-sub-kw
                  (str app-ns "."
                       (namespace src-sub-kw) "/"
                       (cljs.core/name src-sub-kw))

                  src-field
                  (let [owner ((:field->owner ctx) src-field)]
                    (str app-ns "." owner ".subs/"
                         (cljs.core/name src-field)))

                  host
                  (str app-ns "." (:ns-name host)
                       ".subs/" (:field-name host))

                  :else
                  "__NO_SOURCE__")
        tpl-ns  (:ns-name sub-group)
        slot-prop (when (and slot-name (not= "default" slot-name))
                    (str ", \"slot\": \"" slot-name "\""))]
    (str "[\"div\", {\"style\": \"display: contents\""
         slot-prop "}, "
         "...(query(\"" sub-ref "\") || []).map(r => "
         "tpl_" (js-ident tpl-ns) "(r))]")))

(defn- text-for-node
  "Emit the text content fragment for a node.

   Three cases:
   - `:text-field` matches a destructured template-record key →
     emit the bare local symbol (record value).
   - `:text-field` is a doc-level field on some group → emit a
     `query(\"app.<owner>.subs/<field>\")` so the text reflects the
     current sub value (e.g. `:text-field :product-feed-item-count`
     with `:text-field-owner \"product-feed\"`).
   - Otherwise: literal `:text` content, quoted."
  [node {:keys [app-ns template-field-syms field->owner]}]
  (let [tf (:text-field node)
        owner (:text-field-owner node)]
    (cond
      (and tf (contains? template-field-syms tf))
      (cljs.core/name tf)

      tf
      (let [resolved-owner (or owner (field->owner tf))]
        (str "query(\"" app-ns "." resolved-owner ".subs/"
             (cljs.core/name tf) "\")"))

      (and (:text node) (not= "" (:text node)))
      (str "\"" (esc (:text node)) "\""))))

(defn- named-group-call-expr
  "Emit the JS expression that renders a stateful named group at
   `slot-name`. Stateful groups expose `view()` (no args); when
   the group sits in a non-default slot, wrap the result in a
   slot-tagging div via `display: contents` (the cleanest way to
   forward the slot attr without changing the view function's
   shape)."
  [sg slot-name]
  (let [call (str "(" (js-ident (:ns-name sg)) "View())")]
    (if (and slot-name (not= "default" slot-name))
      (str "[\"div\", {\"style\": \"display: contents\", \"slot\": \""
           slot-name "\"}, " call "]")
      call)))

(defn- attrs-js
  "Emit a parsed-HTML attrs map (string keys, string-or-true values)
   as a JS object literal. Empty / nil → nil so the caller can omit
   the props slot in the hiccup array."
  [attrs]
  (when (seq attrs)
    (str "{"
         (str/join ", "
                   (for [[k v] (sort-by key attrs)]
                     (str "\"" (esc k) "\": "
                          (cond
                            (true? v)   "\"\""    ; presence-based attr
                            :else       (str "\"" (esc (str v)) "\"")))))
         "}")))

(defn- inner-html-tree->js
  "Walk an `h2h/parse-html` tree and emit JS array literals matching
   the renderer's hiccup shape. Strings → quoted JS strings; element
   maps → `[\"tag\", {attrs}, ...children]` with the props slot
   omitted when there are no attrs."
  [tree]
  (cond
    (string? tree)
    (str "\"" (esc tree) "\"")

    (map? tree)
    (let [{:keys [tag attrs children]} tree
          tag-js   (str "\"" (esc tag) "\"")
          attrs-js (attrs-js attrs)
          kid-js   (map inner-html-tree->js children)
          parts    (cond-> [tag-js]
                     attrs-js  (conj attrs-js)
                     (seq kid-js) (into kid-js))]
      (str "[" (str/join ", " parts) "]"))))

(defn- inner-html-children-js
  "Emit the parsed children of a node's `:inner-html` as a sequence
   of JS hiccup literals. Defence-in-depth re-sanitises before
   parsing — by codegen time the value is already filtered at load
   and at `ops/set-inner-html` commit, but a stale autosave from a
   pre-sanitiser session could still slip through."
  [raw-html]
  (->> raw-html
       sanitize/sanitize-svg-fragment
       h2h/parse-html
       (map inner-html-tree->js)))

(defn- node->js-hiccup
  "Emit the JS array literal for one node.

   `slot-name` is the slot this node sits in on its parent — emitted
   as a `slot=\"…\"` prop when non-default. The TOP-LEVEL call (root
   walk, template-view body) passes nil since the node has no parent
   slot in scope.

   Stops and emits a template-iteration when the child is an
   instance of a sub-group that's a template; otherwise recurses.

   `:inner-html` nodes (`:raw-html-slot? true` components like
   x-icon) are handled by parsing the inner-html string into a
   hiccup tree at codegen time and emitting the result inline as
   the node's children. Slot-children and `:text` are skipped for
   these — `:inner-html` owns the children slot exclusively, the
   same invariant the editor canvas enforces."
  [node slot-name {:keys [sub-groups-by-id template-groups] :as ctx}]
  (let [tag      (str "\"" (:tag node) "\"")
        props    (node-props-js node slot-name ctx)
        raw      (:inner-html node)
        raw?     (and (string? raw) (not= "" raw))
        text     (when-not raw? (text-for-node node ctx))
        kids     (cond
                   raw?
                   (inner-html-children-js raw)

                   :else
                   (for [[child-slot cs] (m/slot-entries node)
                         c cs]
                     (if-let [sg (get sub-groups-by-id (:id c))]
                       (let [tpl? (contains? template-groups (:ns-name sg))]
                         (if tpl?
                           (let [node-info (m/get-node (:doc ctx) (:id c))
                                 sg+src    (assoc sg
                                                  :source-field (:source-field node-info)
                                                  :source-sub   (:source-sub node-info))]
                             (template-iteration-expr sg+src child-slot ctx))
                           (named-group-call-expr sg child-slot)))
                       (node->js-hiccup c child-slot ctx))))
        parts    (cond-> [tag]
                   props (conj props)
                   text  (conj text)
                   (seq kids) (into kids))]
    (str "[" (str/join ", " parts) "]")))

(defn- template-view
  "Emit a view function for a template group — takes a `record`
   arg, destructures its namespaced keys into local symbols, and
   returns the hiccup tree with those symbols substituting for
   `:text-field`-bound nodes and bindings that read the record."
  [doc group lowered]
  (let [app-ns          "app"
        all-groups      (:groups lowered)
        template-groups (:template-names lowered)
        owner-idx       (:field-owner-ns lowered)
        node            (m/get-node doc (:id group))
        fields          (remove em/computed? (:fields (:data group)))
        field-syms      (set (map :name fields))
        destructure
        (str "const { "
             (str/join ", "
                       (for [fd fields
                             :let [fname (cljs.core/name (:name fd))]]
                         (str "\"" (record-key app-ns (:ns-name group) (:name fd))
                              "\": " fname)))
             " } = record;")
        sub-groups       (em/find-sub-groups node all-groups)
        sub-groups-by-id (into {} (map (juxt :id identity)) sub-groups)
        field->owner     (fn [field]
                           (or (get owner-idx field) (:ns-name group)))
        ctx {:app-ns              app-ns
             :doc                 doc
             :all-groups          all-groups
             :field->owner        field->owner
             :sub-groups-by-id    sub-groups-by-id
             :template-groups     template-groups
             :template-field-syms field-syms
             :template-record-sym "record"}]
    (str "export function view(record) {\n"
         "  " destructure "\n"
         "  return " (node->js-hiccup node nil ctx) ";\n"
         "}\n")))

(defn- stateful-view
  "Emit the no-arg view function for a stateful group."
  [doc group lowered]
  (let [app-ns           "app"
        all-groups       (:groups lowered)
        template-groups  (:template-names lowered)
        owner-idx        (:field-owner-ns lowered)
        node             (m/get-node doc (:id group))
        sub-groups       (em/find-sub-groups node all-groups)
        sub-groups-by-id (into {} (map (juxt :id identity)) sub-groups)
        field->owner     (fn [field]
                           (or (get owner-idx field) (:ns-name group)))
        ctx {:app-ns              app-ns
             :doc                 doc
             :all-groups          all-groups
             :field->owner        field->owner
             :sub-groups-by-id    sub-groups-by-id
             :template-groups     template-groups
             :template-field-syms #{}
             :template-record-sym nil}]
    (str "export function view() {\n"
         "  return " (node->js-hiccup node nil ctx) ";\n"
         "}\n")))

(defn- template-sub-imports
  "Import each template-group's view as `tpl_<ns>` so
   `template-iteration-expr` can call it. Runs for both stateful
   AND template-group views (a template can itself host another
   template, though v0.2 tests don't exercise that)."
  [doc group lowered]
  (let [all-groups      (:groups lowered)
        template-groups (:template-names lowered)
        node            (m/get-node doc (:id group))
        sub-groups      (em/find-sub-groups node all-groups)
        templates       (filter #(contains? template-groups (:ns-name %))
                                sub-groups)]
    (str/join "\n"
              (for [sg (->> templates (map :ns-name) (filter some?) distinct)]
        ;; tpl_<ident> is a JS binding name (must be a valid JS
        ;; identifier — no hyphens). The path segment keeps the
        ;; original kebab-case ns-name since file paths handle
        ;; hyphens fine.
                (str "import { view as tpl_" (js-ident sg)
                     " } from \"../" sg "/views.js\";")))))

(defn emit-group-views
  "Pick the right view shape based on whether the group is a
   template (takes a `record` arg) or stateful (no args)."
  [doc group lowered]
  (let [tpl? (:template? group)
        imports (str "import { query, dispatch } from \"../runtime.js\";\n"
                     (let [tpl-imports (template-sub-imports doc group lowered)]
                       (when (seq tpl-imports)
                         (str tpl-imports "\n"))))
        body (if tpl?
               (template-view doc group lowered)
               (stateful-view doc group lowered))]
    (str "// Auto-generated: view for group \"" (:ns-name group) "\".\n"
         imports
         "\n"
         body)))

;; --- root app.js ---------------------------------------------------------

(defn emit-app-js
  "Top-level entry. Imports each stateful group's db/subs/events,
   composes the root default-db, then walks the document root tree
   and emits its inline hiccup with named-group descendants
   replaced by `<ns>View()` calls. Unnamed root wrappers like
   `x-navbar` and `x-gaussian-blur` come along for the ride —
   they're decorative containers rendered inline; their named
   descendants jump out into their own view fns."
  [doc lowered]
  (let [groups          (:groups lowered)
        template-groups (:template-names lowered)
        owner-idx       (:field-owner-ns lowered)
        stateful        (remove :template? groups)
        ;; Path segments stay kebab-case (the on-disk file layout
        ;; mirrors `<ns-name>/`); JS binding names use `js-ident`
        ;; so a group named "product-feed" imports as
        ;; `product_feedDb` / `product_feedView`.
        imports  (for [g stateful
                       :let [ns   (:ns-name g)
                             jid  (js-ident ns)]]
                   (str "import { defaultDb as " jid "Db } from \"./"
                        ns "/db.js\";\n"
                        "import \"./" ns "/subs.js\";\n"
                        "import \"./" ns "/events.js\";\n"
                        "import { view as " jid "View } from \"./"
                        ns "/views.js\";"))
        merge-bits (for [g stateful] (str "...(" (js-ident (:ns-name g)) "Db)"))
        ;; Build the ctx for the root walk. find-sub-groups on
        ;; the root returns every NEAREST named-group instance —
        ;; cart and product-feed in the demo-store. node->js-hiccup
        ;; emits a `<ns>View()` call for each, and unnamed
        ;; wrappers (x-navbar, x-gaussian-blur, …) stay inline as
        ;; hiccup arrays.
        root-node        (:root doc)
        sub-groups       (em/find-sub-groups root-node groups)
        sub-groups-by-id (into {} (map (juxt :id identity)) sub-groups)
        field->owner     (fn [field] (or (get owner-idx field) "main"))
        ctx {:app-ns              "app"
             :doc                 doc
             :all-groups          groups
             :field->owner        field->owner
             :sub-groups-by-id    sub-groups-by-id
             :template-groups     template-groups
             :template-field-syms #{}
             :template-record-sym nil}
        root-tree (node->js-hiccup root-node nil ctx)]
    (str "// Auto-generated: Bareforge vanilla-JS export entry.\n"
         ;; The root walk inlines hiccup for unnamed wrappers, which
         ;; means `query()` (read bindings, text-fields) and
         ;; `dispatch()` (write bindings on root-level form fields,
         ;; triggers without a payload) can both appear at the
         ;; mount level — import both unconditionally. Cheap; tree-
         ;; shaking handles the unused one if a project doesn't
         ;; need it.
         "import { initStore, addWatcher, query, dispatch } from \"./runtime.js\";\n"
         "import { mount } from \"./renderer.js\";\n"
         (str/join "\n" imports)
         "\n\n"
         "const rootDb = { " (str/join ", " merge-bits) " };\n"
         "initStore(rootDb);\n\n"
         "const container = document.getElementById(\"app\");\n"
         "mount(\n"
         "  container,\n"
         "  () => " root-tree ",\n"
         "  (rerender) => { addWatcher(rerender); }\n"
         ");\n")))
