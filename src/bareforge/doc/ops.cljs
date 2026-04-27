(ns bareforge.doc.ops
  (:refer-clojure :exclude [remove])
  (:require [bareforge.doc.ids :as ids]
            [bareforge.doc.model :as m]
            [bareforge.doc.sanitize :as sanitize]))

(defn- vec-insert [v idx x]
  (let [v   (or v [])
        idx (max 0 (min idx (count v)))]
    (into (conj (subvec v 0 idx) x) (subvec v idx))))

(defn- vec-remove [v idx]
  (into (subvec v 0 idx) (subvec v (inc idx))))

(defn- slot-vec-path [parent-path slot-name]
  (conj parent-path :slots slot-name))

(defn insert
  "Insert `node` under parent-id at slot-name/idx. Assigns a fresh id to the
   inserted node (overwriting any :id on the passed node). Returns
   {:doc doc' :id new-id}. Throws if parent is not found."
  [doc parent-id slot-name idx node]
  (let [parent-path (m/path-to doc parent-id)]
    (when-not parent-path
      (throw (ex-info "insert: parent not found" {:parent-id parent-id})))
    (let [[new-id next-id] (ids/gen (:next-id doc 0))
          node'            (assoc node :id new-id)]
      {:doc (-> doc
                (assoc :next-id next-id)
                (update-in (slot-vec-path parent-path slot-name)
                           (fnil vec-insert []) idx node'))
       :id  new-id})))

(defn insert-new
  "Convenience: create a new node via model/make-node and insert it. Returns
   {:doc doc' :id new-id}."
  ([doc parent-id slot-name idx tag]
   (insert-new doc parent-id slot-name idx tag nil))
  ([doc parent-id slot-name idx tag overrides]
   (insert doc parent-id slot-name idx (m/make-node nil tag overrides))))

(defn remove
  "Remove the node with the given id (and its subtree). Root cannot be
   removed. Throws if the node is missing."
  [doc id]
  (let [node-path (m/path-to doc id)]
    (cond
      (nil? node-path)
      (throw (ex-info "remove: node not found" {:id id}))

      (= node-path [:root])
      (throw (ex-info "remove: cannot remove root" {:id id}))

      :else
      (let [idx        (peek node-path)
            slot-path* (vec (butlast node-path))]
        (update-in doc slot-path* vec-remove idx)))))

(defn move
  "Move an existing node to a new parent/slot/idx. Moving a node under its
   own subtree is an error. Note: when moving within the same slot, the
   effective index is computed *after* removal — callers should reason about
   indices post-remove, not pre-remove."
  [doc id new-parent-id new-slot new-idx]
  (let [subtree (m/subtree-ids doc id)]
    (when (or (nil? subtree)
              (contains? subtree new-parent-id))
      (throw (ex-info "move: invalid move"
                      {:id id :new-parent new-parent-id}))))
  (let [node     (m/get-node doc id)
        removed  (remove doc id)
        pt       (m/path-to removed new-parent-id)]
    (when-not pt
      (throw (ex-info "move: new parent not found"
                      {:new-parent new-parent-id})))
    (update-in removed (slot-vec-path pt new-slot)
               (fnil vec-insert []) new-idx node)))

(defn- at [doc id]
  (or (m/path-to doc id)
      (throw (ex-info "node not found" {:id id}))))

(defn set-attr
  "Set an attr value, dropping unsafe URL schemes silently. URL-typed
   keys (`href`, `src`, `xlink:href`, …) with values like
   `javascript:…` are rejected — the attr is left at its previous
   value rather than written. Non-URL keys pass through unchanged."
  [doc id k v]
  (if (and (sanitize/url-attr? k) (string? v) (not (sanitize/safe-url? v)))
    doc
    (assoc-in doc (conj (at doc id) :attrs k) v)))

(defn unset-attr [doc id k]   (update-in doc (conj (at doc id) :attrs) dissoc k))
(defn set-prop   [doc id k v] (assoc-in  doc (conj (at doc id) :props k) v))
(defn unset-prop [doc id k]   (update-in doc (conj (at doc id) :props) dissoc k))
(defn set-text   [doc id t]   (assoc-in  doc (conj (at doc id) :text) t))

(defn set-inner-html
  "Set raw HTML content on a node's default slot (used by components
   that opt in via `:raw-html-slot?`, like x-icon). Blank strings are
   normalised to nil so the field disappears when the editor is
   cleared. The value is run through `sanitize/sanitize-svg-fragment`
   so a paste from a hostile icon site can't ship `<script>` /
   `on*=` / `javascript:` payloads through the inspector."
  [doc id s]
  (let [v (when (and (string? s) (not= "" s))
            (sanitize/sanitize-svg-fragment s))]
    (assoc-in doc (conj (at doc id) :inner-html) v)))

(defn set-layout [doc id k v] (assoc-in  doc (conj (at doc id) :layout k) v))

(defn set-css-var
  "Set or unset a per-instance CSS custom property override on a node.
   `var-name` is the full custom property string like \"--x-button-fg\".
   Passing nil for `value` removes the entry."
  [doc id var-name value]
  (let [p (at doc id)]
    (if (nil? value)
      (update-in doc (conj p :layout :css-vars) dissoc var-name)
      (assoc-in  doc (conj p :layout :css-vars var-name) value))))

;; --- component naming ----------------------------------------------------

;; Every named group auto-gets this locked id field. Bareforge owns it:
;; the inspector renders it read-only, and remove-field refuses to drop
;; it. When the user clears the name, the id field goes away with it.
(def ^:private id-field-def
  {:name :id :type :number :default 0 :locked? true})

(defn- drop-locked-id [fields]
  (vec (clojure.core/remove
        #(and (:locked? %) (= :id (:name %)))
        fields)))

(defn- ensure-locked-id
  "Idempotent: guarantees exactly one locked ::id field at the head of
   the fields vector. If an unlocked :id field was present in an older
   doc, it gets replaced — not duplicated."
  [fields]
  (let [without-any-id (vec (clojure.core/remove #(= :id (:name %)) fields))]
    (into [id-field-def] without-any-id)))

(defn set-name
  "Set or clear the user-defined component name. Nil or blank clears.

   Named = group. The first time a component gets a non-blank name we
   prepend the locked ::id field to its :fields so the group has an id
   to be retrieved by. Clearing the name removes that locked field."
  [doc id n]
  (let [v     (when (and (string? n) (not= "" n)) n)
        path  (at doc id)
        node  (get-in doc path)]
    (cond-> doc
      true      (assoc-in (conj path :name) v)
      v         (update-in (conj path :fields) ensure-locked-id)
      (not v)   (update-in (conj path :fields) drop-locked-id)
      ;; If the fields vector ends up empty and the name is cleared,
      ;; drop the key entirely so the node reverts to its unnamed shape.
      (and (not v)
           (empty? (drop-locked-id (:fields node))))
      (update-in path dissoc :fields))))

;; --- data bindings -------------------------------------------------------

(defn set-binding
  "Bind a property to a store field. `binding` is a map with
   :field (keyword), :direction (:read/:write/:read-write),
   and optional :owner (node-id string or \"app\")."
  [doc id prop-name binding]
  (assoc-in doc (conj (at doc id) :bindings prop-name) binding))

(defn unset-binding
  "Remove a property binding."
  [doc id prop-name]
  (update-in doc (conj (at doc id) :bindings) dissoc prop-name))

;; --- triggers (DOM event → action dispatch) ------------------------------

(defn add-trigger
  "Add a trigger binding to a node. `t` is a map with :trigger (DOM event
   name string), :action-ref (qualified keyword of the action to fire),
   and optional :payload (vector of {:field :owner} entries whose values
   are resolved at render time and passed positionally to the action)."
  [doc id t]
  (update-in doc (conj (at doc id) :events)
             (fnil conj []) t))

(defn remove-trigger
  "Remove the trigger at `idx` from a node's :events vector."
  [doc id idx]
  (let [p (conj (at doc id) :events)]
    (update-in doc p
               (fn [v] (into (subvec v 0 idx) (subvec v (inc idx)))))))

;; --- group-level field + action definitions ------------------------------

(defn add-field
  "Add a field definition to a node's :fields vector.
   `field-def` is `{:name :type :default}`."
  [doc id field-def]
  (update-in doc (conj (at doc id) :fields)
             (fnil conj []) field-def))

(defn remove-field
  "Remove the field definition at `idx` from a node's :fields vector.
   Locked fields (the auto-inserted ::id on every named group) are owned
   by Bareforge and cannot be removed through this path."
  [doc id idx]
  (let [p     (conj (at doc id) :fields)
        field (get-in doc (conj p idx))]
    (when (:locked? field)
      (throw (ex-info "remove-field: cannot remove locked field"
                      {:id id :idx idx :field (:name field)})))
    (update-in doc p
               (fn [v] (into (subvec v 0 idx) (subvec v (inc idx)))))))

(defn add-action
  "Add an action declaration to a node's :actions vector. An action is
   a named event handler that mutates a field in the same group.
   `action` is `{:name :operation :target-field}`."
  [doc id action]
  (update-in doc (conj (at doc id) :actions)
             (fnil conj []) action))

(defn remove-action
  "Remove the action at `idx` from a node's :actions vector."
  [doc id idx]
  (let [p (conj (at doc id) :actions)]
    (update-in doc p
               (fn [v] (into (subvec v 0 idx) (subvec v (inc idx)))))))

;; --- template instance wiring -------------------------------------------

(defn set-text-field
  "Bind a node's text slot to a field keyword on the enclosing group's
   record shape. `owner-name` is the name of the group the field came
   from — stored as :text-field-owner so disambiguation survives
   save/load when two groups declare the same field name. Passing
   nil for field-kw clears both keys."
  ([doc id field-kw] (set-text-field doc id field-kw nil))
  ([doc id field-kw owner-name]
   (let [p (at doc id)]
     (if (nil? field-kw)
       (-> doc
           (update-in p dissoc :text-field)
           (update-in p dissoc :text-field-owner))
       (cond-> doc
         true
         (assoc-in (conj p :text-field) field-kw)
         owner-name
         (assoc-in (conj p :text-field-owner) owner-name)
         (nil? owner-name)
         (update-in p dissoc :text-field-owner))))))

(defn set-source-sub
  "Point a template instance at an external sub for its records. The
   parent's view generator iterates the named sub and renders this
   node once per record. Passing nil clears the override."
  [doc id sub-kw]
  (let [p (at doc id)]
    (if (nil? sub-kw)
      (update-in doc p dissoc :source-sub)
      (assoc-in doc (conj p :source-sub) sub-kw))))

(defn set-source-field
  "Point a template instance at a collection field on an ancestor group.
   Matches by field keyword — the generator resolves which group owns
   the field via the field-owner index. Passing nil clears it."
  [doc id field-kw]
  (let [p (at doc id)]
    (if (nil? field-kw)
      (update-in doc p dissoc :source-field)
      (assoc-in doc (conj p :source-field) field-kw))))

;; --- field-level edits --------------------------------------------------

(defn- field-index
  "Index of the field named `fname` inside the :fields vector on node
   `id`. Throws when the field isn't found."
  [doc id fname]
  (let [fields (get-in doc (conj (at doc id) :fields))
        idx    (first (keep-indexed (fn [i f] (when (= fname (:name f)) i))
                                    fields))]
    (or idx
        (throw (ex-info "field not found" {:id id :field fname})))))

(defn set-of-group
  "Set (or clear) `:of-group` on a vector field. Declares that the
   field holds records of shape `<group-name>`. Passing nil clears."
  [doc id fname group-name]
  (let [p   (at doc id)
        idx (field-index doc id fname)
        v   (when (and (string? group-name) (not= "" group-name)) group-name)]
    (if v
      (assoc-in  doc (conj p :fields idx :of-group) v)
      (update-in doc (conj p :fields idx) dissoc :of-group))))

(defn set-field-default
  "Replace the `:default` on the field named `fname` with `v`. Used by
   the inspector's seed-records editor to commit changes to a
   collection field's default vector."
  [doc id fname v]
  (let [p   (at doc id)
        idx (field-index doc id fname)]
    (assoc-in doc (conj p :fields idx :default) v)))
