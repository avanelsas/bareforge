(ns bareforge.render.slot-strips-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.meta.slots :as slots]
            [bareforge.render.slot-strips :as ss]))

(deftest render-strips-predicate-table
  (testing "every explicitly-registered container tag with slots qualifies"
    ;; `render-strips?` composes with `registry/container?`, which
    ;; requires at least one `:multiple? true` slot. x-card / x-grid /
    ;; x-container are single-slot containers (they get one full-size
    ;; strip with a label). x-navbar / x-modal / x-drawer / x-popover /
    ;; x-split-pane are multi-slot containers (they get N subdivisions).
    ;; Leaves like x-button / x-typography / x-icon have no :multiple?
    ;; slot, so `classify-position` never returns :inside for them and
    ;; the strips would be dead zones — `render-strips?` excludes them.
    (let [all-tags   (keys slots/slots)
          qualifiers (set (filter ss/render-strips? all-tags))]
      (is (= #{"x-card" "x-grid" "x-container"
               "x-navbar" "x-modal" "x-drawer" "x-popover" "x-split-pane"}
             qualifiers)
          "eight container tags qualify; leaves (x-button / x-typography / x-icon) do not")))
  (testing "unknown tags fall through to single-slot default and return false"
    (is (false? (ss/render-strips? "x-made-up-tag")))
    (is (false? (ss/render-strips? nil)))
    (is (false? (ss/render-strips? "")))))

(defn- host-rect
  ([]       {:left 0 :top 0 :width 1000 :height 800})
  ([l t w h] {:left l :top t :width w :height h}))

(deftest strip-rects-three-equal-strips
  ;; Element at screen coords (10, 20) 300x60, host at origin with no scroll.
  (let [el    {:left 10 :top 20 :width 300 :height 60}
        rects (ss/strip-rects el (host-rect) 0 0 3)]
    (is (= 3 (count rects)))
    (testing "each strip has the same height and y"
      (is (every? #(= 60 (:height %)) rects))
      (is (every? #(= 20 (:top %))    rects)))
    (testing "strips tile without gaps or overlap"
      (is (= 10 (:left (first rects))))
      (let [right (fn [r] (+ (:left r) (:width r)))]
        (is (= (:left (nth rects 1)) (right (first  rects))))
        (is (= (:left (nth rects 2)) (right (nth rects 1))))
        (is (= 310 (right (last rects))) "right edge equals element's right")))
    (testing "equal widths for a cleanly-divisible element"
      (is (= [100 100 100] (mapv :width rects))))))

(deftest strip-rects-six-strips-for-navbar
  (let [el    {:left 0 :top 0 :width 900 :height 48}
        rects (ss/strip-rects el (host-rect) 0 0 6)]
    (is (= 6 (count rects)))
    (is (= [150 150 150 150 150 150] (mapv :width rects)))))

(deftest strip-rects-non-divisible-width-still-tiles
  ;; 100 / 3 is not a whole number; strips must still cover [0, 100]
  ;; exactly with integer pixels — the last strip absorbs the
  ;; rounding residue.
  (let [el    {:left 0 :top 0 :width 100 :height 40}
        rects (ss/strip-rects el (host-rect) 0 0 3)]
    (is (= 3 (count rects)))
    (let [right (fn [r] (+ (:left r) (:width r)))]
      (is (= 0   (:left (first rects))))
      (is (= 100 (right (last rects)))
          "last strip reaches the element's right edge despite floor()")
      (is (= (:left (nth rects 1)) (right (first rects))))
      (is (= (:left (nth rects 2)) (right (nth rects 1)))))
    (is (= 100 (apply + (map :width rects)))
        "widths sum to element width — no pixel lost or duplicated")))

(deftest strip-rects-applies-host-offset-and-scroll
  ;; Element at screen (120, 90); host positioned at (20, 40), scrolled (50, 30).
  (let [el    {:left 120 :top 90 :width 300 :height 60}
        host  (host-rect 20 40 1000 800)
        rects (ss/strip-rects el host 50 30 3)]
    (testing "first strip's :left accounts for host origin and scroll"
      ;; (120 - 20) + 50 = 150
      (is (= 150 (:left (first rects)))))
    (testing ":top also normalised"
      ;; (90 - 40) + 30 = 80
      (is (every? #(= 80 (:top %)) rects)))
    (testing "strips tile within host-content coords"
      (let [right (fn [r] (+ (:left r) (:width r)))]
        ;; host-content right edge of element: 150 + 300 = 450
        (is (= 450 (right (last rects))))))))

(deftest strip-rects-degenerate-guards
  (is (= [] (ss/strip-rects {:left 0 :top 0 :width 100 :height 10}
                            (host-rect) 0 0 0))
      "n=0 returns an empty vec instead of dividing by zero")
  (is (= 1 (count (ss/strip-rects {:left 0 :top 0 :width 50 :height 10}
                                  (host-rect) 0 0 1)))
      "n=1 still renders (though multi-slot? would normally gate it out)"))
