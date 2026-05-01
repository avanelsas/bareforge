(ns bareforge.export.model-test
  (:require [cljs.test :refer [deftest is testing]]
            [cljs.reader :as edn]
            [bareforge.doc.model :as m]
            [bareforge.export.model :as em]))

(defn- read-fixture [path]
  (let [fs (js/require "fs")]
    (edn/read-string (.readFileSync fs path "utf8"))))

(defn- fixture-doc [path] (:document (read-fixture path)))

(defn- demo-store-doc []
  (fixture-doc "test/fixtures/export/demo-store.json"))

;; --- field-def predicates --------------------------------------------------

(deftest computed-predicate
  (is (true?  (em/computed? {:name :count :type :number
                             :computed {:operation :count-of :source-field :items}})))
  (is (false? (em/computed? {:name :count :type :number :default 0})))
  (is (false? (em/computed? {}))))

(deftest collection-field-predicate
  (testing "vector + :of-group qualifies"
    (is (true? (em/collection-field?
                {:name :items :type :vector :of-group "product" :default []}))))
  (testing "vector alone doesn't qualify"
    (is (false? (em/collection-field?
                 {:name :items :type :vector :default []}))))
  (testing "scalar doesn't qualify"
    (is (false? (em/collection-field?
                 {:name :n :type :number :default 0})))))

;; --- ns-name derivation ---------------------------------------------------

(deftest name-to-ns-segment-normalises
  (is (= "product-feed" (em/name->ns-segment "Product Feed")))
  (is (= "cart"         (em/name->ns-segment "  Cart  ")))
  (is (= "my-group"     (em/name->ns-segment "My Group!"))))

(deftest unique-ns-name-suffix-on-collision
  (let [[n1 seen1] (em/unique-ns-name "cart" #{})
        [n2 seen2] (em/unique-ns-name "cart" seen1)
        [n3 _]     (em/unique-ns-name "cart" seen2)]
    (is (= "cart"   n1))
    (is (= "cart_2" n2))
    (is (= "cart_3" n3))))

;; --- group detection over the demo-store fixture --------------------------

(deftest detect-groups-on-demo-store
  (let [{:keys [groups root-order]} (em/detect-groups (demo-store-doc))
        ns-names (set (map :ns-name groups))]
    (testing "every named node becomes a group"
      (is (contains? ns-names "cart"))
      (is (contains? ns-names "cart-item"))
      (is (contains? ns-names "product"))
      (is (contains? ns-names "product-feed")))
    (testing "root-order reflects slot-order of root children"
      (is (seq root-order))
      (is (every? map? root-order)))))

(deftest detect-groups-on-empty-doc
  (let [{:keys [groups]} (em/detect-groups (m/empty-document))]
    (testing "empty doc produces the synthetic root group so views have a home"
      (is (= 1 (count groups)))
      (is (= "main" (:ns-name (first groups)))))))

;; --- classification ------------------------------------------------------

(deftest template-vs-stateful-classification
  (let [doc (demo-store-doc)
        {:keys [groups]} (em/detect-groups doc)
        by-ns (into {} (map (juxt :ns-name identity)) groups)]
    (testing "template groups are those targeted by another group's :of-group"
      (is (em/template-group? doc (get by-ns "cart-item")))
      (is (em/template-group? doc (get by-ns "product"))))
    (testing "stateful groups own the collection fields pointing at templates"
      (is (em/stateful-group? doc (get by-ns "cart")))
      (is (em/stateful-group? doc (get by-ns "product-feed"))))
    (testing "template? and stateful? are complementary"
      (doseq [g groups]
        (is (not= (em/template-group? doc g) (em/stateful-group? doc g))
            (str (:ns-name g) " must be exactly one of template / stateful"))))))

(deftest stateful-host-for-template-resolves
  (let [doc (demo-store-doc)
        {:keys [groups]} (em/detect-groups doc)
        host (em/stateful-host-for-template doc groups "cart-item")]
    (testing "cart-item is hosted by cart's :cart-items field"
      (is (= "cart"       (:ns-name host)))
      (is (= "cart-items" (:field-name host))))))

;; --- per-group collection -------------------------------------------------

(deftest collect-group-data-merges-instances
  (let [doc (demo-store-doc)
        {:keys [groups]} (em/detect-groups doc)
        cart (first (filter #(= "cart" (:ns-name %)) groups))
        data (em/collect-group-data doc (:instance-ids cart) groups)]
    (testing "cart's fields include cart-items + the computed count"
      (is (some #(= :cart-items (:name %)) (:fields data)))
      (is (some #(and (:computed %)
                      (= :count-of (get-in % [:computed :operation])))
                (:fields data))
          "at least one :count-of computed field sits on cart"))
    (testing "cart's declared actions include add-to-cart and remove-from-cart"
      (is (some #(= :add-to-cart      (:name %)) (:actions data)))
      (is (some #(= :remove-from-cart (:name %)) (:actions data))))))

;; --- lower-document ------------------------------------------------------

(deftest lower-document-shape
  (let [lowered (em/lower-document (demo-store-doc))]
    (testing "the lowered representation carries every fact a plugin needs"
      (is (vector? (:groups lowered)))
      (is (vector? (:root-order lowered)))
      (is (set? (:template-names lowered)))
      (is (map? (:field-owner-ns lowered)))
      (is (map? (:name->ns-name lowered))))))

(deftest lower-document-template-names-set
  (testing "template-names is the set of every group whose record
            shape another group's :of-group points at"
    (let [{:keys [template-names]} (em/lower-document (demo-store-doc))]
      (is (contains? template-names "cart-item"))
      (is (contains? template-names "product"))
      (is (not (contains? template-names "cart"))
          "cart owns the :cart-items collection — it's stateful, not a template")
      (is (not (contains? template-names "product-feed"))))))

(deftest lower-document-groups-carry-template-flag
  (let [{:keys [groups]} (em/lower-document (demo-store-doc))
        by-ns (into {} (map (juxt :ns-name identity)) groups)]
    (testing "every group entry carries :template? matching template-names"
      (is (true?  (:template? (get by-ns "cart-item"))))
      (is (true?  (:template? (get by-ns "product"))))
      (is (false? (:template? (get by-ns "cart"))))
      (is (false? (:template? (get by-ns "product-feed")))))))

(deftest lower-document-groups-carry-precomputed-data
  (let [{:keys [groups]} (em/lower-document (demo-store-doc))
        cart (first (filter #(= "cart" (:ns-name %)) groups))]
    (testing "per-group :data matches the standalone collect-group-data
              result so consumers can swap one for the other"
      (let [standalone (em/collect-group-data
                        (demo-store-doc) (:instance-ids cart) groups)]
        (is (= (set (:fields standalone)) (set (:fields (:data cart)))))
        (is (= (set (:actions standalone)) (set (:actions (:data cart)))))))))

(deftest lower-document-field-owner-index-resolves
  (let [{:keys [field-owner-ns]} (em/lower-document (demo-store-doc))]
    (testing "field-owner-ns maps payload field-kw → owning group ns-name"
      (is (= "cart" (get field-owner-ns :cart-items))
          "the :cart-items field is declared on cart"))))

(deftest lower-document-on-empty-doc
  (let [lowered (em/lower-document (m/empty-document))]
    (testing "empty doc still produces a valid lowered shape with the
              synthetic root group"
      (is (= 1 (count (:groups lowered))))
      (is (= "main" (:ns-name (first (:groups lowered)))))
      (is (false? (:template? (first (:groups lowered)))))
      (is (= #{} (:template-names lowered))))))
