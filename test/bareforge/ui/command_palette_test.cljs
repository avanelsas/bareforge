(ns bareforge.ui.command-palette-test
  (:require [cljs.test :refer [deftest is]]
            [bareforge.ui.command-palette :as cp]))

(def ^:private sample
  [{:label "Save project"        :group "File"}
   {:label "Open project…"       :group "File"}
   {:label "New project"         :group "File"}
   {:label "Toggle preview mode" :group "View"}
   {:label "Toggle theme editor" :group "View"}
   {:label "Insert x-button"     :group "Insert component"}
   {:label "Insert x-card"       :group "Insert component"}])

(deftest filter-empty-returns-input-as-vector
  (is (= sample (cp/filter-commands sample "")))
  (is (= sample (cp/filter-commands sample nil)))
  (is (= sample (cp/filter-commands sample "   "))))

(deftest filter-prefix-beats-substring
  (let [out (cp/filter-commands sample "tog")]
    (is (= "Toggle preview mode" (:label (first out)))
        "prefix match scores 0; sorts before any substring hits")))

(deftest filter-substring-match
  (let [out (cp/filter-commands sample "card")]
    (is (= 1 (count out)))
    (is (= "Insert x-card" (:label (first out))))))

(deftest filter-case-insensitive
  (let [out (cp/filter-commands sample "INSERT")]
    (is (= 2 (count out)))
    (is (every? (fn [c] (= "Insert component" (:group c))) out))))

(deftest filter-no-matches-returns-empty
  (is (= [] (cp/filter-commands sample "no-such-thing")))
  (is (vector? (cp/filter-commands sample "xyz"))))

(deftest filter-ties-sort-by-label
  (let [out (cp/filter-commands sample "project")]
    (is (= ["New project" "Open project…" "Save project"]
           (mapv :label out))
        "all three score the same (substring at position 5/6/12);
         labels sort alphabetically as the tiebreaker")))
