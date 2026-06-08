(ns bareforge.meta.placement-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.meta.placement :as p]))

(def ^:private base
  {:parent-id "somewhere_else" :slot "content" :index 2})

;; --- hint-for sanity ----------------------------------------------------

(deftest hint-for-structural-tags
  (is (= :top-full-width (:hint (p/hint-for "x-navbar")))))

(deftest hint-for-sidebar-is-flow
  ;; A sidebar is a flow container the user places like any other block —
  ;; not a top-snapped bar. (x-navbar keeps :top-full-width.)
  (is (= :flow (:hint (p/hint-for "x-sidebar")))))

(deftest hint-for-unknown-tag-defaults-to-flow
  (is (= :flow (:hint (p/hint-for "x-totally-made-up")))))

;; --- apply-snap ---------------------------------------------------------

(deftest apply-snap-top-full-width-redirects-to-root-index-0
  (let [doc (m/empty-document)
        out (p/apply-snap :top-full-width doc base)]
    (is (= {:parent-id "root" :slot "default" :index 0}
           (:target out))
        "target is root/default/0 regardless of where the cursor was")
    (is (= {:width "100%"} (:layout out)))))

(deftest apply-snap-bottom-full-width-redirects-to-root-last-index
  (let [d0       (m/empty-document)
        {d1 :doc} (ops/insert-new d0 "root" "default" 0 "x-card")
        {d2 :doc} (ops/insert-new d1 "root" "default" 1 "x-typography")
        {d3 :doc} (ops/insert-new d2 "root" "default" 2 "x-button")
        out      (p/apply-snap :bottom-full-width d3 base)]
    (testing "index equals current child count so the new node appends"
      (is (= {:parent-id "root" :slot "default" :index 3}
             (:target out))))
    (is (= {:width "100%"} (:layout out)))))

(deftest apply-snap-bottom-full-width-empty-root
  (let [doc (m/empty-document)
        out (p/apply-snap :bottom-full-width doc base)]
    (is (= {:parent-id "root" :slot "default" :index 0}
           (:target out))
        "empty root: bottom is just index 0")))

(deftest apply-snap-non-snap-hints-return-nil
  (let [doc (m/empty-document)]
    (doseq [h [:flow :free :background nil :totally-made-up]]
      (is (nil? (p/apply-snap h doc base))
          (str "hint " h " should not snap")))))

(deftest apply-snap-preserves-unknown-target-keys
  (testing "apply-snap uses assoc, so any extra keys on base-target
            survive the redirect — useful if we add more target
            fields in the future"
    (let [doc      (m/empty-document)
          enriched (assoc base :extra :keep-me)
          out      (p/apply-snap :top-full-width doc enriched)]
      (is (= :keep-me (get-in out [:target :extra]))))))
