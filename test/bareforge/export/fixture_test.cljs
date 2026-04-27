(ns bareforge.export.fixture-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [cljs.reader :as edn]
            [bareforge.doc.spec :as spec]
            [bareforge.doc.model :as m]))

(def ^:private fixture-path "test/fixtures/export/demo-store.json")

(defn- read-fixture []
  (let [fs (js/require "fs")]
    (edn/read-string (.readFileSync fs fixture-path "utf8"))))

(deftest demo-store-fixture-parses
  (let [parsed (read-fixture)]
    (is (some? parsed))
    (is (= "bareforge-project" (:format parsed)))
    (is (= 1 (:version parsed)))))

(deftest demo-store-fixture-conforms-to-spec
  (let [parsed (read-fixture)]
    (is (s/valid? ::spec/project-file parsed)
        (str "spec failures: "
             (s/explain-str ::spec/project-file parsed)))))

(deftest demo-store-fixture-has-expected-structure
  (let [parsed (read-fixture)
        doc    (:document parsed)
        nodes  (m/walk-nodes doc)
        tags   (set (map :tag nodes))]
    (testing "contains all expected component types"
      (is (contains? tags "x-navbar"))
      (is (contains? tags "x-search-field"))
      (is (contains? tags "x-badge"))
      (is (contains? tags "x-card"))
      (is (contains? tags "x-button"))
      (is (contains? tags "x-typography"))
      (is (contains? tags "x-spacer")))
    (testing "node count matches hand-built example"
      (is (= 22 (count nodes))))
    (testing "all node ids are unique"
      (let [ids (map :id nodes)]
        (is (= (count ids) (count (set ids))))))))

;; --- demo-store-blank fixture ---------------------------------------------
;; Same component tree as demo-store.json but with every bareforge data
;; annotation stripped: no :name, :fields, :actions, :events, :bindings,
;; :text-field, :source-field, :source-sub anywhere. Intended to be
;; loaded into Bareforge as a starting point — the user adds group
;; names, fields, bindings, and event attachments interactively through
;; the inspector. The node/tag inventory mirrors demo-store so manual
;; testing of the v1 inspector flow on a realistic layout is one click
;; away.

(def ^:private blank-fixture-path "test/fixtures/export/demo-store-blank.json")

(defn- read-blank-fixture []
  (let [fs (js/require "fs")]
    (edn/read-string (.readFileSync fs blank-fixture-path "utf8"))))

(deftest demo-store-blank-fixture-parses
  (let [parsed (read-blank-fixture)]
    (is (some? parsed))
    (is (= "bareforge-project" (:format parsed)))
    (is (= 1 (:version parsed)))))

(deftest demo-store-blank-fixture-conforms-to-spec
  (let [parsed (read-blank-fixture)]
    (is (s/valid? ::spec/project-file parsed)
        (str "spec failures: "
             (s/explain-str ::spec/project-file parsed)))))

(deftest demo-store-blank-matches-demo-store-design
  (let [orig  (:document (read-fixture))
        blank (:document (read-blank-fixture))
        o-n   (vec (m/walk-nodes orig))
        b-n   (vec (m/walk-nodes blank))]
    (testing "same node count, tags, and ids"
      (is (= (count o-n) (count b-n)))
      (is (= (sort (map :tag o-n)) (sort (map :tag b-n))))
      (is (= (sort (map :id o-n))  (sort (map :id b-n)))))
    (testing "every bareforge data annotation is absent"
      (doseq [k [:name :fields :actions :events :bindings
                 :text-field :source-field :source-sub]]
        (is (empty? (filter #(contains? % k) b-n))
            (str k " must not appear on any node"))))))
