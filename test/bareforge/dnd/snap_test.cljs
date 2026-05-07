(ns bareforge.dnd.snap-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.dnd.snap :as snap]))

(defn- rect [x y w h]
  {:left x :top y :right (+ x w) :bottom (+ y h)})

;; --- empty / no-op cases -----------------------------------------------

(deftest snap-no-siblings-is-noop
  (let [m (rect 100 100 50 50)
        out (snap/snap m [] 6)]
    (is (= m (:rect out)))
    (is (= 0 (:dx out)))
    (is (= 0 (:dy out)))
    (is (empty? (:guides out)))))

(deftest snap-zero-threshold-is-noop
  (let [m (rect 100 100 50 50)
        s (rect 90 100 50 50)]
    (is (= m (:rect (snap/snap m [s] 0))))
    (is (empty? (:guides (snap/snap m [s] 0))))))

(deftest snap-far-sibling-no-snap
  (testing "Sibling outside threshold on both axes is ignored. (A
            sibling that's perfectly aligned on one axis but far on
            the other still produces a guide for the aligned axis,
            which is desirable — it confirms an existing alignment.)"
    (let [m (rect 100 100 50 50)
          s (rect 200 200 50 50)
          out (snap/snap m [s] 6)]
      (is (= 0 (:dx out)))
      (is (= 0 (:dy out)))
      (is (empty? (:guides out))))))

;; --- single-axis snapping -----------------------------------------------

(deftest snap-left-edge
  (testing "moving.left aligns with sibling.left when close"
    (let [m (rect 95 200 50 50)    ;; left=95
          s (rect 100 50 80 30)    ;; left=100
          out (snap/snap m [s] 6)]
      (is (= 5 (:dx out)) "shift right by 5 to land at left=100")
      (is (= 100 (:left (:rect out))))
      (is (= 0 (:dy out)) "y stays put — different y axes")
      (is (= 1 (count (:guides out))))
      (is (= :vertical (-> out :guides first :axis)))
      (is (= 100 (-> out :guides first :x))))))

(deftest snap-center-x
  (testing "horizontal centers align"
    (let [m (rect 92 200 20 20)        ;; cx = 102
          s (rect 60 100 80 40)        ;; cx = 100
          out (snap/snap m [s] 6)]
      (is (= -2 (:dx out)) "shift left by 2 to align cx at 100")
      (is (= 100 (-> out :guides first :x))))))

(deftest snap-right-edge
  (let [m (rect 100 200 50 50)        ;; right = 150
        s (rect 50 50  102 30)        ;; right = 152
        out (snap/snap m [s] 6)]
    (is (= 2 (:dx out)))
    (is (= 152 (-> out :guides first :x)))))

(deftest snap-top-edge
  (let [m (rect 200 95 50 50)
        s (rect 50  100 30 30)
        out (snap/snap m [s] 6)]
    (is (= 0 (:dx out)))
    (is (= 5 (:dy out)))
    (is (= :horizontal (-> out :guides first :axis)))
    (is (= 100 (-> out :guides first :y)))))

;; --- both axes snap independently --------------------------------------

(deftest snap-x-and-y-can-snap-to-different-siblings
  (let [m  (rect 95 95 20 20)
        s1 (rect 100 0 50 50)         ;; x=100 (close to m.left=95)
        s2 (rect 0   100 50 50)       ;; y=100 (close to m.top=95)
        out (snap/snap m [s1 s2] 6)]
    (is (= 5 (:dx out)) "x snapped to s1's left")
    (is (= 5 (:dy out)) "y snapped to s2's top")
    (is (= 2 (count (:guides out))))
    (is (some #(and (= :vertical   (:axis %)) (= 100 (:x %))) (:guides out)))
    (is (some #(and (= :horizontal (:axis %)) (= 100 (:y %))) (:guides out)))))

;; --- closest sibling wins ----------------------------------------------

(deftest snap-picks-closest-when-multiple-candidates
  (testing "Among several near siblings, the smallest |dx| wins"
    (let [m  (rect 100 100 50 50)         ;; left = 100
          s1 (rect 95 0 30 30)            ;; left = 95 → dx = -5
          s2 (rect 102 0 30 30)           ;; left = 102 → dx = +2
          s3 (rect 80 0 30 30)            ;; left = 80 → dx = -20 (out)
          out (snap/snap m [s1 s2 s3] 6)]
      (is (= 2 (:dx out)) "should pick s2 (|dx|=2 < |dx|=5 < threshold=6)")
      (is (= 102 (-> out :guides first :x))))))

(deftest snap-threshold-is-inclusive
  (let [m (rect 94 100 50 50)
        s (rect 100 0 50 50)
        out (snap/snap m [s] 6)]
    (is (= 6 (:dx out)) "exactly-threshold snap is taken")))

;; --- guide extents -----------------------------------------------------

(deftest snap-vertical-guide-spans-both-rects
  (testing "A vertical guide's y0/y1 cover both moving and sibling
            after the snap so the line connects them visually"
    (let [m  (rect 92 50  20 20)          ;; m: top=50, bottom=70
          s  (rect 100 200 20 20)         ;; s: top=200, bottom=220
          out (snap/snap m [s] 10)
          g  (first (:guides out))]
      (is (= :vertical (:axis g)))
      (is (= 50 (:y0 g))  "top edge of dragged is the upper bound")
      (is (= 220 (:y1 g)) "bottom edge of sibling is the lower bound"))))

(deftest snap-horizontal-guide-spans-both-rects
  (let [m  (rect 50  92  20 20)           ;; m: left=50, right=70
        s  (rect 200 100 20 20)           ;; s: left=200, right=220
        out (snap/snap m [s] 10)
        g  (first (:guides out))]
    (is (= :horizontal (:axis g)))
    (is (= 50 (:x0 g)))
    (is (= 220 (:x1 g)))))

;; --- rect-from-layout / parse-px helpers --------------------------------

(deftest rect-from-layout-numeric
  (is (= {:left 10 :top 20 :right 60 :bottom 100}
         (snap/rect-from-layout {:x 10 :y 20 :w 50 :h 80}))))

(deftest rect-from-layout-handles-nil-fields
  (testing "Missing x/y/w/h default to 0 so a partial layout doesn't
            blow up — the snap planner can call without checking"
    (is (= {:left 0 :top 0 :right 0 :bottom 0}
           (snap/rect-from-layout {})))
    (is (= {:left 0 :top 5 :right 0 :bottom 5}
           (snap/rect-from-layout {:y 5})))))

(deftest parse-px-handles-px-suffix-and-bare-numbers
  (is (= 123  (snap/parse-px "123px")))
  (is (= 123.5 (snap/parse-px "123.5px")))
  (is (= 200  (snap/parse-px "200")))
  (is (nil? (snap/parse-px "")))
  (is (nil? (snap/parse-px nil)))
  (is (nil? (snap/parse-px "auto")))
  (is (nil? (snap/parse-px "abc"))))
