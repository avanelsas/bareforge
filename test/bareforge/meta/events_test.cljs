(ns bareforge.meta.events-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.meta.events :as events]))

(deftest sidebar-exposes-toggle-and-dismiss
  (testing "x-sidebar is interactive — its toggle/dismiss events surface
            in the inspector and can be wired in exports"
    (is (= ["toggle" "dismiss"] (events/events-for "x-sidebar")))))

(deftest overlay-dialogs-expose-toggle-and-dismiss
  (testing "x-drawer and x-modal dispatch namespaced toggle/dismiss events"
    (is (= ["x-drawer-toggle" "x-drawer-dismiss"] (events/events-for "x-drawer")))
    (is (= ["x-modal-toggle" "x-modal-dismiss"] (events/events-for "x-modal")))))

(deftest non-interactive-tags-return-nil
  (is (nil? (events/events-for "x-card")))
  (is (nil? (events/events-for "x-totally-made-up"))))
