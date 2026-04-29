(ns bareforge.meta.patterns-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.string :as str]
            [bareforge.meta.patterns :as patterns]
            [bareforge.meta.registry :as registry]))

(deftest patterns-for-known-tag
  (let [pats (patterns/patterns-for "x-button")]
    (is (some? pats))
    (is (every? :id    pats))
    (is (every? :label pats))
    (testing "every entry carries an :overrides map (possibly empty)"
      (is (every? #(map? (:overrides %)) pats)))))

(deftest patterns-for-unknown-tag-is-nil
  (is (nil? (patterns/patterns-for "x-no-such-thing"))))

(deftest pattern-ids-unique-per-tag
  (doseq [[tag pats] (group-by first
                               (mapcat (fn [t]
                                         (when-let [pats (patterns/patterns-for t)]
                                           (mapv (fn [p] [t (:id p)]) pats)))
                                       (patterns/covered-tags)))]
    (let [ids (mapv second pats)]
      (is (= (count ids) (count (distinct ids)))
          (str "duplicate pattern ids on tag " tag)))))

(deftest covered-tags-are-registered
  (let [registered (set (registry/all-tags))]
    (doseq [tag (patterns/covered-tags)]
      (is (contains? registered tag)
          (str tag " has patterns but isn't a registered BareDOM tag")))))

(deftest coverage-warning
  ;; This test never fails — it just prints uncovered tags so a fresh
  ;; PR carrying a new BareDOM bump shows the gap in CI logs.
  (let [registered (set (registry/all-tags))
        covered    (patterns/covered-tags)
        uncovered  (sort (set/difference registered covered))]
    (when (seq uncovered)
      (.warn js/console
             (str "[patterns] " (count uncovered)
                  " tags have no patterns yet: "
                  (str/join ", " (take 12 uncovered))
                  (when (> (count uncovered) 12) "…"))))
    (is true)))
