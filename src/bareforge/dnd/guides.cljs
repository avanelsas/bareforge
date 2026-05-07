(ns bareforge.dnd.guides
  "Live alignment-guide overlay shown during free-drag. Mirrors the
   pure guide data produced by `bareforge.dnd.snap/snap` onto thin
   absolutely-positioned divs in the canvas host.

   The guides come back from `snap` in doc-space (whatever coordinate
   space the caller fed in — `dnd.drag` uses the layout-box space
   read off `offsetLeft / offsetTop`). We translate to viewport-space
   at draw time using the canvas-theme's `getBoundingClientRect` and
   the current zoom, then to host-content-space the same way the
   selection overlay does — so the guides scroll, pan, and zoom in
   lockstep with the rendered content while staying crisp 1-px
   strokes regardless of the canvas-view scale.

   Pool of `<div>` elements grows on demand and never shrinks (extras
   hide via `data-hidden`). One visit per refresh, so a guide that
   disappears while the drag continues just gets hidden, not removed
   from the DOM."
  (:require [bareforge.util.dom :as u]))

(defonce ^:private state #js {:host nil :theme nil :pool #js []})

(defn- ^js host-el  [] (unchecked-get state "host"))
(defn- ^js theme-el [] (unchecked-get state "theme"))
(defn- ^js pool     [] (unchecked-get state "pool"))

(defn- create-line!
  "Lazily append a thin guide line div to the host. The actual size
   and orientation are set in `position-guide!` per refresh, so the
   pool is geometry-agnostic."
  []
  (let [^js l (u/el :div {:class "bareforge-snap-guide" :data-hidden ""})]
    (.appendChild (host-el) l)
    (.push (pool) l)
    l))

(defn- ensure-pool-size! [n]
  (let [^js p (pool)]
    (while (< (.-length p) n)
      (create-line!))))

(defn- position-guide!
  "Translate a doc-space guide map into inline left/top/width/height
   on `^js el`. Vertical guides get a 1-px width and a height equal
   to the snapped y-span × zoom; horizontals are the symmetric flip."
  [^js el guide ^js theme-rect ^js host-rect zoom]
  (let [theme-left (.-left theme-rect)
        theme-top  (.-top  theme-rect)
        host-left  (.-left host-rect)
        host-top   (.-top  host-rect)]
    (case (:axis guide)
      :vertical
      (let [{:keys [x y0 y1]} guide
            vp-x (+ theme-left (* x zoom))
            vp-y (+ theme-top  (* y0 zoom))
            h    (* (- y1 y0) zoom)]
        (set! (.. el -style -left)   (str (- vp-x host-left) "px"))
        (set! (.. el -style -top)    (str (- vp-y host-top)  "px"))
        (set! (.. el -style -width)  "1px")
        (set! (.. el -style -height) (str h "px")))

      :horizontal
      (let [{:keys [y x0 x1]} guide
            vp-x (+ theme-left (* x0 zoom))
            vp-y (+ theme-top  (* y zoom))
            w    (* (- x1 x0) zoom)]
        (set! (.. el -style -left)   (str (- vp-x host-left) "px"))
        (set! (.. el -style -top)    (str (- vp-y host-top)  "px"))
        (set! (.. el -style -width)  (str w "px"))
        (set! (.. el -style -height) "1px")))
    (.removeAttribute el "data-hidden")))

(defn show!
  "Render `guides` (vector of snap guide maps). Pool entries beyond
   `(count guides)` are hidden. `zoom` is the current canvas-view
   zoom factor — caller passes it once per refresh rather than re-
   reading state on every line."
  [guides zoom]
  (let [^js host  (host-el)
        ^js theme (theme-el)]
    (when (and host theme)
      (let [^js theme-rect (.getBoundingClientRect theme)
            ^js host-rect  (.getBoundingClientRect host)
            n              (count guides)
            ^js p          (pool)]
        (ensure-pool-size! n)
        (dotimes [i n]
          (position-guide! (aget p i) (nth guides i) theme-rect host-rect zoom))
        (loop [i n]
          (when (< i (.-length p))
            (.setAttribute (aget p i) "data-hidden" "")
            (recur (inc i))))))))

(defn hide!
  "Hide every guide line. Called from `dnd.drag/cleanup!` so guides
   never linger after a drag ends."
  []
  (let [^js p (pool)]
    (when p
      (dotimes [i (.-length p)]
        (.setAttribute (aget p i) "data-hidden" "")))))

(defn install!
  "Bind the canvas-host (overlay parent) and canvas-theme (transform
   reference) elements. Called once at app startup alongside the
   other overlay installers."
  [^js canvas-host ^js canvas-theme]
  (unchecked-set state "host"  canvas-host)
  (unchecked-set state "theme" canvas-theme)
  (unchecked-set state "pool"  #js []))
