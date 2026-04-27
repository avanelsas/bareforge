(ns bareforge.doc.model-test
  (:require [cljs.test :refer [deftest is]]
            [bareforge.doc.model :as m]))

(deftest make-node-defaults
  (let [n (m/make-node "a" "x-button")]
    (is (= "a" (:id n)))
    (is (= "x-button" (:tag n)))
    (is (= {} (:attrs n)))
    (is (= {} (:props n)))
    (is (= {} (:slots n)))
    (is (nil? (:text n)))
    (is (nil? (:inner-html n)))
    (is (= :flow (get-in n [:layout :placement])))))

(deftest make-node-passes-inner-html
  (let [n (m/make-node "a" "x-icon" {:inner-html "<svg/>"})]
    (is (= "<svg/>" (:inner-html n)))))

(deftest make-node-accepts-overrides
  (let [n (m/make-node "a" "x-button"
                       {:attrs {"variant" "primary"}
                        :props {:disabled true}
                        :text  "Go"
                        :layout {:placement :free}})]
    (is (= {"variant" "primary"} (:attrs n)))
    (is (= {:disabled true} (:props n)))
    (is (= "Go" (:text n)))
    (is (= :free (get-in n [:layout :placement])))))

(deftest empty-document-has-root
  (let [d (m/empty-document)]
    (is (= "root" (get-in d [:root :id])))
    (is (= "x-container" (get-in d [:root :tag])))
    (is (= 0 (:next-id d)))
    (is (= [] (get-in d [:root :slots "default"])))))

(deftest path-to-root
  (is (= [:root] (m/path-to (m/empty-document) "root"))))

(deftest path-to-missing
  (is (nil? (m/path-to (m/empty-document) "nope"))))

(deftest walk-nodes-single
  (is (= 1 (count (m/walk-nodes (m/empty-document))))))

(deftest subtree-ids-for-root-equals-all-ids
  (let [d (m/empty-document)]
    (is (= #{"root"} (m/subtree-ids d "root")))))

(deftest parent-of-root-returns-nil
  (is (nil? (m/parent-of (m/empty-document) "root"))))

(deftest parent-of-missing-returns-nil
  (is (nil? (m/parent-of (m/empty-document) "nope"))))
