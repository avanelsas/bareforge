(ns bareforge.dnd.snap
  "Smart alignment guides for free-placement drag. Pure rect math:
   given a moving rect, a vector of static sibling rects, and a
   threshold, produce a snapped rect plus the guide lines that should
   be drawn while the user holds the alignment.

   Two axes, three alignments per axis:
     X — moving.left ↔ sibling.left
       — moving.cx   ↔ sibling.cx     (horizontal centers)
       — moving.right ↔ sibling.right
     Y — moving.top    ↔ sibling.top
       — moving.cy     ↔ sibling.cy   (vertical centers)
       — moving.bottom ↔ sibling.bottom

   For each axis we pick the candidate with the smallest |delta| that
   is still inside the threshold. The two axes snap independently —
   it's normal for X to align with one sibling and Y with another.

   Rects are plain maps `{:left :top :right :bottom}` in whatever
   coordinate system the caller is using (the integration in
   `bareforge.dnd.drag` uses doc-space). Guide lines come back in the
   same space; the renderer translates them to viewport pixels for
   the actual `<div>` placement."
  (:require [clojure.string :as str]))

;; --- pure: candidate generation ------------------------------------------

(defn- cx ^number [{:keys [left right]}] (/ (+ left right) 2))
(defn- cy ^number [{:keys [top bottom]}] (/ (+ top bottom) 2))

(defn- x-candidates
  "Three x-alignments between moving rect `m` and sibling rect `s`.
   Each candidate carries the `dx` that would align them, the absolute
   line position `:line-x` for the guide, and which sibling matched."
  [m s]
  [{:dx (- (:left s) (:left m))    :line-x (:left s)  :alignment :left   :sibling s}
   {:dx (- (cx s)    (cx m))       :line-x (cx s)     :alignment :cx     :sibling s}
   {:dx (- (:right s) (:right m))  :line-x (:right s) :alignment :right  :sibling s}])

(defn- y-candidates
  "Three y-alignments between moving rect `m` and sibling rect `s`."
  [m s]
  [{:dy (- (:top s)    (:top m))    :line-y (:top s)    :alignment :top    :sibling s}
   {:dy (- (cy s)      (cy m))      :line-y (cy s)      :alignment :cy     :sibling s}
   {:dy (- (:bottom s) (:bottom m)) :line-y (:bottom s) :alignment :bottom :sibling s}])

(defn- abs* ^number [^number n] (if (neg? n) (- n) n))

(defn- best-axis
  "Return the candidate with the smallest |delta| that is within
   `threshold`, or nil if none qualifies. `delta-key` is `:dx` or
   `:dy`. Ties resolve to the earlier candidate (input order),
   matching document-order traversal — `min-key` would pick the
   *last* on a tie, so we use stable `sort-by` + `first` instead.
   Stability matters: the candidate list is `[left cx right]` per
   sibling, so when an edge and a center alignment tie, the edge
   wins, which matches user intuition."
  [candidates delta-key threshold]
  (->> candidates
       (filter #(<= (abs* (delta-key %)) threshold))
       (sort-by #(abs* (delta-key %)))
       first))

;; --- pure: guide construction --------------------------------------------

(defn- vertical-guide
  "Build a vertical guide: a line at constant `x = line-x`, spanning
   from the topmost to the bottommost edge of the snapped moving rect
   and the matched sibling. Caller renders it as a 1-px-wide
   absolutely-positioned div."
  [snapped-m sibling line-x]
  {:axis :vertical
   :x    line-x
   :y0   (min (:top snapped-m) (:top sibling))
   :y1   (max (:bottom snapped-m) (:bottom sibling))})

(defn- horizontal-guide
  "Build a horizontal guide: a line at constant `y = line-y`, spanning
   the leftmost to rightmost edge of the snapped moving + matched
   sibling rect."
  [snapped-m sibling line-y]
  {:axis :horizontal
   :y    line-y
   :x0   (min (:left snapped-m) (:left sibling))
   :x1   (max (:right snapped-m) (:right sibling))})

(defn- shift
  "Translate a rect by (dx, dy)."
  [r dx dy]
  {:left   (+ (:left r)   dx)
   :top    (+ (:top r)    dy)
   :right  (+ (:right r)  dx)
   :bottom (+ (:bottom r) dy)})

;; --- pure: snap entrypoint -----------------------------------------------

(defn snap
  "Pure: snap `moving` to alignment with the nearest entry in
   `siblings` whose alignment delta is within `threshold`. Returns
     `{:rect SNAPPED :dx N :dy N :guides [...]}`.
   When neither axis qualifies, `:dx` and `:dy` are 0, `:rect` is the
   input rect, and `:guides` is empty.

   Empty siblings or non-positive threshold produce a no-op (same
   shape, no guides). Both make the integration's no-op early-out
   trivial."
  [moving siblings threshold]
  (if (or (empty? siblings) (not (pos? threshold)))
    {:rect moving :dx 0 :dy 0 :guides []}
    (let [xs (mapcat #(x-candidates moving %) siblings)
          ys (mapcat #(y-candidates moving %) siblings)
          x* (best-axis xs :dx threshold)
          y* (best-axis ys :dy threshold)
          dx (or (:dx x*) 0)
          dy (or (:dy y*) 0)
          snapped (shift moving dx dy)]
      {:rect   snapped
       :dx     dx
       :dy     dy
       :guides (cond-> []
                 x* (conj (vertical-guide   snapped (:sibling x*) (:line-x x*)))
                 y* (conj (horizontal-guide snapped (:sibling y*) (:line-y y*))))})))

;; --- pure: rect helpers (used by the integration) ------------------------

(defn rect-from-layout
  "Pure: convert a free-placement layout map `{:x :y :w :h}` to a
   rect `{:left :top :right :bottom}`. Numeric inputs only — callers
   that have CSS-string dimensions (e.g. `:width \"200px\"`) should
   parse first via `parse-px` or read from the DOM."
  [{:keys [x y w h]}]
  {:left   (or x 0)
   :top    (or y 0)
   :right  (+ (or x 0) (or w 0))
   :bottom (+ (or y 0) (or h 0))})

(defn parse-px
  "Pure: parse `\"123px\"` (or `\"123.5px\"`) into the numeric value
   `123` / `123.5`. Returns nil for non-px strings, blank strings,
   nil, or NaN."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (let [trimmed (str/trim s)
          n       (cond
                    (str/ends-with? trimmed "px")
                    (js/parseFloat (subs trimmed 0 (- (count trimmed) 2)))

                    :else
                    (js/parseFloat trimmed))]
      (when-not (js/isNaN n) n))))
