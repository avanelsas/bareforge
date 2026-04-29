(ns bareforge.ui.layers-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.ui.layers :as layers]))

(deftest flatten-tree-empty-doc
  (let [rows (vec (layers/flatten-tree (m/empty-document)))]
    (is (= 1 (count rows)))
    (is (= "root" (:id (first rows))))
    (is (= 0 (:depth (first rows))))
    (is (nil? (:parent-id (first rows))))))

(deftest flatten-tree-depth-first-order
  (let [d0 (m/empty-document)
        {d1 :doc id-a :id} (ops/insert-new d0 "root" "default" 0 "x-card")
        {d2 :doc id-b :id} (ops/insert-new d1 id-a  "default" 0 "x-typography")
        {d3 :doc id-c :id} (ops/insert-new d2 "root" "default" 1 "x-button")
        rows               (vec (layers/flatten-tree d3))]
    (is (= ["root" id-a id-b id-c] (mapv :id rows))
        "depth-first order: root, card, typography-inside-card, button")
    (is (= [0 1 2 1] (mapv :depth rows)))))

(deftest flatten-tree-records-parent-and-slot
  (let [d0 (m/empty-document)
        {d1 :doc} (ops/insert-new d0 "root" "default" 0 "x-card")
        rows (vec (layers/flatten-tree d1))]
    (is (= "root"    (:parent-id (second rows))))
    (is (= "default" (:slot      (second rows))))
    (is (= 0         (:index     (second rows))))))

(deftest flatten-tree-uses-label-from-meta
  (let [d0 (m/empty-document)
        {d1 :doc} (ops/insert-new d0 "root" "default" 0 "x-button")
        btn (second (vec (layers/flatten-tree d1)))]
    (testing "augmented tag uses its meta label"
      (is (= "Button" (:label btn))))))

(deftest flatten-tree-unaugmented-humanizes-label
  ;; x-theme is the only component intentionally left unaugmented
  ;; (managed by the top-level theme editor, not the per-node
  ;; inspector), so it's the stable sentinel for the fallback path.
  ;; The registry's fallback now humanizes the tag name via
  ;; `heuristics/humanize-tag`, so the layer row shows "Theme"
  ;; rather than the raw "x-theme".
  (let [d0 (m/empty-document)
        {d1 :doc} (ops/insert-new d0 "root" "default" 0 "x-theme")
        row (second (vec (layers/flatten-tree d1)))]
    (is (= "Theme" (:label row)))))

;; --- keyboard nav (M3.2) ------------------------------------------------

(defn- sample-doc
  "root → [a, parent → [c, d], b]"
  []
  (let [d0 (m/empty-document)
        {d1 :doc id-a :id} (ops/insert-new d0 "root" "default" 0 "x-a")
        {d2 :doc id-p :id} (ops/insert-new d1 "root" "default" 1 "x-card")
        {d3 :doc id-c :id} (ops/insert-new d2 id-p  "default" 0 "x-c")
        {d4 :doc id-d :id} (ops/insert-new d3 id-p  "default" 1 "x-d")
        {d5 :doc id-b :id} (ops/insert-new d4 "root" "default" 2 "x-b")]
    {:doc d5 :ids {:a id-a :p id-p :c id-c :d id-d :b id-b}}))

(deftest nav-target-prev-and-next
  (let [{:keys [doc ids]} (sample-doc)
        rows (vec (layers/flatten-tree doc))]
    ;; depth-first order is root, a, p, c, d, b
    (is (= "root"  (layers/nav-target rows (:a ids) doc :prev)))
    (is (= (:a ids) (layers/nav-target rows (:p ids) doc :prev)))
    (is (= (:p ids) (layers/nav-target rows (:c ids) doc :prev)))
    (is (= (:c ids) (layers/nav-target rows (:d ids) doc :prev)))
    (is (= (:d ids) (layers/nav-target rows (:b ids) doc :prev)))
    (is (nil?       (layers/nav-target rows "root"   doc :prev))
        "prev at the top of the list returns nil")

    (is (= (:a ids) (layers/nav-target rows "root"   doc :next)))
    (is (= (:p ids) (layers/nav-target rows (:a ids) doc :next)))
    (is (= (:c ids) (layers/nav-target rows (:p ids) doc :next)))
    (is (= (:b ids) (layers/nav-target rows (:d ids) doc :next)))
    (is (nil?       (layers/nav-target rows (:b ids) doc :next))
        "next at the end of the list returns nil")))

(deftest nav-target-parent
  (let [{:keys [doc ids]} (sample-doc)
        rows (vec (layers/flatten-tree doc))]
    (is (nil?       (layers/nav-target rows "root"   doc :parent)))
    (is (= "root"   (layers/nav-target rows (:a ids) doc :parent)))
    (is (= (:p ids) (layers/nav-target rows (:c ids) doc :parent)))
    (is (= (:p ids) (layers/nav-target rows (:d ids) doc :parent)))))

(deftest nav-target-first-child
  (let [{:keys [doc ids]} (sample-doc)
        rows (vec (layers/flatten-tree doc))]
    (is (= (:a ids) (layers/nav-target rows "root"   doc :first-child)))
    (is (= (:c ids) (layers/nav-target rows (:p ids) doc :first-child)))
    (is (nil?       (layers/nav-target rows (:a ids) doc :first-child))
        "leaf returns nil")))

(deftest reorder-target-up
  (let [{:keys [doc ids]} (sample-doc)]
    (testing "p is at idx 1 in root/default; up goes to idx 0"
      (is (= {:parent-id "root" :slot "default" :index 0}
             (layers/reorder-target doc (:p ids) :up))))
    (testing "a is at idx 0; up is out of bounds"
      (is (nil? (layers/reorder-target doc (:a ids) :up))))
    (testing "root has no parent"
      (is (nil? (layers/reorder-target doc "root" :up))))))

(deftest reorder-target-down
  (let [{:keys [doc ids]} (sample-doc)]
    (testing "a is at idx 0 in root/default; down goes to idx 1"
      (is (= {:parent-id "root" :slot "default" :index 1}
             (layers/reorder-target doc (:a ids) :down))))
    (testing "b is at idx 2 (last); down is out of bounds"
      (is (nil? (layers/reorder-target doc (:b ids) :down))))))
