(ns bareforge.ui.palette-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.ui.palette :as p]))

(def sample
  [{:tag "x-button"     :label "Button"     :category :form}
   {:tag "x-card"       :label "Card"       :category :layout}
   {:tag "x-container"  :label "Container"  :category :layout}
   {:tag "x-typography" :label "Typography" :category :text}
   {:tag "x-skeleton"   :label "x-skeleton" :category :other}])

(deftest group-tags-builds-common-bucket-first
  (let [groups (p/group-tags sample)
        cats   (map first groups)]
    (is (= :common (first cats)))
    ;; Common should come before any category bucket
    (is (= [:common :layout :form :text :other] cats))))

(deftest common-bucket-contains-pinned-entries-from-metas
  (let [groups      (p/group-tags sample)
        common-items (second (first groups))
        common-tags  (map :tag common-items)]
    (is (contains? (set common-tags) "x-button"))
    (is (contains? (set common-tags) "x-card"))
    (is (contains? (set common-tags) "x-container"))
    (is (contains? (set common-tags) "x-typography"))))

(deftest group-tags-skips-empty-buckets
  (let [groups (p/group-tags [{:tag "x-card" :label "Card" :category :layout}])
        cats   (set (map first groups))]
    (is (contains? cats :layout))
    (is (not (contains? cats :form)))
    (is (not (contains? cats :feedback)))))

(deftest filter-tags-empty-returns-all
  (is (= sample (p/filter-tags sample "")))
  (is (= sample (p/filter-tags sample nil)))
  (is (= sample (p/filter-tags sample "   "))))

(deftest filter-tags-substring-match
  (let [result (p/filter-tags sample "card")]
    (is (= 1 (count result)))
    (is (= "x-card" (:tag (first result))))))

(deftest filter-tags-case-insensitive
  (is (= 1 (count (p/filter-tags sample "BUTTON"))))
  (is (= 1 (count (p/filter-tags sample "Button")))))

(deftest filter-tags-matches-label
  (is (some #(= "x-typography" (:tag %)) (p/filter-tags sample "typo")))
  (is (some #(= "x-container" (:tag %))  (p/filter-tags sample "contain"))))

(deftest seed-for-tag-defaults
  (testing "typography seeds visible text"
    (is (= "Text" (:text (p/seed-for-tag "x-typography")))))
  (testing "button seeds visible label"
    (is (= "Button" (:text (p/seed-for-tag "x-button"))))
    (is (= "Button" (get-in (p/seed-for-tag "x-button") [:attrs "label"]))))
  (testing "overlay components seed open so they're visible on drop"
    (doseq [tag ["x-sidebar" "x-drawer" "x-modal" "x-popover"]]
      (is (= "" (get-in (p/seed-for-tag tag) [:attrs "open"]))
          (str tag " should seed open=\"\""))))
  (testing "sidebar seeds full width so a flow drop is a droppable block"
    (is (= "100%" (get-in (p/seed-for-tag "x-sidebar") [:layout :width]))))
  (testing "table seeds a default 3-column track layout"
    (is (= "repeat(3, 1fr)" (get-in (p/seed-for-tag "x-table") [:attrs "columns"]))))
  (testing "unknown tags get empty overrides"
    (is (= {} (p/seed-for-tag "x-some-unknown")))))

(deftest insertion-target-without-selection
  (let [doc (m/empty-document)]
    (is (= {:parent-id "root" :slot "default" :index 0}
           (p/insertion-target doc nil)))))

(deftest insertion-target-appends-to-root-when-nothing-selected
  (let [d0 (m/empty-document)
        {d1 :doc} (ops/insert-new d0 "root" "default" 0 "x-a")
        {d2 :doc} (ops/insert-new d1 "root" "default" 1 "x-b")]
    (is (= {:parent-id "root" :slot "default" :index 2}
           (p/insertion-target d2 nil)))))

(deftest insertion-target-inserts-after-selected-sibling
  (let [d0                    (m/empty-document)
        {d1 :doc id-a :id}    (ops/insert-new d0 "root" "default" 0 "x-a")
        {d2 :doc}             (ops/insert-new d1 "root" "default" 1 "x-b")]
    (is (= {:parent-id "root" :slot "default" :index 1}
           (p/insertion-target d2 id-a))
        "selecting x-a should cause the next insert to land at index 1")))

(deftest insertion-target-with-root-selected-appends-inside-root
  (let [d0 (m/empty-document)
        {d1 :doc} (ops/insert-new d0 "root" "default" 0 "x-a")]
    (is (= {:parent-id "root" :slot "default" :index 1}
           (p/insertion-target d1 "root"))
        "root is a container, so clicks with root selected append inside it")))

(deftest insertion-target-container-selected-goes-inside
  (testing "selecting an x-navbar sends the next insert into its default slot"
    (let [d0                    (m/empty-document)
          {d1 :doc id-nav :id}  (ops/insert-new d0 "root" "default" 0 "x-navbar")]
      (is (= {:parent-id id-nav :slot "default" :index 0}
             (p/insertion-target d1 id-nav)))))
  (testing "selecting an x-card appends inside its default slot"
    (let [d0                   (m/empty-document)
          {d1 :doc id-card :id} (ops/insert-new d0 "root" "default" 0 "x-card")
          {d2 :doc}             (ops/insert-new d1 id-card "default" 0 "x-typography")]
      (is (= {:parent-id id-card :slot "default" :index 1}
             (p/insertion-target d2 id-card)))))
  (testing "selecting a leaf like x-button inserts as a sibling after"
    (let [d0                   (m/empty-document)
          {d1 :doc}             (ops/insert-new d0 "root" "default" 0 "x-card")
          {d2 :doc id-btn :id}  (ops/insert-new d1 "root" "default" 1 "x-button")]
      (is (= {:parent-id "root" :slot "default" :index 2}
             (p/insertion-target d2 id-btn))))))
