(ns bareforge.doc.actions-test
  (:require [cljs.test :refer [deftest is testing]]
            [cljs.reader :as edn]
            [bareforge.doc.actions :as actions]))

(defn- fixture-doc []
  (let [fs (js/require "fs")
        raw (.readFileSync fs "test/fixtures/export/demo-store.json" "utf8")]
    (:document (edn/read-string raw))))

(deftest all-actions-lists-declared-and-auto
  (let [doc  (fixture-doc)
        all  (actions/all-actions doc "app")
        refs (set (map :action-ref all))]
    (testing "declared cart actions appear"
      (is (contains? refs :app.cart.events/add-to-cart))
      (is (contains? refs :app.cart.events/remove-from-cart)))
    (testing "auto setters appear for every stored field that isn't shadowed"
      (is (contains? refs :app.cart.events/cart-items-changed)))
    (testing "computed fields do NOT get an auto setter"
      (is (not (contains? refs :app.cart.events/cart-count-changed))
          "cart-count is computed (count-of cart-items) → no setter"))
    (testing "collection groups do NOT get per-field auto setters"
      (is (not (contains? refs :app.product.events/id-changed))
          "product is a collection → record fields aren't shared db slots")
      (is (not (contains? refs :app.product.events/title-changed)))
      (is (not (contains? refs :app.product.events/price-changed))))
    (testing "declared entries are marked with :declared, setters with :auto-setter"
      (let [by-ref (into {} (map (juxt :action-ref identity) all))]
        (is (= :declared
               (:source (by-ref :app.cart.events/add-to-cart))))
        (is (= :auto-setter
               (:source (by-ref :app.cart.events/cart-items-changed))))))))

(deftest field-groups-for-picker-orders-enclosing-first
  (let [doc     (fixture-doc)
        groups  (actions/field-groups-for-picker doc "product")
        names   (mapv :owner-name groups)
        encl    (first groups)]
    (testing "enclosing group is first and labelled with the hint"
      (is (= "product" (:owner-name encl)))
      (is (= "product (this group)" (:label encl)))
      (is (:enclosing? encl)))
    (testing "every group in the doc appears"
      (is (= (set ["product" "cart" "cart-item" "Product feed"])
             (set names))))
    (testing "other groups keep their plain name as label"
      (let [cart (first (filter #(= "cart" (:owner-name %)) groups))]
        (is (not (:enclosing? cart)))
        (is (= "cart" (:label cart)))))
    (testing "fields are returned verbatim for each group"
      (let [cart   (first (filter #(= "cart" (:owner-name %)) groups))
            fnames (set (map :name (:fields cart)))]
        (is (contains? fnames :cart-count))
        (is (contains? fnames :cart-items))))))

(deftest field-groups-for-picker-handles-nil-enclosing
  (let [doc    (fixture-doc)
        groups (actions/field-groups-for-picker doc nil)]
    (is (every? (complement :enclosing?) groups)
        "no group is marked enclosing when name is nil")
    (is (every? #(= (:owner-name %) (:label %)) groups)
        "labels match plain names")))

(deftest all-actions-declared-wins-on-name-collision
  ;; Build a minimal synthetic doc: a group named "foo" that declares an
  ;; action called :x-changed and also has a field :x. The declared
  ;; action should suppress the would-be auto setter of the same name.
  (let [doc {:root {:id "r" :tag "x-container"
                    :attrs {} :props {} :layout {:placement :flow}
                    :slots
                    {"default"
                     [{:id "g" :tag "x-container" :name "foo"
                       :attrs {} :props {} :layout {:placement :flow}
                       :fields [{:name :x :type :number :default 0}]
                       :actions [{:name :x-changed :operation :increment
                                  :target-field :x}]
                       :slots {}}]}}}
        entries (actions/all-actions doc "app")
        changed (filter #(= :x-changed (:action-name %)) entries)]
    (is (= 1 (count changed))
        "only the declared action is kept")
    (is (= :declared (:source (first changed))))
    (is (= :increment (:operation (first changed))))))
