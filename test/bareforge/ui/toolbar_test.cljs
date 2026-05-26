(ns bareforge.ui.toolbar-test
  (:require [cljs.test :refer [deftest is]]
            [bareforge.state :as state]
            [bareforge.ui.toolbar :as toolbar]))

(deftest initial-state-disables-both
  (let [t (toolbar/toolbar-state (state/initial-state))]
    (is (true? (:undo-disabled? t)))
    (is (true? (:redo-disabled? t)))
    (is (false? (:preview? t)))))

(deftest commit-enables-undo
  (let [s (state/apply-commit (state/initial-state) {:root {:id "root" :tag "x"}})]
    (is (false? (:undo-disabled? (toolbar/toolbar-state s))))
    (is (true?  (:redo-disabled? (toolbar/toolbar-state s))))))

(deftest undo-enables-redo
  (let [s (-> (state/initial-state)
              (state/apply-commit {:root {:id "root" :tag "x"}})
              state/apply-undo)]
    (is (true?  (:undo-disabled? (toolbar/toolbar-state s))))
    (is (false? (:redo-disabled? (toolbar/toolbar-state s))))))

(deftest preview-flag-flips
  (is (true? (:preview? (toolbar/toolbar-state (assoc (state/initial-state) :mode :preview))))))

(deftest autosave-failed-flag-tracks-ui-flag
  (is (false? (:autosave-failed? (toolbar/toolbar-state (state/initial-state))))
      "fresh state has no autosave failure")
  (let [s (assoc-in (state/initial-state) [:ui :autosave-failed?] true)]
    (is (true? (:autosave-failed? (toolbar/toolbar-state s)))
        "flag bubbles up so the toolbar can show a warning indicator")))
