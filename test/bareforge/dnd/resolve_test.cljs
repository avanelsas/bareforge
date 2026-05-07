(ns bareforge.dnd.resolve-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.dnd.resolve :as resolve]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]))

;; --- fixtures ------------------------------------------------------------

(defn- doc-with
  "Build a document by inserting `tag` at root and returning the doc
   plus the new node id."
  [tag]
  (let [{doc' :doc id :id}
        (ops/insert-new (m/empty-document) "root" "default" 0 tag {})]
    [doc' id]))

(def empty-snapshot
  ;; All snapshot keys present and nil — defaults the planner falls
  ;; back to `palette/insertion-target` for.
  {:source-tag       nil
   :source-node-id   nil
   :slot-target-node nil
   :slot-target-name nil
   :target-id        nil
   :target-position  nil
   :start-x          0
   :start-y          0
   :free-initial-x   0
   :free-initial-y   0})

;; --- before-after-target -------------------------------------------------

(deftest before-after-target-before-keeps-index
  (let [[doc id] (doc-with "x-button")]
    (is (= {:parent-id "root" :slot "default" :index 0}
           (resolve/before-after-target doc id :before)))))

(deftest before-after-target-after-increments-index
  (let [[doc id] (doc-with "x-button")]
    (is (= {:parent-id "root" :slot "default" :index 1}
           (resolve/before-after-target doc id :after)))))

(deftest before-after-target-root-returns-nil
  (testing "root has no parent — caller must fall back"
    (let [doc (m/empty-document)]
      (is (nil? (resolve/before-after-target doc "root" :before)))
      (is (nil? (resolve/before-after-target doc "root" :after))))))

(deftest before-after-target-unknown-id-returns-nil
  (let [doc (m/empty-document)]
    (is (nil? (resolve/before-after-target doc "nope" :before)))))

;; --- resolve-insertion-target -------------------------------------------

(deftest slot-target-wins-over-canvas-hover
  (testing "slot-target-node + slot-target-name take precedence over
            target-id / target-position"
    (let [[doc id] (doc-with "x-container")
          snap     (assoc empty-snapshot
                          :slot-target-node id
                          :slot-target-name "default"
                          :target-id        "should-be-ignored"
                          :target-position  :before)]
      (is (= {:parent-id id :slot "default" :index 0}
             (resolve/resolve-insertion-target doc snap))))))

(deftest slot-target-index-equals-current-child-count
  (let [[doc1 cid] (doc-with "x-container")
        {doc2 :doc} (ops/insert-new doc1 cid "default" 0 "x-button" {})
        snap        (assoc empty-snapshot
                           :slot-target-node cid
                           :slot-target-name "default")]
    (is (= 1
           (:index (resolve/resolve-insertion-target doc2 snap)))
        "appending into a slot with one child lands at index 1")))

(deftest before-position-resolves-to-sibling-insertion
  (let [[doc id] (doc-with "x-button")
        snap     (assoc empty-snapshot
                        :target-id       id
                        :target-position :before)]
    (is (= {:parent-id "root" :slot "default" :index 0}
           (resolve/resolve-insertion-target doc snap)))))

(deftest after-position-resolves-to-sibling-after
  (let [[doc id] (doc-with "x-button")
        snap     (assoc empty-snapshot
                        :target-id       id
                        :target-position :after)]
    (is (= {:parent-id "root" :slot "default" :index 1}
           (resolve/resolve-insertion-target doc snap)))))

(deftest before-after-on-root-falls-back-to-palette-insertion
  (testing "root has no parent so before/after degrade to the
            palette-style fallback (append-inside-or-after-selection)"
    (let [doc  (m/empty-document)
          snap (assoc empty-snapshot
                      :target-id       "root"
                      :target-position :before)
          out  (resolve/resolve-insertion-target doc snap)]
      (is (= "root" (:parent-id out))
          "fallback lands inside root's default slot"))))

(deftest empty-snapshot-falls-back-to-palette-insertion
  (let [doc (m/empty-document)]
    (is (= {:parent-id "root" :slot "default" :index 0}
           (resolve/resolve-insertion-target doc empty-snapshot)))))

;; --- plan-drop -----------------------------------------------------------

(deftest plan-drop-default-flow-tag
  (testing "plain :flow tag (no snap, no background) drops at the
            cursor-targeted slot with no layout overrides"
    (let [doc  (m/empty-document)
          snap (assoc empty-snapshot :source-tag "x-button")
          out  (resolve/plan-drop doc snap)]
      (is (= "root"    (:parent-id out)))
      (is (= "default" (:slot out)))
      (is (= 0         (:index out)))
      (is (= "x-button" (:tag out)))
      (is (not (contains? (:overrides out) :layout))
          "no snap → no :layout override seeded onto the new node"))))

(deftest plan-drop-background-tag-snaps-to-index-zero
  (testing "x-gaussian-blur is a :background hint — index gets pinned
            to 0 in the cursor-targeted slot and the override carries
            :placement :background"
    (let [doc  (m/empty-document)
          snap (assoc empty-snapshot :source-tag "x-gaussian-blur")
          out  (resolve/plan-drop doc snap)]
      (is (= 0 (:index out))
          "background drops paint behind siblings → index 0")
      (is (= :background (get-in out [:overrides :layout :placement]))))))

(deftest plan-drop-top-full-width-snaps-to-root
  (testing "x-navbar is :top-full-width — placement/apply-snap
            redirects to root index 0 and adds width 100% override"
    (let [[doc cid] (doc-with "x-container")
          snap      (assoc empty-snapshot
                           :source-tag       "x-navbar"
                           :slot-target-node cid
                           :slot-target-name "default")
          out       (resolve/plan-drop doc snap)]
      (is (= "root"   (:parent-id out)))
      (is (= "default" (:slot out)))
      (is (= 0        (:index out)))
      (is (= "100%" (get-in out [:overrides :layout :width]))))))

(deftest plan-drop-seed-overrides-merge-with-snap
  (testing "x-grid carries seed attrs from palette/seed-for-tag; a
            snap layer-merges its :layout into the seed without
            blowing the seed away"
    (let [doc  (m/empty-document)
          snap (assoc empty-snapshot :source-tag "x-grid")
          out  (resolve/plan-drop doc snap)]
      (is (= "repeat(3, 1fr)"
             (get-in out [:overrides :attrs "columns"]))
          "seed-for-tag's :attrs survive even when the planner adds
           layout snap overrides"))))

;; --- plan-move -----------------------------------------------------------

(deftest plan-move-attaches-source-id
  (let [[doc id] (doc-with "x-button")
        snap     (assoc empty-snapshot
                        :source-node-id  id
                        :target-id       id
                        :target-position :after)
        out      (resolve/plan-move doc snap)]
    (is (= id        (:src-id out)))
    (is (= "root"    (:parent-id out)))
    (is (= "default" (:slot out)))
    (is (= 1         (:index out)))))

;; --- plan-free-move ------------------------------------------------------

(deftest plan-free-move-adds-cursor-delta-to-initial
  (let [snap (assoc empty-snapshot
                    :source-node-id  "n_3"
                    :start-x         100
                    :start-y         50
                    :free-initial-x  10
                    :free-initial-y  20)]
    (is (= {:src-id "n_3" :x 60 :y 90}
           (resolve/plan-free-move snap 150 120 1))
        "delta (50, 70) added to initial (10, 20) → (60, 90)")))

(deftest plan-free-move-handles-negative-delta
  (let [snap (assoc empty-snapshot
                    :source-node-id  "n_5"
                    :start-x         200
                    :start-y         200
                    :free-initial-x  100
                    :free-initial-y  100)]
    (is (= {:src-id "n_5" :x 50 :y 80}
           (resolve/plan-free-move snap 150 180 1)))))

(deftest plan-free-move-zero-delta-keeps-initial
  (let [snap (assoc empty-snapshot
                    :source-node-id  "n_7"
                    :start-x         100
                    :start-y         100
                    :free-initial-x  42
                    :free-initial-y  17)]
    (is (= {:src-id "n_7" :x 42 :y 17}
           (resolve/plan-free-move snap 100 100 1)))))

(deftest plan-free-move-divides-cursor-delta-by-zoom
  (testing "Cursor deltas are in viewport pixels — at zoom > 1 the
            element should move fewer doc-pixels than the cursor."
    (let [snap (assoc empty-snapshot
                      :source-node-id  "n_9"
                      :start-x         100
                      :start-y         100
                      :free-initial-x  0
                      :free-initial-y  0)]
      (is (= {:src-id "n_9" :x 50 :y 25}
             (resolve/plan-free-move snap 200 150 2))
          "100 px viewport delta at 2× zoom = 50 doc-px in x;
           50 px viewport delta = 25 doc-px in y")
      (is (= {:src-id "n_9" :x 200 :y 200}
             (resolve/plan-free-move snap 200 200 0.5))
          "100 px viewport delta at 0.5× zoom = 200 doc-px"))))

(deftest plan-free-move-nil-zoom-falls-back-to-1
  (testing "A nil zoom (e.g. from a stale state shape) reads as 1 so
            the planner stays defined."
    (let [snap (assoc empty-snapshot
                      :source-node-id  "n_z"
                      :start-x         0
                      :start-y         0
                      :free-initial-x  0
                      :free-initial-y  0)]
      (is (= {:src-id "n_z" :x 30 :y 40}
             (resolve/plan-free-move snap 30 40 nil))))))
