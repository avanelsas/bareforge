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

;; --- effectful -----------------------------------------------------------

(defn- row-el [{:keys [id tag label depth]} selected?]
  (let [row (u/el :div
                  {:class (str "layers-row" (when selected? " is-selected"))
                   :data-bareforge-id        id
                   :data-bareforge-layer-row ""
                   :style  (str "padding-left:" (+ 8 (* depth 14)) "px")}
                  [(u/set-text! (u/el :span {:class "layers-row-label"}) label)
                   (u/set-text! (u/el :span {:class "layers-row-tag"})   tag)])]
    (u/on! row :click (fn [_] (state/set-selection! {:id id})))
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
        ;; Selection stores the raw DOM id so the canvas overlay can
        ;; highlight the specific clicked clone; template-instance
        ;; clones carry a `__seed<N>` suffix. Layer rows key by the
        ;; canonical doc id (no suffix), so canonicalise here before
        ;; comparing — otherwise clicking a product card or any of
        ;; its descendants shows no highlight in the layers panel.
        selected-id (canvas/canonical-node-id (:id selection))]
    (.replaceChildren host-el)
    (doseq [r rows]
      (.appendChild host-el (row-el r (= (:id r) selected-id))))))

(defn create
  "Build the layers panel. Installs a single watcher that rebuilds the
   tree when :document or :selection changes. UI / theme / mode changes
   do not trigger a rebuild."
  []
  (let [list-el (u/el :div {:class "layers-list"})
        panel   (u/el :div {:id    "bareforge-layers"
                            :class "panel panel-layers"}
                      [(u/set-text! (u/el :div {:class "layers-label"}) "Layers")
                       list-el])]
    (render-tree! list-el
                  (:document @state/app-state)
                  (:selection @state/app-state))
    (add-watch state/app-state ::layers
               (fn [_ _ old-state new-state]
                 (when (or (not= (:document old-state)  (:document new-state))
                           (not= (:selection old-state) (:selection new-state)))
                   (render-tree! list-el
                                 (:document new-state)
                                 (:selection new-state)))))
    panel))
