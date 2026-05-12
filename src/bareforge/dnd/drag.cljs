(ns bareforge.dnd.drag
  "Pointer-event drag-and-drop state machine. Phase 1: drag from a
   palette item onto the canvas, using `palette/insertion-target` to
   decide where the new node lands. Moving existing canvas nodes and
   component-aware snap come in follow-up phases.

   Design note: drag state lives in a JS object (not a second atom) so
   the one-atom rule in CLAUDE.md stays intact. Drag is strictly
   effectful DOM plumbing — the only point that touches `state/*` is
   `commit-drop!` at the end. The pure parts of drop / move planning
   (target resolution, snap, free-drag math) live in
   `bareforge.dnd.resolve` and are unit-testable without a browser;
   the `commit-*!` fns here are thin orchestrators that snapshot the
   live JS state into a plain map, hand it to the planner, and apply
   the result via `ops/*` + `state/commit!`."
  (:require [bareforge.dnd.guides :as guides]
            [bareforge.dnd.resolve :as resolve]
            [bareforge.dnd.snap :as snap]
            [bareforge.dnd.target :as target]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.meta.registry :as registry]
            [bareforge.render.canvas :as canvas]
            [bareforge.render.canvas-view :as canvas-view]
            [bareforge.render.slot-strips :as slot-strips]
            [bareforge.state :as state]
            [bareforge.ui.palette :as palette]
            [bareforge.util.dom :as u]))

;; --- mutable drag state ---------------------------------------------------

(defonce ^:private drag-state
  #js {:phase      :idle  ;; :idle | :armed | :dragging | :marquee-armed | :marquee
       :source-tag      nil   ;; palette drag: tag name to insert
       :source-node-id  nil   ;; canvas drag: existing node id to move
       :start-x    0
       :start-y    0
       :ghost      nil
       :target-id  nil          ;; hovered canvas node id, or nil
       :target-el  nil          ;; the highlighted element (canvas OR layer)
       :target-position nil     ;; :before | :after | :inside | nil
       :slot-target-node nil    ;; hovered inspector slot row: node id
       :slot-target-name nil    ;; hovered inspector slot row: slot name
       :slot-target-el   nil    ;; the row element for highlight cleanup
       :valid?     false
       :canvas-el  nil
       :free-drag?     false     ;; true when source is a :free node
       :free-initial-x 0
       :free-initial-y 0
       :marquee-el     nil       ;; the rectangle div drawn during a marquee
       :marquee-additive? false  ;; Shift-held at marquee start: extend selection
       })

(def ^:private move-threshold 4)

(def ^:private snap-threshold
  "Doc-pixel distance within which a free-drag snaps to a sibling
   alignment. Six matches the visual feel of Figma / Webflow at 1×
   zoom; the canvas-view divides cursor deltas by zoom *before* the
   snap planner sees them, so the threshold stays in doc-pixels and
   feels the same at 50% as at 200%."
  6)

(defn- phase       [] (unchecked-get drag-state "phase"))
(defn- set-phase!  [p] (unchecked-set drag-state "phase" p))
(defn- source-tag  [] (unchecked-get drag-state "source-tag"))
(defn- set-source-tag! [t] (unchecked-set drag-state "source-tag" t))
(defn- source-node-id [] (unchecked-get drag-state "source-node-id"))
(defn- set-source-node-id! [id] (unchecked-set drag-state "source-node-id" id))
(defn- free-drag?     [] (unchecked-get drag-state "free-drag?"))
(defn- set-free-drag! [v] (unchecked-set drag-state "free-drag?" (boolean v)))
(defn- snap-initial    [] (unchecked-get drag-state "snap-initial"))
(defn- snap-siblings   [] (or (unchecked-get drag-state "snap-siblings") []))
(defn- set-snap-snapshot!
  [{:keys [initial siblings]}]
  (unchecked-set drag-state "snap-initial"  initial)
  (unchecked-set drag-state "snap-siblings" siblings))
(defn- clear-snap-snapshot!
  []
  (unchecked-set drag-state "snap-initial"  nil)
  (unchecked-set drag-state "snap-siblings" nil))
(defn- start-xy!
  [x y]
  (unchecked-set drag-state "start-x" x)
  (unchecked-set drag-state "start-y" y))
(defn- start-x [] (unchecked-get drag-state "start-x"))
(defn- start-y [] (unchecked-get drag-state "start-y"))

(defn- ghost      [] (unchecked-get drag-state "ghost"))
(defn- set-ghost! [g] (unchecked-set drag-state "ghost" g))
(defn- target-id  [] (unchecked-get drag-state "target-id"))
(defn- set-target-id! [id] (unchecked-set drag-state "target-id" id))
(defn- target-el  [] (unchecked-get drag-state "target-el"))
(defn- set-target-el! [el] (unchecked-set drag-state "target-el" el))
(defn- target-position [] (unchecked-get drag-state "target-position"))
(defn- set-target-position! [p] (unchecked-set drag-state "target-position" p))
(defn- valid?     [] (unchecked-get drag-state "valid?"))
(defn- set-valid! [v] (unchecked-set drag-state "valid?" (boolean v)))
(defn- canvas-el  [] (unchecked-get drag-state "canvas-el"))
(defn- slot-target-node [] (unchecked-get drag-state "slot-target-node"))
(defn- slot-target-name [] (unchecked-get drag-state "slot-target-name"))
(defn- slot-target-el   [] (unchecked-get drag-state "slot-target-el"))

;; --- target highlight -----------------------------------------------------

(def ^:private slot-target-class "bareforge-slot-target")
(def ^:private position-classes
  ["bareforge-drop-before" "bareforge-drop-after" "bareforge-drop-inside"])

(defn- position-class [pos]
  (case pos
    :before "bareforge-drop-before"
    :after  "bareforge-drop-after"
    :inside "bareforge-drop-inside"
    nil))

(defn- clear-target-highlight! []
  (when-let [^js el (target-el)]
    (doseq [c position-classes]
      (.. el -classList (remove c))))
  (set-target-el! nil)
  (set-target-id! nil)
  (set-target-position! nil))

(defn- set-target-highlight!
  "Highlight `el` with a position-aware drop class. Pass nil for `el`
   to clear. Tracks the element directly so cleanup is unambiguous
   even when canvas + layer rows share the same data-bareforge-id."
  [^js el new-id new-pos]
  (when (or (not= new-id (target-id))
            (not= new-pos (target-position))
            (not (identical? el (target-el))))
    (clear-target-highlight!)
    (when (and el new-id)
      (when-let [c (position-class new-pos)]
        (.. el -classList (add c)))
      (set-target-el! el))
    (set-target-id! new-id)
    (set-target-position! new-pos)))

(defn- clear-slot-target! []
  (when-let [^js el (slot-target-el)]
    (.. el -classList (remove slot-target-class)))
  (unchecked-set drag-state "slot-target-node" nil)
  (unchecked-set drag-state "slot-target-name" nil)
  (unchecked-set drag-state "slot-target-el"   nil))

(defn- set-slot-target! [^js row-el node-id slot-name]
  (when (or (not= node-id (slot-target-node))
            (not= slot-name (slot-target-name)))
    (clear-slot-target!)
    (when row-el
      (.. row-el -classList (add slot-target-class))
      (unchecked-set drag-state "slot-target-node" node-id)
      (unchecked-set drag-state "slot-target-name" slot-name)
      (unchecked-set drag-state "slot-target-el"   row-el))))

;; --- marquee selection ---------------------------------------------------

;; `start-marquee!` is paired with the existing palette/canvas drag
;; starters and must reset state through `cleanup!`, which is defined
;; below alongside the other public-API helpers. Forward-declare so
;; the marquee block stays self-contained.
(declare cleanup!)

(defn- marquee-el [] (unchecked-get drag-state "marquee-el"))
(defn- set-marquee-el! [el] (unchecked-set drag-state "marquee-el" el))
(defn- marquee-additive? [] (unchecked-get drag-state "marquee-additive?"))
(defn- set-marquee-additive! [v] (unchecked-set drag-state "marquee-additive?" (boolean v)))

(defn- canvas-content-coord
  "Convert a (clientX, clientY) point to the canvas host's content
   coordinate space — i.e. the same space overlays use, accounting
   for scroll. Returns `[x y]`."
  [^js host client-x client-y]
  (let [^js br (.getBoundingClientRect host)]
    [(+ (- client-x (.-left br)) (.-scrollLeft host))
     (+ (- client-y (.-top  br)) (.-scrollTop  host))]))

(defn- ensure-marquee-el!
  "Lazily create the rectangle div on first move. Lives inside the
   canvas host so it scrolls in lockstep with the rendered tree, and
   uses the same content-coord space as the selection overlay pool."
  []
  (or (marquee-el)
      (let [^js host (canvas-el)
            ^js m    (js/document.createElement "div")]
        (.setAttribute m "class" "bareforge-marquee")
        (.appendChild host m)
        (set-marquee-el! m)
        m)))

(defn- update-marquee-rect!
  "Paint the rectangle for the current cursor position."
  [^js e]
  (let [^js host (canvas-el)
        [x0 y0]  (canvas-content-coord host (start-x) (start-y))
        [x1 y1]  (canvas-content-coord host (.-clientX e) (.-clientY e))
        left     (min x0 x1)
        top      (min y0 y1)
        width    (js/Math.abs (- x1 x0))
        height   (js/Math.abs (- y1 y0))
        ^js m    (ensure-marquee-el!)]
    (set! (.. m -style -left)   (str left   "px"))
    (set! (.. m -style -top)    (str top    "px"))
    (set! (.. m -style -width)  (str width  "px"))
    (set! (.. m -style -height) (str height "px"))))

(defn- clear-marquee-el! []
  (when-let [^js m (marquee-el)]
    (when-let [^js p (.-parentNode m)]
      (.removeChild p m)))
  (set-marquee-el! nil))

(defn- rects-overlap?
  "Pure: AABB intersection test on `{:left :top :right :bottom}` maps."
  [a b]
  (and (< (:left a)   (:right  b))
       (> (:right a)  (:left   b))
       (< (:top a)    (:bottom b))
       (> (:bottom a) (:top    b))))

(defn- marquee-hits
  "Walk every `[data-bareforge-id]` element under the canvas host and
   return the raw DOM ids of those whose bounding box overlaps the
   marquee rectangle. Skips root and skips the canvas-host itself.
   Stable in document order."
  [^js host marquee-rect]
  (let [^js br    (.getBoundingClientRect host)
        sl        (.-scrollLeft host)
        st        (.-scrollTop  host)
        ^js nodes (.querySelectorAll host "[data-bareforge-id]")
        out       (volatile! [])]
    (dotimes [i (.-length nodes)]
      (let [^js el (.item nodes i)
            id     (.getAttribute el "data-bareforge-id")]
        (when (and id (not= id "root"))
          (let [^js eb (.getBoundingClientRect el)
                left   (+ (- (.-left   eb) (.-left br)) sl)
                top    (+ (- (.-top    eb) (.-top  br)) st)
                rect   {:left   left
                        :top    top
                        :right  (+ left (.-width  eb))
                        :bottom (+ top  (.-height eb))}]
            (when (rects-overlap? marquee-rect rect)
              (vswap! out conj id))))))
    @out))

(defn- commit-marquee! [^js e]
  (let [^js host (canvas-el)
        [x0 y0]  (canvas-content-coord host (start-x) (start-y))
        [x1 y1]  (canvas-content-coord host (.-clientX e) (.-clientY e))
        rect     {:left (min x0 x1) :top (min y0 y1)
                  :right (max x0 x1) :bottom (max y0 y1)}
        hits     (marquee-hits host rect)
        existing (if (marquee-additive?)
                   (state/selected-ids @state/app-state)
                   [])
        merged   (vec (distinct (concat existing hits)))]
    (state/set-selection! merged)))

(defn- start-marquee!
  "Arm a marquee selection from a pointerdown that landed on the canvas
   root (no node hit). Stashes whether Shift was held so the eventual
   commit either replaces or extends the existing selection — same
   convention as Figma / Webflow."
  [^js e]
  (when-not (= :idle (phase))
    (cleanup!))
  (set-source-tag! nil)
  (set-source-node-id! nil)
  (start-xy! (.-clientX e) (.-clientY e))
  (set-marquee-additive! (.-shiftKey e))
  (set-phase! :marquee-armed))

;; --- ghost element --------------------------------------------------------

(defn- make-ghost [tag]
  (let [g (u/el :div {:class "bareforge-drag-ghost"})]
    (u/set-text! g tag)
    g))

(defn- set-ghost-valid-style! [ok?]
  (when-let [^js g (ghost)]
    (if ok?
      (.. g -classList (remove "is-invalid"))
      (.. g -classList (add    "is-invalid")))))

(defn- position-ghost! [^js g ^js e]
  (set! (.. g -style -left) (str (+ 12 (.-clientX e)) "px"))
  (set! (.. g -style -top)  (str (+ 12 (.-clientY e)) "px")))

(defn- show-ghost! [tag ^js e]
  (when-not (ghost)
    (let [g (make-ghost tag)]
      (set-ghost! g)
      (.appendChild js/document.body g)))
  (position-ghost! (ghost) e))

(defn- hide-ghost! []
  (when-let [^js g (ghost)]
    (when-let [^js p (.-parentNode g)]
      (.removeChild p g)))
  (set-ghost! nil))

;; --- target resolution ----------------------------------------------------

(defn- element-under-cursor [^js e]
  (let [^js g (ghost)]
    (when g (set! (.. g -style -display) "none"))
    (let [under (js/document.elementFromPoint (.-clientX e) (.-clientY e))]
      (when g (set! (.. g -style -display) ""))
      under)))

(defn- inside-canvas? [^js el]
  (let [^js c (canvas-el)]
    (boolean (and c el (.contains c el)))))

(defn- find-slot-row
  "Walk up from `el` to the nearest inspector slot row, or nil."
  [^js el]
  (when el
    (.closest el "[data-bareforge-slot-node]")))

(defn- find-layer-row
  "Walk up from `el` to the nearest layer-tree row, or nil."
  [^js el]
  (when el
    (.closest el "[data-bareforge-layer-row]")))

(defn- node-position
  "Compute the half-element drop classification for a hovered DOM
   element backed by document node `id`. Looks up whether the node's
   tag is a container so the rule (top half / 50/50 vs top quarter /
   25/50/25) matches its capabilities."
  [^js node-el id cursor-y]
  (let [container? (let [doc (:document @state/app-state)
                         tag (some-> (m/get-node doc id) :tag)]
                     (boolean (and tag (registry/container? tag))))
        ^js bcr    (.getBoundingClientRect node-el)
        rect       {:top (.-top bcr) :height (.-height bcr)}]
    (target/classify-position rect cursor-y container?)))

(defn- canvas-target-el
  "Find the element with `data-bareforge-id` that is also inside the
   canvas host (avoids matching the layers panel row with the same id)."
  [id]
  (let [host (canvas-el)]
    (when (and host id)
      (let [^js nodes (.querySelectorAll host (str "[data-bareforge-id=\"" id "\"]"))]
        (when (pos? (.-length nodes))
          (.item nodes 0))))))

(defn- resolve-target!
  "Inspect the element under the cursor. Valid drop targets are:
   (a) an inspector slot row OR a canvas slot strip (both carry
       `data-bareforge-slot-node`; `find-slot-row` walks up to either),
   (b) a layers-panel row (data-bareforge-layer-row),
   (c) the canvas host and any of its descendants.
   Everything else is invalid.

   For multi-slot canvas targets, the (c) branch mounts per-slot
   strips via `render.slot-strips` and then re-invokes itself once so
   the freshly-appended strip becomes the under-cursor element on
   this same pointermove — otherwise a stationary drop after strips
   appear would commit to the default slot rather than the strip
   under the cursor. The `re-entered?` guard bounds recursion depth
   to one."
  ([^js e] (resolve-target! e false))
  ([^js e re-entered?]
   (let [under     (element-under-cursor e)
         slot-row  (find-slot-row under)
         layer-row (find-layer-row under)]
     (cond
       slot-row
       (let [node-id   (.getAttribute slot-row "data-bareforge-slot-node")
             slot-name (.getAttribute slot-row "data-bareforge-slot-name")]
         (set-valid! true)
         (set-ghost-valid-style! true)
         (set-target-highlight! nil nil nil)
         (set-slot-target! slot-row node-id slot-name)
         ;; Strips for node X are themselves slot rows; keep them
         ;; visible while the cursor stays on them. Hide when the
         ;; hovered row belongs to any OTHER node (an Inspector row,
         ;; or a different canvas host).
         (when (and (slot-strips/current-host-id)
                    (not= node-id (slot-strips/current-host-id)))
           (slot-strips/hide!)))

       layer-row
       (let [id  (.getAttribute layer-row "data-bareforge-id")
             pos (node-position layer-row id (.-clientY e))]
         (set-valid! true)
         (set-ghost-valid-style! true)
         (clear-slot-target!)
         (set-target-highlight! layer-row id pos)
         (slot-strips/hide!))

       (inside-canvas? under)
       (let [id      (canvas/element->node-id under)
             ^js el  (canvas-target-el id)
             pos     (when el (node-position el id (.-clientY e)))
             doc     (:document @state/app-state)
             tag     (some-> (m/get-node doc (canvas/canonical-node-id id)) :tag)
             strips? (and el (= pos :inside) (slot-strips/render-strips? tag))]
         (set-valid! true)
         (set-ghost-valid-style! true)
         (clear-slot-target!)
         (if strips?
           (do
             ;; Strips tile the element and carry their own outline;
             ;; suppress the usual `.bareforge-drop-inside` class that
             ;; `set-target-highlight!` would add.
             (set-target-highlight! nil nil nil)
             (when (not= id (slot-strips/current-host-id))
               (slot-strips/show-for-element! tag id el)
               ;; After mounting, re-run once so the strip under the
               ;; cursor is picked up via `find-slot-row` — guarantees
               ;; a stationary drop targets the correct slot.
               (when-not re-entered?
                 (resolve-target! e true))))
           (do
             (set-target-highlight! el id pos)
             (slot-strips/hide!))))

       :else
       (do
         (set-valid! false)
         (set-ghost-valid-style! false)
         (clear-slot-target!)
         (set-target-highlight! nil nil nil)
         (slot-strips/hide!))))))

;; --- commit drop ----------------------------------------------------------

(defn- snapshot-drag-state
  "Read the JS drag-state fields the planner cares about into a
   plain Clojure map. The JS object stays as the live event-loop
   state; this snapshot is the value handed across the pure
   boundary at commit time."
  []
  {:source-tag       (source-tag)
   :source-node-id   (source-node-id)
   :slot-target-node (slot-target-node)
   :slot-target-name (slot-target-name)
   :target-id        (target-id)
   :target-position  (target-position)
   :start-x          (start-x)
   :start-y          (start-y)
   :free-initial-x   (unchecked-get drag-state "free-initial-x")
   :free-initial-y   (unchecked-get drag-state "free-initial-y")})

(defn- snap-during-drag
  "Pure-ish helper: given the raw doc-space cursor delta and the
   pointerdown snapshot, run `snap/snap` against the captured siblings
   and return `{:total-dx :total-dy :guides}`. `total-*` is the delta
   to write to the live `transform: translate(...)` and the value the
   commit step uses to construct the final `:layout :x / :y`. Returns
   raw deltas + empty guides when no snapshot was captured (e.g. a
   non-free drag that somehow reached this code path)."
  [raw-dx raw-dy]
  (if-let [initial (snap-initial)]
    (let [proposed (-> initial
                       (update :left + raw-dx) (update :right + raw-dx)
                       (update :top + raw-dy)  (update :bottom + raw-dy))
          result   (snap/snap proposed (snap-siblings) snap-threshold)]
      {:total-dx (+ raw-dx (:dx result))
       :total-dy (+ raw-dy (:dy result))
       :guides   (:guides result)})
    {:total-dx raw-dx :total-dy raw-dy :guides []}))

(defn- update-free-drag-position!
  "Visual feedback during a :free drag. Uses CSS transform so we
   never touch the document until pointerup — keeps history clean
   and avoids a thousand-entry undo stack from a single drag.
   Divides the cursor delta by the canvas zoom (translate on a child
   of a scaled parent is itself scaled at render time), then runs
   `snap/snap` against the snapshotted siblings so the element pulls
   to alignment within `snap-threshold`. Active alignments are drawn
   live via `dnd.guides/show!`."
  [^js e]
  (when-let [^js el (canvas/dom-for-id (source-node-id))]
    (let [zoom   (:zoom (state/canvas-view @state/app-state))
          raw-dx (/ (- (.-clientX e) (start-x)) zoom)
          raw-dy (/ (- (.-clientY e) (start-y)) zoom)
          {:keys [total-dx total-dy guides]} (snap-during-drag raw-dx raw-dy)]
      (set! (.. el -style -transform)
            (str "translate(" total-dx "px," total-dy "px)"))
      (guides/show! guides zoom))))

(defn- commit-free-move!
  "On drop, run `snap-during-drag` to mirror the live feedback's
   alignment behaviour, then commit `(:layout :x)` / `(:layout :y)`
   as `free-initial + total-delta`. The reconciler runs on rAF after
   commit; its `apply-layout-style!` writes a fresh style attribute
   without the transform, so the element lands cleanly at its new
   coordinates."
  [^js e]
  (let [doc  (:document @state/app-state)
        zoom (:zoom (state/canvas-view @state/app-state))
        raw-dx (/ (- (.-clientX e) (start-x)) zoom)
        raw-dy (/ (- (.-clientY e) (start-y)) zoom)
        {:keys [total-dx total-dy]} (snap-during-drag raw-dx raw-dy)
        free-x (unchecked-get drag-state "free-initial-x")
        free-y (unchecked-get drag-state "free-initial-y")
        x      (+ free-x total-dx)
        y      (+ free-y total-dy)
        src-id (source-node-id)
        doc'   (-> doc
                   (ops/set-layout src-id :x x)
                   (ops/set-layout src-id :y y))]
    (state/commit! doc')))

(defn- clear-free-transform!
  "Reset the transform of whichever element we were free-dragging.
   Called on cancel (Escape) where no commit fires to overwrite it."
  []
  (when (and (free-drag?) (source-node-id))
    (when-let [^js el (canvas/dom-for-id (source-node-id))]
      (set! (.. el -style -transform) ""))))

(defn- commit-drop! [^js _e]
  (when (valid?)
    (let [doc  (:document @state/app-state)
          {:keys [parent-id slot index tag overrides]}
          (resolve/plan-drop doc (snapshot-drag-state))
          {doc' :doc id :id}
          (ops/insert-new doc parent-id slot index tag overrides)]
      (state/commit! doc')
      (state/select-one! id))))

(defn- commit-move! [^js _e]
  (when (valid?)
    (let [doc (:document @state/app-state)
          {:keys [src-id parent-id slot index]}
          (resolve/plan-move doc (snapshot-drag-state))]
      ;; ops/move throws on cycles (moving a node under its own
      ;; descendant) and on stale ids. Swallow both silently so an
      ;; invalid drop is simply a no-op.
      (try
        (let [doc' (ops/move doc src-id parent-id slot index)]
          (state/commit! doc')
          (state/select-one! src-id))
        (catch :default _ nil)))))

;; --- public API -----------------------------------------------------------

(defn- cleanup! []
  (hide-ghost!)
  (clear-target-highlight!)
  (clear-slot-target!)
  (slot-strips/hide!)
  (clear-marquee-el!)
  (guides/hide!)
  (clear-snap-snapshot!)
  (set-marquee-additive! false)
  (set-valid! false)
  (set-target-position! nil)
  (set-phase! :idle)
  (set-source-tag! nil)
  (set-source-node-id! nil)
  (set-free-drag! false))

(defn cancel!
  "Abort any in-progress drag. Safe to call from :idle."
  []
  (clear-free-transform!)
  (cleanup!))

(defn start-from-palette!
  "Arm a drag from a palette item. Does not yet enter :dragging — the
   threshold check in `on-move!` handles the transition."
  [^js e tag]
  (when-not (= :idle (phase))
    (cleanup!))
  (set-source-tag! tag)
  (set-source-node-id! nil)
  (start-xy! (.-clientX e) (.-clientY e))
  (set-phase! :armed))

(defn- capture-snap-snapshot
  "Walk the dragged element's DOM siblings (those with
   `data-bareforge-id`) and capture each one's layout-box rect via
   `offsetLeft / offsetTop / offsetWidth / offsetHeight`. Layout-box
   coords are *not* affected by canvas-view's transform, so the
   snapshot lives in the same doc-space the snap math expects.

   Siblings whose `offsetParent` differs from the dragged element's
   are skipped: a `position: fixed` sibling (e.g. an `x-metaball-cursor`)
   reports `offsetLeft/Top` in viewport coordinates rather than the
   shared offsetParent's, which would feed nonsense candidates into
   the snap planner. Sharing offsetParent guarantees both rects live
   in the same coordinate system.

   `parentNode.children` is fine for the walk: free-placement nodes
   are absolute-positioned, but their DOM parent is still the layout
   container, and that's where their alignment candidates naturally
   live."
  [^js el]
  (let [^js parent (.-parentNode el)
        children   (when parent (.-children parent))
        my-op      (.-offsetParent el)
        x          (.-offsetLeft   el)
        y          (.-offsetTop    el)
        w          (.-offsetWidth  el)
        h          (.-offsetHeight el)
        siblings   (when children
                     (loop [i 0 acc (transient [])]
                       (if (< i (.-length children))
                         (let [^js c (aget children i)]
                           (recur
                            (inc i)
                            (cond-> acc
                              (and (not (identical? c el))
                                   (.getAttribute c "data-bareforge-id")
                                   (identical? (.-offsetParent c) my-op))
                              (conj! (let [cx (.-offsetLeft   c)
                                           cy (.-offsetTop    c)
                                           cw (.-offsetWidth  c)
                                           ch (.-offsetHeight c)]
                                       {:left   cx
                                        :top    cy
                                        :right  (+ cx cw)
                                        :bottom (+ cy ch)})))))
                         (persistent! acc))))]
    {:initial  {:left   x   :top    y
                :right  (+ x w) :bottom (+ y h)}
     :siblings (or siblings [])}))

(defn start-from-canvas!
  "Arm a drag from an existing canvas node. A release without meaningful
   movement selects the node (canvas tap); a release past the drag
   threshold moves the node via ops/move for flow / background
   nodes, or updates `:layout :x / :y` for `:free` nodes."
  [^js e node-id]
  (when-not (= :idle (phase))
    (cleanup!))
  (set-source-tag! nil)
  (set-source-node-id! node-id)
  (let [doc  (:document @state/app-state)
        node (m/get-node doc node-id)
        free? (= :free (get-in node [:layout :placement]))]
    (set-free-drag! free?)
    (when free?
      (unchecked-set drag-state "free-initial-x"
                     (or (get-in node [:layout :x]) 0))
      (unchecked-set drag-state "free-initial-y"
                     (or (get-in node [:layout :y]) 0))
      ;; Snap snapshot lives in drag-state for the duration of the
      ;; gesture: capture once at pointerdown so per-tick snap math
      ;; stays cheap (no DOM walks during pointermove).
      (when-let [^js el (canvas/dom-for-id node-id)]
        (set-snap-snapshot! (capture-snap-snapshot el)))))
  (start-xy! (.-clientX e) (.-clientY e))
  (set-phase! :armed))

(defn- ghost-label
  "Text shown on the drag ghost — tag for a palette drag, short
   'move <tag>' for a canvas drag."
  []
  (or (source-tag)
      (when-let [id (source-node-id)]
        (let [doc (:document @state/app-state)
              n   (m/get-node doc id)]
          (str "move " (:tag n))))))

(defn on-move! [^js e]
  (case (phase)
    :armed
    (let [dx (- (.-clientX e) (start-x))
          dy (- (.-clientY e) (start-y))]
      (when (>= (+ (* dx dx) (* dy dy)) (* move-threshold move-threshold))
        (set-phase! :dragging)
        (if (free-drag?)
          (do (set-valid! true)
              (update-free-drag-position! e))
          (do (show-ghost! (ghost-label) e)
              (resolve-target! e)))))

    :dragging
    (if (free-drag?)
      (update-free-drag-position! e)
      (do (position-ghost! (ghost) e)
          (resolve-target! e)))

    :marquee-armed
    (let [dx (- (.-clientX e) (start-x))
          dy (- (.-clientY e) (start-y))]
      (when (>= (+ (* dx dx) (* dy dy)) (* move-threshold move-threshold))
        (set-phase! :marquee)
        (update-marquee-rect! e)))

    :marquee
    (update-marquee-rect! e)

    nil))

(defn on-up! [^js e]
  (case (phase)
    :dragging
    (do
      (cond
        (free-drag?)       (commit-free-move! e)
        (source-node-id)   (commit-move! e)
        :else              (commit-drop! e))
      (cleanup!))

    :armed
    (let [tag     (source-tag)
          node-id (source-node-id)
          shift?  (.-shiftKey e)]
      (cleanup!)
      (cond
        ;; Palette tap (no movement) → insert at current selection.
        tag     (palette/insert-at-selection! tag)
        ;; Canvas tap → select the node. Store the raw DOM id
        ;; (which for template-instance clones carries a
        ;; `__seed<N>` suffix) so the selection overlay can
        ;; highlight the specific clicked element; doc-lookup
        ;; sites (inspector, shortcuts) canonicalise on read.
        ;; Shift-tap toggles membership for multi-select; plain
        ;; tap collapses the selection back to a single id.
        node-id (if shift?
                  (state/select-toggle! node-id)
                  (state/select-one! node-id))))

    :marquee
    (do (commit-marquee! e)
        (cleanup!))

    :marquee-armed
    ;; Tap on empty canvas with no movement: clear selection unless
    ;; Shift was held (additive marquee with no rectangle is a no-op).
    (let [additive? (marquee-additive?)]
      (cleanup!)
      (when-not additive?
        (state/select-clear!)))

    nil))

(defn on-key!
  "Keydown handler for the drag layer. Only reacts to Escape, and
   only while a drag is in flight (:armed or :dragging). Calls
   `stopImmediatePropagation` so the `shortcuts.cljs` keydown
   listener — which handles Escape as a 'deselect' action — does
   not ALSO fire on the same event. That listener is registered in
   bubble phase; this one uses capture (see `install-window-listeners!`)
   so the drag layer always wins the race for a Escape-during-drag."
  [^js e]
  (when (and (contains? #{:armed :dragging :marquee-armed :marquee} (phase))
             (= "Escape" (.-key e)))
    (.stopImmediatePropagation e)
    (cancel!)))

(defn- on-canvas-pointerdown! [^js e]
  ;; Delegate: any pointerdown inside the canvas host starts a drag on
  ;; the nearest bareforge-rendered ancestor. A pointerdown that
  ;; resolves to root, or that lands on the canvas padding (no
  ;; bareforge-rendered ancestor at all), starts a marquee selection
  ;; instead — root is not draggable, and the padding is conceptually
  ;; empty space. Preview mode disables both drag and marquee entirely
  ;; so native click / pointer events flow through to the user's own
  ;; components.
  ;;
  ;; Space-held pan wins over everything: when the user is mid-pan the
  ;; gesture must not also drop a marquee or pick up a node. We give
  ;; `canvas-view/try-begin-pan!` first refusal — it returns truthy if
  ;; it consumed the pointerdown, and we bail out of the rest.
  (when (and (not= :preview (:mode @state/app-state))
             (not (canvas-view/try-begin-pan! e)))
    (let [id (canvas/element->node-id (.-target e))]
      (cond
        (or (nil? id) (= id "root")) (start-marquee! e)
        :else                         (start-from-canvas! e id)))))

(defn install-window-listeners!
  "Install the pointermove, pointerup, pointercancel, and keydown
   handlers on the document. Call once at application startup, passing
   the canvas host element — only drops whose cursor is inside that
   element are treated as valid, and pointerdowns inside it start
   canvas drags on existing nodes."
  [^js canvas-host]
  (unchecked-set drag-state "canvas-el" canvas-host)
  (.addEventListener canvas-host "pointerdown"   on-canvas-pointerdown!)
  (.addEventListener js/document  "pointermove"   on-move!)
  (.addEventListener js/document  "pointerup"     on-up!)
  (.addEventListener js/document  "pointercancel" on-up!)
  ;; Capture phase so Escape-during-drag wins the race over the
  ;; shortcut layer's bubble-phase keydown listener.
  (.addEventListener js/document  "keydown"       on-key! true))

(defn dragging? [] (= :dragging (phase)))
