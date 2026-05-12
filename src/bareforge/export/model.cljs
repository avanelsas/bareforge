(ns bareforge.export.model
  "Shared export model. Lowers a Bareforge document into a
   target-agnostic semantic form every exporter consumes: named
   groups, field kinds (scalar / computed / collection), action
   semantics with `:of-group` re-keying, bindings / triggers /
   text-field ownership, and cross-group references like a
   template group's stateful host or a filter-by's of-group
   target.

   This module is the **stable boundary** between Bareforge's
   document model and the per-target code generators under
   `src/bareforge/export/<name>/`. Plugins consume these helpers
   (and, in later phases, a top-level `(lower-document doc opts)`
   fn that returns a composed model map) so every exporter
   interprets fields / computed / filter-by / :of-group / etc.
   identically.

   Everything here is pure: no atom reads, no DOM, no side
   effects. Outputs are plain maps / vectors / keywords."
  (:require [bareforge.doc.actions :as actions]
            [bareforge.doc.model :as m]))

;; --- field-def predicates ------------------------------------------------
;; `computed?` / `collection-field?` are the load-bearing field-def
;; shape predicates. Their definitions live in `bareforge.doc.actions`
;; (single source of truth — the Inspector uses them too); we
;; re-export them here so plugin authors can reach them through the
;; export-model surface without a second import of `doc.actions`.

(def computed?
  "True when a field-def is a computed (derived) field. Computed
   fields have no `default-db` entry and no auto setter — the
   generator emits them as derived subs."
  actions/computed?)

(def collection-field?
  "True when a field-def holds a vector of records of another
   group's shape via `:of-group`."
  actions/collection-field?)

;; --- ns-name derivation + uniqueness -------------------------------------

(def name->ns-segment
  "Convert a user-defined `:name` into a namespace segment. Single
   source of truth lives in `bareforge.doc.actions` so action-ref
   construction (in the doc layer) and `:ns-name` derivation (here)
   produce identical strings — otherwise a `Dashboard` group ends up
   with `app.Dashboard.events` action-refs but `app.dashboard.events`
   file paths."
  actions/name->ns-segment)

(defn unique-ns-name
  "Pick a variant of `ns-name` not in `seen`, appending `_2`,
   `_3`, … on collision. Pure: returns `[picked-name
   seen-with-picked-added]` so the caller can thread a set through
   without mutating."
  [ns-name seen]
  (let [pick (if (contains? seen ns-name)
               (loop [i 2]
                 (let [candidate (str ns-name "_" i)]
                   (if (contains? seen candidate)
                     (recur (inc i))
                     candidate)))
               ns-name)]
    [pick (conj seen pick)]))

;; --- group detection -----------------------------------------------------

(defn- named-node?
  "A node qualifies as a group when it carries a non-empty `:name`
   and isn't root itself."
  [node]
  (and (not= "root" (:id node))
       (:name node)
       (seq (:name node))))

(defn- accumulate-group
  "Pass-1 step: fold `node` into the groups/group-ids/seen
   accumulator. Reuses an existing group entry when another
   instance with the same name already landed; otherwise mints a
   unique ns-name and seeds a fresh entry."
  [{:keys [groups seen] :as acc} node]
  (if-not (named-node? node)
    acc
    (let [ns-name (name->ns-segment (:name node))]
      (if (contains? groups ns-name)
        (-> acc
            (update-in [:groups ns-name :instance-ids] conj (:id node))
            (update :group-ids conj (:id node)))
        (let [[unique seen'] (unique-ns-name ns-name seen)]
          (-> acc
              (assoc-in [:groups unique]
                        {:id           (:id node)
                         :tag          (:tag node)
                         :ns-name      unique
                         :parent       "root"
                         :instance-ids [(:id node)]})
              (update :group-ids conj (:id node))
              (assoc :seen seen')))))))

(defn- instance-id->group
  "Flatten `groups` (map ns-name → entry) into an instance-id →
   entry lookup so root-order resolution is one step per child."
  [groups]
  (into {}
        (for [g   (vals groups)
              iid (:instance-ids g)]
          [iid g])))

(defn detect-groups
  "Walk the document tree and identify UI component groups.

   Rule: a node is a group iff it carries a non-empty `:name`.
   Unnamed containers (x-container, x-card, x-gaussian-blur,
   etc.) are pure decorative / layout wrappers that render inline
   inside the enclosing group's (or the root app's) hiccup.

   Returns a map:
     {:groups      [{:id :tag :ns-name :parent :instance-ids} ...]
      :root-order  [{:id :group? :ns-name} ...]}

   :root-order preserves the slot-order of root's children so
   the top-level app view can render them in the correct sequence."
  [doc]
  (let [root (:root doc)
        {:keys [groups]}
        (reduce accumulate-group
                {:groups {} :group-ids #{} :seen #{}}
                (m/walk-nodes doc))
        id->g      (instance-id->group groups)
        root-kids  (mapcat second (m/slot-entries root))
        root-order (mapv (fn [child]
                           (let [gid (:id child)]
                             (if-let [g (id->g gid)]
                               {:id gid :group? true :ns-name (:ns-name g)}
                               {:id gid :group? false})))
                         root-kids)
        gs (vec (vals groups))]
    {:groups     (if (empty? gs)
                   [{:id "root" :tag (:tag root) :ns-name "main" :parent nil
                     :instance-ids ["root"]}]
                   gs)
     :root-order root-order}))

;; --- per-group data collection -------------------------------------------

(defn collect-node-data
  "Collect fields, actions, bindings, and triggers from a single
   node and its descendants, stopping at sub-group boundaries."
  [node sub-group-ids]
  (let [all-nodes (tree-seq (fn [n] (seq (:slots n)))
                            (fn [n]
                              (for [[_ kids] (:slots n)
                                    child kids
                                    :when (not (contains? sub-group-ids (:id child)))]
                                child))
                            node)]
    {:fields   (or (:fields node) [])
     :actions  (or (:actions node) [])
     :bindings (into []
                     (for [n all-nodes
                           [prop-name {:keys [field direction]}] (:bindings n)]
                       {:prop      prop-name
                        :field     field
                        :direction direction
                        :node-id   (:id n)
                        :tag       (:tag n)}))
     :events   (into []
                     (for [n all-nodes
                           t (:events n)]
                       (assoc t :node-id (:id n) :tag (:tag n))))}))

(defn collect-group-data
  "Collect all fields, actions, bindings, and triggers from all
   instances of a group. Multiple nodes with the same name are
   instances of one group — their data is merged and deduplicated.
   Stops at sub-group boundaries."
  [doc instance-ids all-groups]
  (let [all-group-ids (into #{} (mapcat :instance-ids) all-groups)
        per-instance (keep #(when-let [n (m/get-node doc %)]
                              (collect-node-data n all-group-ids))
                           instance-ids)]
    {:fields   (vec (distinct (mapcat :fields per-instance)))
     :actions  (vec (distinct (mapcat :actions per-instance)))
     :bindings (vec (distinct (mapcat :bindings per-instance)))
     :events   (vec (distinct (mapcat :events per-instance)))}))

;; --- group classification ------------------------------------------------

(defn template-group?
  "True when the group's fields describe a record shape targeted by
   another group's collection field (`:of-group`). Template groups
   own no state — their db file carries specs only."
  [doc group]
  (boolean
   (some (fn [id]
           (when-let [n (m/get-node doc id)]
             (actions/template-group? doc n)))
         (:instance-ids group))))

(defn stateful-group?
  "True when a group owns state — the inverse of template-group?."
  [doc group]
  (not (template-group? doc group)))

;; --- field-owner + name->ns cross-references -----------------------------

(defn field-owner-index
  "Build a map of field-keyword -> owning group's :ns-name by
   scanning every group's declared fields. Used by views to resolve
   payload field refs on triggers.

   When two groups declare the same field name, the LAST one wins
   — callers that care about disambiguation should consult
   `explicit-field-owners` first and fall back to this index."
  [doc all-groups]
  (into {}
        (for [g all-groups
              {:keys [name]} (:fields (collect-group-data doc (:instance-ids g) all-groups))]
          [name (:ns-name g)])))

(defn name->ns-name-map
  "Map from a group's user-facing `:name` (as stored in bindings /
   text-field-owner) to its ns-name, so the generator can translate
   the picked group name back to the compiled namespace identifier."
  [doc all-groups]
  (into {}
        (for [g     all-groups
              :let  [node (m/get-node doc (first (:instance-ids g)))]
              :when (:name node)]
          [(:name node) (:ns-name g)])))

(defn explicit-field-owners
  "Walk `node`'s subtree (stopping at sub-group boundaries) and
   collect field → picked-group-name pairs wherever the user
   explicitly recorded ownership. Two sources: binding entries
   carrying `:owner`, and nodes carrying `:text-field` +
   `:text-field-owner`."
  ([node] (explicit-field-owners node #{}))
  ([node sub-group-ids]
   (let [own   (concat
                (when (and (:text-field node)
                           (:text-field-owner node))
                  [[(:text-field node) (:text-field-owner node)]])
                (for [[_ {:keys [field owner]}] (:bindings node)
                      :when (and field owner)]
                  [field owner]))
         kids  (for [[_ kids] (m/slot-entries node)
                     c        kids
                     :when    (not (contains? sub-group-ids (:id c)))
                     pair     (explicit-field-owners c sub-group-ids)]
                 pair)]
     (into {} (concat own kids)))))

(defn resolve-explicit-field-owners
  "`explicit-field-owners` records user-facing owner *names* on a
   node's subtree; plugins emitting requires need those resolved to
   compiled `:ns-name` segments. This helper composes the two: walk
   the subtree, then map each picked-owner name through `name->ns`.
   Names not present in `name->ns` are passed through unchanged
   (matching the historical fallback at the call sites).

   Returns `{field-kw → ns-name-string}`."
  [node sub-group-ids name->ns]
  (into {}
        (for [[f owner] (explicit-field-owners node sub-group-ids)]
          [f (get name->ns owner owner)])))

;; --- binding + trigger collection ----------------------------------------

(defn collect-read-bindings
  "Collect all read/read-write field references from a node and
   its descendants — both attribute `:bindings` with :read direction
   and `:text-field` values. The enclosing view needs a let-binding
   for each so the generated hiccup can substitute the field symbol."
  ([node] (collect-read-bindings node #{}))
  ([node sub-group-ids]
   (let [own (concat
              (for [[_ {:keys [field direction]}] (:bindings node)
                    :when (contains? #{:read :read-write} direction)]
                field)
              (when-let [tf (:text-field node)] [tf]))
         child-bindings (for [[_ kids] (m/slot-entries node)
                              child kids
                              :when (not (contains? sub-group-ids (:id child)))
                              field (collect-read-bindings child sub-group-ids)]
                          field)]
     (distinct (concat own child-bindings)))))

(defn collect-trigger-payload-fields
  "Every payload field referenced by any trigger in the subtree
   (stopping at sub-group boundaries). Used by generate-views to
   decide which subscriptions to query in the enclosing `let`."
  ([node] (collect-trigger-payload-fields node #{}))
  ([node sub-group-ids]
   (let [own   (for [t (:events node)
                     pe (:payload t)
                     :when (:field pe)]
                 (:field pe))
         kids  (for [[_ ks] (m/slot-entries node)
                     c ks
                     :when (not (contains? sub-group-ids (:id c)))
                     f (collect-trigger-payload-fields c sub-group-ids)]
                 f)]
     (distinct (concat own kids)))))

(defn collect-trigger-action-refs
  "Every fully qualified action-ref keyword referenced in the
   subtree, stopping at sub-group boundaries."
  ([node] (collect-trigger-action-refs node #{}))
  ([node sub-group-ids]
   (let [own (for [t (:events node) :when (:action-ref t)] (:action-ref t))
         kids (for [[_ ks] (m/slot-entries node)
                    c ks
                    :when (not (contains? sub-group-ids (:id c)))
                    r (collect-trigger-action-refs c sub-group-ids)]
                r)]
     (distinct (concat own kids)))))

;; --- sub-group + template resolution -------------------------------------

(defn find-sub-groups
  "Find every node under `node` that is an instance of another
   group, walking the whole subtree (but stopping at each sub-group
   boundary so we don't double-render nested groups). Returns
   entries of `{:id :ns-name :slot-name :source-sub :source-field}`.

   `:source-sub` and `:source-field` are read from the instance node
   itself — they only carry a value on template-instance children
   that the user has bound to a runtime sub or a doc-level
   collection. Plugins emitting template iteration consume them
   directly instead of re-fetching the instance via `m/get-node`."
  [node all-groups]
  (let [group-ids (into #{} (mapcat :instance-ids) all-groups)
        walk (fn walk [n]
               (for [[_ kids] (m/slot-entries n)
                     child kids
                     entry (if (contains? group-ids (:id child))
                             [{:child child :slot-name (first
                                                        (for [[s ks] (m/slot-entries n)
                                                              c ks
                                                              :when (= (:id c) (:id child))]
                                                          s))}]
                             (walk child))]
                 entry))]
    (for [{:keys [child slot-name]} (walk node)]
      (let [g (first (filter #(some #{(:id child)} (:instance-ids %)) all-groups))]
        {:id           (:id child)
         :ns-name      (:ns-name g)
         :slot-name    slot-name
         :source-sub   (:source-sub child)
         :source-field (:source-field child)}))))

(defn stateful-host-for-template
  "Find the `{:ns-name :field-name}` of the stateful group that
   owns the collection field pointing at template-group `tpl-name`
   via `:of-group`. Returns nil if no such host exists."
  [doc all-groups tpl-name]
  (first
   (for [g all-groups
         :let [node (m/get-node doc (first (:instance-ids g)))]
         fd    (:fields node)
         :when (and (collection-field? fd)
                    (= (:of-group fd) tpl-name))]
     {:ns-name    (:ns-name g)
      :field-name (cljs.core/name (:name fd))})))

(defn filter-by-of-group
  "Template group name that owns the record shape for a
   `:filter-by` computed — looked up via the `:of-group` of the
   filtered `:source-field` so a divergent `:of-group` on the
   computed field itself (if a user hand-edits the doc) never
   silently picks a wrong template."
  [{:keys [computed]} doc all-groups]
  (when (= :filter-by (:operation computed))
    (let [sf (:source-field computed)]
      (some (fn [g]
              (let [n (m/get-node doc (first (:instance-ids g)))]
                (some (fn [f]
                        (when (= sf (:name f))
                          (:of-group f)))
                      (:fields n))))
            all-groups))))

(defn resolve-template-source
  "Resolve where a template-instance's records come from. Three
   cases, tried in order:

     1. `:source-sub` set on the instance — explicit runtime sub.
        Returns `{:kind :source-sub :sub source-sub-kw}`.
     2. `:source-field` set on the instance, and the field is owned
        by some group in `field-owner-ns`. Returns
        `{:kind :source-field :owner-ns string :field source-field-kw}`.
     3. Neither set — implicit fallback to the unique stateful host
        whose collection points at this template via `:of-group`.
        Returns `{:kind :auto-host :owner-ns string :field-name string}`.

   `nil` when none of the three resolves (no explicit source, no
   field-owner match, and no auto-host).

   `template-instance` is a map carrying at least `:ns-name`
   (template group's ns-name), `:source-sub`, and `:source-field` —
   the shape `find-sub-groups` returns. Plugins emitting template
   iteration switch on `:kind` to format the appropriate sub-ref
   string for their target."
  [{:keys [ns-name source-sub source-field]} doc all-groups field-owner-ns]
  (cond
    source-sub
    {:kind :source-sub :sub source-sub}

    source-field
    (when-let [owner-ns (get field-owner-ns source-field)]
      {:kind :source-field :owner-ns owner-ns :field source-field})

    :else
    (when-let [host (stateful-host-for-template doc all-groups ns-name)]
      {:kind       :auto-host
       :owner-ns   (:ns-name host)
       :field-name (:field-name host)})))

(defn action-target-of-group-ns
  "Resolve the fully-qualified db namespace for an action step's
   target field's `:of-group`, or nil when the matching field-def
   carries no `:of-group`. Used by plugins emitting collection
   mutations: an `:add` step against a `:cart-items :of-group
   \"cart-item\"` field needs the incoming payload re-keyed into
   `<app-ns>.cart-item.db/*` before it lands in the collection.

   `step` is an action step shaped `{:target-field <kw> …}`,
   `group-fields` is the enclosing group's `:fields` vector,
   `app-ns` is the root app namespace. Returns
   `\"<app-ns>.<of-group>.db\"` or nil."
  [step group-fields app-ns]
  (let [tgt (:target-field step)]
    (some (fn [fd]
            (when (= tgt (:name fd))
              (when-let [og (:of-group fd)]
                (str app-ns "." og ".db"))))
          group-fields)))

;; --- composed lowered representation -------------------------------------

(defn lower-document
  "Compose every per-document fact a plugin needs into one canonical
   value, so generators stop re-deriving them. Pure: returns a map
   shaped as

     {:groups          [<lowered-group> ...]
      :root-order      [{:id :group? :ns-name?} ...]
      :template-names  #{<ns-name> ...}
      :field-owner-ns  {<field-kw> <owner-ns-name>}
      :name->ns-name   {<user-facing-name> <ns-name>}}

   Each lowered-group is the original `detect-groups` entry plus

     {:template?       boolean   ;; cheap (set lookup)
      :data            <collect-group-data result, deduped + cached>}

   so a downstream `(template-group? doc g)` becomes
   `(:template? g)` (one keyword lookup instead of a re-walk per
   instance-id) and a downstream `(collect-group-data doc ...)`
   becomes `(:data g)` (one walk total per group instead of one
   per generator).

   This is the **single canonical lowered representation** plugins
   should consume; the lower-level helpers stay public for cases
   that genuinely need them (e.g. computing one fact about an
   ad-hoc group not in the lowered set), but `lower-document` is
   the right entry point for the per-target codegen pipeline."
  [doc]
  (let [{:keys [groups root-order]} (detect-groups doc)
        template-names (into #{}
                             (keep #(when (template-group? doc %)
                                      (:ns-name %)))
                             groups)
        groups-with-data (mapv (fn [g]
                                 (assoc g
                                        :template?
                                        (contains? template-names (:ns-name g))
                                        :data
                                        (collect-group-data
                                         doc (:instance-ids g) groups)))
                               groups)]
    {:groups         groups-with-data
     :root-order     root-order
     :template-names template-names
     :field-owner-ns (field-owner-index doc groups)
     :name->ns-name  (name->ns-name-map doc groups)}))
