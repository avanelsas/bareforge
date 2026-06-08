(ns bareforge.render.selection-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.render.selection :as sel]))

(deftest overlay-rect-no-scroll
  (let [el   {:left 200 :top 150 :width 80 :height 24}
        host {:left 100 :top 100 :width 800 :height 600}]
    (is (= {:left 100 :top 50 :width 80 :height 24}
           (sel/overlay-rect el host 0 0)))))

(deftest overlay-rect-with-scroll-adjustment
  ;; When the host has scrolled, the visible BCR of the element
  ;; reflects the scroll, but the overlay (a child of the same
  ;; scrollable container) needs to sit at the element's content-frame
  ;; position. Adding the host's scroll offset compensates.
  (let [el   {:left 200 :top 60  :width 80 :height 24}
        host {:left 100 :top 100 :width 800 :height 600}]
    (is (= {:left 100 :top -40 :width 80 :height 24}
           (sel/overlay-rect el host 0 0))
        "without scroll, an element above the host top has negative top")
    (is (= {:left 100 :top 50 :width 80 :height 24}
           (sel/overlay-rect el host 0 90))
        "scrollTop 90 lifts the result to a positive content position")))

(deftest overlay-rect-horizontal-scroll
  (let [el   {:left 80  :top 100 :width 50 :height 30}
        host {:left 100 :top 100 :width 400 :height 300}]
    (is (= {:left 30 :top 0 :width 50 :height 30}
           (sel/overlay-rect el host 50 0)))))

(deftest overlay-rect-preserves-size
  (let [el   {:left 0 :top 0 :width 123 :height 45}
        host {:left 0 :top 0 :width 999 :height 999}
        r    (sel/overlay-rect el host 0 0)]
    (is (= 123 (:width r)))
    (is (= 45  (:height r)))))

;; --- compute-resize-dims -------------------------------------------------

(def ^:private start-100 {:x 100 :y 100 :w 200 :h 150})

(deftest resize-se-grows-width-and-height
  (is (= {:x 100 :y 100 :w 230 :h 170}
         (sel/compute-resize-dims :se 30 20 start-100))))

(deftest resize-e-only-width
  (is (= {:x 100 :y 100 :w 250 :h 150}
         (sel/compute-resize-dims :e 50 999 start-100))
      "east handle ignores vertical delta"))

(deftest resize-s-only-height
  (is (= {:x 100 :y 100 :w 200 :h 180}
         (sel/compute-resize-dims :s 999 30 start-100))
      "south handle ignores horizontal delta"))

(deftest resize-nw-shifts-origin-and-shrinks
  (is (= {:x 120 :y 110 :w 180 :h 140}
         (sel/compute-resize-dims :nw 20 10 start-100))))

(deftest resize-w-moves-x-and-shrinks-width
  (is (= {:x 140 :y 100 :w 160 :h 150}
         (sel/compute-resize-dims :w 40 0 start-100))))

(deftest resize-n-moves-y-and-shrinks-height
  (is (= {:x 100 :y 125 :w 200 :h 125}
         (sel/compute-resize-dims :n 0 25 start-100))))

(deftest resize-ne-grows-width-shrinks-from-top
  (is (= {:x 100 :y 120 :w 250 :h 130}
         (sel/compute-resize-dims :ne 50 20 start-100))))

(deftest resize-sw-shifts-x-and-grows-h
  (is (= {:x 130 :y 100 :w 170 :h 180}
         (sel/compute-resize-dims :sw 30 30 start-100))))

;; --- clamp-resize-dims ---------------------------------------------------

(deftest clamp-resize-clamps-width-when-east-shrinks-past-zero
  ;; East handle can shrink width by dragging left past the left edge.
  ;; Width hits min-size; x should not move (east handle doesn't own x).
  (let [raw (sel/compute-resize-dims :e -500 0 start-100)]
    (is (= {:x 100 :y 100 :w 10 :h 150}
           (sel/clamp-resize-dims :e raw start-100)))))

(deftest clamp-resize-freezes-x-when-west-flips
  ;; West handle drags past the right edge → width would go negative.
  ;; Clamp pins w at min-size and freezes x at the right edge − min.
  (let [raw (sel/compute-resize-dims :w 500 0 start-100)]
    ;; Start x=100, w=200 → right edge at 300. Frozen x = 300 − 10 = 290.
    (is (= {:x 290 :y 100 :w 10 :h 150}
           (sel/clamp-resize-dims :w raw start-100)))))

(deftest clamp-resize-freezes-y-when-north-flips
  (let [raw (sel/compute-resize-dims :n 0 500 start-100)]
    ;; Start y=100, h=150 → bottom at 250. Frozen y = 250 − 10 = 240.
    (is (= {:x 100 :y 240 :w 200 :h 10}
           (sel/clamp-resize-dims :n raw start-100)))))

(deftest clamp-resize-passes-through-when-within-bounds
  (let [raw (sel/compute-resize-dims :se 30 20 start-100)]
    (is (= raw (sel/clamp-resize-dims :se raw start-100)))))

;; --- resize-mode-for-node ------------------------------------------------

(defn- node-with [placement]
  {:id "n" :tag "x-foo" :layout {:placement placement}})

(deftest resize-mode-free-node-returns-free
  (is (= :free (sel/resize-mode-for-node (node-with :free)))))

(deftest resize-mode-flow-node-returns-flow
  (is (= :flow (sel/resize-mode-for-node (node-with :flow)))))

(deftest resize-mode-background-node-returns-nil
  (is (nil? (sel/resize-mode-for-node (node-with :background)))))

(deftest resize-mode-nil-node-returns-nil
  (is (nil? (sel/resize-mode-for-node nil))))

(deftest resize-mode-missing-placement-defaults-to-flow
  (testing "older nodes without a :placement key still get flow handles"
    (is (= :flow (sel/resize-mode-for-node {:id "n" :layout {}})))))

(deftest resize-mode-overlay-tags-get-no-handles
  (testing "overlay components size via CSS vars, not flow w/h — no handles"
    (doseq [tag ["x-sidebar" "x-drawer" "x-modal" "x-popover"]]
      (is (nil? (sel/resize-mode-for-node
                 {:id "n" :tag tag :layout {:placement :flow}}))
          (str tag " should expose no resize handles")))))

;; --- resize-mode-handle-allowed? -----------------------------------------

(deftest handle-allowed-free-accepts-everything
  (doseq [h sel/handles]
    (is (true? (sel/resize-mode-handle-allowed? :free h))
        (str "free should allow " h))))

(deftest handle-allowed-flow-only-e-s-se
  (is (true?  (sel/resize-mode-handle-allowed? :flow :e)))
  (is (true?  (sel/resize-mode-handle-allowed? :flow :s)))
  (is (true?  (sel/resize-mode-handle-allowed? :flow :se)))
  (doseq [h [:nw :n :ne :sw :w]]
    (is (false? (sel/resize-mode-handle-allowed? :flow h))
        (str "flow should reject " h))))

(deftest handle-allowed-nil-mode-rejects-all
  (doseq [h sel/handles]
    (is (false? (sel/resize-mode-handle-allowed? nil h)))))
