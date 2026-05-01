(ns bareforge.export.cljs-project
  "Generate a ClojureScript project from a Bareforge document.

   Pure: every public function takes data in and returns data out.
   The top-level `generate` function returns a map of
   {file-path-string -> file-content-string} representing the
   complete project tree.

   Views use hiccup notation rendered by an included `renderer.cljs`
   (adapted from bare-demo). Each detected UI component group gets
   its own folder with db/subs/events/views files."
  (:require [bareforge.doc.actions :as actions]
            [bareforge.doc.model :as m]
            [bareforge.export.clj-form :as cf]
            [bareforge.export.html-to-hiccup :as h2h]
            [bareforge.export.model :as em]
            [bareforge.meta.versions :as versions]
            [bareforge.render.reconcile :as rec]
            [clojure.string :as str]
            [shadow.resource :as rc]))

;; The canonical framework source lives in
;; `src/bareforge/export/cljs_project/framework.cljs` and is covered
;; by Bareforge's own tests. We inline it at build time and rewrite
;; the `ns` prefix so every exported project carries byte-identical
;; logic. No second drift-prone copy.
(def ^:private framework-template
  (rc/inline "bareforge/export/cljs_project/framework.cljs"))

;; Same lockstep as the framework: the canonical renderer lives at
;; `src/bareforge/export/cljs_project/renderer.cljs`; we inline it
;; at build time and rewrite the ns prefix. `::render` and other
;; auto-resolved keywords stay as text — the emitted project's
;; compiler re-resolves them under `<app-ns>.renderer`, which is
;; what we want.
(def ^:private renderer-template
  (rc/inline "bareforge/export/cljs_project/renderer.cljs"))

;; --- file writing (node-only) ----------------------------------------------

(defn write-project!
  "Write a generated project map to disk under `output-dir`.
   Creates directories as needed. Node.js only."
  [output-dir files]
  (let [fs   (js/require "fs")
        path (js/require "path")]
    (doseq [[rel-path content] files]
      (let [full-path (.join path output-dir rel-path)
            dir       (.dirname path full-path)]
        (.mkdirSync fs dir #js {:recursive true})
        (.writeFileSync fs full-path content "utf8")))))

;; --- naming helpers --------------------------------------------------------

(defn- kebab->snake
  "Convert kebab-case to snake_case for ClojureScript file paths."
  [s]
  (str/replace s #"-" "_"))

;; --- group detection / data collection -------------------------------------
;; The helpers below all lowered into `bareforge.export.model` in Phase A
;; of the export-plugin refactor; the aliases keep call-site churn nil.
;; A later phase will formalise a composed `lower-document` entry point
;; and rewrite generators to consume that, at which point these aliases
;; go away.

(def detect-groups em/detect-groups)
(def ^:private collect-group-data em/collect-group-data)

(defn- field-type->spec
  "Convert a field type keyword to a spec predicate string."
  [t]
  (case t
    :number  "number?"
    :boolean "boolean?"
    :keyword "keyword?"
    :vector  "vector?"
    "string?"))

(defn- field-type->default
  "Return the default value for a field type."
  [t]
  (case t
    :number  "0"
    :boolean "false"
    :keyword "nil"
    :vector  "[]"
    "\"\""))

;; --- hiccup generation -----------------------------------------------------

(defn- normalize-attr-value
  "Safety net for attrs that must be valid CSS values. `x-grid`'s
   `columns` is a `grid-template-columns` track list — a bare integer
   like \"3\" is invalid CSS and stacks every child on its own row.
   Coerce to `repeat(N, 1fr)` so the resulting template is valid. All
   other attrs pass through unchanged."
  [tag attr-name v]
  (if (and (= "x-grid" tag)
           (= "columns" attr-name)
           (string? v)
           (re-matches #"\s*\d+\s*" v))
    (str "repeat(" (str/trim v) ", 1fr)")
    v))

(defn- format-props-map
  "Format an already-serialized list of `:key value` prop strings into
   a hiccup props map. Single prop → inline; two or more → one-per-line
   aligned after the opening `{`. `tag` and `depth` drive the alignment."
  [props tag depth]
  (when (seq props)
    (if (<= (count props) 1)
      (str "{" (first props) "}")
      (let [align (apply str (repeat (+ depth 3 (count (or tag ""))) " "))]
        (str "{" (first props) "\n"
             (str/join "\n"
                       (for [p (rest props)]
                         (str align p)))
             "}")))))

(defn- node->prop-strings
  "Build the ordered list of `:key value` prop strings for a node.
   Includes slot, attrs (with binding substitution), bindings that
   have no matching static attr (e.g. `:open` on an x-popover where
   the designer only set the binding), JS properties committed through
   the inspector's augment boolean/number widgets (stored on `:props`),
   and layout style."
  [node slot-name]
  (let [bindings   (:bindings node)
        attr-keys  (into #{} (keys (:attrs node)))
        prop-keys  (into #{} (map #(cljs.core/name %) (keys (:props node))))
        attrs      (for [[k v] (sort-by key (:attrs node))
                         :when (and k (some? v))]
                     (let [binding (get bindings k)]
                       (if (and binding
                                (contains? #{:read :read-write} (:direction binding)))
                         (str ":" k " " (cljs.core/name (:field binding)))
                         (if (= "" v)
                           (str ":" k " true")
                           (str ":" k " \""
                                (h2h/escape-cljs-str
                                 (normalize-attr-value (:tag node) k v))
                                "\"")))))
        ;; :props holds values the inspector committed via setv! — one
        ;; per augment-declared boolean / number / enum widget — that
        ;; the designer hasn't duplicated in :attrs. Emit them into the
        ;; hiccup prop-map as-is (the exported renderer will setv!
        ;; them back onto the element), booleans serialised without
        ;; quotes so observed-attribute paths fire.
        prop-pairs (for [[k v] (sort-by key (:props node))
                         :let [kn (cljs.core/name k)]
                         :when (and (not (contains? attr-keys kn))
                                    (some? v))]
                     (cond
                       (boolean? v) (str ":" kn " " v)
                       (number?  v) (str ":" kn " " v)
                       :else        (str ":" kn " \""
                                         (h2h/escape-cljs-str (str v)) "\"")))
        binding-only (for [[k {:keys [field direction]}] (sort-by key bindings)
                           :when (and (contains? #{:read :read-write} direction)
                                      (not (contains? attr-keys k))
                                      (not (contains? prop-keys k)))]
                       (str ":" k " " (cljs.core/name field)))
        slot-prop  (when (and slot-name (not= "default" slot-name))
                     (str ":slot \"" slot-name "\""))
        style      (rec/layout->css (:layout node))
        style-prop (when style
                     (str ":style \"" (h2h/escape-cljs-str style) "\""))]
    (vec (remove nil? (concat [slot-prop] attrs prop-pairs binding-only [style-prop])))))

(defn- indent [n s]
  (let [pad (apply str (repeat n " "))]
    (str pad s)))

;; --- code generation: views ------------------------------------------------

(def ^:private template-group?   em/template-group?)
(def ^:private stateful-group?   em/stateful-group?)
(def ^:private computed-field?   em/computed?)
(def ^:private collection-field? em/collection-field?)

(def ^:private field-owner-index              em/field-owner-index)
(def ^:private name->ns-name-map               em/name->ns-name-map)
(def ^:private explicit-field-owners           em/explicit-field-owners)
(def ^:private collect-read-bindings           em/collect-read-bindings)
(def ^:private collect-trigger-payload-fields  em/collect-trigger-payload-fields)
(def ^:private collect-trigger-action-refs     em/collect-trigger-action-refs)

(defn- action-ref->alias
  "Turn an action-ref qualified keyword like :app.cart.events/add-to-cart
   into the require alias string: \"cart.events\". Matches the
   dot-notation alias convention used elsewhere in the generator.

   The group segment is passed through `actions/name->ns-segment`
   so an action-ref that was committed before the
   `bareforge.doc.actions/action-ref` canonicalisation fix (e.g.
   `:app.Dashboard.events/tick` in an older doc) still emits a
   lowercase require — `[app.dashboard.events :as dashboard.events]`
   matching the file at `src/app/dashboard/events.cljs`."
  [ref]
  (let [ns         (namespace ref)
        first-dot  (.indexOf ns ".")
        last-dot   (.lastIndexOf ns ".")
        group-seg  (subs ns (inc first-dot) last-dot)
        suffix     (subs ns last-dot)]   ;; ".events" or ".subs"
    (str (actions/name->ns-segment group-seg) suffix)))

;; --- :write / :read-write binding → DOM event handler ---------------------
;;
;; A binding with `:write` or `:read-write` direction needs a DOM event
;; listener that calls the field's auto setter (`::<field>-changed`) on
;; every value change. The event name a BareDOM component fires varies
;; by tag, so we keep a small table — one row per form-like component.
;; Tags not in the table produce no handler (we don't silently guess),
;; which means hand-authored triggers still cover that case.

(def ^:private write-binding-event-names
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

(defn- write-binding-event-name [tag]
  (get write-binding-event-names tag))

(defn- write-binding->dispatch
  "Emit `:on-<ename> (fn [^js e] (rf/dispatch [::<owner>.events/<field>-changed
   (.. e -detail -<prop-key>)]))` for a single write/read-write
   binding. Returns nil when the tag has no registered value-changed
   event or the owner ns couldn't be resolved — in either case,
   emitting would produce dead code."
  [tag prop-key field owner-ns]
  (when (and owner-ns (write-binding-event-name tag))
    (str ":on-" (write-binding-event-name tag) " "
         "(fn [^js e] (rf/dispatch [::" owner-ns ".events/"
         (cljs.core/name field) "-changed"
         " (.. e -detail -" prop-key ")]))")))

(defn- node-write-binding-owners
  "The set of `<owner>.events` alias strings needed by write/read-write
   bindings on this node alone (no subtree walk). Owner resolution:
   explicit `:owner` on the binding (via `name->ns`), else
   `field-owner-idx` lookup, else the enclosing group. Entries whose
   owner can't be resolved are dropped."
  [node name->ns owner-idx own-ns-name]
  (for [[_ {:keys [field direction owner]}] (:bindings node)
        :when (and (contains? #{:write :read-write} direction)
                   (write-binding-event-name (:tag node)))
        :let [owner-ns (or (when owner (get name->ns owner owner))
                           (get owner-idx field)
                           own-ns-name)]
        :when owner-ns]
    (str owner-ns ".events")))

(defn- collect-write-binding-aliases
  "Walk `node`'s subtree (stopping at `sub-group-ids`) and collect
   the distinct `<owner>.events` aliases needed for every write /
   read-write binding. Used to extend a view's require list so the
   auto-setter dispatch actually resolves."
  [node sub-group-ids name->ns owner-idx own-ns-name]
  (distinct
   (concat
    (node-write-binding-owners node name->ns owner-idx own-ns-name)
    (for [[_ kids] (m/slot-entries node)
          c kids
          :when (not (contains? sub-group-ids (:id c)))
          a (collect-write-binding-aliases
             c sub-group-ids name->ns owner-idx own-ns-name)]
      a))))

(defn- payload-arg
  "Resolve one payload entry to the argument expression inserted into
   the dispatch vector. Three shapes:

   - `{:literal v}`         → a literal EDN value.
   - `{:event-detail :kw}`  → reads `(.. e -detail -kw)` (needs
     event-aware dispatch, see `trigger->event-prop`).
   - `{:field :field …}`    → resolves through `field->sym` (either
     a let-bound rf/query sym, or a destructured record key in a
     collection view); falls back to the raw field name."
  [pe field->sym]
  (cond
    (contains? pe :literal)
    (pr-str (:literal pe))

    (contains? pe :event-detail)
    (str "(.. e -detail -" (cljs.core/name (:event-detail pe)) ")")

    :else
    (or (get field->sym (:field pe))
        (cljs.core/name (:field pe)))))

(defn- trigger->event-prop
  "Emit the full `:on-<ename> <handler>` event-prop string for a
   single trigger — the one place that implements rule 17.

   Payload resolution (via `payload-arg`):
   - `{:literal v}`        — EDN literal dispatched verbatim.
   - `{:event-detail :kw}` — reads `(.. e -detail -kw)`; forces the
                             event-aware `(fn [^js e] …)` wrapper.
   - `{:field :f …}`       — resolved through `field->sym`.

   Wrapper selection: `#(rf/dispatch [...])` by default, or
   `(fn [^js e] …)` when the handler needs access to the DOM event —
   i.e. any payload entry reads from `:event-detail` OR the trigger
   carries `:prevent-default? true`. When `:prevent-default?` is set,
   `.preventDefault()` runs before the dispatch so the component's
   built-in behaviour for that event is skipped (e.g. x-popover-toggle,
   where letting the component toggle its own `open` attr would race
   our db sync).

   Implicit-payload fallback: when `:payload` is absent, dispatch the
   enclosing template record arg named by `tmpl-record-sym` (or fire
   with no args when outside a template group)."
  [trigger field->sym tmpl-record-sym]
  (let [aref      (:action-ref trigger)
        alias     (action-ref->alias aref)
        ename     (cljs.core/name aref)
        payload   (:payload trigger)
        prevent?  (boolean (:prevent-default? trigger))
        args      (cond
                    (seq payload)
                    (for [pe payload] (payload-arg pe field->sym))

                    tmpl-record-sym
                    [tmpl-record-sym]

                    :else
                    nil)
        args-str  (apply str (for [a args] (str " " a)))
        dispatch  (str "(rf/dispatch [::" alias "/" ename args-str "])")
        body      (if prevent?
                    (str "(.preventDefault e) " dispatch)
                    dispatch)
        event-needed? (or prevent? (some :event-detail payload))
        handler   (if event-needed?
                    (str "(fn [^js e] " body ")")
                    (str "#" body))]
    (str ":on-" (:trigger trigger) " " handler)))

(defn- collection-iteration-call
  "Emit a `for`-iteration over a template-group's records wrapped in
   a `display: contents` `<div>`. The wrapper gives the reconciler a
   stable position for the iteration so a shrinking list doesn't
   shift its tail into trigger-slotted siblings — see
   `bareforge.export.renderer` on stored children for context. The
   `display: contents` style makes the wrapper visually transparent:
   children lay out as if they were direct children of the enclosing
   element, so grid / flex / popover default-slot all behave the
   same as without the wrapper.

   The record source is either:
   - `:source-sub` on the instance node — a fully qualified sub
     keyword; iterate that sub directly.
   - `:source-field` on the instance node — a plain field keyword;
     resolve its owning group via the field-owner index and iterate
     `::<owner>.subs/<field>`."
  [sub-group-name pad source-sub source-field field-owner-ns]
  (let [sub-ref (cond
                  source-sub
                  (str "::" (action-ref->alias source-sub) "/"
                       (name source-sub))

                  (and source-field field-owner-ns)
                  (str "::" field-owner-ns ".subs/"
                       (cljs.core/name source-field))

                  :else
                  (throw (ex-info
                          (str "Template group \"" sub-group-name
                               "\" has no data source. Select its root node in "
                               "the inspector and pick a 'Rendered from' "
                               "source field (or declare a :source-sub).")
                          {:group sub-group-name})))]
    (str (pad "[:div {:style \"display: contents\"}")
         "\n"
         (pad (str "  (for [p (rf/query [" sub-ref "])]"))
         "\n"
         (pad (str "    (" sub-group-name ".views/" sub-group-name " p))]")))))

(declare stateful-host-for-template)

(declare node->hiccup-with-events)

(defn- emit-sub-group-child
  "Render one sub-group child of a node into its hiccup string.
   Returns `[rendered-or-nil rendered-tpls']` so the caller can
   dedupe templates within a parent slot — the first encounter
   of a template sub-group renders its iteration call; later
   encounters return nil so a parent with N seed-backed clones
   still emits one iteration. Singleton sub-groups return the
   plain `(<ns>.views/<ns>)` call."
  [ctx child gname tpl? rendered-tpls kid-pad]
  (let [{:keys [doc all-groups field-owner-ns-map]} ctx]
    (cond
      (and tpl? (contains? rendered-tpls gname))
      [nil rendered-tpls]

      tpl?
      (let [;; Fall back to the (single) collection field that
            ;; points at this template when the instance has no
            ;; explicit :source-field / :source-sub set. Lets the
            ;; user declare a collection + name the template
            ;; without also having to manually wire the
            ;; 'Rendered from' source in the inspector.
            fallback  (when (and (nil? (:source-sub child))
                                 (nil? (:source-field child)))
                        (stateful-host-for-template
                         doc all-groups gname))
            src-field (or (:source-field child)
                          (when fallback (keyword (:field-name fallback))))
            field-ns  (or (get field-owner-ns-map (:source-field child))
                          (when fallback (:ns-name fallback)))]
        [(collection-iteration-call gname kid-pad
                                    (:source-sub child)
                                    src-field
                                    field-ns)
         (conj rendered-tpls gname)])

      :else
      [(kid-pad (str "(" gname ".views/" gname ")"))
       rendered-tpls])))

(defn- walk-slotted-children
  "Render every child of `node` across all its slots. Sub-group
   children dispatch to `emit-sub-group-child`; non-group children
   recurse via `node->hiccup-with-events`. Returns a vector of
   hiccup strings in document order, with template-group
   iterations deduped per slot."
  [ctx node depth]
  (let [{:keys [sub-group-ids all-groups template-groups]} ctx
        sub-set (set sub-group-ids)
        kid-pad (fn [s] (indent (+ depth 1) s))]
    (first
     (reduce
      (fn [[acc rendered-tpls] [sname child]]
        (if (contains? sub-set (:id child))
          (let [g (first (filter #(some #{(:id child)} (:instance-ids %))
                                 all-groups))
                gname (:ns-name g)
                tpl?  (contains? template-groups gname)
                [rendered tpls']
                (emit-sub-group-child ctx child gname tpl?
                                      rendered-tpls kid-pad)]
            [(cond-> acc rendered (conj rendered)) tpls'])
          [(conj acc (node->hiccup-with-events ctx child sname (+ depth 1)))
           rendered-tpls]))
      [[] #{}]
      (for [[sname kids] (m/slot-entries node)
            child kids]
        [sname child])))))

(defn- node->hiccup-with-events
  "Like node->hiccup but also adds :on-* handlers for event triggers
   and honours `:text-field` on nodes by emitting a local symbol when
   it's present in `field->sym`.

   `ctx` carries the per-walk constants so the signature stays at
   four args across the recursive calls:
     :doc                — the document
     :field->sym         — payload/text-field field-kw → local sym
                           (let-bound from rf/query in template-parent
                           views, or destructured from the record arg
                           in template-group views)
     :sub-group-ids      — node ids whose subtree belongs to another group
     :all-groups         — every group's metadata
     :template-groups    — set of template-group ns-names
                           (rendered as a `for` loop rather than once)
     :field-owner-ns-map — field-kw → owning group's ns-name (used to
                           resolve a template instance's :source-field)
     :tmpl-record-sym    — name of the record arg in a template-group
                           view, or nil
     :name->ns           — user-facing group `:name` → ns-name (used to
                           resolve a binding's `:owner`)
     :own-ns-name        — enclosing view's group ns-name; default
                           owner for write bindings with no `:owner`
                           and no field-owner match

   Children walking + sub-group dispatch live in
   `walk-slotted-children` / `emit-sub-group-child`; this fn
   handles props + text + final output assembly."
  [{:keys [field->sym tmpl-record-sym field-owner-ns-map name->ns own-ns-name]
    :as ctx}
   node slot-name depth]
  (let [tag         (str ":" (:tag node))
        base-props  (node->prop-strings node slot-name)
        event-props (for [t (:events node)]
                      (trigger->event-prop t field->sym tmpl-record-sym))
        write-props (for [[k {:keys [field direction owner]}] (:bindings node)
                          :when (contains? #{:write :read-write} direction)
                          :let  [owner-ns (or (when owner (get name->ns owner owner))
                                              (get field-owner-ns-map field)
                                              own-ns-name)
                                 prop    (write-binding->dispatch
                                          (:tag node) k field owner-ns)]
                          :when prop]
                      prop)
        props       (format-props-map (concat base-props event-props write-props)
                                      (:tag node) depth)
        tf          (:text-field node)
        text        (cond
                      (and tf (contains? field->sym tf))
                      (get field->sym tf)

                      (and (:text node) (not= "" (:text node)))
                      (str "\"" (h2h/escape-cljs-str (:text node)) "\""))
        inner-html  (:inner-html node)
        pad         (fn [s] (indent depth s))
        children    (walk-slotted-children ctx node depth)]
    (cond
      inner-html
      (let [inner-hiccup (h2h/html->hiccup-str inner-html (+ depth 1))]
        (str (pad (str "[" tag (when props (str " " props))))
             "\n" inner-hiccup "]"))

      (and (empty? children) (nil? text))
      (pad (str "[" tag (when props (str " " props)) "]"))

      (and (empty? children) text)
      (pad (str "[" tag (when props (str " " props)) " " text "]"))

      :else
      (str (pad (str "[" tag (when props (str " " props))))
           (when text (str "\n" (indent (+ depth 1) text)))
           (str/join "" (map #(str "\n" %) children))
           "]"))))

(def ^:private find-sub-groups em/find-sub-groups)

(defn- view-context
  "Pure: derive every datum `generate-views` needs to emit a view
   file for `group`. Returns a bundle map: `:fn-name :ns-path
   :fn-sig :require-entries :hiccup-ctx :node :root-slot
   :let-fields :field->owner :has-let?`. Splitting this out keeps
   `generate-views` itself a thin orchestrator over a known-shape
   data bundle — the data assembly is large enough to read as a
   small program of its own, and the formatting step is easier to
   audit when it stops sharing scope with 25 derived locals."
  [doc group all-groups app-ns]
  (let [node            (m/get-node doc (:id group))
        fn-name         (:ns-name group)
        parent-info     (m/parent-of doc (:id group))
        root-slot       (when parent-info (:slot parent-info))
        ns-path         (str app-ns "." (:ns-name group) ".views")
        own-ns-name     (:ns-name group)
        template?       (template-group? doc group)
        template-groups (into #{}
                              (for [g all-groups :when (template-group? doc g)]
                                (:ns-name g)))
        sub-groups      (find-sub-groups node all-groups)
        sub-group-ids   (set (map :id sub-groups))
        read-fields     (collect-read-bindings node sub-group-ids)
        payload-fields  (collect-trigger-payload-fields node sub-group-ids)
        ;; Template view destructures its own fields off the record
        ;; arg; those don't need a `let` binding.
        own-record-fields (when template?
                            (->> (:fields node)
                                 (remove computed-field?)
                                 (map :name)
                                 vec))
        own-field-set   (set own-record-fields)
        let-fields      (->> (concat read-fields payload-fields)
                             distinct
                             (remove own-field-set))
        has-let?        (seq let-fields)
        owner-idx       (field-owner-index doc all-groups)
        name->ns        (name->ns-name-map doc all-groups)
        explicit        (into {}
                              (for [[f owner] (explicit-field-owners node sub-group-ids)]
                                [f (get name->ns owner owner)]))
        field->owner    (into {} (for [f let-fields]
                                   [f (or (get explicit f)
                                          (get owner-idx f)
                                          own-ns-name)]))
        subs-aliases    (distinct (map #(str % ".subs") (vals field->owner)))
        field->sym      (into {}
                              (concat
                               (for [f let-fields] [f (cljs.core/name f)])
                               (for [f own-record-fields] [f (cljs.core/name f)])))
        action-aliases  (distinct (map action-ref->alias
                                       (collect-trigger-action-refs node sub-group-ids)))
        ;; Write / read-write bindings dispatch the field's auto
        ;; setter event; the enclosing view must require the owning
        ;; group's `.events` ns to resolve `::<alias>/<field>-changed`.
        write-events-aliases (collect-write-binding-aliases
                              node sub-group-ids name->ns owner-idx own-ns-name)
        ;; For each template sub-group child, its records come from
        ;; either :source-sub (its own ns) or :source-field (owning
        ;; group's subs ns — resolved via field-owner-idx). When
        ;; :source-field is absent but exactly one collection targets
        ;; the template via :of-group, the emitter falls back to that
        ;; host (see collection-iteration-call's fallback); this
        ;; require list must mirror that fallback so the auto-
        ;; resolved sub's namespace is actually imported.
        tpl-child-sub-aliases
        (distinct
         (concat
          (for [sg sub-groups
                :let [inst (m/get-node doc (:id sg))
                      src  (:source-sub inst)]
                :when (and (contains? template-groups (:ns-name sg)) src)]
            (action-ref->alias src))
          (for [sg sub-groups
                :let [inst     (m/get-node doc (:id sg))
                      sf       (:source-field inst)
                      owner    (when sf (get owner-idx sf))
                      fallback (when (and (nil? sf) (nil? (:source-sub inst)))
                                 (stateful-host-for-template
                                  doc all-groups (:ns-name sg)))
                      owner*   (or owner (:ns-name fallback))]
                :when (and (contains? template-groups (:ns-name sg))
                           owner*)]
            (str owner* ".subs"))))
        own-db-alias    (when template?
                          (str own-ns-name ".db"))
        all-subs-aliases (distinct (concat subs-aliases tpl-child-sub-aliases))
        ;; When the view is a template group, keep the whole record
        ;; available as `record` (via `:as record`) so triggers with
        ;; no explicit :payload can dispatch it implicitly.
        tmpl-record-sym (when template? "record")
        fn-sig          (if template?
                          (str "[{:keys ["
                               (str/join " "
                                         (for [f own-record-fields]
                                           (str "::" own-db-alias "/"
                                                (cljs.core/name f))))
                               "] :as " tmpl-record-sym "}]")
                          "[]")
        has-template-subgroup? (boolean
                                (some #(contains? template-groups (:ns-name %))
                                      sub-groups))
        uses-rf?        (or has-let?
                            (seq action-aliases)
                            (seq write-events-aliases)
                            has-template-subgroup?)
        require-entries (distinct
                         (concat
                          (when uses-rf?
                            [(str "[" app-ns ".framework :as rf]")])
                          (when own-db-alias
                            [(str "[" app-ns "." own-db-alias
                                  " :as " own-db-alias "]")])
                          (for [a all-subs-aliases]
                            (str "[" app-ns "." a " :as " a "]"))
                          (for [a action-aliases]
                            (str "[" app-ns "." a " :as " a "]"))
                          (for [a write-events-aliases]
                            (str "[" app-ns "." a " :as " a "]"))
                          (for [sg (distinct (map :ns-name sub-groups))]
                            (str "[" app-ns "." sg ".views :as " sg ".views]"))))
        hiccup-ctx      {:doc                doc
                         :field->sym         field->sym
                         :sub-group-ids      sub-group-ids
                         :all-groups         all-groups
                         :template-groups    template-groups
                         :field-owner-ns-map owner-idx
                         :tmpl-record-sym    tmpl-record-sym
                         :name->ns           name->ns
                         :own-ns-name        own-ns-name}]
    {:fn-name         fn-name
     :ns-path         ns-path
     :fn-sig          fn-sig
     :require-entries require-entries
     :hiccup-ctx      hiccup-ctx
     :node            node
     :root-slot       root-slot
     :let-fields      let-fields
     :field->owner    field->owner
     :has-let?        (boolean has-let?)}))

(defn- generate-views
  "Generate the views.cljs content for a group using hiccup.

   A template group's view takes the record as a single arg and
   destructures its fully-qualified keys. A stateful group's view
   takes no arg; it `rf/query`s every field it reads via attribute
   bindings or trigger payloads.

   Triggers emit `:on-<event>` handlers that dispatch an action by
   its fully qualified action-ref keyword. The view's :require list
   includes every sub/action/db/child-views ns it actually touches.

   Sub-groups are rendered two ways:
   - Singleton sub-groups → a plain `(<ns>.views/<ns>)` call.
   - Template sub-groups → a `(for [p (rf/query [<sub>])] ...)`
     loop, where `<sub>` comes from `:source-sub` (explicit) or
     `:source-field` (resolved via the field-owner index).

   Data assembly lives in `view-context`; this fn formats the file
   string from that bundle."
  [doc group all-groups app-ns]
  (let [{:keys [fn-name ns-path fn-sig require-entries hiccup-ctx
                node root-slot let-fields field->owner has-let?]}
        (view-context doc group all-groups app-ns)
        ns-clause (if (seq require-entries)
                    (str "(ns " ns-path "\n"
                         "  (:require " (str/join "\n            " require-entries) "))\n")
                    (str "(ns " ns-path ")\n"))]
    (str ns-clause
         "\n"
         "(defn " fn-name " " fn-sig "\n"
         (if has-let?
           (str "  (let ["
                (str/join "\n        "
                          (for [field let-fields]
                            (let [owner (field->owner field)
                                  alias (str owner ".subs")
                                  fname (cljs.core/name field)]
                              (str fname " (rf/query [::" alias "/" fname "])"))))
                "]\n"
                (node->hiccup-with-events hiccup-ctx node root-slot 4)
                "))\n")
           (str (node->hiccup-with-events hiccup-ctx node root-slot 2)
                ")\n")))))

;; --- code generation: db/subs/events ----------------------------------------

(defn- generate-db-template
  "Generate db.cljs for a template group. The file holds only spec
   definitions — one per stored (non-computed) field plus a `::record`
   spec. No `default-db`: template groups own no state.

   A collection field that points at this group via `:of-group`
   resolves its record spec through this file's `::record`."
  [doc group app-ns]
  (let [ns-path (str app-ns "." (:ns-name group) ".db")
        fields  (:fields (m/get-node doc (first (:instance-ids group))))
        stored  (remove computed-field? fields)
        field-specs (for [{:keys [name type]} stored]
                      (str "(s/def ::" (cljs.core/name name) " "
                           (field-type->spec type) ")"))
        record-spec (str "(s/def ::record\n"
                         "  (s/keys :req [::"
                         (str/join " ::"
                                   (for [{:keys [name]} stored]
                                     (cljs.core/name name)))
                         "]))")]
    (str "(ns " ns-path "\n"
         "  (:require [clojure.spec.alpha :as s]))\n"
         "\n"
         (str/join "\n" field-specs)
         "\n\n"
         record-spec
         "\n")))

(defn- field-spec-def
  "Spec def string for one stored field on a stateful group.
   Collection fields reference the target group's `::record` spec
   (requires the target's db ns at the caller). Scalars use the
   built-in predicate from `field-type->spec`."
  [fd]
  (let [name (cljs.core/name (:name fd))]
    (if (collection-field? fd)
      (str "(s/def ::" name " (s/coll-of ::"
           (:of-group fd) ".db/record :kind vector?))")
      (str "(s/def ::" name " " (field-type->spec (:type fd)) ")"))))

(defn- format-seed-record
  "Emit one seed record as a ClojureScript map literal with
   `::<og-ns>/<field>` auto-resolved keys, one key per line, no commas.
   `key-col` is the 1-indexed output column where each continuation
   key should land (aligned with the first key, just past the `{`)."
  [og-ns record key-col]
  (let [entries (vec
                 (for [[k v] record]
                   (str "::" og-ns "/" (cljs.core/name k) " " (pr-str v))))
        key-pad (apply str (repeat (dec key-col) " "))]
    (str "{" (first entries)
         (apply str (for [e (rest entries)] (str "\n" key-pad e)))
         "}")))

(defn- format-seed-records
  "Emit a vector of seed records. `start-col` is the 1-indexed column
   where the enclosing `[` will land; records inside align one column
   past it, keys inside each record align another column past that."
  [og-ns records start-col]
  (let [rec-col (inc start-col)
        key-col (inc rec-col)
        rec-pad (apply str (repeat (dec rec-col) " "))
        formatted (for [r records]
                    (format-seed-record og-ns r key-col))]
    (str "[" (str/join (str "\n" rec-pad) formatted) "]")))

(defn- field-default-entry
  "Default-db entry for one stored field on a stateful group. When the
   field is a collection of records (`:of-group` set), seed records are
   emitted with `::<og>.db/<field>` auto-resolved keys — one key per
   line, no commas — so the generated file parses the way the template
   view's fully-qualified `:keys` destructure expects."
  [fd]
  (let [nm (cljs.core/name (:name fd))
        v  (:default fd)
        og (:of-group fd)]
    (if (and og (sequential? v) (every? map? v))
      (let [prefix (str "::" nm " ")
            ;; The enclosing default-db map body starts at column 3
            ;; (after "  {" at the `(def default-db\n  {` opener) and
            ;; separates subsequent entries with "\n   " (3 spaces).
            ;; So every entry's first char sits at column 3.
            vec-col (+ 3 (count prefix))]
        (str prefix (format-seed-records (str og ".db") v vec-col)))
      (str "::" nm " "
           (if (some? v)
             (pr-str v)
             (field-type->default (:type fd)))))))

(defn- generate-db
  "Generate db.cljs for a group. Two shapes:

   - **Template group**: specs only (`::<field>` + `::record`); no
     `default-db`. Its records live behind another group's collection
     field that targets it via `:of-group`.
   - **Stateful group**: each stored scalar becomes a spec + a
     `default-db` entry seeded from `:default`. Collection fields
     produce a `(s/coll-of ::<of-group>.db/record)` spec and carry
     their seed records in `:default` verbatim. Computed fields are
     excluded — their sub is derived in `generate-subs`."
  [doc group all-groups app-ns]
  (if (template-group? doc group)
    (generate-db-template doc group app-ns)
    (let [data          (collect-group-data doc (:instance-ids group) all-groups)
          all-fields    (:fields data)
          declared-keys (set (map :name all-fields))
          stored        (remove computed-field? all-fields)
          bindings      (:bindings data)
          ns-path       (str app-ns "." (:ns-name group) ".db")
          has-data?     (or (seq stored) (seq bindings))
          of-group-requires (distinct (keep :of-group stored))]
      (if has-data?
        (let [spec-defs (concat
                         (for [fd stored] (field-spec-def fd))
                         (for [{:keys [field]} bindings
                               :when (not (contains? declared-keys field))]
                           (str "(s/def ::" (cljs.core/name field) " any?)")))
              db-entries (distinct
                          (concat
                           (for [fd stored] (field-default-entry fd))
                           (for [{:keys [field]} bindings
                                 :when (not (contains? declared-keys field))]
                             (str "::" (cljs.core/name field) " nil"))))
              extra-requires
              (apply str
                     (for [og of-group-requires]
                       (str "\n            [" app-ns "." og ".db :as " og ".db]")))]
          (str "(ns " ns-path "\n"
               "  (:require [clojure.spec.alpha :as s]" extra-requires "))\n"
               "\n"
               (str/join "\n" (distinct spec-defs))
               "\n\n"
               "(def default-db\n"
               "  {" (str/join "\n   " db-entries)
               "})\n"))
        (str "(ns " ns-path ")\n"
             "\n"
             "(def default-db\n"
             "  {})\n")))))

(def ^:private computed-op->fn
  "Inlined ClojureScript extractor function for each computed
   operation with a numeric-primitive or trivial source. Emitted as
   the `:->` argument on a derived sub."
  {:count-of  "count"
   :sum-of    "#(reduce + 0 %)"
   :empty-of  "empty?"
   :negation  "not"})

(defn- emit-stored-sub
  "Direct 3-arity `:->` sub pointing at a root-db key. Built as
   `clj-form` data and formatted at the edge — `:pair` keeps the
   `:-> ::field` directive on one line under the multi-line
   reg-sub layout."
  [field db-alias]
  (let [fname (cljs.core/name field)]
    (cf/format-form
     [:invoke-block [:symbol 'rf/reg-sub]
      [:auto-keyword fname]
      [:pair [:keyword :->] [:auto-keyword (str db-alias "/" fname)]]])))

(defn- sum-of-extractor
  "Extractor function for `:sum-of` with a :project-field: project
   the numeric field off each record, then reduce. The record keys
   are fully namespaced by the source's `:of-group` group."
  [source-field project-field doc all-groups]
  (let [project-name (cljs.core/name project-field)
        ;; Resolve the :of-group of the source field so we can qualify
        ;; the project keyword under the record template's db ns.
        owner        (some (fn [g]
                             (let [n (m/get-node doc (first (:instance-ids g)))]
                               (some (fn [f]
                                       (when (= source-field (:name f))
                                         {:of-group (:of-group f)}))
                                     (:fields n))))
                           all-groups)
        of-group     (:of-group owner)]
    (if of-group
      (str "#(transduce (map ::" of-group ".db/" project-name ") + 0 %)")
      (str "#(reduce + 0 (map " project-name " %))"))))

(defn- emit-computed-sub
  "Derived 5-arity sub: reads the source sub (same group) and applies
   the operation's extractor fn. Routes `:any-of`, `:join-on`, and
   `:filter-by` to specialised emitters upstream, so `op` here must
   be one of the simple ops in `computed-op->fn`. Unknown ops (e.g.
   the pre-v1 `:first-of` / `:last-of` / `:lookup-in`) throw — see
   CLAUDE.md rule 12 on the closed v1 op set."
  [{:keys [name computed]} doc all-groups]
  (let [fname  (cljs.core/name name)
        src    (cljs.core/name (:source-field computed))
        op     (:operation computed)
        proj   (:project-field computed)
        op-fn  (cond
                 (and (= :sum-of op) proj)
                 (sum-of-extractor (:source-field computed) proj doc all-groups)

                 (contains? computed-op->fn op)
                 (get computed-op->fn op)

                 :else
                 (throw (ex-info "Unknown computed operation"
                                 {:op        op
                                  :field     name
                                  :known-ops (into #{:any-of :join-on :filter-by}
                                                   (keys computed-op->fn))})))]
    (cf/format-form
     [:invoke-block [:symbol 'rf/reg-sub]
      [:auto-keyword fname]
      [:pair [:keyword :<-] [:vector [:auto-keyword src]]]
      [:pair [:keyword :->] [:raw op-fn]]])))

(defn- emit-any-of-sub
  "Derived multi-signal sub that's truthy iff any listed source-field
   is truthy. Emits:

     (rf/reg-sub ::<name>
       :<- [::a]
       :<- [::b]
       (fn [vs _] (boolean (some identity vs))))"
  [{:keys [name computed]}]
  (let [fname   (cljs.core/name name)
        sources (:source-fields computed)]
    (cf/format-form
     (vec
      (concat
       [:invoke-block [:symbol 'rf/reg-sub]
        [:auto-keyword fname]]
       (for [s sources]
         [:pair [:keyword :<-]
          [:vector [:auto-keyword (cljs.core/name s)]]])
       [[:fn [:vector [:symbol 'vs] [:symbol '_]]
         [:invoke [:symbol 'boolean]
          [:invoke [:symbol 'some]
           [:symbol 'identity] [:symbol 'vs]]]]])))))

(def ^:private stateful-host-for-template em/stateful-host-for-template)

(defn- emit-join-sub
  "Derived multi-signal join sub. Walks `ids` (from :source-field) and
   looks each up in the target template group's records via
   :match-field. When `:of-group` is set on the join-target, the
   matching records are passed through `rf/qualify-map` to re-key
   them under the target group's db namespace.

   The target records live in the stateful group that owns the
   collection field pointing at the template group (resolved via
   `stateful-host-for-template`).

   The handler body's hand-formatted multi-line shape is kept as
   `:raw` text — the shape (->> over keep + a nested when-fn over
   `(= id (match-kw %))`) is more readable as a literal than as
   over-decomposed clj-form data; the outer reg-sub stays as
   structured data."
  [{:keys [name computed]} doc all-groups app-ns]
  (let [fname         (cljs.core/name name)
        src           (cljs.core/name (:source-field computed))
        {:keys [group-name match-field of-group]} (:join-target computed)
        host          (stateful-host-for-template doc all-groups group-name)
        host-sub-ref  (str "::" (:ns-name host) ".subs/" (:field-name host))
        match-kw      (str "::" group-name ".db/" (cljs.core/name match-field))
        as-ns         (when of-group (str app-ns "." of-group ".db"))
        handler-body  (str "(fn [[ids records] _]\n"
                           "   (->> ids\n"
                           "        (keep (fn [id]\n"
                           "                (some #(when (= id (" match-kw " %)) %)\n"
                           "                      records)))\n"
                           (if as-ns
                             (str "        (mapv #(rf/qualify-map % \"" as-ns "\"))))")
                             "        vec))"))]
    (cf/format-form
     [:invoke-block [:symbol 'rf/reg-sub]
      [:auto-keyword fname]
      [:pair [:keyword :<-] [:vector [:auto-keyword src]]]
      [:pair [:keyword :<-] [:vector [:raw host-sub-ref]]]
      [:raw handler-body]])))

(defn- join-target-subs-alias
  "Alias (e.g. `product-feed.subs`) to require for a join's target —
   the subs ns of the stateful group that owns the collection field
   pointing at the template."
  [{:keys [computed]} doc all-groups]
  (when (= :join-on (:operation computed))
    (let [tpl  (get-in computed [:join-target :group-name])
          host (stateful-host-for-template doc all-groups tpl)]
      (when host (str (:ns-name host) ".subs")))))

(defn- join-target-db-alias
  "Alias (e.g. `product.db`) to require for a join's target template
   group's db namespace — used in the match-field keyword."
  [{:keys [computed]}]
  (when (= :join-on (:operation computed))
    (str (get-in computed [:join-target :group-name]) ".db")))

(def ^:private filter-by-of-group em/filter-by-of-group)

(defn- filter-by-db-alias
  "Alias (e.g. `product.db`) to require for a filter-by's target
   template group's db namespace — used inside the emitted handler
   for the match-field keyword."
  [fd doc all-groups]
  (when-let [tpl (filter-by-of-group fd doc all-groups)]
    (str tpl ".db")))

(defn- emit-filter-sub
  "Derived multi-signal filter sub. Combines the filtered collection
   sub (`:source-field`) with the search-term sub (`:filter-spec
   :search-field`) and returns the collection unchanged when the
   term is blank, otherwise filters by case-insensitive substring
   match against the target template's `:match-field`.

   Match-field values are read via the template group's namespaced
   keyword (e.g. `::product.db/title`) and defensively coerced to a
   string so a nil/number record field doesn't blow up the handler.

   As with `emit-join-sub`, the handler body keeps its hand-formatted
   multi-line shape as `:raw` text — the structure (`if` over
   `str/blank?`, `let` over `needle`, `filterv` over `str/includes?`
   on a lowercased projection) is more readable as a literal than
   as decomposed clj-form data."
  [{:keys [name computed] :as fd} doc all-groups]
  (let [fname    (cljs.core/name name)
        src      (cljs.core/name (:source-field computed))
        fs       (:filter-spec computed)
        search   (cljs.core/name (:search-field fs))
        match    (cljs.core/name (:match-field fs))
        tpl      (filter-by-of-group fd doc all-groups)
        match-kw (str "::" tpl ".db/" match)
        handler-body
        (str "(fn [[items term] _]\n"
             "   (if (str/blank? term)\n"
             "     items\n"
             "     (let [needle (str/lower-case term)]\n"
             "       (filterv\n"
             "        (fn [r]\n"
             "          (str/includes?\n"
             "           (str/lower-case (str (" match-kw " r)))\n"
             "           needle))\n"
             "        items))))")]
    (cf/format-form
     [:invoke-block [:symbol 'rf/reg-sub]
      [:auto-keyword fname]
      [:pair [:keyword :<-] [:vector [:auto-keyword src]]]
      [:pair [:keyword :<-] [:vector [:auto-keyword search]]]
      [:raw handler-body]])))

(defn- generate-subs
  "Generate subs.cljs for a group. Skipped for template groups — they
   own no state, so nothing to subscribe to.

   Stateful groups emit:
   - One `:->` sub per stored field (scalar OR collection).
   - One derived sub per computed field.
   - Per-field `any?`-typed subs for any binding-only fields not
     already declared (legacy binding path)."
  [doc group all-groups app-ns]
  (let [ns-path   (str app-ns "." (:ns-name group) ".subs")
        db-alias  (str (:ns-name group) ".db")
        db-ns     (str app-ns "." (:ns-name group) ".db")]
    (when-not (template-group? doc group)
      (let [data         (collect-group-data doc (:instance-ids group) all-groups)
            bindings     (filter #(contains? #{:read :read-write} (:direction %))
                                 (:bindings data))
            fields       (:fields data)
            computed     (filter computed-field? fields)
            computed-names (set (map :name computed))
            stored-names (distinct
                          (concat
                           (for [f fields :when (not (computed-field? f))]
                             (:name f))
                           (for [{:keys [field]} bindings
                                 :when (not (contains? computed-names field))]
                             field)))
            stored-forms   (for [f stored-names] (emit-stored-sub f db-alias))
            computed-forms (for [c computed]
                             (case (get-in c [:computed :operation])
                               :join-on   (emit-join-sub c doc all-groups app-ns)
                               :any-of    (emit-any-of-sub c)
                               :filter-by (emit-filter-sub c doc all-groups)
                               (emit-computed-sub c doc all-groups)))
            all-forms      (concat stored-forms computed-forms)
            ns-aliases     (distinct
                            (concat (keep #(join-target-subs-alias % doc all-groups)
                                          computed)
                                    (keep join-target-db-alias computed)
                                    (keep #(filter-by-db-alias % doc all-groups)
                                          computed)))
            extra-requires (str/join ""
                                     (for [a ns-aliases]
                                       (str "\n            [" app-ns "." a " :as " a "]")))
            ;; `clojure.string` is needed by every emitted :filter-by
            ;; handler — add one require when any such field exists.
            string-require (when (some #(= :filter-by (get-in % [:computed :operation]))
                                       computed)
                             "\n            [clojure.string :as str]")]
        (when (seq all-forms)
          (str "(ns " ns-path "\n"
               "  (:require [" app-ns ".framework :as rf]\n"
               "            [" db-ns " :as " db-alias "]"
               extra-requires
               string-require
               "))\n"
               "\n"
               (str/join "\n\n" all-forms)
               "\n"))))))

(defn- field-setter-event-name
  "The auto-generated setter event name for a field, as an unqualified
   string (to be prefixed with `::` in the emitted code)."
  [field-kw]
  (str (cljs.core/name field-kw) "-changed"))

(defn- action->handler
  "Build the handler fn form for a declared action. Returns
   `[fn-head body-tail]` where body-tail ends with the two closers
   for the fn and reg-event forms.

   With `trim-v` + `path`, the first handler arg is the narrowed value
   at the field and the second is the trimmed event vector. Ops that
   consume a payload value use `(fn [_ [v]] body)`; ops that don't use
   `(fn [v _] body)`.

   When the target field is `:of-group G`, the incoming payload is
   re-keyed under `G`'s db namespace via `rf/qualify-map` so a record
   dispatched from another template group (e.g. a `product` record
   dispatched into a `cart-items :of-group \"cart-item\"` collection)
   lands with the correct spec-conforming keys. qualify-map is
   idempotent on already-qualified input."
  [op of-group-ns]
  (let [wrap (fn [x]
               (if of-group-ns
                 (str "(rf/qualify-map " x " \"" of-group-ns "\")")
                 x))]
    (case op
      :set       [" (fn [_ [v]]\n"  (str "   " (wrap "v") "))")]
      :toggle    [" (fn [v _]\n"    "   (not v)))"]
      :increment [" (fn [v _]\n"    "   (inc v)))"]
      :decrement [" (fn [v _]\n"    "   (dec v)))"]
      :clear     [" (fn [v _]\n"    "   nil))"]
      :add       [" (fn [v [x]]\n"  (str "   (conj v " (wrap "x") ")))")]
      :remove    [" (fn [v [x]]\n"  (str "   (filterv #(not= % " (wrap "x") ") v)))")])))

(defn- step-payload-expr
  "Resolve a step's payload entry to the value expression used in its
   db-update form. v1 honours only `{:literal v}` at the step level;
   anything else (or no `:payload`) defaults to `x` — the trimmed
   trigger arg."
  [step]
  (let [pe (some-> step :payload first)]
    (cond
      (and pe (contains? pe :literal)) (pr-str (:literal pe))
      :else "x")))

(defn- step->thread-form
  "Emit one step's contribution to the `(-> db ...)` thread inside a
   multi-step handler. Returns a string like
   `(update ::cart.db/cart-items conj (rf/qualify-map x \"app.cart-item.db\"))`.
   Honours `:of-group` payload re-keying for `:set` / `:add` / `:remove`."
  [{:keys [operation target-field] :as step} db-alias fields app-ns]
  (let [fname    (cljs.core/name target-field)
        of-group (some (fn [fd]
                         (when (= target-field (:name fd)) (:of-group fd)))
                       fields)
        of-ns    (when of-group (str app-ns "." of-group ".db"))
        wrap     (fn [x] (if of-ns (str "(rf/qualify-map " x " \"" of-ns "\")") x))
        vexpr    (step-payload-expr step)
        fk       (str "::" db-alias "/" fname)]
    (case operation
      :set       (str "(assoc " fk " " (wrap vexpr) ")")
      :toggle    (str "(update " fk " not)")
      :increment (str "(update " fk " inc)")
      :decrement (str "(update " fk " dec)")
      :clear     (str "(assoc " fk " nil)")
      :add       (str "(update " fk " conj " (wrap vexpr) ")")
      :remove    (str "(update " fk
                      " (fn [vs#] (filterv #(not= % "
                      (wrap vexpr) ") vs#)))"))))

(defn- emit-action-event
  "Emit a declared action as a reg-event form. Two paths:

   - **Single-step** (`{:operation :target-field}` or `:steps` of size
     1): emit the legacy shape with `trim-v` + `path` interceptors,
     a value-narrowed handler. Pinned by `cljs_project_test`.
   - **Multi-step** (`:steps` length ≥ 2): emit a no-`path` handler
     that threads the full db through each step's transformation,
     so a single dispatch can update multiple fields in order. Each
     step honours its own `:payload` (literal-only in v1).

   `db-alias` is the group's own db alias; `fields` is the group's
   `:fields` (used to look up `:of-group` for payload re-keying);
   `app-ns` is the root app namespace."
  [{:keys [name] :as action} db-alias fields app-ns]
  (let [ename (cljs.core/name name)
        steps (actions/step-list action)]
    (if (= 1 (count steps))
      (let [{:keys [operation target-field]} (first steps)
            fname        (cljs.core/name target-field)
            of-group     (some (fn [fd]
                                 (when (= target-field (:name fd)) (:of-group fd)))
                               fields)
            of-group-ns  (when of-group (str app-ns "." of-group ".db"))
            [fn-head body-tail] (action->handler operation of-group-ns)]
        (str "(rf/reg-event\n"
             " ::" ename "\n"
             " [rf/trim-v\n"
             "  (rf/path ::" db-alias "/" fname ")]\n"
             fn-head
             body-tail))
      (let [thread (str/join
                    "\n       "
                    (for [s steps]
                      (step->thread-form s db-alias fields app-ns)))]
        (str "(rf/reg-event\n"
             " ::" ename "\n"
             " [rf/trim-v]\n"
             " (fn [db [x]]\n"
             "   (-> db\n"
             "       " thread ")))")))))

(defn- emit-setter-event
  "Emit an auto `<field>-changed` setter event using re-frame-style
   `trim-v` + `path` interceptors. `db-alias` is the group's own db
   alias (since the field is declared on this group).

   Generated form:

     (rf/reg-event
      ::<field>-changed
      [rf/trim-v
       (rf/path ::<ns>/<field>)]
      (fn [_ [new-<field>]]
        new-<field>))"
  [field-kw db-alias]
  (let [fname   (cljs.core/name field-kw)
        ename   (field-setter-event-name field-kw)
        arg-sym (str "new-" fname)]
    (str "(rf/reg-event\n"
         " ::" ename "\n"
         " [rf/trim-v\n"
         "  (rf/path ::" db-alias "/" fname ")]\n"
         " (fn [_ [" arg-sym "]]\n"
         "   " arg-sym "))")))

(defn- generate-events
  "Generate events.cljs for a group. Emits two kinds of handlers:

   1. **Declared actions** (user-authored on the group node) — each is
      emitted with `trim-v` + `path` against its target-field and an
      op-specific body. Actions are the primary way to wire up
      behaviour like add-to-cart / remove-from-cart.
   2. **Auto setter events** — one `::<field>-changed` event per
      declared field, emitted only when no declared action already
      uses that name (declared action wins).

   All actions and setters target fields in THIS group, so the only
   db require is the group's own db namespace.

   Returns nil when the group has neither fields nor declared actions."
  [doc group all-groups app-ns]
  (let [data        (collect-group-data doc (:instance-ids group) all-groups)
        fields      (:fields data)
        actions     (:actions data)
        emitting-ns (:ns-name group)
        ns-path     (str app-ns "." emitting-ns ".events")
        db-ns       (str app-ns "." emitting-ns ".db")
        db-alias    (str emitting-ns ".db")
        declared-names (into #{} (map (comp cljs.core/name :name) actions))
        template?      (template-group? doc group)
        setter-fields  (if template?
                         ;; Template groups own no state — no setters.
                         []
                         (->> fields
                              (remove computed-field?)
                              (remove #(contains? declared-names
                                                  (field-setter-event-name (:name %))))))
        action-forms   (for [a actions] (emit-action-event a db-alias fields app-ns))
        setter-forms   (for [{:keys [name]} setter-fields]
                         (emit-setter-event name db-alias))
        all-forms      (concat action-forms setter-forms)]
    (when (seq all-forms)
      (str "(ns " ns-path "\n"
           "  (:require [" app-ns ".framework :as rf]\n"
           "            [" db-ns " :as " db-alias "]))\n"
           "\n"
           (str/join "\n\n" all-forms)
           "\n"))))

;; --- code generation: root files -------------------------------------------

(defn- generate-root-db
  "Generate the root db.cljs that merges the `default-db` of every
   stateful group. Template groups carry no state, so they're
   excluded from the require list and the merge."
  [doc groups app-ns]
  (let [stateful (filter #(stateful-group? doc %) groups)
        requires (str/join "\n            "
                           (cons (str "[" app-ns ".framework :as rf]")
                                 (for [g stateful]
                                   (str "[" app-ns "." (:ns-name g) ".db :as "
                                        (:ns-name g) ".db]"))))
        merge-args (str/join "\n                       "
                             (for [g stateful]
                               (str (:ns-name g) ".db/default-db")))]
    (str "(ns " app-ns ".db\n"
         "  (:require " requires "))\n"
         "\n"
         "(rf/init-store! (merge " merge-args "))\n")))

(defn- named-descendants
  "Return every node under `node` that carries a non-empty `:name`,
   stopping descent once a named node is hit. Used by the inline walk
   in generate-core to identify decorative-wrapper boundaries: when an
   unnamed container (x-gaussian-blur, x-container, …) is rendered as
   inline hiccup, any named descendant inside it is emitted as a
   `(<ns>.views/<ns>)` call instead of being inlined."
  [node]
  (letfn [(walk [n]
            (for [[_ kids] (m/slot-entries n)
                  child    kids
                  entry    (if (and (:name child) (seq (:name child)))
                             [child]
                             (walk child))]
              entry))]
    (walk node)))

;; --- core.cljs emission helpers ------------------------------------------
;; `generate-core` threads a small context map through these helpers
;; instead of a long arg list. The ctx carries the derived values
;; every helper needs (template-names, owner-idx, name->ns) so the
;; signatures stay readable.

(defn- inline-entry
  "Pre-compute metadata for one unnamed root-order entry. Collects
   the subtree's named descendants (they become sub-group stop-
   points + drive view requires) and the read/payload fields the
   inline hiccup will let-bind through `rf/query`. Keeps the
   shape symmetrical with per-group view metadata in generate-views."
  [doc groups name->ns owner-idx entry]
  (let [node           (m/get-node doc (:id entry))
        nameds         (named-descendants node)
        sg-ids         (into #{} (map :id) nameds)
        nested-groups  (for [n nameds
                             :let [g (first
                                      (filter #(some #{(:id n)}
                                                     (:instance-ids %))
                                              groups))]
                             :when g]
                         g)
        read-fields    (collect-read-bindings node sg-ids)
        payload-fields (collect-trigger-payload-fields node sg-ids)
        explicit       (into {}
                             (for [[f o] (explicit-field-owners node sg-ids)]
                               [f (get name->ns o o)]))
        candidate-let  (distinct (concat read-fields payload-fields))
        field->owner   (into {}
                             (for [f candidate-let
                                   :let [o (or (get explicit f)
                                               (get owner-idx f))]
                                   :when o]
                               [f o]))
        let-fields     (vec (filter field->owner candidate-let))
        field->sym     (into {}
                             (for [f let-fields] [f (cljs.core/name f)]))]
    {:entry         entry
     :node          node
     :sub-group-ids sg-ids
     :nested-groups (vec nested-groups)
     :let-fields    let-fields
     :field->sym    field->sym
     :field->owner  field->owner}))

(defn- require-line
  "Emit one `:require` vector — indented to match the `(ns …
   (:require …))` layout the rest of the generator uses."
  [app-ns alias suffix]
  (str "\n            [" app-ns "." alias suffix " :as " alias suffix "]"))

(defn- core-requires
  "Build the `:require` clauses (newline-joined, leading newlines
   per entry) for `app.core`. Covers views of every root-level
   group, subs for any root-level template-instance iteration, and
   views / subs / events / write-binding aliases for the fields,
   actions, and bindings inside unnamed root-level wrappers."
  [{:keys [app-ns doc groups owner-idx name->ns template-names
           root-groups root-tpl-subs inline-entries]}]
  (let [inline-sub-aliases
        (distinct (for [{:keys [field->owner]} inline-entries
                        owner (distinct (vals field->owner))]
                    (str owner ".subs")))
        nested-view-aliases
        (distinct (for [{:keys [nested-groups]} inline-entries
                        g nested-groups]
                    (:ns-name g)))
        nested-tpl-subs
        (distinct (for [{:keys [nested-groups]} inline-entries
                        g nested-groups
                        :when (contains? template-names (:ns-name g))
                        :let [inst  (m/get-node doc (:id g))
                              sf    (:source-field inst)
                              host  (when-not sf
                                      (stateful-host-for-template
                                       doc groups (:ns-name g)))
                              owner (or (get owner-idx sf)
                                        (:ns-name host))]
                        :when owner]
                    owner))
        inline-action-aliases
        (distinct (for [{:keys [node]} inline-entries
                        ref  (collect-trigger-action-refs node #{})]
                    (action-ref->alias ref)))
        inline-write-aliases
        (distinct (for [{:keys [node sub-group-ids]} inline-entries
                        a (collect-write-binding-aliases
                           node sub-group-ids name->ns owner-idx nil)]
                    a))]
    (str/join ""
              (distinct
               (concat
                (for [g root-groups] (require-line app-ns (:ns-name g) ".views"))
                (for [n root-tpl-subs]       (require-line app-ns n ".subs"))
                (for [n nested-view-aliases] (require-line app-ns n ".views"))
                (for [n nested-tpl-subs]     (require-line app-ns n ".subs"))
                (for [a inline-sub-aliases]    (require-line app-ns a ""))
                (for [a inline-action-aliases] (require-line app-ns a ""))
                (for [a inline-write-aliases]  (require-line app-ns a "")))))))

(defn- core-group-body
  "Body segment for a root-order entry that IS a named group —
   either a singular `(<group>.views/<group>)` call or, when the
   group is a template, a `(for [p (rf/query [<sub>])] …)` loop
   wrapped in a `display: contents` div (see
   `collection-iteration-call` for the wrapper rationale)."
  [entry {:keys [doc owner-idx template-names]}]
  (if-not (contains? template-names (:ns-name entry))
    (str "   (" (:ns-name entry) ".views/" (:ns-name entry) ")")
    (let [inst    (m/get-node doc (:id entry))
          ss      (:source-sub inst)
          sf      (:source-field inst)
          owner   (get owner-idx sf)
          sub-ref (cond
                    ss             (str "::" (action-ref->alias ss) "/"
                                        (name ss))
                    (and sf owner) (str "::" owner ".subs/"
                                        (cljs.core/name sf))
                    :else          "::!no-source-for-template")]
      (str "   [:div {:style \"display: contents\"}\n"
           "    (for [p (rf/query [" sub-ref "])]\n"
           "      (" (:ns-name entry) ".views/" (:ns-name entry) " p))]"))))

(defn- core-inline-body
  "Body segment for a root-order unnamed-wrapper entry — inline
   hiccup, wrapped in a `let` when the subtree has any read /
   payload fields that need `rf/query` bindings. Same let-shape
   `generate-views` uses for named groups, by design."
  [entry inline-entries
   {:keys [doc groups template-names owner-idx name->ns]}]
  (let [{:keys [node sub-group-ids let-fields field->sym field->owner]}
        (first (filter #(= (:id (:entry %)) (:id entry))
                       inline-entries))
        hiccup (node->hiccup-with-events
                {:doc                doc
                 :field->sym         field->sym
                 :sub-group-ids      sub-group-ids
                 :all-groups         groups
                 :template-groups    template-names
                 :field-owner-ns-map owner-idx
                 :tmpl-record-sym    nil
                 :name->ns           name->ns
                 :own-ns-name        nil}
                node nil (if (seq let-fields) 4 3))]
    (if-not (seq let-fields)
      hiccup
      (str "   (let ["
           (str/join "\n         "
                     (for [f let-fields
                           :let [owner (field->owner f)
                                 fname (cljs.core/name f)]]
                       (str fname " (rf/query [::" owner ".subs/" fname "])")))
           "]\n"
           hiccup ")"))))

(defn- core-view-body
  "Emit the `[:div …]` body for `app.core/app`: one segment per
   root-order entry, newline-separated."
  [inline-entries {:keys [root-order] :as ctx}]
  (str/join "\n"
            (for [entry root-order]
              (if (:group? entry)
                (core-group-body entry ctx)
                (core-inline-body entry inline-entries ctx)))))

(defn- generate-core
  "Generate core.cljs — entry point using renderer/mount! with hiccup
   views.

   Root-level template groups iterate by resolving `:source-field` or
   `:source-sub` on the canvas instance (same rule as nested template
   groups). Root-level **unnamed** wrappers (x-container, x-card,
   x-gaussian-blur, …) render as inline hiccup; named descendants
   inside them are recognised and emitted as group-view calls so the
   group's own subtree stays self-contained."
  [doc groups root-order app-ns]
  (let [root-groups    (filter :group? root-order)
        template-names (into #{}
                             (for [g groups :when (template-group? doc g)]
                               (:ns-name g)))
        owner-idx      (field-owner-index doc groups)
        name->ns       (name->ns-name-map doc groups)
        root-tpl-subs  (distinct
                        (for [entry root-groups
                              :when (contains? template-names (:ns-name entry))
                              :let [inst  (m/get-node doc (:id entry))
                                    owner (get owner-idx (:source-field inst))]
                              :when owner]
                          owner))
        inline-entries (vec
                        (for [entry root-order
                              :when (not (:group? entry))]
                          (inline-entry doc groups name->ns owner-idx entry)))
        ctx            {:app-ns         app-ns
                        :doc            doc
                        :groups         groups
                        :root-order     root-order
                        :root-groups    root-groups
                        :root-tpl-subs  root-tpl-subs
                        :template-names template-names
                        :owner-idx      owner-idx
                        :name->ns       name->ns
                        :inline-entries inline-entries}]
    (str "(ns " app-ns ".core\n"
         "  (:require [baredom.exports.all :as baredom]\n"
         "            [" app-ns ".db]\n"
         "            [" app-ns ".framework :as rf]\n"
         "            [" app-ns ".renderer :as renderer]\n"
         (core-requires ctx) "))\n"
         "\n"
         "(defn- app []\n"
         "  [:div\n"
         (core-view-body inline-entries ctx) "])\n"
         "\n"
         "(defn ^:export init []\n"
         "  (baredom/register!)\n"
         "  (renderer/mount! (js/document.getElementById \"app\") app (rf/get-store)))\n")))

(defn- generate-framework
  "Return the framework.cljs source for an exported project. Inlines
   the canonical `bareforge.export.cljs-project.framework` source and
   rewrites the `ns` prefix so the emitted file lives under
   `<app-ns>.framework`. One source of truth: `framework_test`
   doubles as the ground truth for what gets emitted into every
   export."
  [app-ns]
  (str/replace-first framework-template
                     "bareforge.export.cljs-project.framework"
                     (str app-ns ".framework")))

(defn- generate-renderer
  "Return the renderer.cljs source for an exported project. Inlines
   the canonical `bareforge.export.cljs-project.renderer` source and
   rewrites the `ns` prefix so the emitted file lives under
   `<app-ns>.renderer`. One source of truth: the extracted .cljs
   doubles as development reference for the reconciler's semantics
   (stored children, parent-anchored mutations, etc.)."
  [app-ns]
  (str/replace-first renderer-template
                     "bareforge.export.cljs-project.renderer"
                     (str app-ns ".renderer")))

(defn- generate-deps-edn []
  (str "{:paths [\"src\"]\n"
       " :deps  {org.clojure/clojure       {:mvn/version \"1.12.0\"}\n"
       "         org.clojure/clojurescript {:mvn/version \"1.11.132\"}\n"
       "         thheller/shadow-cljs      {:mvn/version \"2.28.18\"}\n"
       "         com.github.avanelsas/baredom {:mvn/version \""
       versions/baredom-version
       "\"}}}\n"))

(defn- generate-shadow-cljs-edn [app-ns port]
  (str "{:deps     true\n"
       " :dev-http {" port " \"public\"}\n"
       " :builds\n"
       " {:app {:target     :browser\n"
       "        :output-dir \"public/js\"\n"
       "        :asset-path \"/js\"\n"
       "        :modules    {:main {:init-fn " app-ns ".core/init}}\n"
       "        :compiler-options {:output-feature-set :es2020}}}}\n"))

(defn- generate-index-html [title]
  (str "<!doctype html>\n"
       "<html lang=\"en\">\n"
       "<head>\n"
       "  <meta charset=\"utf-8\">\n"
       ;; CLJS export self-hosts the shadow-cljs JS bundle; everything
       ;; runs from same-origin. `'unsafe-eval'` is included so
       ;; `shadow-cljs watch` (dev hot-reload) works out of the box —
       ;; the dev runtime relies on `eval` for module loading. For
       ;; production deployments (`shadow-cljs release app`) the
       ;; emitted bundle needs no eval; tighten the script-src by
       ;; dropping `'unsafe-eval'` (and ideally serving the CSP via
       ;; HTTP headers rather than this meta tag).
       "  <meta http-equiv=\"Content-Security-Policy\" content=\""
       "default-src 'self'; "
       "script-src 'self' 'unsafe-inline' 'unsafe-eval'; "
       "style-src 'self' 'unsafe-inline'; "
       "img-src 'self' data:; "
       "font-src 'self' data:; "
       "connect-src 'self' ws: wss:; "
       "object-src 'none'; "
       "base-uri 'self'\">\n"
       "  <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
       "  <title>" title "</title>\n"
       "  <style>\n"
       "    :root { color-scheme: light dark; }\n"
       "    html, body { margin: 0; padding: 0; min-height: 100%; }\n"
       "    body { font-family: system-ui, sans-serif; }\n"
       "    x-theme {\n"
       "      display: block;\n"
       "      min-height: 100vh;\n"
       "      background: var(--x-color-bg);\n"
       "      color: var(--x-color-text);\n"
       "    }\n"
       "    /* Decorative background components shouldn't absorb pointer\n"
       "       events that belong to overlaying interactive UI like\n"
       "       popovers and menus. Children opt back in so the wrapped\n"
       "       content stays clickable. */\n"
       "    x-gaussian-blur,\n"
       "    x-metaball-cursor,\n"
       "    x-neural-glow,\n"
       "    x-liquid-glass { pointer-events: none; }\n"
       "    x-gaussian-blur > *,\n"
       "    x-metaball-cursor > *,\n"
       "    x-neural-glow > *,\n"
       "    x-liquid-glass > * { pointer-events: auto; }\n"
       "    /* Overlays paint above flow siblings regardless of DOM\n"
       "       order. `position: relative` creates a new stacking\n"
       "       context; the z-index ladders so open overlays sit\n"
       "       above resting triggers. */\n"
       "    x-popover, x-menu, x-dropdown, x-context-menu,\n"
       "    x-modal, x-drawer, x-cancel-dialogue {\n"
       "      position: relative;\n"
       "      z-index: 10;\n"
       "    }\n"
       "    x-popover[open], x-menu[open], x-dropdown[open],\n"
       "    x-context-menu[open], x-modal[open], x-drawer[open],\n"
       "    x-cancel-dialogue[open] { z-index: 1000; }\n"
       "  </style>\n"
       "</head>\n"
       "<body>\n"
       "  <x-theme preset=\"default\">\n"
       "    <div id=\"app\"></div>\n"
       "  </x-theme>\n"
       "  <script src=\"js/main.js\"></script>\n"
       "</body>\n"
       "</html>\n"))

(defn- generate-package-json
  "Generate package.json with shadow-cljs as a dev dependency."
  [title]
  (str "{\n"
       "  \"name\": \"" (-> title str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")) "\",\n"
       "  \"version\": \"0.1.0\",\n"
       "  \"private\": true,\n"
       "  \"devDependencies\": {\n"
       "    \"shadow-cljs\": \"^2.28.18\"\n"
       "  }\n"
       "}\n"))

;; --- top-level generate ----------------------------------------------------

(defn generate
  "Generate a complete ClojureScript project from a Bareforge document.

   Options:
     :app-ns    — root namespace (default \"app\")
     :title     — HTML page title (default \"Bareforge Export\")
     :port      — dev server port (default 9000)

   Returns a map of {relative-file-path -> file-content-string}."
  [doc & [{:keys [app-ns title port]
           :or   {app-ns "app" title "Bareforge Export" port 9000}}]]
  (let [{:keys [groups root-order]} (detect-groups doc)
        src-base (str "src/" (kebab->snake app-ns) "/")
        group-files (into {}
                          (for [g groups
                                [suffix content] [["db.cljs"     (generate-db doc g groups app-ns)]
                                                  ["subs.cljs"   (generate-subs doc g groups app-ns)]
                                                  ["events.cljs" (generate-events doc g groups app-ns)]
                                                  ["views.cljs"  (generate-views doc g groups app-ns)]]
                                :when content]
                            [(str src-base (kebab->snake (:ns-name g)) "/" suffix)
                             content]))
        core-file (generate-core doc groups root-order app-ns)]
    (merge
     {"deps.edn"          (generate-deps-edn)
      "shadow-cljs.edn"   (generate-shadow-cljs-edn app-ns port)
      "package.json"      (generate-package-json title)
      "public/index.html" (generate-index-html title)}

     {(str src-base "framework.cljs") (generate-framework app-ns)
      (str src-base "renderer.cljs")  (generate-renderer app-ns)}

     {(str src-base "db.cljs")   (generate-root-db doc groups app-ns)
      (str src-base "core.cljs") core-file}

     group-files)))
