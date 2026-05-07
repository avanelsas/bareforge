(ns bareforge.ui.align-bar
  "Contextual alignment + distribution toolbar pinned to the
   canvas-host's bottom-center. Hidden by default; revealed by the
   `::align-bar` watcher when the current selection holds at least two
   nodes whose layouts are fully numeric (i.e. free-placement nodes
   that the pure align math can actually move).

   Architecture follows the rest of the project:

   - The math is in `bareforge.doc.align` (pure, fully unit-tested).
   - This namespace is effectful only: it builds DOM, mounts buttons,
     and on click reads the current selection, runs the pure planner,
     and commits a single document update.
   - One `add-watch` keyed `::align-bar`, with the standard early-exit
     guard on the slices it cares about (`:selection` + `:document`)."
  (:require [bareforge.doc.align :as align]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.render.canvas :as canvas]
            [bareforge.state :as state]
            [bareforge.util.dom :as u]))

;; --- pure: selection projection + visibility ---------------------------

(defn- selection-doc-ids
  "Pure: project `app-state` into the de-duplicated, root-stripped,
   canonical id vector that every align action operates on."
  [app-state]
  (->> (state/selected-ids app-state)
       (map #(canvas/canonical-node-id %))
       distinct
       (remove #{"root"})
       (remove nil?)
       vec))

(defn bar-state
  "Pure: project app-state into `{:visible? :can-distribute?}`. The
   bar shows whenever 2+ free-placement nodes are selected; the
   distribute buttons enable separately at 3+."
  [app-state]
  (let [doc   (:document app-state)
        nodes (keep #(m/get-node doc %) (selection-doc-ids app-state))]
    {:visible?        (align/alignable?     (vec nodes))
     :can-distribute? (align/distributable? (vec nodes))}))

;; --- effectful: commit a planner result --------------------------------

(defn- dom-size
  "Live `offsetWidth / offsetHeight` for `id`'s rendered element.
   Returns nil when the element isn't in the index (stale id, or a
   template-clone that never rendered) so the caller can drop the
   entry."
  [id]
  (when-let [^js el (canvas/dom-for-id id)]
    {:width  (.-offsetWidth  el)
     :height (.-offsetHeight el)}))

(defn- selected-rects
  "Read the current selection's free-placement rects. Width / height
   come from the live DOM (`offsetWidth / offsetHeight`) since fresh
   free nodes don't carry explicit `:w` / `:h` in the doc model;
   x / y come from the doc layout (defaulting to 0). Order matches
   the selection."
  []
  (let [s     @state/app-state
        doc   (:document s)
        ids   (selection-doc-ids s)]
    (vec
     (keep
      (fn [id]
        (let [node (m/get-node doc id)]
          (when (and node
                     (= :free (get-in node [:layout :placement])))
            (when-let [size (dom-size id)]
              (align/rect-from-node-and-size (assoc node :id id) size)))))
      ids))))

(defn- commit-positions!
  "Apply a vector of new rects (`{:id :left :top :w :h}`) onto the
   document via `ops/set-layout`, in a single `state/commit!`. Skips
   ops where the position is unchanged so the history doesn't grow
   for no-ops (e.g. clicking Align Left when everything is already
   aligned)."
  [new-rects]
  (let [doc  (:document @state/app-state)
        doc' (reduce
              (fn [d {:keys [id left top]}]
                (let [node (m/get-node d id)
                      cur-x (get-in node [:layout :x])
                      cur-y (get-in node [:layout :y])]
                  (cond-> d
                    (not= cur-x left) (ops/set-layout id :x left)
                    (not= cur-y top)  (ops/set-layout id :y top))))
              doc
              new-rects)]
    (when (not= doc doc')
      (state/commit! doc'))))

(defn align!
  "Align the current selection along `kind` (one of
   `align/alignment-kinds`). No-op when the planner returns the same
   rects as input (already aligned, or fewer than 2 free nodes)."
  [kind]
  (let [rects (selected-rects)]
    (when (>= (count rects) 2)
      (commit-positions! (align/align-rects rects kind)))))

(defn distribute!
  "Distribute the current selection's centers along `axis`
   (`:horizontal` or `:vertical`). No-op for fewer than 3 free nodes."
  [axis]
  (let [rects (selected-rects)]
    (when (>= (count rects) 3)
      (commit-positions! (align/distribute-rects rects axis)))))

;; --- DOM build ---------------------------------------------------------

(defn- tooltip-wrap
  "Wrap `trigger` in a `<span>` carrying a `data-tip` attribute. A
   CSS rule on `.align-bar-tip[data-tip]:hover::after` (in
   `public/index.html`) renders the tip via a pseudo-element with
   `white-space: nowrap`, so the tip is always one line wide — no
   reliance on BareDOM's x-tooltip, which gets squished by the bar's
   inline-flex layout."
  [tip trigger]
  (let [^js s (u/el :span {:class "align-bar-tip" :data-tip tip})]
    (.appendChild s trigger)
    s))

(defn- icon-button
  "Small square button carrying a single character glyph and an
   accessible name. Returns the DOM element."
  [glyph accessible-name handler]
  (let [text (u/set-text! (u/el :span) glyph)]
    (-> (u/el :x-button
              {:variant    "tertiary"
               :size       "sm"
               :label      accessible-name
               :aria-label accessible-name
               :class      "align-bar-button"}
              [text])
        (u/on! :click handler))))

(defn- divider [] (u/el :div {:class "align-bar-divider"}))

(defn- build-bar
  "Construct the bar DOM. Each button is wrapped via `tooltip-wrap`
   so a one-word descriptor surfaces on hover — the unicode glyphs
   alone don't carry enough meaning. A pointerdown listener on the
   bar stops propagation so clicks on the bar don't bubble to the
   canvas's marquee handler (without it, a click would commit an
   empty marquee and clear the selection before the button's `click`
   event fires)."
  []
  (let [bar      (u/el :div {:class "align-bar" :data-hidden ""})
        align-l  (icon-button "⇤" "Align left"               (fn [_] (align! :left)))
        align-cx (icon-button "↔" "Align horizontal centers" (fn [_] (align! :cx)))
        align-r  (icon-button "⇥" "Align right"              (fn [_] (align! :right)))
        align-t  (icon-button "⇡" "Align top"                (fn [_] (align! :top)))
        align-cy (icon-button "↕" "Align vertical centers"   (fn [_] (align! :cy)))
        align-b  (icon-button "⇣" "Align bottom"             (fn [_] (align! :bottom)))
        dist-h   (icon-button "⇿" "Distribute horizontally"  (fn [_] (distribute! :horizontal)))
        dist-v   (icon-button "⇳" "Distribute vertically"    (fn [_] (distribute! :vertical)))]
    (u/on! bar :pointerdown (fn [^js e] (.stopPropagation e)))
    (doseq [c [(tooltip-wrap "Left"    align-l)
               (tooltip-wrap "Center"  align-cx)
               (tooltip-wrap "Right"   align-r)
               (divider)
               (tooltip-wrap "Top"     align-t)
               (tooltip-wrap "Middle"  align-cy)
               (tooltip-wrap "Bottom"  align-b)
               (divider)
               (tooltip-wrap "Spread"  dist-h)
               (tooltip-wrap "Stack"   dist-v)]]
      (.appendChild bar c))
    {:el bar :dist-h dist-h :dist-v dist-v}))

(defn- apply-state!
  "Mirror the derived `bar-state` onto the bar element + distribute
   buttons. Visibility flips via `data-hidden`; distribute buttons
   carry their own `disabled` state when only 2 nodes are selected."
  [{:keys [^js el ^js dist-h ^js dist-v]} {:keys [visible? can-distribute?]}]
  (if visible?
    (.removeAttribute el "data-hidden")
    (.setAttribute    el "data-hidden" ""))
  (u/set-attr! dist-h :disabled (when-not can-distribute? ""))
  (u/set-attr! dist-v :disabled (when-not can-distribute? "")))

(defn install!
  "Mount the align bar into `canvas-host`. Returns the bar element
   so the caller can verify presence in tests / introspection;
   nothing else needs the handle.

   Visibility is selection- and document-driven, so the watcher
   compares `bar-state` of old vs new — early-exit when the derived
   state is unchanged keeps the DOM writes off the hot path."
  [^js canvas-host]
  (let [parts (build-bar)
        ^js bar (:el parts)]
    (.appendChild canvas-host bar)
    (apply-state! parts (bar-state @state/app-state))
    (add-watch state/app-state ::align-bar
               (fn [_ _ old-state new-state]
                 (let [old-b (bar-state old-state)
                       new-b (bar-state new-state)]
                   (when (not= old-b new-b)
                     (apply-state! parts new-b)))))
    bar))
