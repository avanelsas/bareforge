(ns bareforge.ui.command-palette-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.ui.command-palette :as cp]))

;; --- ->item -------------------------------------------------------------

(deftest ->item-projects-fields
  (let [out (cp/->item 0 {:label "Save project"
                          :group "File"
                          :keywords "save"
                          :run! (constantly nil)})]
    (is (= "cmd-0" (.-id out)))
    (is (= "Save project" (.-label out)))
    (is (= "File" (.-group out)))
    (is (= "save" (.-keywords out)))))

(deftest ->item-uses-index-as-synthetic-id
  (testing "synthetic id format is stable so the select-event id
            can index back into the run-by-id map"
    (is (= "cmd-7" (.-id (cp/->item 7 {:label "x"}))))))

(deftest ->item-keywords-default-to-empty-string
  (testing "missing :keywords becomes an empty string so the
            x-command-palette filter doesn't trip on null"
    (let [out (cp/->item 0 {:label "x" :group "g"})]
      (is (= "" (.-keywords out))))))

;; --- curated-commands ---------------------------------------------------

(deftest curated-commands-shape
  (let [cmds (cp/curated-commands)]
    (is (pos? (count cmds)) "curated list is non-empty")
    (is (every? :label cmds))
    (is (every? :group cmds))
    (is (every? #(fn? (:run! %)) cmds)
        "every command's :run! is callable")))

(deftest curated-commands-no-duplicate-labels
  (let [labels (mapv :label (cp/curated-commands))]
    (is (= (count labels) (count (distinct labels)))
        "labels are unique within the curated list")))

(deftest curated-commands-only-known-groups
  (testing "curated entries land in one of the four declared
            groups so the palette's category headings stay
            consistent"
    (let [allowed #{"File" "View" "Selection"}]
      (doseq [c (cp/curated-commands)]
        (is (contains? allowed (:group c))
            (str "command " (:label c) " has unknown group "
                 (:group c)))))))

;; --- wrap-commands ------------------------------------------------------

(deftest wrap-commands-shape
  (let [cmds (cp/wrap-commands)]
    (is (pos? (count cmds)))
    (is (every? :label cmds))
    (is (every? #(= "Selection" (:group %)) cmds))
    (is (every? #(fn? (:run! %)) cmds))))

(deftest wrap-commands-label-references-tag
  (testing "every wrap entry's label embeds the wrap target tag"
    (doseq [c (cp/wrap-commands)]
      (is (re-find #"Wrap selection in x-" (:label c))))))
