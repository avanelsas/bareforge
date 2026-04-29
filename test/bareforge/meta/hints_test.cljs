(ns bareforge.meta.hints-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.meta.hints :as hints]
            [bareforge.meta.registry :as registry]))

(deftest hint-for-known-tag
  (is (string? (hints/hint-for "x-card"))))

(deftest hint-for-unknown-tag-is-nil
  (is (nil? (hints/hint-for "x-no-such-thing"))))

(deftest covered-tags-are-registered
  (let [registered (set (registry/all-tags))]
    (doseq [tag (hints/covered-tags)]
      (is (contains? registered tag)
          (str tag " has a hint but isn't a registered BareDOM tag")))))

(deftest hints-fit-the-empty-footprint
  (testing "every hint stays under 28 chars so it fits the dashed
            empty-state region without wrapping"
    (doseq [tag (hints/covered-tags)]
      (let [h (hints/hint-for tag)]
        (is (<= (count h) 28)
            (str "hint for " tag " is " (count h) " chars: " h))))))
