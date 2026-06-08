(ns bareforge.meta.probes-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.meta.probes :as probes]))

(deftest overlay-components-have-selection-selectors
  (testing "the four overlay components map to their visible shadow surface"
    (is (= ".panel"        (probes/selection-selector "x-sidebar")))
    (is (= "[part=panel]"  (probes/selection-selector "x-drawer")))
    (is (= "[part=dialog]" (probes/selection-selector "x-modal")))
    (is (= "[part=panel]"  (probes/selection-selector "x-popover")))))

(deftest ordinary-tags-have-no-selector
  (testing "host box is the right measure for non-overlay components"
    (is (nil? (probes/selection-selector "x-button")))
    (is (nil? (probes/selection-selector "x-card")))
    (is (nil? (probes/selection-selector "x-some-unknown")))))
