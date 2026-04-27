(ns bareforge.doc.ids-test
  (:require [cljs.test :refer [deftest is]]
            [bareforge.doc.ids :as ids]))

(deftest gen-produces-string-and-bumps-counter
  (let [[id n] (ids/gen 0)]
    (is (string? id))
    (is (= 1 n))
    (is (re-matches #"n_.+" id))))

(deftest gen-is-deterministic
  (is (= (ids/gen 5) (ids/gen 5))))

(deftest gen-produces-distinct-ids
  (let [[a _] (ids/gen 1)
        [b _] (ids/gen 2)]
    (is (not= a b))))
