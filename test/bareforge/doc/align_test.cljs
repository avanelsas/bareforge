(ns bareforge.doc.align-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.doc.align :as align]))

(defn- r [x y w h]
  {:left x :top y :w w :h h})

;; --- align-rects -------------------------------------------------------

(deftest align-left-pulls-every-rect-to-bbox-left
  (let [rs  [(r 100 0 50 50) (r 60 100 30 30) (r 120 200 40 40)]
        out (align/align-rects rs :left)]
    (is (= 60 (-> out (nth 0) :left)))
    (is (= 60 (-> out (nth 1) :left)))
    (is (= 60 (-> out (nth 2) :left)))
    (testing "y / w / h are unchanged"
      (is (= [0 100 200] (mapv :top out)))
      (is (= [50 30 40]  (mapv :w   out))))))

(deftest align-right-pulls-every-rect-to-bbox-right
  (let [rs  [(r 100 0 50 50) (r 60 100 30 30) (r 120 200 40 40)]
        ;; bbox.right = max(left+w) = max(150, 90, 160) = 160
        out (align/align-rects rs :right)]
    (is (= [110 130 120] (mapv :left out))
        "every left = bbox.right - rect.w (= 160 - w)")))

(deftest align-cx-centers-on-bbox-center
  (let [rs  [(r 0 0 100 50) (r 200 100 40 30)]
        ;; bbox: left 0, right 240, cx = 120
        out (align/align-rects rs :cx)]
    (is (= [70 100] (mapv :left out))
        "left = 120 - w/2 → 120 - 50 = 70 ; 120 - 20 = 100")))

(deftest align-top-and-bottom
  (let [rs  [(r 0 50 30 30) (r 100 100 30 60) (r 200 80 30 20)]
        ;; bbox.top = 50, bbox.bottom = max(top+h) = max(80, 160, 100) = 160
        top (align/align-rects rs :top)
        bot (align/align-rects rs :bottom)]
    (is (= [50 50 50] (mapv :top top)))
    (is (= [130 100 140] (mapv :top bot))
        "top = bbox.bottom - h → 160 - 30 = 130 ; 160 - 60 = 100 ; 160 - 20 = 140")))

(deftest align-cy
  (let [rs  [(r 0 0 30 100) (r 100 200 30 40)]
        ;; bbox.top 0, bottom 240, cy = 120
        out (align/align-rects rs :cy)]
    (is (= [70 100] (mapv :top out))
        "top = 120 - h/2 → 120 - 50 = 70 ; 120 - 20 = 100")))

(deftest align-with-fewer-than-two-rects-is-noop
  (testing "Single rect or empty input passes through unchanged"
    (is (= [] (align/align-rects [] :left)))
    (let [single [(r 50 50 20 20)]]
      (is (= single (align/align-rects single :left))
          "alignment of a lone rect is meaningless — no-op"))))

(deftest align-preserves-input-order
  (testing "Output order matches input even when bbox edges come from
            different positions in the vector"
    (let [rs  [(r 200 0 30 30)   ;; rightmost
               (r 50  0 30 30)   ;; leftmost — bbox.left
               (r 130 0 30 30)]  ;; middle
          out (align/align-rects rs :left)]
      (is (= [50 50 50] (mapv :left out))
          "all three pulled to bbox.left = 50, in original order"))))

;; --- distribute-rects --------------------------------------------------

(deftest distribute-horizontal-spaces-centers
  (testing "Three rects at cx 100 / 110 / 200 distribute to cx 100 / 150 / 200"
    (let [;; cx targets: a=100, b=300, then redistribute
          rs  [(r  90 0 20 20)   ;; cx 100
               (r 100 0 20 20)   ;; cx 110
               (r 290 0 20 20)]  ;; cx 300
          out (align/distribute-rects rs :horizontal)]
      (is (= [100 200 300] (mapv #(+ (:left %) (/ (:w %) 2)) out))
          "centers should be 100, 200, 300 — first/last anchored, middle moves to halfway"))))

(deftest distribute-vertical-spaces-centers
  (let [rs  [(r 0  0  20 20)    ;; cy 10
             (r 0  20 20 20)    ;; cy 30 (will move to cy 50)
             (r 0  80 20 20)]   ;; cy 90
        out (align/distribute-rects rs :vertical)
        cys (mapv #(+ (:top %) (/ (:h %) 2)) out)]
    (is (= [10 50 90] cys))))

(deftest distribute-fewer-than-three-is-noop
  (testing "Distribution requires anchors + at least one middle rect"
    (is (= [] (align/distribute-rects [] :horizontal)))
    (let [one [(r 0 0 20 20)]]
      (is (= one (align/distribute-rects one :horizontal))))
    (let [two [(r 0 0 20 20) (r 100 0 20 20)]]
      (is (= two (align/distribute-rects two :horizontal))))))

(deftest distribute-handles-already-evenly-spaced
  (testing "When centers are already equidistant, output equals input"
    (let [rs  [(r 0   0 20 20)
               (r 100 0 20 20)
               (r 200 0 20 20)]
          out (align/distribute-rects rs :horizontal)]
      (is (= rs out)))))

(deftest distribute-preserves-input-order
  (testing "Output is in input order even though the math sorts by center"
    (let [rs  [(r 290 0 20 20)   ;; rightmost in input order is index 0
               (r  90 0 20 20)
               (r 100 0 20 20)]
          out (align/distribute-rects rs :horizontal)
          cys (mapv #(+ (:left %) (/ (:w %) 2)) out)]
      ;; After sort: leftmost cx 100, middle cx 110, rightmost cx 300.
      ;; Anchors stay at 100 / 300, middle moves to 200.
      (is (= 300 (nth cys 0)) "input-index 0 was rightmost — stays at 300")
      (is (= 100 (nth cys 1)) "input-index 1 was leftmost — stays at 100")
      (is (= 200 (nth cys 2)) "input-index 2 was middle — redistributed to 200"))))

;; --- alignable? / distributable? --------------------------------------

(defn- node [x y w h]
  {:layout {:x x :y y :w w :h h}})

(deftest alignable-needs-two-numeric-layouts
  (is (false? (align/alignable? [])))
  (is (false? (align/alignable? [(node 0 0 10 10)])))
  (is (true?  (align/alignable? [(node 0 0 10 10) (node 5 5 10 10)])))
  (testing "non-numeric layout doesn't count"
    (is (false? (align/alignable? [(node 0 0 10 10)
                                   {:layout {:width "100px"}}]))
        "a CSS-string sized node leaves only one numeric — fewer than two"))
  (testing "no layout at all → nope"
    (is (false? (align/alignable? [{} {} {}])))))

(deftest distributable-needs-three-numeric-layouts
  (is (false? (align/distributable?
               [(node 0 0 10 10) (node 1 1 10 10)])))
  (is (true?  (align/distributable?
               [(node 0 0 10 10) (node 1 1 10 10) (node 2 2 10 10)]))))

;; --- nodes->rects ------------------------------------------------------

(deftest nodes->rects-filters-non-numeric
  (let [nodes [{:id "a" :layout {:x 10 :y 20 :w 30 :h 40}}
               {:id "b" :layout {:width "100px"}}     ;; no x/y → drop
               {:id "c" :layout {:x 50 :y 60 :w 70 :h 80}}]
        rects (align/nodes->rects nodes)]
    (is (= 2 (count rects)))
    (is (= "a" (-> rects (nth 0) :id)))
    (is (= "c" (-> rects (nth 1) :id)))))

(deftest alignment-kinds-set
  (is (= #{:left :cx :right :top :cy :bottom} align/alignment-kinds)))
