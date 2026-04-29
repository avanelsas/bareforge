(ns bareforge.ui.layers
  "Left-hand layers tree. The document is flattened into a depth-first
   seq of rows (pure, unit-tested) which is then rendered as a flat list
   whose indentation comes from `padding-left`. Click a row to set
   `:selection` on the app state. A single watcher re-renders on any
   document or selection change.

   Drag-to-reparent is a build-order step 9 concern and is not wired
   here — step 7 is selection only."
  (:require [bareforge.dnd.drag :as drag]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.meta.registry :as registry]
            [bareforge.render.canvas :as canvas]
            [bareforge.state :as state]
            [bareforge.util.dom :as u]))

;; --- pure ----------------------------------------------------------------

(defn- label-for [tag]
  (or (:label (registry/get-meta tag)) tag))

(defn flatten-tree
  "Return a depth-first seq of rows for the layers panel. Each row is
   `{:id :tag :label :depth :parent-id :slot :index}`. The document root
   has depth 0, nil parent, and nil slot."
  [doc]
  (letfn [(walk [node depth parent-id slot idx]
            (cons {:id        (:id node)
                   :tag       (:tag node)
                   :label     (or (:name node) (label-for (:tag node)))
                   :depth     depth
                   :parent-id parent-id
                   :slot      slot
                   :index     idx}
                  (mapcat (fn [[slot-name children]]
                            (mapcat (fn [[i c]]
                                      (walk c (inc depth) (:id node) slot-name i))
                                    (map-indexed vector children)))
                          (m/slot-entries node))))]
    (walk (:root doc) 0 nil nil 0)))

;; --- pure: keyboard nav --------------------------------------------------

(defn nav-target
  "Pure: given the canonical id of the currently-selected layer,
   return the id to select for `direction` ∈ #{:prev :next :parent
   :first-child}. Up / Down walk siblings within the same parent
   slot — the natural tree-nav gesture, distinct from depth-first
   list traversal — so stepping into the children is reserved for
   ArrowRight. Returns nil when the move isn't valid (already at
   the edge of a slot, root with no parent, leaf with no children)."
  [current-id doc direction]
  (case direction
    :prev
    (when-let [{:keys [parent-id slot index]} (m/parent-of doc current-id)]
      (when (pos? index)
        (let [siblings (get-in (m/get-node doc parent-id) [:slots slot])]
          (:id (nth siblings (dec index))))))

    :next
    (when-let [{:keys [parent-id slot index]} (m/parent-of doc current-id)]
      (let [siblings (get-in (m/get-node doc parent-id) [:slots slot])
            n        (count siblings)]
        (when (< index (dec n))
          (:id (nth siblings (inc index))))))

    :parent
    (:parent-id (m/parent-of doc current-id))

    :first-child
    (when-let [node (m/get-node doc current-id)]
      (some-> (m/slot-entries node) first second first :id))))

(defn reorder-target
  "Pure: compute the post-removal target index for an Alt-Up / Alt-Down
   reorder of `current-id`. Returns `{:parent-id :slot :index}` or
   nil when the move is out of bounds (already at the edge) or when
   the node has no parent (root)."
  [doc current-id direction]
  (when-let [{:keys [parent-id slot index]} (m/parent-of doc current-id)]
    (let [siblings (get-in (m/get-node doc parent-id) [:slots slot])
          n        (count siblings)
          new-idx  (case direction
                     :up   (dec index)
                     :down (inc index))]
      (when (and (>= new-idx 0) (<= new-idx (dec n)))
        {:parent-id parent-id :slot slot :index new-idx}))))

;; --- effectful -----------------------------------------------------------

(defn- row-el [{:keys [id tag label depth]} selected?]
  (let [row (u/el :div
                  {:class (str "layers-row" (when selected? " is-selected"))
                   :data-bareforge-id        id
                   :data-bareforge-layer-row ""
                   :style  (str "padding-left:" (+ 8 (* depth 14)) "px")}
                  [(u/set-text! (u/el :span {:class "layers-row-label"}) label)
                   (u/set-text! (u/el :span {:class "layers-row-tag"})   tag)])]
    (u/on! row :click
           (fn [^js e]
             (if (.-shiftKey e)
               (state/select-toggle! id)
               (state/select-one! id))))
    ;; Layer rows are also drag sources — pointerdown starts a
    ;; canvas-existing drag for the row's node so the user can
    ;; reposition deep in the tree without needing the canvas hit.
    ;; Root is not draggable.
    (when (not= id "root")
      (u/on! row :pointerdown
             (fn [^js e] (drag/start-from-canvas! e id))))
    row))

(defn- render-tree! [^js host-el doc selection]
  (let [rows        (flatten-tree doc)
        ;; Selection stores raw DOM ids so the canvas overlay can
        ;; highlight the specific clicked clone; template-instance
        ;; clones carry a `__seed<N>` suffix. Layer rows key by the
        ;; canonical doc id (no suffix), so canonicalise the entire
        ;; selection vector and dedupe before deciding which rows to
        ;; mark — otherwise clicking a product card (or any of its
        ;; descendants) shows no highlight in the layers panel.
        selected    (into #{}
                          (comp (map canvas/canonical-node-id) (remove nil?))
                          selection)]
    (.replaceChildren host-el)
    (doseq [r rows]
      (.appendChild host-el (row-el r (contains? selected (:id r)))))))

(defn- on-keydown!
  "Tree-walk keyboard nav. Up/Down move to the previous / next visible
   row, Left selects the parent, Right enters the first child. Alt-Up
   and Alt-Down reorder the selection within its parent slot via
   ops/move. Stops propagation so the document-level shortcut layer
   doesn't also fire (e.g. an arrow key on a free-placed selection
   wouldn't simultaneously nudge and navigate)."
  [^js e]
  (let [k       (.-key e)
        meta?   (or (.-metaKey e) (.-ctrlKey e))
        alt?    (.-altKey e)
        nav     (cond
                  (= k "ArrowUp")    (if alt? :reorder-up   :prev)
                  (= k "ArrowDown")  (if alt? :reorder-down :next)
                  (= k "ArrowLeft")  :parent
                  (= k "ArrowRight") :first-child
                  :else              nil)]
    (when (and nav (not meta?))
      (let [doc      (:document @state/app-state)
            sel      (some-> (state/single-selected-id @state/app-state)
                             canvas/canonical-node-id)]
        (when sel
          (case nav
            :reorder-up
            (when-let [{:keys [parent-id slot index]}
                       (reorder-target doc sel :up)]
              (.preventDefault e)
              (.stopPropagation e)
              (try
                (let [doc' (ops/move doc sel parent-id slot index)]
                  (state/commit! doc'))
                (catch :default _ nil)))

            :reorder-down
            (when-let [{:keys [parent-id slot index]}
                       (reorder-target doc sel :down)]
              (.preventDefault e)
              (.stopPropagation e)
              (try
                (let [doc' (ops/move doc sel parent-id slot index)]
                  (state/commit! doc'))
                (catch :default _ nil)))

            ;; Plain arrow walks: select the resolved id and stop the
            ;; event so it can't bubble up as a nudge or scroll.
            (when-let [target (nav-target sel doc nav)]
              (.preventDefault e)
              (.stopPropagation e)
              (state/select-one! target))))))))

(defn create
  "Build the layers panel. Installs a single watcher that rebuilds the
   tree when :document or :selection changes. UI / theme / mode changes
   do not trigger a rebuild."
  []
  (let [;; tabindex makes the list itself a focus stop so keyboard nav
        ;; can engage without having to click an individual row first.
        list-el (u/el :div {:class "layers-list"
                            :tabindex "0"})
        panel   (u/el :div {:id    "bareforge-layers"
                            :class "panel panel-layers"}
                      [(u/set-text! (u/el :div {:class "layers-label"}) "Layers")
                       list-el])]
    (render-tree! list-el
                  (:document @state/app-state)
                  (:selection @state/app-state))
    (u/on! list-el :keydown on-keydown!)
    (add-watch state/app-state ::layers
               (fn [_ _ old-state new-state]
                 (when (or (not= (:document old-state)  (:document new-state))
                           (not= (:selection old-state) (:selection new-state)))
                   (render-tree! list-el
                                 (:document new-state)
                                 (:selection new-state)))))
    panel))
