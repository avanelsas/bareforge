(ns bareforge.render.selection
  "Canvas selection overlay. A pool of thin, absolutely-positioned
   `<div>` siblings of the rendered tree inside the canvas host is
   repositioned on every selection / document change to draw a 1 px
   border *around* every currently-selected element. Overlays never
   modify the selected elements' own CSS, so they cannot interact
   with the user's design (no box-shadow inflation, no layout shift).

   Coordinates are computed relative to the canvas host's padding box,
   which is also the containing block for the overlays (canvas-host has
   `position: relative`). Because overlays are children of the same
   scrollable container as the rendered content, scrolling moves both
   in lockstep — no scroll listener is needed.

   The first overlay in the pool (the 'primary') also carries 8
   resize handles as children. Which handles are interactive depends
   on the selected node's placement: `:free` nodes get the full 8
   (updates `:layout :x :y :w :h`); `:flow` nodes get only E/S/SE
   (grow the width/height axis, updating `:layout :width :height` as
   CSS length strings); `:background` nodes and the root get none.
   Resize is single-select only: when more than one node is selected
   the primary overlay's `data-resize-mode` attribute is cleared so
   the handles stay hidden. Live visual feedback writes directly to
   the element's inline style during drag, then a single commit at
   pointerup keeps the undo history clean."
  (:require [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.meta.probes :as probes]
            [bareforge.render.canvas :as canvas]
            [bareforge.state :as state]))

;; --- pure ----------------------------------------------------------------

(def handles
  "The eight resize handle directions, in document order."
  [:nw :n :ne :e :se :s :sw :w])

(def ^:private min-size 10)

(defn compute-resize-dims
  "Pure: given the handle direction, cursor delta `(dx, dy)`, and the
   pre-drag `{:x :y :w :h}` snapshot, return the new
   `{:x :y :w :h}`. Corners update two axes at once; edges update
   one. `:nw :n :ne` shift the y/h axis; `:nw :w :sw` shift the x/w
   axis; the opposite sides only grow."
  [handle dx dy {:keys [x y w h]}]
  (case handle
    :nw {:x (+ x dx) :y (+ y dy) :w (- w dx) :h (- h dy)}
    :n  {:x x       :y (+ y dy) :w w       :h (- h dy)}
    :ne {:x x       :y (+ y dy) :w (+ w dx) :h (- h dy)}
    :e  {:x x       :y y       :w (+ w dx) :h h}
    :se {:x x       :y y       :w (+ w dx) :h (+ h dy)}
    :s  {:x x       :y y       :w w       :h (+ h dy)}
    :sw {:x (+ x dx) :y y       :w (- w dx) :h (+ h dy)}
    :w  {:x (+ x dx) :y y       :w (- w dx) :h h}))

(defn clamp-resize-dims
  "Pure: clamp a resize result to `min-size`. When the user drags a
   north/west-ish handle past the opposite edge, freeze the shrinking
   axis at `min-size` *and* the matching x / y so the box does not
   flip inside out."
  [handle dims {sx :x sy :y sw :w sh :h}]
  (let [{:keys [x y w h]} dims
        shrinks-x? (contains? #{:nw :w :sw} handle)
        shrinks-y? (contains? #{:nw :n :ne} handle)
        [w' x'] (if (< w min-size)
                  [min-size (if shrinks-x? (+ sx (- sw min-size)) x)]
                  [w x])
        [h' y'] (if (< h min-size)
                  [min-size (if shrinks-y? (+ sy (- sh min-size)) y)]
                  [h y])]
    {:x x' :y y' :w w' :h h'}))

(defn resize-mode-for-node
  "Pure: decide which resize mode to use for the given node, or nil
   if the node shouldn't show handles at all. `:background` is nil
   (the placement's whole job is to fill its parent). `:free` gets
   the full 8-handle treatment. Everything else (including
   nil/:flow) falls through to `:flow`, which exposes E/S/SE only
   and grows `:width / :height` instead of moving x/y."
  [node]
  (when node
    ;; Overlay components (sidebar/drawer/modal/popover) size their
    ;; panel from CSS custom properties, not from flow width/height —
    ;; flow-resize handles would write `:layout :width/:height` onto the
    ;; host and change nothing visible. Suppress handles for them; they
    ;; are exactly the tags carrying a selection probe.
    (when-not (probes/selection-selector (:tag node))
      (case (get-in node [:layout :placement])
        :background nil
        :free       :free
        :flow))))

(def ^:private flow-handles #{:e :s :se})

(defn resize-mode-handle-allowed?
  "Pure: does the given resize mode allow the named handle direction?
   `:free` allows all eight; `:flow` only the three growth directions
   (E, S, SE); nil disallows everything."
  [mode handle]
  (case mode
    :free true
    :flow (contains? flow-handles handle)
    false))

(defn overlay-rect
  "Compute `{:left :top :width :height}` for the overlay so that it
   sits exactly around `el-rect` within `host-rect`'s content frame.
   Inputs are plain maps with `:left :top :width :height`. The host
   scroll offsets are added so the result is in the host's content
   coordinates, not its viewport coordinates."
  [el-rect host-rect host-scroll-left host-scroll-top]
  {:left   (+ (- (:left el-rect) (:left host-rect)) host-scroll-left)
   :top    (+ (- (:top el-rect)  (:top host-rect))  host-scroll-top)
   :width  (:width  el-rect)
   :height (:height el-rect)})

;; --- effectful -----------------------------------------------------------

;; Pool of overlay elements. The first ('primary') carries the 8 resize
;; handles; secondaries are plain border boxes. Pool grows on demand and
;; never shrinks — extras are simply hidden via `data-hidden` when the
;; selection contracts.
(defonce ^:private overlay-state #js {:host nil :pool #js []})

(defonce ^:private resize-state
  #js {:active?  false
       :mode     nil     ;; :free | :flow
       :handle   nil
       :node-id  nil
       :element  nil
       :start-cx 0 :start-cy 0
       :start-x  0 :start-y  0
       :start-w  0 :start-h  0})

(defn- ^js host-el [] (unchecked-get overlay-state "host"))

(defn- ^js pool
  "Return the overlay pool, lazy-initialising it on first access. The
   lazy init survives a shadow-cljs hot-reload that started under an
   earlier version of `overlay-state` whose shape lacked the `:pool`
   key — without it, refresh! would dereference undefined and the
   overlay would silently stop drawing."
  []
  (or (unchecked-get overlay-state "pool")
      (let [p #js []]
        (unchecked-set overlay-state "pool" p)
        p)))
(defn- ^js primary-overlay []
  (let [^js p (pool)]
    (when (and p (pos? (.-length p)))
      (aget p 0))))

;; Backwards-compatible alias used by resize-state initialisation
;; below — the resize machinery only ever uses the primary overlay.
(defn- ^js overlay-el [] (primary-overlay))

(defn- resize-active? [] (unchecked-get resize-state "active?"))

(defn- bcr->map [^js r]
  {:left (.-left r) :top (.-top r) :width (.-width r) :height (.-height r)})

(defn- ^js nonzero-box
  "Return `el` when its bounding box has a positive area, else nil. A
   collapsed panel (e.g. an empty docked sidebar whose `min-block-size:
   100%` resolves against an auto-height host) reports a zero-area box."
  [^js el]
  (when el
    (let [r (.getBoundingClientRect el)]
      (when (and (pos? (.-width r)) (pos? (.-height r)))
        el))))

(defn- ^js measure-target
  "The element whose box the overlay should trace for the rendered host
   `el` of `tag`. Overlay components paint a panel outside their host
   box (a docked sidebar is narrower than its full-width host; drawer /
   modal / popover panels are position:fixed and leave it entirely), so
   for those we measure the shadow-DOM panel named by
   `probes/selection-selector` — the border then hugs the visible
   surface across every variant. Falls back to the host element when
   there is no probe (the common case), the panel isn't in the shadow
   tree yet, or the panel is collapsed to zero area (an empty docked
   sidebar — the host then carries the empty-container affordance box)."
  [^js el tag]
  (or (when-let [sel (probes/selection-selector tag)]
        (nonzero-box (some-> (.-shadowRoot el) (.querySelector sel))))
      el))

(defn- show!
  [^js overlay ^js el ^js host]
  (let [er   (.getBoundingClientRect el)
        hr   (.getBoundingClientRect host)
        rect (overlay-rect (bcr->map er) (bcr->map hr)
                           (.-scrollLeft host) (.-scrollTop host))]
    (set! (.. overlay -style -left)   (str (:left rect)   "px"))
    (set! (.. overlay -style -top)    (str (:top rect)    "px"))
    (set! (.. overlay -style -width)  (str (:width rect)  "px"))
    (set! (.. overlay -style -height) (str (:height rect) "px"))
    (.removeAttribute overlay "data-hidden")))

(defn- hide! [^js overlay]
  (.setAttribute overlay "data-hidden" ""))

(declare build-handles!)

(defn- create-overlay!
  "Append a fresh overlay element to the host and push it onto the
   pool. The first overlay created (`primary?` true) gets the resize
   handle children installed."
  [primary?]
  (let [^js o (js/document.createElement "div")]
    (.setAttribute o "class" "bareforge-selection-overlay")
    (.setAttribute o "data-hidden" "")
    (.appendChild (host-el) o)
    (.push (pool) o)
    (when primary? (build-handles! o))
    o))

(defn- ensure-pool-size! [n]
  (let [^js p (pool)]
    (while (< (.-length p) n)
      (create-overlay! false))))

(defn- selection-entries
  "Resolve `:selection` to a vector of {:id :el :node} maps for ids
   whose DOM element exists. Missing / stale ids are dropped silently
   so a render pass mid-edit doesn't flicker the overlay."
  []
  (let [doc (:document @state/app-state)]
    (into []
          (keep (fn [id]
                  (when-let [^js el (canvas/dom-for-id id)]
                    {:id   id
                     :el   el
                     :node (m/get-node doc (canvas/canonical-node-id id))})))
          (state/selected-ids @state/app-state))))

(defn- refresh!
  "Walk the current selection vector, position one overlay per
   resolved DOM element, and hide any pool entries beyond that count.
   The primary overlay carries `data-resize-mode` only when exactly
   one node is selected and that node's placement supports resize —
   otherwise the handles stay hidden via CSS, even though they remain
   in the DOM as primary's children."
  []
  (let [^js host (host-el)]
    (when host
      (let [entries (selection-entries)
            n       (count entries)
            single? (= n 1)]
        (ensure-pool-size! n)
        (let [^js p (pool)]
          (dotimes [i n]
            (let [{:keys [id el node]} (nth entries i)
                  ^js o (aget p i)
                  ^js target (measure-target el (:tag node))
                  primary? (zero? i)]
              (show! o target host)
              (if (and primary? single? (not= "root" id))
                (let [mode (resize-mode-for-node node)]
                  (if mode
                    (.setAttribute o "data-resize-mode" (name mode))
                    (.removeAttribute o "data-resize-mode")))
                (when primary? (.removeAttribute o "data-resize-mode")))))
          ;; Hide pool entries that are no longer needed.
          (loop [i n]
            (when (< i (.-length p))
              (hide! (aget p i))
              (recur (inc i)))))))))

(declare on-resize-move! on-resize-up!)

(defn- apply-live-dims!
  "During a resize drag, write the new dimensions directly onto the
   element's inline style (bypassing the reconciler) so the user sees
   immediate feedback. Free mode updates all four coordinates; flow
   mode only touches width/height since the element stays at its
   in-flow position. Re-reads the element rect and repositions the
   overlay so the handles stay on the edges."
  [^js el mode {:keys [x y w h]}]
  (case mode
    :free (do (set! (.. el -style -left)   (str x "px"))
              (set! (.. el -style -top)    (str y "px"))
              (set! (.. el -style -width)  (str w "px"))
              (set! (.. el -style -height) (str h "px")))
    :flow (do (set! (.. el -style -width)  (str w "px"))
              (set! (.. el -style -height) (str h "px"))))
  (refresh!))

(defn- read-resize-snapshot []
  {:x (unchecked-get resize-state "start-x")
   :y (unchecked-get resize-state "start-y")
   :w (unchecked-get resize-state "start-w")
   :h (unchecked-get resize-state "start-h")})

(defn- resize-dims-for-event [^js e]
  (let [handle   (unchecked-get resize-state "handle")
        start    (read-resize-snapshot)
        ;; Cursor deltas are in viewport (post-scale) pixels — the
        ;; element being resized lives inside the transformed
        ;; canvas-theme, so a 50 px cursor sweep at 2× zoom should
        ;; resize the element by 25 doc-px. Divide the raw client
        ;; delta by the current canvas zoom before feeding it to the
        ;; pure resize math.
        zoom     (:zoom (state/canvas-view @state/app-state))
        dx       (/ (- (.-clientX e) (unchecked-get resize-state "start-cx")) zoom)
        dy       (/ (- (.-clientY e) (unchecked-get resize-state "start-cy")) zoom)
        raw      (compute-resize-dims handle dx dy start)]
    (clamp-resize-dims handle raw start)))

(defn on-resize-move! [^js e]
  (when (resize-active?)
    (.preventDefault e)
    (let [^js el (unchecked-get resize-state "element")
          mode   (unchecked-get resize-state "mode")
          dims   (resize-dims-for-event e)]
      (when el (apply-live-dims! el mode dims)))))

(defn- finish-resize! []
  (unchecked-set resize-state "active?" false)
  (unchecked-set resize-state "mode"    nil)
  (unchecked-set resize-state "element" nil)
  (unchecked-set resize-state "node-id" nil)
  (when-let [overlay (overlay-el)]
    (.removeAttribute overlay "data-resizing")))

(defn- commit-resize!
  "Build the updated document for a resize commit. Free mode writes
   x/y/w/h as numbers; flow mode writes :width/:height as CSS length
   strings (`<n>px`) so the existing inspector width/height fields
   and the reconciler's layout->css pick them up unchanged."
  [mode doc node-id {:keys [x y w h]}]
  (case mode
    :free (-> doc
              (ops/set-layout node-id :x x)
              (ops/set-layout node-id :y y)
              (ops/set-layout node-id :w w)
              (ops/set-layout node-id :h h))
    :flow (-> doc
              (ops/set-layout node-id :width  (str w "px"))
              (ops/set-layout node-id :height (str h "px")))))

(defn on-resize-up! [^js e]
  (when (resize-active?)
    (let [mode    (unchecked-get resize-state "mode")
          node-id (unchecked-get resize-state "node-id")
          dims    (resize-dims-for-event e)
          doc'    (commit-resize! mode (:document @state/app-state) node-id dims)]
      (state/commit! doc')
      (finish-resize!))))

(defn- start-resize!
  [^js e handle-str]
  (let [sel-id (state/single-selected-id @state/app-state)
        node   (when sel-id
                 (m/get-node (:document @state/app-state)
                             (canvas/canonical-node-id sel-id)))
        ^js el (when sel-id (canvas/dom-for-id sel-id))
        handle (keyword handle-str)
        mode   (when node (resize-mode-for-node node))]
    (when (and node el mode
               (not= "root" sel-id)
               (resize-mode-handle-allowed? mode handle))
      (let [^js rect (.getBoundingClientRect el)
            cur-x    (if (= :free mode)
                       (or (get-in node [:layout :x]) 0)
                       0)
            cur-y    (if (= :free mode)
                       (or (get-in node [:layout :y]) 0)
                       0)
            cur-w    (if (= :free mode)
                       (or (get-in node [:layout :w])
                           (js/Math.round (.-width rect)))
                       (js/Math.round (.-width rect)))
            cur-h    (if (= :free mode)
                       (or (get-in node [:layout :h])
                           (js/Math.round (.-height rect)))
                       (js/Math.round (.-height rect)))]
        (.preventDefault e)
        (.stopPropagation e)
        (unchecked-set resize-state "active?"  true)
        (unchecked-set resize-state "mode"     mode)
        (unchecked-set resize-state "handle"   handle)
        (unchecked-set resize-state "node-id"  sel-id)
        (unchecked-set resize-state "element"  el)
        (unchecked-set resize-state "start-cx" (.-clientX e))
        (unchecked-set resize-state "start-cy" (.-clientY e))
        (unchecked-set resize-state "start-x"  cur-x)
        (unchecked-set resize-state "start-y"  cur-y)
        (unchecked-set resize-state "start-w"  cur-w)
        (unchecked-set resize-state "start-h"  cur-h)
        (when-let [overlay (overlay-el)]
          (.setAttribute overlay "data-resizing" ""))))))

(defn- on-handle-pointerdown! [^js e]
  (let [^js target (.-target e)
        handle-str (.getAttribute target "data-handle")]
    (when handle-str
      (start-resize! e handle-str))))

(defn- build-handles!
  "Create the 8 resize handle elements and append them to the overlay.
   Each handle carries a `data-handle` direction. A single delegated
   pointerdown listener on the overlay dispatches based on the
   attribute."
  [^js overlay]
  (doseq [dir handles]
    (let [^js h (js/document.createElement "div")]
      (.setAttribute h "class" "bareforge-selection-handle")
      (.setAttribute h "data-handle" (name dir))
      (.appendChild overlay h)))
  (.addEventListener overlay "pointerdown" on-handle-pointerdown!))

(defn- schedule-refresh!
  "Defer the next refresh until *after* the next animation frame so
   the canvas reconciler has a chance to apply its own pending patch
   first. Two rAF hops handle the case where the reconciler queues a
   patch in the same tick we did."
  []
  (js/requestAnimationFrame
   (fn []
     (js/requestAnimationFrame refresh!))))

(defn install!
  "Mount the selection overlay pool inside `canvas-host-el`. Seeds the
   pool with one primary overlay (handles attached), installs a
   watcher on `state/app-state` that fires on selection / document
   changes, wires a window resize listener for layout-shift cases, and
   attaches the resize-drag pointermove / pointerup listeners *once*
   here — both handlers are inert (early-exit on `resize-active?`)
   until a handle pointerdown starts a drag. Safe to call once at app
   startup."
  [^js canvas-host-el]
  (unchecked-set overlay-state "host" canvas-host-el)
  (unchecked-set overlay-state "pool" #js [])
  (create-overlay! true)
  (schedule-refresh!)
  ;; Refresh on selection or document changes (an edit or a click moves
  ;; the rect under us) AND on canvas-view changes (zoom + pan move
  ;; the rendered nodes' visual rects, and overlays are read off
  ;; getBoundingClientRect — without this the blue border stays put
  ;; while the canvas slides under it).
  (add-watch state/app-state ::selection-overlay
             (fn [_ _ old-state new-state]
               (when (or (not= (:selection old-state) (:selection new-state))
                         (not= (:document  old-state) (:document  new-state))
                         (not= (state/canvas-view old-state)
                               (state/canvas-view new-state)))
                 (schedule-refresh!))))
  (.addEventListener js/window "resize"
                     (fn [_] (schedule-refresh!)))
  (.addEventListener js/window "pointermove" on-resize-move!)
  (.addEventListener js/window "pointerup"   on-resize-up!))
