(ns bareforge.doc.align
  "Pure alignment + distribution math for multi-selection of free-
   placement nodes. Rect-in / rect-out: every public fn takes a
   vector of `{:left :top :w :h}` maps and returns a vector of the
   same length, in the same order, with new `:left` / `:top` values.
   Sizes are preserved.

   Two operations:

   - `align-rects` projects every rect's relevant edge / center to
     the *selection bounding box* — Figma-style, not 'align to first
     selected'. Six kinds: `:left :cx :right :top :cy :bottom`.

   - `distribute-rects` keeps the leftmost and rightmost (or topmost
     / bottommost) rects in place and spaces the others between them
     so all centers are evenly distributed. Requires 3+ rects.
     Returns input unchanged when given fewer.

   The effectful caller (`bareforge.ui.align-bar`) reads numeric
   `:layout :x / :y / :w / :h` off selected nodes, builds the rect
   vector, runs the math, writes new `:x / :y` back through
   `doc.ops/set-layout`. Nodes whose layout isn't fully numeric (CSS-
   string `:width`, missing `:x` etc.) are filtered out at the
   integration boundary and never reach this module.")

;; --- pure: bounding box -------------------------------------------------

(defn- bbox
  "Pure: outer rect of `rects`. Returns `{:left :top :right :bottom}`."
  [rects]
  {:left   (apply min (map :left rects))
   :top    (apply min (map :top  rects))
   :right  (apply max (map (fn [r] (+ (:left r) (:w r))) rects))
   :bottom (apply max (map (fn [r] (+ (:top  r) (:h r))) rects))})

(defn- cx ^number [r] (+ (:left r) (/ (:w r) 2)))
(defn- cy ^number [r] (+ (:top  r) (/ (:h r) 2)))

;; --- align ---------------------------------------------------------------

(defn align-rects
  "Pure: align every rect to the selection bounding box on the
   requested axis.

     :left   — every rect's left   = bbox.left
     :cx     — every rect's center = bbox horizontal center
     :right  — every rect's right  = bbox.right
     :top    — every rect's top    = bbox.top
     :cy     — every rect's center = bbox vertical center
     :bottom — every rect's bottom = bbox.bottom

   Returns a vector of `{:left :top :w :h}` in input order. With
   fewer than 2 rects, returns the input unchanged — alignment of
   one element is a no-op."
  [rects kind]
  (if (< (count rects) 2)
    rects
    (let [{:keys [left top right bottom]} (bbox rects)
          bb-cx (/ (+ left right)  2)
          bb-cy (/ (+ top  bottom) 2)]
      (mapv
       (fn [{:keys [w h] :as r}]
         (case kind
           :left   (assoc r :left left)
           :cx     (assoc r :left (- bb-cx (/ w 2)))
           :right  (assoc r :left (- right w))
           :top    (assoc r :top  top)
           :cy     (assoc r :top  (- bb-cy (/ h 2)))
           :bottom (assoc r :top  (- bottom h))))
       rects))))

;; --- distribute ----------------------------------------------------------

(defn- distribute-axis
  "Pure: distribute centers evenly along `axis` (`:horizontal` or
   `:vertical`). Sorts rects by their center on that axis, keeps the
   first and last in place, spaces the middle ones at equal
   intervals. Output preserves the original order — same vector, new
   positions for the inner rects, outer rects unchanged."
  [rects axis]
  (let [center (case axis :horizontal cx :vertical cy)
        ordered     (sort-by (fn [[_ r]] (center r))
                             (map-indexed vector rects))
        n           (count ordered)
        first-c     (center (second (first ordered)))
        last-c      (center (second (last  ordered)))
        step        (/ (- last-c first-c) (- n 1))
        update-pos  (fn [r target]
                      (case axis
                        :horizontal (assoc r :left (- target (/ (:w r) 2)))
                        :vertical   (assoc r :top  (- target (/ (:h r) 2)))))
        ;; Build a {original-index → new-rect} map preserving the
        ;; outermost rects untouched (k=0 and k=n-1) and spacing the
        ;; inner ones at first-c + k * step.
        out         (into {}
                          (map-indexed
                           (fn [k [orig-idx r]]
                             [orig-idx (cond
                                         (or (zero? k) (= k (dec n))) r
                                         :else (update-pos r (+ first-c (* k step))))])
                           ordered))]
    (mapv #(get out %) (range (count rects)))))

(defn distribute-rects
  "Pure: redistribute rect centers evenly along `axis`. With fewer
   than 3 rects there's nothing to redistribute — returns the input
   unchanged. `axis` is `:horizontal` (X) or `:vertical` (Y)."
  [rects axis]
  (if (< (count rects) 3)
    rects
    (distribute-axis rects axis)))

;; --- selection-fitness check --------------------------------------------

(def alignment-kinds
  "Public set of valid `kind` keywords for `align-rects`."
  #{:left :cx :right :top :cy :bottom})

(defn alignable?
  "Pure: should the align/distribute toolbar appear for `selected-nodes`?
   True when at least two nodes have numeric `:layout :x / :y / :w /
   :h` — non-numeric (CSS-string) sizes are filtered upstream by the
   caller. Independent of which axis the user picks."
  [nodes]
  (let [n (count (filter
                  (fn [{:keys [layout]}]
                    (and (number? (:x layout))
                         (number? (:y layout))
                         (number? (:w layout))
                         (number? (:h layout))))
                  nodes))]
    (>= n 2)))

(defn distributable?
  "Pure: like `alignable?` but the threshold is 3 — distribute
   needs first / last anchors plus at least one middle rect."
  [nodes]
  (let [n (count (filter
                  (fn [{:keys [layout]}]
                    (and (number? (:x layout))
                         (number? (:y layout))
                         (number? (:w layout))
                         (number? (:h layout))))
                  nodes))]
    (>= n 3)))

;; --- doc → rects helper -------------------------------------------------

(defn nodes->rects
  "Pure: lift `nodes` (each with `:id` and a numeric-layout `:layout`)
   into a vector of `{:id :left :top :w :h}` ready for align-rects /
   distribute-rects. Drops nodes without numeric coords so the math
   never sees an `nil`. Order is preserved."
  [nodes]
  (vec
   (keep
    (fn [{:keys [id layout]}]
      (let [{:keys [x y w h]} layout]
        (when (and (number? x) (number? y) (number? w) (number? h))
          {:id id :left x :top y :w w :h h})))
    nodes)))

