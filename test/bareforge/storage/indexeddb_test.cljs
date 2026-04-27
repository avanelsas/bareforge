(ns bareforge.storage.indexeddb-test
  (:require [cljs.test :refer [deftest is]]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.state :as state]
            [bareforge.storage.indexeddb :as idb]))

(deftest serialize-shape
  (let [s (state/initial-state)
        p (idb/serialize s)]
    (is (= "bareforge-project" (:format p)))
    (is (= 1 (:version p)))
    (is (= (:document s) (:document p)))
    (is (= (:theme    s) (:theme    p)))))

(deftest round-trip-empty-document
  (let [s (state/initial-state)
        r (idb/deserialize (pr-str (idb/serialize s)))]
    (is (= (:document s) (:document r)))
    (is (= (:theme    s) (:theme    r)))))

(deftest round-trip-populated-document
  (let [s0                    (state/initial-state)
        d0                    (:document s0)
        {d1 :doc id-card :id} (ops/insert-new d0 "root" "default" 0 "x-card")
        {d2 :doc}             (ops/insert-new d1 id-card "default" 0 "x-typography"
                                              {:text "Hello"
                                               :attrs {"variant" "h1"}})
        {d3 :doc}             (ops/insert-new d2 "root" "default" 1 "x-button"
                                              {:text "Click me"
                                               :attrs {"variant" "primary"}
                                               :props {:disabled true}})
        s1                    (assoc s0 :document d3
                                        :theme {:base-preset "aurora"
                                                :overrides {"--x-color-primary" "#ff00aa"
                                                            "--x-radius-lg"    "24px"}})
        r                     (idb/deserialize (pr-str (idb/serialize s1)))]
    (is (= (:document s1) (:document r)))
    (is (= (:theme    s1) (:theme    r)))
    (is (= "Hello"
           (get-in r [:document :root :slots "default" 0 :slots "default" 0 :text])))
    (is (= true
           (get-in r [:document :root :slots "default" 1 :props :disabled])))
    (is (= "#ff00aa"
           (get-in r [:theme :overrides "--x-color-primary"])))))

(deftest deserialize-rejects-garbage
  (is (nil? (idb/deserialize "not edn")))
  (is (nil? (idb/deserialize "{:no-format 1}")))
  (is (nil? (idb/deserialize "{:format \"other\" :document {}}")))
  (is (nil? (idb/deserialize nil))))

(deftest deserialize-accepts-minimal-valid
  (is (some? (idb/deserialize (pr-str {:format "bareforge-project"
                                        :version 1
                                        :document (m/empty-document)
                                        :theme {}})))))
