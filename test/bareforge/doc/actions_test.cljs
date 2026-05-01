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
    (is (= [{:operation :increment :target-field :x}]
           (:steps (first changed)))
        "single-step shape canonicalises to a one-element :steps list")))

;; --- step-list canonicalisation (multi-step actions) -----------------

(deftest step-list-single-step-shape
  (testing "old shape `{:operation :target-field}` reads as a
            one-element step list"
    (is (= [{:operation :increment :target-field :x}]
           (actions/step-list {:name :bump :operation :increment :target-field :x})))))

(deftest step-list-multi-step-shape
  (testing "new shape `{:steps [...]}` returns its steps verbatim"
    (let [steps [{:operation :add :target-field :cart-items}
                 {:operation :set :target-field :is-popover-open
                  :payload [{:literal false}]}]]
      (is (= steps (actions/step-list {:name :add-and-close :steps steps}))))))

(deftest step-list-never-empty
  (testing "even an action map missing both shapes returns a nil-filled
            single-step rather than an empty vector — callers can treat
            the return as guaranteed non-empty"
    (is (= 1 (count (actions/step-list {:name :bare}))))))

(deftest action-needs-payload?-flags-payload-consumers
  (testing ":set / :add / :remove operations consume the trigger payload;
            others ignore it"
    (is (true?  (actions/action-needs-payload?
                 {:name :a :operation :add :target-field :xs})))
    (is (true?  (actions/action-needs-payload?
                 {:name :a :operation :set :target-field :x})))
    (is (false? (actions/action-needs-payload?
                 {:name :a :operation :toggle :target-field :x})))
    (is (false? (actions/action-needs-payload?
                 {:name :a :operation :increment :target-field :x})))))

(deftest action-needs-payload?-multi-step-mixes-flag
  (testing "a multi-step action needs payload iff at least one step
            actually consumes it"
    (is (true?  (actions/action-needs-payload?
                 {:name :a
                  :steps [{:operation :add :target-field :xs}
                          {:operation :toggle :target-field :flag}]})))
    (is (false? (actions/action-needs-payload?
                 {:name :a
                  :steps [{:operation :toggle :target-field :flag}
                          {:operation :clear :target-field :search}]})))))

;; --- name->ns-segment + action-ref canonicalisation -------------------

(deftest name->ns-segment-lowercases-and-normalises
  (testing "user-typed names always produce a lowercase ns segment"
    (is (= "dashboard"     (actions/name->ns-segment "Dashboard")))
    (is (= "user-profile"  (actions/name->ns-segment "User Profile")))
    (is (= "cart-2"        (actions/name->ns-segment "  Cart 2  ")))
    (is (= ""              (actions/name->ns-segment nil))
        "nil is tolerated — empty input returns empty string"))
  (testing "non-[a-z0-9] runs collapse to single hyphens, no
            leading/trailing hyphens"
    (is (= "abc-def" (actions/name->ns-segment "abc!!def")))
    (is (= "abc-def" (actions/name->ns-segment "  abc-def--")))))

(deftest action-ref-uses-canonical-segment
  ;; Regression for: a Dashboard x-card produced action-refs in the
  ;; `app.Dashboard.events` namespace while the events file was at
  ;; `src/app/dashboard/events.cljs`. shadow-cljs rejected the build
  ;; because the path-derived namespace didn't match the (ns …) form.
  (let [doc {:root {:id "r" :tag "x-container"
                    :attrs {} :props {} :layout {:placement :flow}
                    :slots
                    {"default"
                     [{:id "g" :tag "x-card" :name "Dashboard"
                       :attrs {} :props {} :layout {:placement :flow}
                       :fields  [{:name :clicks :type :number :default 0}]
                       :actions [{:name :tick :operation :increment
                                  :target-field :clicks}]
                       :slots {}}]}}}
        entries (actions/all-actions doc "app")
        tick    (first (filter #(= :tick (:action-name %)) entries))]
    (is (= :app.dashboard.events/tick (:action-ref tick))
        "the group segment is lowercased — matches what the export
         pipeline emits for the file path and (ns …) form")))
