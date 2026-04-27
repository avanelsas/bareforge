(ns bareforge.render.selection
  "Canvas selection overlay. A thin, absolutely-positioned `<div>`
   sibling of the rendered tree inside the canvas host is repositioned
   on every selection / document change to draw a 1 px border *around*
   the currently-selected element. The overlay never modifies the
   selected element's own CSS, so it cannot interact with the user's
   design (no box-shadow inflation, no layout shift).

   Coordinates are computed relative to the canvas host's padding box,
   which is also the containing block for the overlay (canvas-host has
   `position: relative`). Because the overlay is a child of the same
   scrollable container as the rendered content, scrolling moves both
   in lockstep — no scroll listener is needed.

   The overlay also renders 8 resize handles as children. Which
   handles are interactive depends on the selected node's placement:
   `:free` nodes get the full 8 (updates `:layout :x :y :w :h`);
   `:flow` nodes get only E/S/SE (grow the width/height axis,
   updating `:layout :width :height` as CSS length strings);
   `:background` nodes and the root get none. Live visual feedback
   writes directly to the element's inline style during drag, then
   a single commit at pointerup keeps the undo history clean."
  (:require [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
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
    (case (get-in node [:layout :placement])
      :background nil
      :free       :free
      :flow)))

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

(defonce ^:private overlay-state #js {:el nil :host nil})

(defonce ^:private resize-state
  #js {:active?  false
       :mode     nil     ;; :free | :flow
       :handle   nil
       :node-id  nil
       :element  nil
       :start-cx 0 :start-cy 0
       :start-x  0 :start-y  0
       :start-w  0 :start-h  0})

(defn- ^js overlay-el [] (unchecked-get overlay-state "el"))
(defn- ^js host-el    [] (unchecked-get overlay-state "host"))
(defn- resize-active? [] (unchecked-get resize-state "active?"))

(defn- bcr->map [^js r]
  {:left (.-left r) :top (.-top r) :width (.-width r) :height (.-height r)})

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

(defn- selected-node []
  (when-let [sel-id (get-in @state/app-state [:selection :id])]
    (m/get-node (:document @state/app-state) sel-id)))

(defn- refresh!
  "Read the current selection from app-state, look up its DOM element,
   and reposition the overlay (or hide it if nothing valid is selected).
   Sets `data-resize-mode` on the overlay to `\"free\"` or `\"flow\"`
   so CSS can show the appropriate handle subset; root and background
   selections clear the attribute entirely."
  []
  (let [^js overlay (overlay-el)
        ^js host    (host-el)]
    (when (and overlay host)
      (let [sel-id (get-in @state/app-state [:selection :id])
            ^js el (canvas/dom-for-id sel-id)
            node   (selected-node)
            mode   (when (and node (not= "root" sel-id))
                     (resize-mode-for-node node))]
        (if el
          (do (show! overlay el host)
              (if mode
                (.setAttribute overlay "data-resize-mode" (name mode))
                (.removeAttribute overlay "data-resize-mode")))
          (hide! overlay))))))

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
        dx       (- (.-clientX e) (unchecked-get resize-state "start-cx"))
        dy       (- (.-clientY e) (unchecked-get resize-state "start-cy"))
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
    (.removeAttribute overlay "data-resizing"))
  (.removeEventListener js/window "pointermove" on-resize-move!)
  (.removeEventListener js/window "pointerup"   on-resize-up!))

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
  (let [sel-id (get-in @state/app-state [:selection :id])
        node   (selected-node)
        ^js el (canvas/dom-for-id sel-id)
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
          (.setAttribute overlay "data-resizing" ""))
        (.addEventListener js/window "pointermove" on-resize-move!)
        (.addEventListener js/window "pointerup"   on-resize-up!)))))

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
  "Mount the selection overlay inside `canvas-host-el`. Creates the
   overlay element, appends it to the host, installs a watcher on
   `state/app-state` that fires on selection / document changes, and
   wires a window resize listener for layout-shift cases. Safe to
   call once at app startup."
  [^js canvas-host-el]
  (let [overlay (js/document.createElement "div")]
    (.setAttribute overlay "class" "bareforge-selection-overlay")
    (.setAttribute overlay "data-hidden" "")
    (.appendChild canvas-host-el overlay)
    (unchecked-set overlay-state "el"   overlay)
    (unchecked-set overlay-state "host" canvas-host-el)
    (build-handles! overlay)
    (schedule-refresh!)
    (add-watch state/app-state ::selection-overlay
               (fn [_ _ old-state new-state]
                 (when (or (not= (:selection old-state) (:selection new-state))
                           (not= (:document  old-state) (:document  new-state)))
                   (schedule-refresh!))))
    (.addEventListener js/window "resize"
                       (fn [_] (schedule-refresh!)))))
