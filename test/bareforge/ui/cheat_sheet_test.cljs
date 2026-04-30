(ns bareforge.ui.cheat-sheet-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.ui.cheat-sheet :as cheat-sheet]
            [bareforge.ui.shortcuts :as sh]))

(def ^:private fake-categories
  [[:editing    "Editing"]
   [:selection  "Selection"]
   [:navigation "Navigation"]
   [:view       "View"]])

(deftest group-rows-empty-info
  (is (= [] (cheat-sheet/group-rows [] fake-categories))
      "no entries → no groups"))

(deftest group-rows-single-category
  (let [info [{:category :editing :keys "X" :label "x"}
              {:category :editing :keys "Y" :label "y"}]
        out  (cheat-sheet/group-rows info fake-categories)]
    (is (= 1 (count out)))
    (is (= "Editing" (-> out first first)))
    (is (= 2 (count (-> out first second))))))

(deftest group-rows-preserves-category-order
  (testing "output order matches category-labels order, even when
            input entries land in a different order"
    (let [info [{:category :view       :keys "?" :label "help"}
                {:category :navigation :keys "Esc" :label "deselect"}
                {:category :editing    :keys "Cmd+Z" :label "undo"}]
          out  (cheat-sheet/group-rows info fake-categories)]
      (is (= ["Editing" "Navigation" "View"]
             (mapv first out))
          ":selection drops out (no entries); the rest follow declared order"))))

(deftest group-rows-drops-empty-categories
  (let [info [{:category :editing :keys "Cmd+Z" :label "undo"}]
        out  (cheat-sheet/group-rows info fake-categories)]
    (is (= 1 (count out)))
    (is (= "Editing" (-> out first first)))))

(deftest group-rows-against-real-shortcut-info
  (testing "the live shortcut-info renders with no missing
            categories or empty groups"
    (let [out (cheat-sheet/group-rows sh/shortcut-info sh/category-labels)]
      (is (pos? (count out)))
      (is (every? string? (mapv first out)))
      (is (every? (fn [[_ entries]] (pos? (count entries))) out)
          "no group should appear with zero entries"))))
