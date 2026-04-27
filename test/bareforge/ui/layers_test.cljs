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
