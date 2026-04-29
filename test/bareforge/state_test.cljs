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
  (state/select-one! "abc")
  (is (= ["abc"] (:selection @state/app-state)))
  (testing "theme changes do not enter history"
    (is (= [] (get-in @state/app-state [:history :past])))
    (is (= [] (get-in @state/app-state [:history :future]))))
  (state/reset-state!))

;; --- selection helpers ---------------------------------------------------

(deftest selected-ids-defaults-empty
  (is (= [] (state/selected-ids (fresh))))
  (is (= [] (state/selected-ids {}))))

(deftest selected?-and-single-selected-id
  (let [s0 (assoc (fresh) :selection [])
        s1 (assoc (fresh) :selection ["a"])
        s2 (assoc (fresh) :selection ["a" "b"])]
    (is (false? (state/selected? s0 "a")))
    (is (true?  (state/selected? s1 "a")))
    (is (false? (state/selected? s1 "b")))
    (is (true?  (state/selected? s2 "b")))
    (is (nil?   (state/single-selected-id s0)))
    (is (= "a"  (state/single-selected-id s1)))
    (is (nil?   (state/single-selected-id s2))
        "multi-select degrades single-selected-id to nil")))

(deftest set-selection!-normalises-to-vector
  (state/reset-state!)
  (state/set-selection! ["a" "b" "c"])
  (is (= ["a" "b" "c"] (:selection @state/app-state)))
  (state/set-selection! '("d" "e"))
  (is (= ["d" "e"] (:selection @state/app-state))
      "lists are coerced to vectors")
  (state/set-selection! nil)
  (is (= [] (:selection @state/app-state))
      "nil clears the selection")
  (state/reset-state!))

(deftest select-one!-and-clear!
  (state/reset-state!)
  (state/select-one! "abc")
  (is (= ["abc"] (:selection @state/app-state)))
  (state/select-clear!)
  (is (= [] (:selection @state/app-state)))
  (state/select-one! nil)
  (is (= [] (:selection @state/app-state))
      "select-one! nil clears the selection")
  (state/reset-state!))

(deftest select-toggle!-add-and-remove
  (state/reset-state!)
  (state/select-toggle! "a")
  (is (= ["a"] (:selection @state/app-state)))
  (state/select-toggle! "b")
  (is (= ["a" "b"] (:selection @state/app-state))
      "second toggle conjs onto the existing vector — newest at tail")
  (state/select-toggle! "a")
  (is (= ["b"] (:selection @state/app-state))
      "toggling a present id removes it")
  (state/select-toggle! "b")
  (is (= [] (:selection @state/app-state))
      "toggling the last id clears the selection")
  (state/reset-state!))

;; --- attribute clipboard --------------------------------------------------

(deftest clipboard-attrs-defaults-nil
  (is (nil? (state/clipboard-attrs (fresh)))))

(deftest set-clipboard-attrs-roundtrip
  (state/reset-state!)
  (let [entry {:source-tag "x-button"
               :attrs {"variant" "primary"}
               :props {:disabled true}}]
    (state/set-clipboard-attrs! entry)
    (is (= entry (state/clipboard-attrs @state/app-state))))
  (state/set-clipboard-attrs! nil)
  (is (nil? (state/clipboard-attrs @state/app-state))
      "nil clears the clipboard")
  (state/reset-state!))
