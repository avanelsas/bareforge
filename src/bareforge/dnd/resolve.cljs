(ns bareforge.dnd.resolve
  "Pure drop / move planning. Given a snapshot of the live drag state
   (a plain Clojure map produced by `dnd/drag`) plus the current
   document, returns the data the commit step needs — parent-id,
   slot, index, layout overrides, snap selection. Holds zero JS
   interop, zero atom reads, zero DOM lookups: every input arrives
   as a value across the boundary, every output is a value the
   effectful layer applies.

   Snapshot keys consumed here:
     :source-tag        — palette tag string (palette drops only)
     :source-node-id    — existing node id   (canvas moves only)
     :slot-target-node  — id of the slot row under the cursor, or nil
     :slot-target-name  — slot name on that row, or nil
     :target-id         — id of the canvas/layer node under the cursor
     :target-position   — :before | :after | :inside | nil
     :start-x :start-y  — pointerdown position (free-drag math only)
     :free-initial-x :free-initial-y
                        — pre-drag layout position (free-drag math only)"
  (:require [bareforge.doc.model :as m]
            [bareforge.meta.placement :as placement]
            [bareforge.ui.palette :as palette]))

(defn before-after-target
  "Pure: build a `{:parent-id :slot :index}` map for inserting
   `:before` or `:after` an existing canvas node. Returns nil when
   the target is the root (no parent) or the id is unknown."
  [doc target-id position]
  (when-let [info (m/parent-of doc target-id)]
    (case position
      :before info
      :after  (update info :index inc)
      nil)))

(defn resolve-insertion-target
  "Pure: derive `{:parent-id :slot :index}` from a drag-state
   snapshot. The snapshot's slot-target fields take precedence over
   its canvas-hover fields, mirroring the cursor-resolution order
   the drag layer applies. Falls back to `palette/insertion-target`
   for ambiguous canvas hovers (root targets, no clear before/after)."
  [doc {:keys [slot-target-node slot-target-name
               target-id target-position]}]
  (cond
    slot-target-node
    (let [node     (m/get-node doc slot-target-node)
          children (get-in node [:slots slot-target-name] [])]
      {:parent-id slot-target-node
       :slot      slot-target-name
       :index     (count children)})

    (and target-id (#{:before :after} target-position))
    (or (before-after-target doc target-id target-position)
        ;; Root has no parent; degrade to the palette-style fallback.
        (palette/insertion-target doc target-id))

    :else
    (palette/insertion-target doc target-id)))

(defn plan-drop
  "Pure: plan a palette-source drop. Returns
   `{:parent-id :slot :index :tag :overrides}`, ready to feed into
   `ops/insert-new`. Encodes the snap precedence:

     1. `:background` — index 0 in the cursor-targeted slot, with
        `[:layout :placement] :background` in the overrides. Painting
        order is set by the stylesheet, not DOM order.
     2. `:top-full-width` / `:bottom-full-width` — `placement/apply-snap`
        redirects the insertion target to root and adds
        `:layout {:width \"100%\"}`.
     3. Default — cursor-targeted slot, no override.

   Snap fires at drop time only; once placed, the node is a normal
   document node and the user can reparent, resize, and edit it
   without any snap-back."
  [doc {:keys [source-tag] :as snapshot}]
  (let [hint        (:hint (placement/hint-for source-tag))
        background? (= :background hint)
        base        (resolve-insertion-target doc snapshot)
        snap        (when-not background?
                      (placement/apply-snap hint doc base))
        target      (cond
                      background?  (assoc base :index 0)
                      (some? snap) (:target snap)
                      :else        base)
        overrides   (cond-> (palette/seed-for-tag source-tag)
                      background?
                      (assoc-in [:layout :placement] :background)

                      (some? snap)
                      (update :layout merge (:layout snap)))]
    (assoc target :tag source-tag :overrides overrides)))

(defn plan-move
  "Pure: plan a canvas-source move. Returns
   `{:src-id :parent-id :slot :index}`, ready to feed into `ops/move`."
  [doc {:keys [source-node-id] :as snapshot}]
  (assoc (resolve-insertion-target doc snapshot)
         :src-id source-node-id))

(defn plan-free-move
  "Pure: compute the new `[:layout :x]` / `[:layout :y]` for a
   free-drag commit. `cursor-x` / `cursor-y` are the pointerup
   position; the snapshot supplies the pointerdown position
   (`:start-x` / `:start-y`) and the pre-drag layout position
   (`:free-initial-x` / `:free-initial-y`). `zoom` is the canvas
   viewport zoom factor — cursor deltas are in viewport (post-scale)
   pixels, so we divide by `zoom` to get document-space deltas before
   adding to the unscaled initial layout coordinates. Returns
   `{:src-id :x :y}`."
  [{:keys [source-node-id start-x start-y
           free-initial-x free-initial-y]}
   cursor-x cursor-y zoom]
  (let [z (or zoom 1)]
    {:src-id source-node-id
     :x      (+ free-initial-x (/ (- cursor-x start-x) z))
     :y      (+ free-initial-y (/ (- cursor-y start-y) z))}))
