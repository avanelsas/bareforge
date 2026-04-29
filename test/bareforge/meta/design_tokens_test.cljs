(ns bareforge.meta.design-tokens-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.meta.design-tokens :as tokens]))

(deftest all-tokens-have-x-prefix
  (testing "every token name follows BareDOM's --x- convention"
    (doseq [{:keys [name]} tokens/all-tokens]
      (is (re-find #"^--x-" name)
          (str "token " name " is missing the --x- prefix")))))

(deftest all-tokens-have-known-category
  (let [allowed #{:color :length :font :shadow :motion :z :opacity}]
    (doseq [{:keys [name category]} tokens/all-tokens]
      (is (contains? allowed category)
          (str name " has unknown category " category)))))

(deftest tokens-for-filters-by-category
  (let [colors  (tokens/tokens-for :color)
        lengths (tokens/tokens-for :length)]
    (is (every? #(= :color  (:category %)) colors))
    (is (every? #(= :length (:category %)) lengths))
    (is (pos? (count colors))  "at least one colour token")
    (is (pos? (count lengths)) "at least one length token")))

(deftest var-of-wraps-in-css-var
  (is (= "var(--x-color-primary)"
         (tokens/var-of "--x-color-primary")))
  (is (= "var(--x-space-md)"
         (tokens/var-of "--x-space-md"))))

(deftest names-are-unique
  (let [names (mapv :name tokens/all-tokens)]
    (is (= (count names) (count (distinct names)))
        "no duplicate token names")))
