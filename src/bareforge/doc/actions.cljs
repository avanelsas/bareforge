(ns bareforge.doc.actions
  "Pure helpers for enumerating the dispatchable actions in a document.

   An action is a named event handler that lives in a group's
   `<group>.events` namespace. Two sources produce action entries:

   - **Declared** actions on a group node's `:actions` vector.
   - **Auto-setter** actions derived from each group's `:fields` — every
     stored scalar field `:foo` implicitly exposes a `::foo-changed`
     setter.

   When a declared action shares a name with an auto-setter, the
   declared entry wins (matching the generator's collision rule).

   Used by the inspector to populate trigger action pickers, and by
   tests that want to introspect what a doc dispatches."
  (:require [bareforge.doc.model :as m]
            [clojure.string :as str]))

;; --- name normalisation --------------------------------------------------

(defn name->ns-segment
  "Convert a user-defined `:name` into a namespace segment: lower,
   trim, collapse non-[a-z0-9] runs to single hyphens, trim leading
   and trailing hyphens.

   Lives here so action-ref construction (below) and the export
   model's `:ns-name` derivation share one canonical form. Without
   this, a group named \"Dashboard\" would mint action-refs in the
   `app.Dashboard.events` namespace while every other generator path
   (file paths, ns forms, db aliases) used the lowercased
   `app.dashboard.events` — which is exactly the shadow-cljs
   \"resource does not have expected namespace\" error."
  [n]
  (-> (or n "") str/lower-case str/trim
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))

(defn computed?
  "True when a field-def is a computed (derived) field — i.e. carries
   a `:computed` sub-map declaring its operation and source field.
   Computed fields have no `default-db` entry and no auto setter."
  [field-def]
  (some? (:computed field-def)))

(defn collection-field?
  "True when a field-def holds a vector of records of another group's
   shape — declared by the user via `:of-group`."
  [field-def]
  (and (= :vector (:type field-def))
       (some? (:of-group field-def))))

(defn filter-by?
  "True when a field-def is a `:filter-by` computed field. Shape:
   `{:computed {:operation :filter-by
                :source-field :<coll>
                :filter-spec {:search-field :<str>
                              :match-field  :<field-on-template>
                              :match-kind   :contains-ci}}}`."
  [field-def]
  (= :filter-by (get-in field-def [:computed :operation])))

(defn- group-nodes
  "All named container nodes in the doc — every group is declared by a
   `:name` on a container."
  [doc]
  (for [n (m/walk-nodes doc)
        :when (and (:name n) (seq (:name n)))]
    n))

(defn- all-of-group-targets
  "Set of group-name strings that some other group's collection field
   targets via `:of-group`. A group in this set has its `:fields`
   treated as a record shape, not as state slots."
  [doc]
  (into #{}
        (for [g (group-nodes doc)
              fd (:fields g)
              :when (collection-field? fd)]
          (:of-group fd))))

(defn template-group?
  "True when a group's `:fields` describe a record shape rather than
   state of its own — i.e. some other group's collection field targets
   this group via `:of-group`."
  [doc group-node]
  (contains? (all-of-group-targets doc) (:name group-node)))

(defn field-owner
  "Return the group whose `:fields` declare `fname`, or nil. Used by
   the generator to resolve payload field refs and `:source-field`
   pointers."
  [doc fname]
  (first
   (for [g (group-nodes doc)
         :when (some #(= fname (:name %)) (:fields g))]
     g)))

(defn group-by-name
  "Return the group node whose `:name` equals `gname`, or nil."
  [doc gname]
  (first (filter #(= gname (:name %)) (group-nodes doc))))

(defn- action-ref
  "Build the fully qualified action-ref keyword for a group's action.
   Canonicalises `group-name` via `name->ns-segment` so the namespace
   matches the export pipeline's `:ns-name`-derived file paths and
   `(ns …)` forms."
  [app-ns group-name action-name]
  (keyword (str app-ns "." (name->ns-segment group-name) ".events")
           (name action-name)))

(defn step-list
  "Canonicalise either action shape to a vector of step maps.

   Old single-step shape `{:name :operation :target-field}` reads as
   a one-element list; new multi-step shape `{:name :steps [...]}`
   returns its `:steps` directly. Always returns at least one step.

   Pure: the original action map is never mutated. All consumers
   (inspector picker, code generators, payload-required check) iterate
   via this function so the on-disk representation can drift forward
   without churn at the call sites."
  [action]
  (or (some-> (:steps action) vec)
      [{:operation    (:operation action)
        :target-field (:target-field action)}]))

(defn payload-step?
  "True when a step's operation consumes the trigger payload (i.e.
   `:set`, `:add`, or `:remove`). Useful for trigger UIs that need
   to know whether dispatching the action requires a record arg."
  [step]
  (contains? #{:set :add :remove} (:operation step)))

(defn action-needs-payload?
  "True when ANY step in this action consumes the trigger payload.
   Multi-step actions can mix payload-consuming steps (`:add`) with
   payload-ignoring ones (`:toggle`); the trigger only needs to
   provide a payload if at least one step actually uses it."
  [action]
  (boolean (some payload-step? (step-list action))))

(defn- declared-actions
  "Action entries contributed by a group's `:actions` vector. Each
   entry exposes `:steps` (canonical) so callers iterate the same way
   regardless of single- vs multi-step authoring."
  [app-ns group-node]
  (let [gname (:name group-node)]
    (for [{:keys [name] :as a} (:actions group-node)]
      {:group-name   gname
       :action-name  name
       :action-ref   (action-ref app-ns gname name)
       :steps        (step-list a)
       :source       :declared})))

(defn- auto-setter-actions
  "The implicit `::<field>-changed` setters — one per stored field on
   a stateful group. Skipped for:
   - template groups (fields describe a record shape, not state)
   - computed fields (derived, not stored)
   - setters whose name collides with a declared action

   Collection fields (`:of-group` vectors) DO get auto setters — the
   setter replaces the whole vector wholesale, while :add / :remove
   actions handle incremental updates."
  [app-ns doc group-node declared-names]
  (when-not (template-group? doc group-node)
    (let [gname (:name group-node)]
      (for [{:keys [name] :as fd} (:fields group-node)
            :when (not (computed? fd))
            :let  [setter-kw (keyword (str (cljs.core/name name) "-changed"))]
            :when (not (contains? declared-names setter-kw))]
        {:group-name   gname
         :action-name  setter-kw
         :action-ref   (action-ref app-ns gname setter-kw)
         :steps        [{:operation :set :target-field name}]
         :source       :auto-setter}))))

(defn all-actions
  "Every dispatchable action in `doc`, both declared and auto-setters.
   `app-ns` defaults to `\"app\"` to match the export pipeline's default.

   Returns a vector of maps:

     {:group-name   \"cart\"
      :action-name  :add-to-cart
      :action-ref   :app.cart.events/add-to-cart
      :steps        [{:operation :add :target-field :cart-items}]
      :source       :declared}"
  ([doc] (all-actions doc "app"))
  ([doc app-ns]
   (into []
         (for [g (group-nodes doc)
               :let [declared (declared-actions app-ns g)
                     taken    (set (map :action-name declared))]
               entry (concat declared
                             (auto-setter-actions app-ns doc g taken))]
           entry))))

(defn field-groups-for-picker
  "Return the doc's declared fields grouped by owning group, ordered
   for presentation in a payload picker: the enclosing group first
   (labelled `<name> (this group)`), then every other group in
   document order.

   Returns a vector of maps:

     [{:owner-name \"product\"
       :label      \"product (this group)\"
       :enclosing? true
       :fields     [{:name :id :type :number}]}
      {:owner-name \"cart\"
       :label      \"cart\"
       :enclosing? false
       :fields     [{:name :cart-count ...}]}]

   `enclosing-name` identifies the group the trigger sits in (nil if
   unknown — in which case no group is marked as enclosing)."
  [doc enclosing-name]
  (let [groups    (group-nodes doc)
        enclosing (filter #(= enclosing-name (:name %)) groups)
        others    (remove #(= enclosing-name (:name %)) groups)]
    (vec
     (for [g (concat enclosing others)
           :let [enclosing? (= enclosing-name (:name g))]]
       {:owner-name (:name g)
        :label      (if enclosing?
                      (str (:name g) " (this group)")
                      (:name g))
        :enclosing? enclosing?
        :fields     (vec (:fields g))}))))
