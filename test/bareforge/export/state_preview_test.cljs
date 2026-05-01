(ns bareforge.export.state-preview-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.export.state-preview :as sp]))

(defn- empty-doc [] (m/empty-document))

(defn- doc-with-cart-style-group
  "Build a single named group at root[0] with the given :fields. Used
   to keep test fixtures terse — every test that needs a group calls
   into this helper rather than hand-rolling a full doc tree."
  [fields]
  (let [{d :doc id :id} (ops/insert-new (empty-doc) "root" "default" 0 "x-card")
        d1 (ops/set-name d id "cart")]
    {:doc (assoc-in d1 (conj (m/path-to d1 id) :fields) (vec fields))
     :id  id}))

;; --- named-groups --------------------------------------------------------

(deftest named-groups-empty-doc
  (is (= [] (sp/named-groups (empty-doc)))
      "an empty doc has no named groups"))

(deftest named-groups-skip-unnamed-containers
  (let [{d :doc} (ops/insert-new (empty-doc) "root" "default" 0 "x-container")]
    (is (= [] (sp/named-groups d))
        "an unnamed container at root is decorative, not a group")))

(deftest named-groups-returns-name-and-tag
  (let [{d :doc} (doc-with-cart-style-group [])
        gs (sp/named-groups d)]
    (is (= 1 (count gs)))
    (is (= "cart"   (:name (first gs))))
    (is (= "x-card" (:tag  (first gs))))))

;; --- stored-field evaluation --------------------------------------------

(deftest stored-field-surfaces-default-verbatim
  (testing "non-computed fields just expose their :default as :value
            — that's what the exported app's default-db will carry"
    (let [{d :doc} (doc-with-cart-style-group
                    [{:name :id        :type :number :default 0 :locked? true}
                     {:name :cart-name :type :string :default "Untitled"}])
          [g] (sp/snapshot d)]
      (is (= "cart" (:name g)))
      (is (false? (:template? g)))
      (let [name-row (some #(when (= :cart-name (:name %)) %) (:fields g))]
        (is (= "Untitled" (:value     name-row)))
        (is (true?        (:stored?   name-row)))
        (is (false?       (:computed? name-row)))
        (is (false?       (:locked?   name-row))))
      (let [id-row (some #(when (= :id (:name %)) %) (:fields g))]
        (is (true? (:locked? id-row))
            "locked? carries through verbatim — renderer dims locked
             rows rather than hiding them")))))

;; --- computed: :count-of -------------------------------------------------

(deftest count-of-evaluates-source-vector-length
  (let [{d :doc} (doc-with-cart-style-group
                  [{:name :cart-items :type :vector :of-group "cart-item"
                    :default [{:id 1} {:id 2} {:id 3}]}
                   {:name :cart-count :type :number
                    :computed {:operation :count-of :source-field :cart-items}}])
        [g] (sp/snapshot d)
        cnt (some #(when (= :cart-count (:name %)) %) (:fields g))]
    (is (true? (:computed? cnt)))
    (is (= 3   (:value     cnt))
        ":count-of returns the seed collection's count")))

(deftest count-of-on-empty-collection-is-zero
  (let [{d :doc} (doc-with-cart-style-group
                  [{:name :cart-items :type :vector :default []}
                   {:name :cart-count :type :number
                    :computed {:operation :count-of :source-field :cart-items}}])
        [g] (sp/snapshot d)
        cnt (some #(when (= :cart-count (:name %)) %) (:fields g))]
    (is (= 0 (:value cnt)))))

;; --- computed: :empty-of -------------------------------------------------

(deftest empty-of-true-when-collection-is-empty
  (let [{d :doc} (doc-with-cart-style-group
                  [{:name :items :type :vector :default []}
                   {:name :empty? :type :boolean
                    :computed {:operation :empty-of :source-field :items}}])
        [g] (sp/snapshot d)
        e   (some #(when (= :empty? (:name %)) %) (:fields g))]
    (is (true? (:value e)))))

(deftest empty-of-false-when-collection-has-elements
  (let [{d :doc} (doc-with-cart-style-group
                  [{:name :items :type :vector :default [{:id 1}]}
                   {:name :empty? :type :boolean
                    :computed {:operation :empty-of :source-field :items}}])
        [g] (sp/snapshot d)
        e   (some #(when (= :empty? (:name %)) %) (:fields g))]
    (is (false? (:value e)))))

;; --- computed: :negation -------------------------------------------------

(deftest negation-flips-source-boolean
  (let [{d :doc} (doc-with-cart-style-group
                  [{:name :pinned? :type :boolean :default false}
                   {:name :unpinned? :type :boolean
                    :computed {:operation :negation :source-field :pinned?}}])
        [g] (sp/snapshot d)
        u   (some #(when (= :unpinned? (:name %)) %) (:fields g))]
    (is (true? (:value u))
        "(not false) → true; chained computeds work the same way")))

(deftest negation-chains-through-computed-source
  (testing "negation can read from another computed on the same group
            — `has-items = (negation is-empty)` resolves both layers"
    (let [{d :doc} (doc-with-cart-style-group
                    [{:name :items :type :vector :default []}
                     {:name :is-empty :type :boolean
                      :computed {:operation :empty-of :source-field :items}}
                     {:name :has-items :type :boolean
                      :computed {:operation :negation :source-field :is-empty}}])
          [g] (sp/snapshot d)
          h   (some #(when (= :has-items (:name %)) %) (:fields g))]
      (is (false? (:value h))
          "items=[] → is-empty=true → has-items=false"))))

;; --- computed: :sum-of ---------------------------------------------------

(deftest sum-of-numbers-without-project-field
  (let [{d :doc} (doc-with-cart-style-group
                  [{:name :scores :type :vector :default [10 20 12]}
                   {:name :total :type :number
                    :computed {:operation :sum-of :source-field :scores}}])
        [g] (sp/snapshot d)
        t   (some #(when (= :total (:name %)) %) (:fields g))]
    (is (= 42 (:value t)))))

(deftest sum-of-records-with-project-field
  (let [{d :doc} (doc-with-cart-style-group
                  [{:name :cart-items :type :vector :of-group "cart-item"
                    :default [{:id 1 :price 9.99}
                              {:id 2 :price 8.75}
                              {:id 3 :price 14.0}]}
                   {:name :cart-total :type :number
                    :computed {:operation :sum-of :source-field :cart-items
                               :project-field :price}}])
        [g] (sp/snapshot d)
        t   (some #(when (= :cart-total (:name %)) %) (:fields g))]
    ;; floating-point sum — 32.74 is exact in this case.
    (is (= 32.74 (:value t)))))

;; --- computed: runtime-only ops -----------------------------------------

(deftest unsupported-computed-ops-flag-runtime-only
  (testing ":any-of / :filter-by / :join-on need a cross-group sub
            graph that doesn't exist at design time — they're
            reported with :runtime-only so the renderer can show
            a placeholder instead of a misleading value"
    (let [{d :doc} (doc-with-cart-style-group
                    [{:name :a :type :vector :default []}
                     {:name :any-flag :type :boolean
                      :computed {:operation :any-of :source-fields [:a]}}
                     {:name :join-out :type :vector
                      :computed {:operation :join-on
                                 :source-field :a
                                 :join-target {:group-name "x"
                                               :match-field :id}}}])
          [g] (sp/snapshot d)
          any* (some #(when (= :any-flag (:name %)) %) (:fields g))
          join* (some #(when (= :join-out (:name %)) %) (:fields g))]
      (is (true? (:runtime-only any*)))
      (is (nil?  (:value any*)))
      (is (true? (:runtime-only join*))))))

;; --- template groups -----------------------------------------------------

(deftest template-group-flagged-when-other-group-targets-it
  (testing "a group whose :name matches another group's collection
            field's :of-group is a template (record shape, no state)"
    (let [d0 (empty-doc)
          {d1 :doc cart-id :id} (ops/insert-new d0 "root" "default" 0 "x-card")
          {d2 :doc tpl-id  :id} (ops/insert-new d1 "root" "default" 1 "x-grid")
          d3 (-> d2
                 (ops/set-name cart-id "cart")
                 (ops/set-name tpl-id  "cart-item")
                 (ops/add-field cart-id {:name :cart-items :type :vector
                                         :of-group "cart-item" :default []})
                 (ops/add-field tpl-id  {:name :title :type :string :default ""}))
          gs (sp/snapshot d3)
          cart (some #(when (= "cart"      (:name %)) %) gs)
          tpl  (some #(when (= "cart-item" (:name %)) %) gs)]
      (is (false? (:template? cart)))
      (is (true?  (:template? tpl))
          "cart-item is targeted by cart's :of-group → template"))))

;; --- snapshot ------------------------------------------------------------

(deftest snapshot-empty-doc-returns-empty-vector
  (is (= [] (sp/snapshot (empty-doc)))))

(deftest snapshot-preserves-document-order
  (let [d0 (empty-doc)
        {d1 :doc id1 :id} (ops/insert-new d0 "root" "default" 0 "x-card")
        {d2 :doc id2 :id} (ops/insert-new d1 "root" "default" 1 "x-card")
        d3 (-> d2
               (ops/set-name id1 "first")
               (ops/set-name id2 "second"))]
    (is (= ["first" "second"]
           (mapv :name (sp/snapshot d3))))))
