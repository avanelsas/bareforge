(ns bareforge.doc.spec-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.doc.spec :as ds]
            [bareforge.storage.indexeddb :as idb]
            [bareforge.storage.project-file :as pf]
            [clojure.spec.alpha :as s]))

;; --- layout ::width vs canvas ::width collision regression --------------

(deftest layout-width-accepts-css-length-string
  (testing "a layout map with a CSS length string :width conforms
            — the old ::width / ::width collision used to reject it
            because the canvas pos-int? spec was overriding the
            layout string spec."
    (let [layout {:placement :flow :width "100%" :height "50vh"}]
      (is (s/valid? ::ds/layout layout)))))

(deftest layout-width-accepts-nil
  (is (s/valid? ::ds/layout {:placement :flow :width nil :height nil})))

(deftest layout-width-rejects-number
  (is (not (s/valid? ::ds/layout {:placement :flow :width 100}))))

(deftest canvas-width-requires-positive-int
  (is (s/valid? ::ds/canvas {:width 1200 :content-col {:left 0 :right 1200}}))
  (is (not (s/valid? ::ds/canvas {:width "1200px" :content-col {:left 0 :right 1200}})))
  (is (not (s/valid? ::ds/canvas {:width 0 :content-col {:left 0 :right 1200}}))))

;; --- ::document sanity ---------------------------------------------------

(deftest empty-document-conforms
  (is (s/valid? ::ds/document (m/empty-document))))

(deftest document-with-layout-widths-conforms
  (let [d0        (m/empty-document)
        {d1 :doc id :id} (ops/insert-new d0 "root" "default" 0 "x-button")
        d2        (ops/set-layout d1 id :width "200px")
        d3        (ops/set-layout d2 id :height "50px")]
    (is (s/valid? ::ds/document d3))))

(deftest document-with-inner-html-conforms
  (let [d0               (m/empty-document)
        {d1 :doc id :id} (ops/insert-new d0 "root" "default" 0 "x-icon")
        d2               (ops/set-inner-html d1 id "<svg><circle/></svg>")]
    (is (s/valid? ::ds/document d2))))

(deftest document-rejects-bad-placement
  (let [doc (assoc-in (m/empty-document)
                      [:root :layout :placement] :sideways)]
    (is (not (s/valid? ::ds/document doc)))))

;; --- ::project-file wrapper ---------------------------------------------

(defn- valid-project-payload []
  (idb/serialize {:document (m/empty-document)
                  :theme    {:base-preset "ocean" :overrides {}}}))

(deftest project-file-happy-path
  (is (s/valid? ::ds/project-file (valid-project-payload)))
  (is (nil? (pf/validate-project (valid-project-payload)))))

(deftest project-file-rejects-missing-format
  (let [payload (dissoc (valid-project-payload) :format)]
    (is (not (s/valid? ::ds/project-file payload)))
    (is (some? (pf/validate-project payload)))))

(deftest project-file-rejects-wrong-format-string
  (let [payload (assoc (valid-project-payload) :format "some-other-thing")]
    (is (not (s/valid? ::ds/project-file payload)))
    (is (some? (pf/validate-project payload)))))

(deftest project-file-rejects-missing-document
  (let [payload (dissoc (valid-project-payload) :document)]
    (is (not (s/valid? ::ds/project-file payload)))
    (is (some? (pf/validate-project payload)))))

(deftest project-file-rejects-malformed-document
  (testing "a document with a broken node (no :tag) fails validation"
    (let [payload (assoc-in (valid-project-payload)
                            [:document :root :tag] nil)]
      (is (not (s/valid? ::ds/project-file payload)))
      (is (some? (pf/validate-project payload))))))

(deftest project-file-rejects-bad-placement-in-nested-node
  (let [d0        (m/empty-document)
        {d1 :doc} (ops/insert-new d0 "root" "default" 0 "x-button")
        payload   (-> (valid-project-payload)
                      (assoc :document d1)
                      (assoc-in [:document :root :slots "default" 0
                                 :layout :placement]
                                :bogus))]
    (is (not (s/valid? ::ds/project-file payload)))
    (is (some? (pf/validate-project payload)))))

(deftest validate-project-nil-input-is-nil
  (testing "validate-project on nil returns nil (not an explanation)
            so the open! branch can distinguish 'deserialize failed'
            from 'deserialize succeeded but spec rejected it'"
    (is (nil? (pf/validate-project nil)))))
