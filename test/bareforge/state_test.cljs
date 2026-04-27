(ns bareforge.state-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.doc.ops :as ops]
            [bareforge.state :as state]))

(defn- fresh [] (state/initial-state))

(defn- commit-tag [state tag]
  (let [{doc' :doc} (ops/insert-new (:document state) "root" "default" 0 tag)]
    (state/apply-commit state doc')))

(deftest initial-state-has-empty-history
  (let [s (fresh)]
    (is (= [] (get-in s [:history :past])))
    (is (= [] (get-in s [:history :future])))
    (is (false? (:dirty? s)))
    (is (= :edit (:mode s)))))

(deftest commit-pushes-previous-and-clears-future
  (let [s0 (fresh)
        s1 (commit-tag s0 "x-a")]
    (is (= 1 (count (get-in s1 [:history :past]))))
    (is (= [] (get-in s1 [:history :future])))
    (is (true? (:dirty? s1)))
    (is (= "x-a" (get-in s1 [:document :root :slots "default" 0 :tag])))))

(deftest undo-restores-previous-and-moves-to-future
  (let [s0 (fresh)
        s1 (commit-tag s0 "x-a")
        s2 (state/apply-undo s1)]
    (is (= (:document s0) (:document s2)))
    (is (= 1 (count (get-in s2 [:history :future]))))
    (is (= 0 (count (get-in s2 [:history :past]))))))

(deftest undo-on-empty-past-is-no-op
  (let [s0 (fresh)
        s1 (state/apply-undo s0)]
    (is (= s0 s1))))

(deftest redo-restores-undone-state
  (let [s0 (fresh)
        s1 (commit-tag s0 "x-a")
        s2 (state/apply-undo s1)
        s3 (state/apply-redo s2)]
    (is (= (:document s1) (:document s3)))
    (is (= [] (get-in s3 [:history :future])))
    (is (= 1 (count (get-in s3 [:history :past]))))))

(deftest redo-on-empty-future-is-no-op
  (let [s0 (fresh)
        s1 (commit-tag s0 "x-a")
        s2 (state/apply-redo s1)]
    (is (= s1 s2))))

(deftest commit-clears-future-stack
  (let [s0 (fresh)
        s1 (commit-tag s0 "x-a")
        s2 (commit-tag s1 "x-b")
        s3 (state/apply-undo s2)]
    (is (= 1 (count (get-in s3 [:history :future]))))
    (let [s4 (commit-tag s3 "x-c")]
      (is (= [] (get-in s4 [:history :future])) "new commit clears redo")
      (is (= 2 (count (get-in s4 [:history :past])))))))

(deftest multi-undo-redo-round-trip
  (let [s0 (fresh)
        s1 (commit-tag s0 "x-a")
        s2 (commit-tag s1 "x-b")
        s3 (commit-tag s2 "x-c")
        ;; undo three times
        s4 (-> s3 state/apply-undo state/apply-undo state/apply-undo)
        ;; redo three times
        s5 (-> s4 state/apply-redo state/apply-redo state/apply-redo)]
    (is (= (:document s0) (:document s4)))
    (is (= (:document s3) (:document s5)))))

(defn- coalesce-tag [state tag]
  (let [{doc' :doc} (ops/insert-new (:document state) "root" "default" 0 tag)]
    (state/apply-coalesce state doc')))

(deftest coalesce-does-not-grow-past
  (let [s0 (fresh)
        s1 (commit-tag  s0 "x-a")
        s2 (coalesce-tag s1 "x-b")
        s3 (coalesce-tag s2 "x-c")]
    (is (= 1 (count (get-in s3 [:history :past])))
        "three edits collapse into one undo entry")
    (is (= (:document s0) (peek (get-in s3 [:history :past])))
        "the single :past entry is the pre-first-commit document")
    (is (= "x-c" (get-in s3 [:document :root :slots "default" 0 :tag]))
        "document reflects the latest coalesced edit")))

(deftest coalesce-still-clears-future
  (let [s0 (fresh)
        s1 (commit-tag  s0 "x-a")
        s2 (commit-tag  s1 "x-b")
        s3 (state/apply-undo s2)]
    (is (= 1 (count (get-in s3 [:history :future]))))
    (let [s4 (coalesce-tag s3 "x-c")]
      (is (= [] (get-in s4 [:history :future]))
          "a coalesced edit after undo discards the redo branch"))))

(deftest coalesce-marks-dirty
  (let [s0 (fresh)
        s1 (coalesce-tag s0 "x-a")]
    (is (true? (:dirty? s1)))))

(deftest undo-after-coalesced-burst-returns-to-pre-burst
  ;; Real nudge call pattern: the FIRST nudge commits normally (so
  ;; the pre-burst state lands in :past), and every nudge after
  ;; coalesces on top of it. One Cmd+Z then reverses the whole
  ;; burst in a single step.
  (let [s0 (fresh)
        s1 (commit-tag   s0 "x-a")   ;; ambient prior edit
        s2 (commit-tag   s1 "x-b")   ;; pre-burst state
        ;; burst begins — first nudge uses commit, rest coalesce
        s3 (commit-tag   s2 "x-c")
        s4 (coalesce-tag s3 "x-d")
        s5 (coalesce-tag s4 "x-e")
        s6 (state/apply-undo s5)]
    (is (= (:document s2) (:document s6))
        "one undo reverses the entire coalesced burst back to pre-burst")
    (is (= 2 (count (get-in s6 [:history :past])))
        ":past still holds the two pre-burst entries after undo")))

(deftest history-is-capped
  (let [s0   (fresh)
        many (reduce (fn [s n] (commit-tag s (str "x-" n)))
                     s0
                     (range (+ state/history-limit 10)))]
    (is (= state/history-limit
           (count (get-in many [:history :past]))))))

(deftest effectful-atom-wrappers
  (state/reset-state!)
  (is (= :edit (:mode @state/app-state)))
  (state/set-mode! :preview)
  (is (= :preview (:mode @state/app-state)))
  (state/set-theme-override! "--x-color-primary" "#4f46e5")
  (is (= "#4f46e5" (get-in @state/app-state [:theme :overrides "--x-color-primary"])))
  (state/assoc-ui! :palette-search "nav")
  (is (= "nav" (get-in @state/app-state [:ui :palette-search])))
  (state/set-selection! {:id "abc"})
  (is (= {:id "abc"} (:selection @state/app-state)))
  (testing "theme changes do not enter history"
    (is (= [] (get-in @state/app-state [:history :past])))
    (is (= [] (get-in @state/app-state [:history :future]))))
  (state/reset-state!))
