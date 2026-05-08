(ns bareforge.ui.inspector.scrub
  "Numeric-drag scrubbing for inspector field rows. Wires a horizontal
   pointer drag on a row's label to scrub its companion numeric input;
   the drag survives the pointer leaving the label (window-level move /
   up listeners) so the gesture continues until release.

   The single `scrub-state` JS object is the global drag-in-flight
   tracker — only one inspector row can be scrubbed at a time, and the
   label captures the pointer so other handlers don't compete.

   Extracted from `bareforge.ui.inspector` so the file's pure widget /
   binding code doesn't carry the scrub helpers along."
  (:require [bareforge.util.dom :as u]))

(defn ^js attach-scrub-meta!
  "Stash a scrub spec on a widget element. `field-row` reads it back
   to wire a horizontal-drag scrubber on the row's label. Spec map:
   `{:read-fn :commit-fn! :step}`. Returns the element for thread-
   friendly use in builder pipelines."
  [^js el spec]
  (set! (.-bareforgeScrub el) spec)
  el)

(defn read-scrub-meta [^js el]
  (when el (.-bareforgeScrub el)))

(defonce ^:private scrub-state
  ;; One global drag-in-flight tracker is enough — only one inspector
  ;; row can be scrubbed at a time, and a label captures the pointer
  ;; so other handlers don't compete.
  #js {:active? false
       :start-x 0
       :start-val 0
       :first? true
       :pointer-id nil
       :label nil
       :input nil
       :commit-fn nil
       :step 1})

(defn- on-scrub-move! [^js e]
  (when (.-active? scrub-state)
    (.preventDefault e)
    (let [dx        (- (.-clientX e) (.-start-x scrub-state))
          step-px   (.-step scrub-state)
          mult      (if (.-shiftKey e) 10 1)
          start-val (.-start-val scrub-state)
          new-val   (+ start-val (* dx step-px mult))
          ;; Snap to an integer when the step is integer-valued; for
          ;; sub-unit steps fall through with the raw float.
          rounded   (if (zero? (mod step-px 1))
                      (js/Math.round new-val)
                      new-val)
          ^js input (.-input scrub-state)
          first?    (.-first? scrub-state)
          commit-fn (.-commit-fn scrub-state)]
      (commit-fn rounded first?)
      (when input (u/set-attr! input :value (str rounded)))
      (when first? (set! (.-first? scrub-state) false)))))

(defn- end-scrub! []
  (let [^js label (.-label scrub-state)
        pid       (.-pointer-id scrub-state)]
    (when (and label pid)
      (try (.releasePointerCapture label pid) (catch :default _ nil)))
    (set! (.-active? scrub-state) false)
    (set! (.-label scrub-state) nil)
    (set! (.-input scrub-state) nil)
    (set! (.-commit-fn scrub-state) nil)
    (set! (.-pointer-id scrub-state) nil)
    (.removeEventListener js/window "pointermove" on-scrub-move!)
    (.removeEventListener js/window "pointerup"   end-scrub!)
    (.removeEventListener js/window "pointercancel" end-scrub!)))

(defn pointer-scrub!
  "Wire pointerdown on `label-el` so a horizontal drag scrubs the
   numeric value of `input-el`. `read-fn` returns the current numeric
   value (nil to suppress the gesture). `commit-fn!` is called as
   `(new-val first?)`; the first call pushes a fresh history entry
   via `state/commit!`, every later call uses `state/commit-coalesced!`
   so the whole drag undoes as a single step. Shift multiplies the
   step by 10."
  [^js label-el ^js input-el read-fn commit-fn! step]
  (.. label-el -classList (add "is-scrubbable"))
  (u/on! label-el :pointerdown
         (fn [^js e]
           (let [v (read-fn)]
             (when (number? v)
               (.preventDefault e)
               (try (.setPointerCapture label-el (.-pointerId e))
                    (catch :default _ nil))
               (set! (.-active? scrub-state)    true)
               (set! (.-start-x scrub-state)    (.-clientX e))
               (set! (.-start-val scrub-state)  v)
               (set! (.-first? scrub-state)     true)
               (set! (.-pointer-id scrub-state) (.-pointerId e))
               (set! (.-label scrub-state)      label-el)
               (set! (.-input scrub-state)      input-el)
               (set! (.-commit-fn scrub-state)  commit-fn!)
               (set! (.-step scrub-state)       (or step 1))
               ;; Window-level move/up means the drag survives the
               ;; pointer leaving the label, which is the natural
               ;; gesture (drag continues until release).
               (.addEventListener js/window "pointermove"   on-scrub-move!)
               (.addEventListener js/window "pointerup"     end-scrub!)
               (.addEventListener js/window "pointercancel" end-scrub!))))))
