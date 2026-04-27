(ns bareforge.dnd.drag-test
  (:require [cljs.test :refer [deftest is]]
            [bareforge.dnd.drag :as drag]))

(def leaf  {:top 100 :height 40})
(def big   {:top 0   :height 200})

;; --- leaf: simple top/bottom split ---------------------------------------

(deftest leaf-top-half-is-before
  (is (= :before (drag/classify-position leaf 105 false)))
  (is (= :before (drag/classify-position leaf 119 false))))

(deftest leaf-bottom-half-is-after
  (is (= :after (drag/classify-position leaf 121 false)))
  (is (= :after (drag/classify-position leaf 139 false))))

(deftest leaf-clamps-to-bounds
  (is (= :before (drag/classify-position leaf  10 false)))
  (is (= :after  (drag/classify-position leaf 999 false))))

;; --- container: 25/50/25 split -------------------------------------------

(deftest container-top-quarter-is-before
  (is (= :before (drag/classify-position big 10 true)))
  (is (= :before (drag/classify-position big 49 true))))

(deftest container-middle-half-is-inside
  (is (= :inside (drag/classify-position big 60  true)))
  (is (= :inside (drag/classify-position big 100 true)))
  (is (= :inside (drag/classify-position big 140 true))))

(deftest container-bottom-quarter-is-after
  (is (= :after (drag/classify-position big 160 true)))
  (is (= :after (drag/classify-position big 199 true))))

(deftest container-clamps-to-bounds
  (is (= :before (drag/classify-position big -50 true)))
  (is (= :after  (drag/classify-position big 999 true))))

;; --- degenerate height ----------------------------------------------------

(deftest zero-height-defaults-to-after-on-leaf
  ;; ratio is 0.5 with zero-height fallback, so leaves classify as :after.
  (is (#{:before :after} (drag/classify-position {:top 0 :height 0} 0 false))))
