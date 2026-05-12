(ns bareforge.dnd.target
  "Pure drop-target classification.

   `bareforge.dnd.drag` is the effectful state-machine driving pointer
   events, DOM walks, ghost / overlay mutations, and the eventual
   commit through `bareforge.doc.ops`. This namespace owns the
   *decision* — given a snapshot of what the cursor's DOM context
   currently looks like (which element is under the cursor, whether
   we're inside a slot-row / layer-row / canvas-element / outside,
   whether the hovered tag is a strip-eligible container, whose
   strips are currently mounted), classify the drop into one of five
   tagged actions:

     - `:slot-row`       a drop targets a specific named slot.
     - `:layer-row`      a drop targets a node in the layers panel.
     - `:canvas-element` a drop targets a canvas node, with a
                          `:before` / `:after` / `:inside` position.
     - `:needs-strips`   the hovered node is a multi-slot container
                          whose strips haven't been mounted yet — the
                          orchestrator must mount the slot-strip
                          overlay and re-snapshot before classifying
                          a final answer.
     - `:invalid`        nothing valid is under the cursor.

   Everything here is pure: no DOM reads, no atom reads, no
   `js/document`. The orchestrator (`bareforge.dnd.drag/resolve-target!`)
   collects the snapshot effectfully and hands it in as a value;
   the result is a value the orchestrator dispatches on. Unit-tested
   in `test/bareforge/dnd/target_test.cljs` against hand-curated
   snapshots covering every branch.")

;; --- position classification --------------------------------------------

(defn classify-position
  "Pure: given a hovered element's `{:top :height}`, the cursor's
   `clientY`, and whether the target is a container, return one of
   `:before`, `:after`, or `:inside`. Containers get a 25/50/25 split
   (top quarter → :before, middle half → :inside, bottom quarter →
   :after); leaves get a 50/50 split with no `:inside` band."
  [{:keys [top height]} cursor-y container?]
  (let [bottom  (+ top height)
        clamped (max top (min cursor-y bottom))
        offset  (- clamped top)
        ratio   (if (pos? height) (/ offset height) 0.5)]
    (if container?
      (cond
        (< ratio 0.25) :before
        (> ratio 0.75) :after
        :else          :inside)
      (if (< ratio 0.5) :before :after))))

;; --- drop-target classification -----------------------------------------

(defn classify-drop-target
  "Pure: classify the drop target for a pointermove gesture.

   `snapshot` shape (collected effectfully by the orchestrator):
     {:hovered-context  :slot-row | :layer-row | :canvas-element | :outside
      :hovered-id       <string> or nil   ;; node id; canonical for canvas
      :hovered-slot     <string> or nil   ;; slot name when context = :slot-row
      :hovered-rect     {:top :height} or nil  ;; for position classification
      :hovered-el       <js dom el> or nil ;; passed through to the result
                                            ;; so the orchestrator doesn't
                                            ;; re-walk the DOM to highlight
      :cursor-y         number
      :node             <doc-node> or nil ;; resolved (and canonicalised)
                                          ;; node for the hovered id
      :container?       boolean           ;; registry/container? on tag
      :strips?          boolean           ;; slot-strips/render-strips? on tag
      :strip-host-id    <string> or nil   ;; whose strips are currently
                                          ;; mounted, or nil if none}

   Returns a tagged map:

     {:kind :slot-row       :slot-node id :slot-name name
            :hovered-el el  :valid? true
            :hide-stale-strips? <bool>}
     {:kind :layer-row      :id id :hovered-el el :position pos
            :valid? true}
     {:kind :canvas-element :id id :hovered-el el :position pos
            :valid? true}
     {:kind :needs-strips   :tag tag :id id :hovered-el el}
     {:kind :invalid        :valid? false}

   The `:needs-strips` precondition is `(and strips? (not= id
   strip-host-id))` — equivalent to the line-432 guard in the legacy
   `resolve-target!`. The orchestrator's strip-mount + re-snapshot
   loop is bounded to two passes by this gate: once strips are
   mounted for `id`, `strip-host-id` equals `id` and the classifier
   routes the second pass to either `:slot-row` (if the cursor is
   now over a strip) or back to `:canvas-element :inside` (if it is
   not — e.g. the cursor is over the container's own bounds but
   outside any strip rect; unusual, but possible)."
  [{:keys [hovered-context hovered-id hovered-slot hovered-rect hovered-el
           cursor-y node container? strips? strip-host-id]}]
  (case hovered-context
    :slot-row
    {:kind               :slot-row
     :slot-node          hovered-id
     :slot-name          hovered-slot
     :hovered-el         hovered-el
     :valid?             true
     ;; Hide stale strips whenever the hovered slot-row belongs to a
     ;; node OTHER than the one whose strips are currently mounted.
     ;; Mirrors lines 403–405 of the legacy resolver: an Inspector
     ;; slot row hovered while canvas strips for a different node
     ;; still cling on screen should knock the strips down.
     :hide-stale-strips? (and (some? strip-host-id)
                              (not= hovered-id strip-host-id))}

    :layer-row
    {:kind       :layer-row
     :id         hovered-id
     :hovered-el hovered-el
     :position   (when (and hovered-rect (some? cursor-y))
                   (classify-position hovered-rect cursor-y (boolean container?)))
     :valid?     true}

    :canvas-element
    (cond
      ;; Stale id: hovered something inside the canvas host but the
      ;; canonical doc node is gone (e.g. just deleted between the
      ;; pointermove fire and the snapshot collection). Treat as
      ;; invalid so the orchestrator clears highlights.
      (nil? node)
      {:kind :invalid :valid? false}

      :else
      (let [position (when (and hovered-rect (some? cursor-y))
                       (classify-position hovered-rect cursor-y
                                          (boolean container?)))]
        (if (and strips?
                 (= :inside position)
                 (not= hovered-id strip-host-id))
          ;; Multi-slot container, strips not yet mounted for THIS
          ;; node → ask the orchestrator to mount and re-classify.
          {:kind       :needs-strips
           :tag        (:tag node)
           :id         hovered-id
           :hovered-el hovered-el}
          {:kind       :canvas-element
           :id         hovered-id
           :hovered-el hovered-el
           :position   position
           :valid?     true})))

    ;; :outside or any unknown context
    {:kind :invalid :valid? false}))
