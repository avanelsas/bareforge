(ns bareforge.render.canvas
  "Hand-written DOM reconciler. Keeps a JS Map of id→DOM element and a
   mutable JS object holding the last-rendered document so each patch can
   diff against it. The mutable object is not a Clojure atom — it is
   imperative rendering-pipeline bookkeeping local to this namespace, in
   keeping with the effectful-zone rules in CLAUDE.md."
  (:require [bareforge.doc.actions :as actions]
            [bareforge.doc.model :as m]
            [bareforge.meta.hints :as hints]
            [bareforge.meta.registry :as registry]
            [bareforge.render.reconcile :as rec]
            [bareforge.state :as state]
            [clojure.string :as str]))

;; --- internal mutable state -----------------------------------------------

(defonce ^:private node-index (js/Map.))

(defonce ^:private render-state
  #js {:rendered-doc nil
       :pending?     false})

(defn- rendered-doc   [] (unchecked-get render-state "rendered-doc"))
(defn- set-rendered-doc! [doc] (unchecked-set render-state "rendered-doc" doc))
(defn- pending?       [] (unchecked-get render-state "pending?"))
(defn- set-pending!   [v] (unchecked-set render-state "pending?" v))

;; --- design-time template iteration ---------------------------------------
;; A canvas WYSIWYG feature: a template-instance node with a :source-field
;; pointing at a seed-backed collection is expanded to N clones at design
;; time, one per seed. Each clone gets suffix-derived ids (stable across
;; patches) and per-seed text substitution on `:text-field`-bound
;; descendants. Template instances bound to a :source-sub (runtime-only)
;; stay as single placeholders since we cannot resolve a sub at design time.

(defn- seed-records-for
  "Seed records feeding a template-instance node at design time. Returns
   a vector of record maps or nil when nothing is resolvable (e.g. the
   node has a :source-sub instead of a :source-field, or the owning
   field has no :default seeds).

   When the referenced field is a `:filter-by` computed, walks its
   `:source-field` back to the underlying seed-backed collection so
   the designer still sees the template rows (filters are runtime-
   only — at design time we preview all items). Guards against a
   self-referencing chain."
  [doc node]
  (when-let [sf0 (:source-field node)]
    (loop [sf sf0 seen #{}]
      (when-not (contains? seen sf)
        (when-let [owner (actions/field-owner doc sf)]
          (let [fd (first (filter #(= sf (:name %)) (:fields owner)))]
            (cond
              (nil? fd)
              nil

              (actions/filter-by? fd)
              (recur (get-in fd [:computed :source-field])
                     (conj seen sf))

              (seq (:default fd))
              (vec (:default fd)))))))))

(defn- suffix-id [id suffix] (str id "__" suffix))

(defn- clone-subtree
  "Deep-clone `node` and its subtree, rewriting each id with `suffix`
   so the expanded doc has stable, unique ids. Descendants whose
   `:text-field` matches a key in `seed` get their `:text` replaced
   with the seed value — this is the visible WYSIWYG payoff.
   `:source-field` and `:source-sub` are stripped on the clone so the
   reconciler treats the clones as concrete nodes rather than
   re-expanding them recursively."
  [node suffix seed]
  (let [subs-text (when-let [tf (:text-field node)]
                    (get seed tf))]
    (cond-> (-> node
                (assoc :id (suffix-id (:id node) suffix))
                (dissoc :source-field :source-sub))
      subs-text
      (assoc :text (str subs-text))
      (:slots node)
      (assoc :slots
             (reduce-kv (fn [acc slot-name children]
                          (assoc acc slot-name
                                 (mapv #(clone-subtree % suffix seed)
                                       children)))
                        {} (:slots node))))))

(declare walk-expand expand-child)

(defn- walk-expand
  "Walk a node's slots, replacing each child with its expansion (0..N
   sibling clones for template instances, exactly 1 otherwise)."
  [doc node]
  (if-let [slots (:slots node)]
    (assoc node :slots
           (reduce-kv (fn [acc slot-name children]
                        (assoc acc slot-name
                               (vec (mapcat #(expand-child doc %) children))))
                      {} slots))
    node))

(defn- expand-child
  "Expand a single child: process its subtree first, then — if it is a
   seed-backed template instance — produce one clone per seed record.
   Non-template children are returned as a single-element seq."
  [doc child]
  (let [processed (walk-expand doc child)]
    (if-let [seeds (seed-records-for doc processed)]
      (map-indexed (fn [idx seed]
                     (clone-subtree processed (str "seed" idx) seed))
                   seeds)
      [processed])))

(defn- expand-templates
  "Pre-render pass: expand template-instance nodes into per-seed clones
   so the reconciler renders one DOM node per seed. Pure — returns a
   new document with expanded :root."
  [doc]
  (update doc :root #(walk-expand doc %)))

;; --- helpers --------------------------------------------------------------

(defn- walk-ids-set
  "Set of all node ids present in `doc` (or empty set if nil)."
  [doc]
  (if doc
    (into #{} (map :id) (m/walk-nodes doc))
    #{}))

(defn- build-id->node
  "Return a map id → node for every node in the document."
  [doc]
  (if doc
    (into {} (map (juxt :id identity)) (m/walk-nodes doc))
    {}))

;; --- upsert / remove passes ----------------------------------------------

(defn- upsert-node!
  "Reconcile attrs/props/text/layout-style for a single node. If the id
   is new, create the element, stamp it with `data-bareforge-id` so
   drag-drop can map DOM back to document ids, and index it. Does
   NOT touch children — `arrange-slots!` handles DOM ordering in a
   later pass."
  [^js node old-by-id]
  (let [id        (:id node)
        existing  (.get node-index id)
        ;; Raw-html nodes own their children via :inner-html. Structured
        ;; text / child slots are ignored on these nodes so innerHTML
        ;; cannot be clobbered by a stray text node or appendChild pass.
        raw-html? (some? (:inner-html node))]
    (if existing
      (let [old-node (get old-by-id id)]
        (when old-node
          (rec/apply-attrs! existing
                            (rec/attr-diff (:attrs old-node) (:attrs node)))
          (rec/apply-props! existing
                            (rec/prop-diff (:props old-node) (:props node)))
          (when-not raw-html?
            (rec/set-text-child! existing (:text old-node) (:text node)))
          (when raw-html?
            (rec/set-inner-html! existing (:inner-html node)))
          (rec/apply-layout-style! existing (:layout old-node) (:layout node))
          (rec/apply-placement-attr! existing (:layout node))))
      (let [el (rec/create-element (:tag node))]
        (.setAttribute el "data-bareforge-id"  id)
        (.setAttribute el "data-bareforge-tag" (:tag node))
        (when (registry/container? (:tag node))
          (.setAttribute el "data-bareforge-container" "")
          ;; Empty-state CSS reads `data-bareforge-hint` to show a
          ;; per-tag prompt ("Drop nav links / actions" inside an
          ;; empty x-navbar etc.). Tags without a curated hint fall
          ;; back to the generic `<tag>  (empty)` style.
          (when-let [hint (hints/hint-for (:tag node))]
            (.setAttribute el "data-bareforge-hint" hint)))
        (rec/apply-attrs! el (rec/attr-diff {} (:attrs node)))
        (rec/apply-props! el (rec/prop-diff {} (:props node)))
        (when-not raw-html?
          (rec/set-text-child! el nil (:text node)))
        (when raw-html?
          (rec/set-inner-html! el (:inner-html node)))
        (rec/apply-layout-style! el nil (:layout node))
        (rec/apply-placement-attr! el (:layout node))
        (.set node-index id el)))))

(defn element->node-id
  "Given any DOM element, walk up the tree until a node tagged with
   `data-bareforge-id` is found, and return that id. Returns nil when
   no bareforge-rendered ancestor exists. For template-instance
   clones the returned id carries a `__seed<N>` suffix — callers that
   need a doc-resolvable id should pass the result through
   `canonical-node-id`."
  [^js el]
  (when el
    (when-let [^js closest (.closest el "[data-bareforge-id]")]
      (.getAttribute closest "data-bareforge-id"))))

(defn canonical-node-id
  "Strip the trailing `__seed<N>` suffix that `expand-templates`
   appends to every template-clone's id. Selection code needs the
   canonical (doc-resolvable) id so the inspector can look the node
   up in `(:document app-state)` — a clone id isn't in the doc."
  [id]
  (when (string? id)
    (str/replace id #"__seed\d+$" "")))

(defn dom-for-id
  "Return the DOM element for `id` from the render index, or nil. Used
   by the selection overlay to resolve a selection id back to its
   live element. When the exact id isn't in the index (e.g. a layers-
   panel click on a template node whose only rendered instances are
   seed-suffixed clones), falls back to the first `<id>__seed<N>`
   clone in the index so the overlay still has something to highlight."
  ^js [id]
  (when id
    (or (.get node-index id)
        ;; Only try the fallback for canonical ids — if the caller
        ;; already has a clone-suffixed id, the miss is real.
        (when-not (re-find #"__seed\d+$" id)
          (let [prefix (str id "__seed")
                ^js keys-iter (.keys node-index)
                ^js match (loop []
                            (let [^js step (.next keys-iter)]
                              (cond
                                (.-done step) nil
                                (clojure.string/starts-with?
                                 (.-value step) prefix)
                                (.-value step)
                                :else (recur))))]
            (when match (.get node-index match)))))))

(defn- remove-stale!
  "Remove DOM elements (and index entries) for ids present in the
   previously rendered document but absent from the new one."
  [old-doc new-doc]
  (let [new-ids (walk-ids-set new-doc)]
    (doseq [old (when old-doc (m/walk-nodes old-doc))]
      (let [id (:id old)]
        (when (and (not (contains? new-ids id))
                   (.has node-index id))
          (rec/remove-el! (.get node-index id))
          (.delete node-index id))))))

;; --- arrange-slots pass ---------------------------------------------------

(defn- apply-child-placement!
  "Flip the parent to position:relative (via CSS class) when a child
   is rendered as a `:background` or `:free` layer. Child-side
   absolute-position CSS is handled in `upsert-node!` via
   `rec/layout->css`."
  [^js parent-el _child-el placement]
  (when (or (= :background placement) (= :free placement))
    (rec/ensure-parent-positioned! parent-el)))

(defn- arrange-slots!
  "Walk the new document top-down and ensure each parent's children are
   in the correct slot and in document order. `appendChild` on an element
   already in the DOM moves it, so this doubles as a reorder pass.
   Raw-html nodes (nodes with `:inner-html`) own their element's children
   directly and are skipped here so the innerHTML isn't clobbered."
  [node]
  (when-not (:inner-html node)
    (let [^js parent-el (.get node-index (:id node))]
      (doseq [[slot-name children] (:slots node)
              child                children]
        (let [^js child-el (.get node-index (:id child))]
          (rec/set-slot-attr! child-el slot-name)
          (.appendChild parent-el child-el)
          (apply-child-placement! parent-el child-el
                                  (get-in child [:layout :placement]))))
      (doseq [child (mapcat val (:slots node))]
        (arrange-slots! child)))))

;; --- root constraint ------------------------------------------------------

(defn- apply-root-constraint!
  "Apply the canvas width as a max-width + auto margins on the root
   element so the page preview is centered, matching the exported HTML."
  [^js root-el canvas]
  (let [w (:width canvas)]
    (when (pos-int? w)
      (set! (.. root-el -style -maxWidth)    (str w "px"))
      (set! (.. root-el -style -marginLeft)  "auto")
      (set! (.. root-el -style -marginRight) "auto"))))

;; --- public patch / mount -------------------------------------------------

(defn patch!
  "Reconcile the DOM inside `mount-el` against `new-doc`. Safe to call
   with or without a prior render — the first call initialises, subsequent
   calls diff incrementally. Template-instance nodes bound to seed-
   backed collections are expanded to one clone per seed before
   reconciliation so the canvas renders the collection at design time.

   Owns the last-rendered snapshot in `render-state`: each call reads
   the previous one to diff against, then writes the new one for the
   next call to consume."
  [^js mount-el new-doc]
  (let [expanded  (expand-templates new-doc)
        old-doc   (rendered-doc)
        old-by-id (build-id->node old-doc)]
    (doseq [node (m/walk-nodes expanded)]
      (upsert-node! node old-by-id))
    (remove-stale! old-doc expanded)
    (arrange-slots! (:root expanded))
    (let [^js root-el (.get node-index (get-in expanded [:root :id]))]
      (apply-root-constraint! root-el (:canvas expanded))
      (when-not (identical? (.-parentNode root-el) mount-el)
        (.replaceChildren mount-el root-el)))
    (set-rendered-doc! expanded)))

(defn- schedule-patch!
  "Coalesce multiple state changes into a single rAF-scheduled patch."
  [^js mount-el]
  (when-not (pending?)
    (set-pending! true)
    (js/requestAnimationFrame
     (fn []
       (set-pending! false)
       (patch! mount-el (:document @state/app-state))))))

(defn install-watch!
  "Install the render trigger on state/app-state. Fires on document
   changes only — selection, mode, theme, and UI changes are handled
   by their own namespaces in later build-order steps."
  [^js mount-el]
  (add-watch state/app-state ::render
             (fn [_ _ old-state new-state]
               (when (not= (:document old-state) (:document new-state))
                 (schedule-patch! mount-el)))))

(defn clear!
  "Reset all render state so the next `patch!` treats the canvas as a
   fresh mount. Called by `apply-template!` when the entire document is
   replaced, because newly-built documents reuse the same id sequence
   and the reconciler would otherwise try to patch existing elements
   with a different tag."
  []
  (.clear node-index)
  (set-rendered-doc! nil))

(defn mount!
  "Perform the initial render into `mount-el` and install the render
   watch. Safe to call once at app startup."
  [^js mount-el]
  (patch! mount-el (:document @state/app-state))
  (install-watch! mount-el))

(defn unmount!
  "Remove the render watch and clear internal state. Primarily for tests
   and hot reload."
  []
  (remove-watch state/app-state ::render)
  (.clear node-index)
  (set-rendered-doc! nil)
  (set-pending! false))
