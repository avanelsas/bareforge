(ns bareforge.ui.inline-edit-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.ui.inline-edit :as ie]))

(deftest text-editable-mode-attr-components
  (testing "components with a 'text' augment property return :attr"
    (doseq [tag ["x-alert" "x-badge" "x-copy"
                  "x-kinetic-typography" "x-kinetic-font"]]
      (let [node {:id "n" :tag tag :attrs {} :props {} :slots {} :text nil
                  :layout {:placement :flow}}]
        (is (= :attr (ie/text-editable-mode tag node))
            (str tag " should return :attr"))))))

(deftest text-editable-mode-child-components
  (testing "x-typography and x-button return :child"
    (doseq [tag ["x-typography" "x-button"]]
      (let [node {:id "n" :tag tag :attrs {} :props {} :slots {} :text nil
                  :layout {:placement :flow}}]
        (is (= :child (ie/text-editable-mode tag node))
            (str tag " should return :child"))))))

(deftest text-editable-mode-child-when-text-set
  (testing "a node with :text set returns :child even if tag is unusual"
    (let [node {:id "n" :tag "x-typography" :attrs {} :props {}
                :slots {} :text "Hello" :layout {:placement :flow}}]
      (is (= :child (ie/text-editable-mode "x-typography" node))))))

(deftest text-editable-mode-nil-for-non-text-components
  (testing "containers, cards, icons, etc. return nil"
    (doseq [tag ["x-container" "x-card" "x-grid" "x-icon"
                  "x-spinner" "x-navbar"]]
      (let [node {:id "n" :tag tag :attrs {} :props {} :slots {} :text nil
                  :layout {:placement :flow}}]
        (is (nil? (ie/text-editable-mode tag node))
            (str tag " should return nil"))))))
