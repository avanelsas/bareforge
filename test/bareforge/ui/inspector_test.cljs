(ns bareforge.ui.inspector-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.doc.ops :as ops]
            [bareforge.state :as state]
            [bareforge.ui.inspector :as insp]))

;; --- editor-spec ---------------------------------------------------------

(deftest editor-spec-boolean
  (is (= "x-switch" (:widget-tag (insp/editor-spec {:kind :boolean})))))

(deftest editor-spec-enum-passes-choices
  (let [spec (insp/editor-spec {:kind :enum :choices ["a" "b"]})]
    (is (= "x-select" (:widget-tag spec)))
    (is (= ["a" "b"]  (:choices spec)))))

(deftest editor-spec-string-long
  (is (= "x-text-area" (:widget-tag (insp/editor-spec {:kind :string-long})))))

(deftest editor-spec-string-short
  (is (= "x-search-field" (:widget-tag (insp/editor-spec {:kind :string-short})))))

(deftest editor-spec-number-annotates-type
  (let [spec (insp/editor-spec {:kind :number})]
    (is (= "x-search-field" (:widget-tag spec)))
    (is (= "number"         (:type spec)))))

(deftest editor-spec-unknown-fallback
  (is (= "x-search-field" (:widget-tag (insp/editor-spec {:kind :unknown}))))
  (is (= "x-search-field" (:widget-tag (insp/editor-spec {:kind :color})))))

;; --- display-label -------------------------------------------------------

(deftest display-label-plain
  (is (= "variant" (insp/display-label {:name "variant"}))))

(deftest display-label-label-attribute-gets-aria-suffix
  (is (= "label (aria)" (insp/display-label {:name "label"}))))

(deftest display-label-aria-prefix-gets-suffix
  (is (= "aria-label (aria)"      (insp/display-label {:name "aria-label"})))
  (is (= "aria-describedby (aria)" (insp/display-label {:name "aria-describedby"}))))

(deftest display-label-explicit-override-wins
  (is (= "Custom Label"
         (insp/display-label {:name "label" :display-name "Custom Label"}))))

;; --- current-value --------------------------------------------------------

(deftest current-value-reads-attr-for-non-boolean
  (let [node {:id "n" :tag "x-button" :attrs {"variant" "ghost"} :props {}}]
    (is (= "ghost" (insp/current-value node {:name "variant" :kind :enum})))))

(deftest current-value-reads-prop-for-boolean
  (let [node {:id "n" :tag "x-button" :attrs {} :props {:disabled true}}]
    (is (true? (insp/current-value node {:name "disabled" :kind :boolean})))))

(deftest current-value-nil-when-absent
  (let [node {:id "n" :tag "x-button" :attrs {} :props {}}]
    (is (nil? (insp/current-value node {:name "variant" :kind :enum})))))

;; --- inspector-model ------------------------------------------------------

(deftest inspector-model-nil-without-selection
  (is (nil? (insp/inspector-model (state/initial-state)))))

(deftest inspector-model-nil-for-missing-id
  (let [s (assoc (state/initial-state) :selection ["does-not-exist"])]
    (is (nil? (insp/inspector-model s)))))

(deftest inspector-model-returns-node-and-meta
  (let [s0 (state/initial-state)
        {d1 :doc id :id} (ops/insert-new (:document s0) "root" "default" 0 "x-button")
        s1 (-> s0
               (assoc :document d1)
               (assoc :selection [id]))
        model (insp/inspector-model s1)]
    (is (some? model))
    (is (= id             (get-in model [:node :id])))
    (is (= "x-button"     (get-in model [:node :tag])))
    (testing "meta is populated from the registry"
      (is (= "Button"     (get-in model [:meta :label])))
      (is (= :form        (get-in model [:meta :category])))
      (is (some #(= "variant" (:name %))
                (get-in model [:meta :properties]))))))

(deftest inspector-model-works-for-root
  (let [s (assoc (state/initial-state) :selection ["root"])
        model (insp/inspector-model s)]
    (is (some? model))
    (is (= "root"        (get-in model [:node :id])))
    (is (= "x-container" (get-in model [:node :tag])))))

(deftest inspector-model-multi-select-returns-multi-marker
  (let [s0 (state/initial-state)
        {d1 :doc id-a :id} (ops/insert-new (:document s0) "root" "default" 0 "x-button")
        {d2 :doc id-b :id} (ops/insert-new d1 "root" "default" 1 "x-card")
        s  (-> s0
               (assoc :document d2)
               (assoc :selection [id-a id-b]))
        model (insp/inspector-model s)]
    (is (= {:multi 2} model)
        "two distinct doc nodes selected → :multi marker, no :node payload")))

(deftest inspector-model-collapses-clones-of-one-doc-node
  (testing "two raw DOM ids that canonicalise to the same doc node still
            count as a single selection — clone-aware overlays don't
            confuse the inspector."
    (let [s0 (state/initial-state)
          {d1 :doc id :id} (ops/insert-new (:document s0) "root" "default" 0 "x-button")
          s  (-> s0
                 (assoc :document d1)
                 (assoc :selection [id (str id "__seed1")]))
          model (insp/inspector-model s)]
      (is (some? model))
      (is (nil? (:multi model)))
      (is (= id (get-in model [:node :id]))))))
