(ns bareforge.render.canvas-view
  "Canvas viewport — zoom and pan. The reconciler renders into the
   `<x-theme>` element inside `.canvas-host`; this namespace owns the
   `transform: translate(panX, panY) scale(zoom)` applied to that
   element from `:ui :canvas` state.

   Two zones, like everywhere else in the codebase:

   - **Pure** (`clamp-zoom`, `apply-zoom-at`, `wheel-zoom-factor`,
     `apply-wheel`): viewport math only. No DOM, no atom reads, fully
     unit-testable from node.
   - **Effectful** (`install!`, `install-watch!`): wheel-event listener
     on the canvas host, a single `::canvas-view` watch on
     `state/app-state` that mirrors the view onto CSS variables, and
     the on-canvas zoom-percent indicator.

   Overlay positioning (`render.selection`, `dnd.drag` marquee,
   `render.slot-strips`) is *not* changed by zoom/pan: those overlays
   are direct children of `.canvas-host`, untransformed, and their
   `getBoundingClientRect`-based math reads the *visual* (post-scale)
   rect of each rendered node — which already lines up with the
   transformed content. The places that *do* care about scale are the
   three cursor-delta sites: free-drag commit (`dnd.resolve`), live
   free-drag transform (`dnd.drag`), and resize delta
   (`render.selection`). Each divides its raw client delta by
   `(:zoom (state/canvas-view ...))` so a 50 px viewport drag at 200%
   zoom moves the node by 25 doc-px, not 50.

   Pan is gesture-based, not native scroll: `.canvas-host` is set to
   `overflow: hidden` and a `wheel` handler on it intercepts every
   wheel event — no modifier pans, Cmd/Ctrl zooms anchored at the
   cursor, the way design tools have done it since Figma."
  (:require [bareforge.state :as state]
            [bareforge.util.dom :as u]))

;; --- pure ----------------------------------------------------------------

(def ^:const min-zoom 0.25)
(def ^:const max-zoom 4.0)

(defn clamp-zoom
  "Pure: clamp `z` to `[min-zoom, max-zoom]`. Out-of-range values are
   pinned at the nearest bound rather than rejected so a wheel burst
   that would overshoot just stops at the limit."
  [z]
  (-> z (max min-zoom) (min max-zoom)))

(defn apply-zoom-at
  "Pure: change the viewport zoom to `new-zoom` while keeping the
   content point currently under cursor `[cx cy]` in canvas-host
   content space *under cursor*. Returns the next viewport map.

   Derivation: at any zoom `z` and pan `p`, a content point `c` is
   rendered at screen `s = p + c * z`. Holding `s` fixed across a zoom
   change yields `p' = s - c * z' = p + c * (z - z') = p * (z'/z) + s
   * (1 - z'/z)`. With `s ≡ cx`, that is the formula below."
  [{:keys [zoom pan-x pan-y]} new-zoom cx cy]
  (let [z'    (clamp-zoom new-zoom)
        ratio (/ z' zoom)]
    {:zoom  z'
     :pan-x (+ (* cx (- 1 ratio)) (* pan-x ratio))
     :pan-y (+ (* cy (- 1 ratio)) (* pan-y ratio))}))

(defn wheel-zoom-factor
  "Pure: convert a wheel `delta-y` (px) into a multiplicative zoom
   factor. Exponential so the per-tick step is proportional to the
   current zoom — zooming out from 4× and zooming in from 0.25× feel
   symmetrical, no asymmetric jumps near the limits.

   Negative `delta-y` (wheel up / two-finger swipe up) zooms in
   (factor > 1); positive zooms out."
  [delta-y]
  (js/Math.pow 1.0015 (- delta-y)))

(defn apply-wheel
  "Pure: classify a wheel event and return the next viewport.
   `:zoom?` true → multiplicative zoom anchored at `[cx cy]`.
   `:zoom?` false → additive pan: subtract the wheel deltas from the
   current pan so the *content* moves opposite the wheel direction
   (wheel down → content scrolls up)."
  [view {:keys [zoom? cx cy delta-x delta-y]}]
  (if zoom?
    (apply-zoom-at view (* (:zoom view) (wheel-zoom-factor delta-y)) cx cy)
    {:zoom  (:zoom view)
     :pan-x (- (:pan-x view) delta-x)
     :pan-y (- (:pan-y view) delta-y)}))

(defn format-zoom-percent
  "Pure: render `zoom` as a percent string for the indicator, rounded
   to the nearest integer (`1.0` → `\"100%\"`, `0.834` → `\"83%\"`)."
  [zoom]
  (str (js/Math.round (* 100 zoom)) "%"))

;; --- effectful -----------------------------------------------------------

;; The DOM elements this namespace touches. The theme element is the
;; one that gets the transform; the host is the gesture surface and
;; the indicator container.
(defonce ^:private view-state #js {:host nil :theme nil :indicator nil})

(defn- ^js host-el      [] (unchecked-get view-state "host"))
(defn- ^js theme-el     [] (unchecked-get view-state "theme"))
(defn- ^js indicator-el [] (unchecked-get view-state "indicator"))

(defn- edit-mode?
  "Preview mode neutralises every canvas-view gesture so the rendered
   page behaves like the deployed site — native scroll, no pan,
   typing a space stays a space. The CSS sibling rules cover the
   visual side (`.chrome[data-mode=\"preview\"] …`); this guard
   covers the event side."
  []
  (= :edit (:mode @state/app-state)))

(defn- apply-view!
  "Mirror `view` onto the theme element's CSS variables and the
   indicator's text. The transform itself is declared in CSS using
   the variables so `apply-view!` does not have to recompose the
   string on every wheel tick."
  [{:keys [zoom pan-x pan-y]}]
  (when-let [^js t (theme-el)]
    (.. t -style (setProperty "--canvas-zoom"  (str zoom)))
    (.. t -style (setProperty "--canvas-pan-x" (str pan-x "px")))
    (.. t -style (setProperty "--canvas-pan-y" (str pan-y "px"))))
  (when-let [^js i (indicator-el)]
    (u/set-text! i (format-zoom-percent zoom))))

(defn- cursor-content-coord
  "Convert clientX/clientY to canvas-host content-space coords (origin
   at the host's padding-box top-left). Same convention as
   `dnd.drag/canvas-content-coord`."
  [^js host client-x client-y]
  (let [^js br (.getBoundingClientRect host)]
    [(- client-x (.-left br))
     (- client-y (.-top  br))]))

(defn- on-wheel!
  "Wheel handler: Cmd/Ctrl wheel zooms anchored at cursor, plain
   wheel pans. Both branches preventDefault so the canvas host does
   not also scroll natively (it has `overflow: hidden`, so this is
   defensive — and it stops the page behind the editor from scrolling
   when the wheel reaches the body). Preview mode bails out so native
   scroll on the rendered page works."
  [^js e]
  (let [^js host (host-el)]
    (when (and host (edit-mode?))
      (.preventDefault e)
      (let [zoom?    (or (.-ctrlKey e) (.-metaKey e))
            [cx cy]  (cursor-content-coord host (.-clientX e) (.-clientY e))
            view     (state/canvas-view @state/app-state)
            view'    (apply-wheel view {:zoom?   zoom?
                                        :cx      cx
                                        :cy      cy
                                        :delta-x (.-deltaX e)
                                        :delta-y (.-deltaY e)})]
        (when (not= view view')
          (state/set-canvas-view! view'))))))

;; --- space-drag pan -------------------------------------------------------

;; Holding Space turns the cursor into a hand. Left-button drag while
;; the hand is down translates pan-x / pan-y in viewport pixels (no
;; scale divisor: pan is screen-space). Released Space restores normal
;; selection drag. The state lives in a JS object to avoid threading
;; through the atom for transient gesture data.
(defonce ^:private pan-state
  #js {:space-down? false
       :active?     false
       :start-x     0
       :start-y     0
       :start-pan-x 0
       :start-pan-y 0})

(defn- space-down? [] (unchecked-get pan-state "space-down?"))
(defn- pan-active? [] (unchecked-get pan-state "active?"))

(defn- update-host-cursor!
  "Reflect the gesture state on the host so the cursor changes as the
   user picks up / drops the spacebar — `grab` while armed, `grabbing`
   mid-drag, default otherwise."
  []
  (when-let [^js h (host-el)]
    (cond
      (pan-active?) (do (.removeAttribute h "data-pan-armed")
                        (.setAttribute h "data-pan-active" ""))
      (space-down?) (do (.setAttribute h "data-pan-armed" "")
                        (.removeAttribute h "data-pan-active"))
      :else         (do (.removeAttribute h "data-pan-armed")
                        (.removeAttribute h "data-pan-active")))))

(defn- begin-pan! [^js e]
  (let [view (state/canvas-view @state/app-state)]
    (unchecked-set pan-state "active?"     true)
    (unchecked-set pan-state "start-x"     (.-clientX e))
    (unchecked-set pan-state "start-y"     (.-clientY e))
    (unchecked-set pan-state "start-pan-x" (:pan-x view))
    (unchecked-set pan-state "start-pan-y" (:pan-y view))
    (update-host-cursor!)))

(defn- end-pan! []
  (unchecked-set pan-state "active?" false)
  (update-host-cursor!))

(defn- on-pan-move! [^js e]
  (when (pan-active?)
    (.preventDefault e)
    (let [view  (state/canvas-view @state/app-state)
          dx    (- (.-clientX e) (unchecked-get pan-state "start-x"))
          dy    (- (.-clientY e) (unchecked-get pan-state "start-y"))
          view' (assoc view
                       :pan-x (+ (unchecked-get pan-state "start-pan-x") dx)
                       :pan-y (+ (unchecked-get pan-state "start-pan-y") dy))]
      (state/set-canvas-view! view'))))

(defn editable-target?
  "Pure: true when `el` is a text-editable widget (native input/
   textarea/select or a contenteditable host). Used to decide whether
   a Space keystroke is the user typing vs. arming the canvas pan."
  [^js el]
  (boolean
   (and el
        (or (.-isContentEditable el)
            (contains? #{"input" "textarea" "select"}
                       (some-> el .-tagName .toLowerCase))))))

(defn- on-keydown!
  "Track Space so a subsequent left-button drag pans. Ignored when the
   keystroke targets an editable widget — typing a space into an
   inspector field must not arm pan. Preview mode is also ignored so
   spaces typed into the rendered page stay spaces.

   Reads the deepest composed-path node, not `.-target`: inspector
   fields are custom elements (x-search-field, x-text-area) whose real
   input lives in shadow DOM. A keydown bubbling to window is retargeted
   to the host, so `.-target` would be the custom-element tag and the
   editable check would miss it — swallowing the space."
  [^js e]
  (when (and (= " " (.-key e))
             (not (.-repeat e))
             (not (space-down?))
             (edit-mode?))
    (let [^js path (.composedPath e)
          ^js t    (if (and path (pos? (.-length path)))
                     (aget path 0)
                     (.-target e))]
      (when-not (editable-target? t)
        (.preventDefault e)
        (unchecked-set pan-state "space-down?" true)
        (update-host-cursor!)))))

(defn- on-keyup! [^js e]
  (when (= " " (.-key e))
    (unchecked-set pan-state "space-down?" false)
    (when (pan-active?) (end-pan!))
    (update-host-cursor!)))

(defn pan-pointerdown?
  "Pure: should this pointerdown start a pan instead of falling through
   to the dnd layer? True iff the spacebar is held (set by
   `on-keydown!`). Exposed so `dnd.drag` can short-circuit before
   marquee / drag selection logic."
  []
  (space-down?))

(defn try-begin-pan!
  "Effectful: if Space is held when a pointerdown lands on the canvas,
   start a pan and return true. dnd.drag calls this from
   `on-canvas-pointerdown!` and aborts its own state machine on a
   true return so the gesture doesn't double-fire."
  [^js e]
  (when (space-down?)
    (.preventDefault e)
    (.stopPropagation e)
    (begin-pan! e)
    true))

;; --- install ----------------------------------------------------------------

(defn- create-indicator!
  "Build the on-canvas zoom indicator and append it to the host. A
   simple absolutely-positioned chip in the bottom-right corner, never
   intercepts pointer events. Initial text is set immediately so the
   chip is non-empty even before `install-watch!` mirrors the live
   view onto it."
  [^js host]
  (let [^js i (u/el :div {:class "canvas-zoom-indicator"})]
    (u/set-text! i (format-zoom-percent 1.0))
    (.appendChild host i)
    i))

(defn install-watch!
  "Mirror `[:ui :canvas]` onto the theme element and indicator on every
   change. Single watch keyed `::canvas-view` per the project rule."
  []
  (apply-view! (state/canvas-view @state/app-state))
  (add-watch state/app-state ::canvas-view
             (fn [_ _ old-state new-state]
               (let [a (state/canvas-view old-state)
                     b (state/canvas-view new-state)]
                 (when (not= a b) (apply-view! b))))))

(defn install!
  "Wire up viewport gestures. Pass the canvas-host (gesture surface +
   indicator parent) and the canvas-theme element (transform target).
   Safe to call once at app startup."
  [^js canvas-host ^js canvas-theme]
  (unchecked-set view-state "host"      canvas-host)
  (unchecked-set view-state "theme"     canvas-theme)
  (unchecked-set view-state "indicator" (create-indicator! canvas-host))
  (.addEventListener canvas-host "wheel" on-wheel! #js {:passive false})
  (.addEventListener js/window     "keydown"     on-keydown!)
  (.addEventListener js/window     "keyup"       on-keyup!)
  (.addEventListener js/window     "pointermove" on-pan-move!)
  (.addEventListener js/window     "pointerup"
                     (fn [_] (when (pan-active?) (end-pan!))))
  (install-watch!))
