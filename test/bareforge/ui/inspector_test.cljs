(ns bareforge.ui.inspector-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.doc.ops :as ops]
            [bareforge.meta.registry :as registry]
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

(deftest inspector-model-multi-select-surfaces-nodes-and-tags
  (let [s0 (state/initial-state)
        {d1 :doc id-a :id} (ops/insert-new (:document s0) "root" "default" 0 "x-button")
        {d2 :doc id-b :id} (ops/insert-new d1 "root" "default" 1 "x-card")
        s  (-> s0
               (assoc :document d2)
               (assoc :selection [id-a id-b]))
        model (insp/inspector-model s)]
    (is (true? (:multi model)))
    (is (= 2 (count (:nodes model))))
    (is (= #{"x-button" "x-card"} (:tags model)))))

;; --- shared-properties + joint-attr-value (M2.3) ----------------------

(deftest shared-properties-intersect-by-name-and-kind
  (let [props (insp/shared-properties #{"x-button" "x-card"})]
    (testing "every returned descriptor exists on both tags"
      (doseq [p props]
        (is (contains? #{:enum :boolean :string-short :string-long
                         :number :url :color :date :unknown}
                       (or (:kind p) :unknown))
            "descriptor has a recognisable kind")))))

(deftest shared-properties-empty-for-empty-tag-set
  (is (= [] (insp/shared-properties #{}))))

(deftest shared-properties-single-tag-returns-its-properties
  (let [single (insp/shared-properties #{"x-button"})
        all    (-> "x-button" registry/get-meta :properties)]
    (is (= (count all) (count single))
        "with a single tag, every property is trivially shared")))

(deftest joint-attr-value-uniform
  (let [n1 {:attrs {"variant" "primary"}}
        n2 {:attrs {"variant" "primary"}}
        out (insp/joint-attr-value [n1 n2] {:name "variant" :kind :enum})]
    (is (= "primary" (:value out)))
    (is (false? (:mixed? out)))))

(deftest joint-attr-value-mixed
  (let [n1 {:attrs {"variant" "primary"}}
        n2 {:attrs {"variant" "ghost"}}
        out (insp/joint-attr-value [n1 n2] {:name "variant" :kind :enum})]
    (is (true? (:mixed? out)))))

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

;; --- inline binding chip — label formatting (A1) ----------------------

(deftest attr-binding-label-with-explicit-owner
  (testing "binding carrying its own :owner produces qualified label
            without walking the doc"
    (let [doc (:document (state/initial-state))
          binding {:field :cart-count :owner "cart" :direction :read}]
      (is (= "↔ cart.cart-count"
             (insp/attr-binding-label doc binding))))))

(deftest attr-binding-label-walks-doc-when-owner-missing
  (testing "legacy bindings without :owner recover the group name by
            walking the doc for a matching field declaration"
    (let [s0 (state/initial-state)
          {d1 :doc cart-id :id} (ops/insert-new (:document s0) "root" "default" 0 "x-container")
          d2 (ops/set-name d1 cart-id "cart")
          d3 (ops/add-field d2 cart-id {:name :cart-count :type :int :default 0})
          binding {:field :cart-count :direction :read}]
      (is (= "↔ cart.cart-count"
             (insp/attr-binding-label d3 binding))))))

(deftest attr-binding-label-bare-when-no-owner-found
  (testing "a stray binding pointing at a field no group declares
            falls back to the bare ↔ field form"
    (let [doc (:document (state/initial-state))
          binding {:field :ghost :direction :read}]
      (is (= "↔ ghost"
             (insp/attr-binding-label doc binding))))))

(deftest text-bind-label-uses-explicit-owner
  (testing "node carrying :text-field-owner skips the doc walk and
            produces the qualified text-field label"
    (let [doc  (:document (state/initial-state))
          node {:text-field :title :text-field-owner "post"}]
      (is (= "↔ post.title"
             (insp/text-bind-label doc node :title))))))

(deftest text-bind-label-walks-doc-when-owner-missing
  (testing "legacy text-field without :text-field-owner recovers the
            owning group name by scanning the doc"
    (let [s0 (state/initial-state)
          {d1 :doc post-id :id} (ops/insert-new (:document s0) "root" "default" 0 "x-container")
          d2 (ops/set-name d1 post-id "post")
          d3 (ops/add-field d2 post-id {:name :title :type :string :default ""})
          node {:text-field :title}]
      (is (= "↔ post.title"
             (insp/text-bind-label d3 node :title))))))
