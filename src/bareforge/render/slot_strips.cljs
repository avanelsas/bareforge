(ns bareforge.render.slot-strips
  "Per-slot drop-zone overlay for container components. When a drag
   is in flight and the cursor hovers over a canvas container with
   one or more explicitly-declared slots, the drag layer asks this
   module to render N equal horizontal strips across the hovered
   element — one per slot, labelled with the slot's `:label` from
   `bareforge.meta.slots` and tagged with `data-bareforge-slot-node`
   + `data-bareforge-slot-name` so the existing inspector slot-row
   drop routing picks them up automatically.

   Single-slot containers (x-card, x-grid, x-container) get one
   full-size strip: same dashed-outline + tint visual as before,
   plus a label pill in the center so users always see the slot
   they're dropping into. Multi-slot containers (x-navbar with 6,
   x-modal / x-drawer / x-popover with 3 each) get N subdivisions.

   `render-strips?` composes with `registry/container?` — tags whose
   slots are all `:multiple? false` (notably `x-button`,
   `x-typography`, `x-icon`) aren't drop containers: the drag
   layer's `classify-position` never returns `:inside` for them, so
   strips would render over a zone that can't be committed to.

   Mount and lifecycle mirror `bareforge.render.selection`: an
   absolute-positioned overlay div is appended once at startup as a
   sibling of the rendered tree inside the canvas host; per-strip
   children are rebuilt on every `show-for-element!` call."
  (:require [bareforge.meta.registry :as registry]
            [bareforge.meta.slots :as slots]))

;; --- pure -----------------------------------------------------------------

(defn render-strips?
  "Single source of truth for 'this tag should render labelled drop
   strips'. Any explicitly-registered container tag with at least
   one slot qualifies — single-slot containers (x-card, x-grid,
   x-container) get one full-size strip, multi-slot containers get
   N subdivisions. Tags with no explicit entry (falling back to the
   generic default) and non-container leaves (all-:multiple?-false
   slot lists) return false."
  [tag]
  (and (string? tag)
       (registry/container? tag)
       (pos? (count (slots/slots-for tag)))))

(defn strip-rects
  "Compute the overlay-relative rectangles for `n` equal horizontal
   strips that together tile `el-rect` (the hovered element's bcr
   shape). Inputs are plain maps with `:left :top :width :height`;
   `host-rect` and `host-scroll-*` convert bcrs to host-content
   coordinates the same way `bareforge.render.selection/overlay-rect`
   does for single-element overlays.

   Returns a vector of N maps; strip `i`'s `:left` is the floor of
   `base-left + i * width/n` and its `:width` is the difference
   between strip `i+1`'s `:left` and its own, so the strips tile
   without gaps even when `width/n` isn't an integer."
  [el-rect host-rect host-scroll-left host-scroll-top n]
  (if (pos? n)
    (let [{:keys [width height]} el-rect
          base-left (+ (- (:left el-rect) (:left host-rect)) host-scroll-left)
          base-top  (+ (- (:top  el-rect) (:top  host-rect)) host-scroll-top)
          cut       (fn [i] (+ base-left (long (* (/ (* width i) n)))))]
      (vec
        (for [i (range n)
              :let [left  (cut i)
                    right (if (= i (dec n))
                            (+ base-left width)
                            (cut (inc i)))]]
          {:left   left
           :top    base-top
           :width  (- right left)
           :height height})))
    []))

;; --- effectful -----------------------------------------------------------

(defonce ^:private overlay-state
  #js {:el       nil
       :host     nil
       :host-id  nil})

(defn- ^js overlay-el [] (unchecked-get overlay-state "el"))
(defn- ^js host-el    [] (unchecked-get overlay-state "host"))

(defn current-host-id
  "The node id of the component whose strips are currently drawn,
   or nil. Drag state uses this to decide whether to rebuild."
  []
  (unchecked-get overlay-state "host-id"))

(defn- set-host-id! [id]
  (unchecked-set overlay-state "host-id" id))

(defn- bcr->map [^js r]
  {:left (.-left r) :top (.-top r) :width (.-width r) :height (.-height r)})

(defn- make-strip-el
  "Build one strip div at `rect` carrying the slot-row data attrs so
   the drag layer's existing `find-slot-row` walker picks it up as a
   slot target on hover. `node-id` addresses the hovered component in
   the doc; `slot-name` is the slot= value a drop commits into."
  [node-id {:keys [name label]} {:keys [left top width height]}]
  (let [strip (js/document.createElement "div")
        pill  (js/document.createElement "div")]
    (.setAttribute strip "class" "bareforge-slot-strip")
    (.setAttribute strip "data-bareforge-slot-node" node-id)
    (.setAttribute strip "data-bareforge-slot-name" name)
    (set! (.. strip -style -left)   (str left   "px"))
    (set! (.. strip -style -top)    (str top    "px"))
    (set! (.. strip -style -width)  (str width  "px"))
    (set! (.. strip -style -height) (str height "px"))
    (.setAttribute pill "class" "bareforge-slot-strip-label")
    (set! (.-textContent pill) label)
    (.appendChild strip pill)
    strip))

(defn hide!
  "Clear any rendered strips and mark the overlay hidden."
  []
  (when-let [^js overlay (overlay-el)]
    (.setAttribute overlay "data-hidden" "")
    (.replaceChildren overlay))
  (set-host-id! nil))

(defn show-for-element!
  "Render `tag`'s slot strips over the given element `el`. `node-id`
   is the doc id of the hovered component — stamped onto each strip
   so the existing slot-row routing dispatches to the right node.
   Replaces any previously-rendered strips (call `hide!` first if you
   want to guarantee a clean slate before measuring)."
  [tag node-id ^js el]
  (let [^js overlay (overlay-el)
        ^js host    (host-el)]
    (when (and overlay host el)
      (let [slots      (slots/slots-for tag)
            n          (count slots)
            er         (.getBoundingClientRect el)
            hr         (.getBoundingClientRect host)
            rects      (strip-rects (bcr->map er) (bcr->map hr)
                                    (.-scrollLeft host) (.-scrollTop host)
                                    n)
            child-nodes (mapv (fn [slot rect]
                                (make-strip-el node-id slot rect))
                              slots rects)]
        (.replaceChildren overlay)
        (doseq [child child-nodes]
          (.appendChild overlay child))
        (.removeAttribute overlay "data-hidden")
        (set-host-id! node-id)))))

(defn install!
  "Mount the slot-strips overlay inside `canvas-host-el`. Safe to call
   once at app startup alongside `selection/install!`. Idempotent —
   subsequent calls skip appending if an overlay already exists."
  [^js canvas-host-el]
  (when-not (overlay-el)
    (let [overlay (js/document.createElement "div")]
      (.setAttribute overlay "class" "bareforge-slot-strips-overlay")
      (.setAttribute overlay "data-hidden" "")
      (.appendChild canvas-host-el overlay)
      (unchecked-set overlay-state "el"   overlay)
      (unchecked-set overlay-state "host" canvas-host-el))))
