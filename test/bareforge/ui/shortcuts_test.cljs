(ns bareforge.ui.shortcuts-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.ui.shortcuts :as k]))

(def base
  {:key              "a"
   :meta?            false
   :shift?           false
   :tag-name         "DIV"
   :content-editable? false
   :has-selection?   false
   :selection-id     nil})

(deftest editable-target-input
  (is (true?  (k/editable-target? {:tag-name "INPUT"})))
  (is (true?  (k/editable-target? {:tag-name "textarea"})))
  (is (true?  (k/editable-target? {:tag-name "X-SEARCH-FIELD"})))
  (is (true?  (k/editable-target? {:content-editable? true})))
  (is (false? (k/editable-target? {:tag-name "DIV"}))))

(deftest dispatch-noop
  (is (= :noop (k/dispatch base))))

(deftest dispatch-cmd-z-undoes
  (is (= :undo  (k/dispatch (assoc base :key "z" :meta? true))))
  (is (= :undo  (k/dispatch (assoc base :key "z" :meta? true :shift? false)))))

(deftest dispatch-cmd-shift-z-redoes
  (is (= :redo (k/dispatch (assoc base :key "z" :meta? true :shift? true))))
  (is (= :redo (k/dispatch (assoc base :key "Z" :meta? true :shift? true)))))

(deftest dispatch-ignores-undo-in-editable
  (is (= :noop (k/dispatch (assoc base :key "z" :meta? true :tag-name "INPUT")))))

(deftest dispatch-delete-when-selected
  (is (= :delete
         (k/dispatch (assoc base :key "Delete"
                            :has-selection? true :selection-id "n_3")))))

(deftest dispatch-backspace-when-selected
  (is (= :delete
         (k/dispatch (assoc base :key "Backspace"
                            :has-selection? true :selection-id "n_3")))))

(deftest dispatch-delete-noop-without-selection
  (is (= :noop (k/dispatch (assoc base :key "Delete" :has-selection? false)))))

(deftest dispatch-delete-noop-on-root
  (is (= :noop
         (k/dispatch (assoc base :key "Delete"
                            :has-selection? true
                            :selection-id "root")))))

;; --- file-menu shortcuts -----------------------------------------------

(deftest dispatch-cmd-s-saves
  (is (= :save (k/dispatch (assoc base :key "s" :meta? true)))))

(deftest dispatch-cmd-o-opens
  (is (= :open (k/dispatch (assoc base :key "o" :meta? true)))))

(deftest dispatch-cmd-n-news
  (is (= :new (k/dispatch (assoc base :key "n" :meta? true)))))

(deftest dispatch-cmd-shift-s-is-noop
  (testing "Cmd+Shift+S is reserved (Save As / OS screenshot)"
    (is (= :noop (k/dispatch (assoc base :key "s" :meta? true :shift? true))))))

(deftest dispatch-plain-s-is-noop
  (is (= :noop (k/dispatch (assoc base :key "s")))))

(deftest dispatch-cmd-s-ignored-in-editable
  (testing "typing 's' with Cmd held in an input (e.g. search field)
            falls through to the native behaviour"
    (is (= :noop (k/dispatch (assoc base :key "s" :meta? true
                                    :tag-name "INPUT"))))
    (is (= :noop (k/dispatch (assoc base :key "o" :meta? true
                                    :tag-name "X-SEARCH-FIELD"))))
    (is (= :noop (k/dispatch (assoc base :key "n" :meta? true
                                    :content-editable? true))))))

;; --- exit-text-edit ----------------------------------------------------

(deftest dispatch-escape-exits-text-edit-when-editing
  (is (= :exit-text-edit
         (k/dispatch (assoc base :key "Escape"
                            :has-selection? true
                            :selection-id   "n_3"
                            :text-editing-id "n_3")))))

(deftest dispatch-escape-exits-text-edit-even-in-editable
  (testing "Escape in the inline edit textarea still maps to
            :exit-text-edit (the textarea stopPropagation's it
            first, but this is the fallback path)"
    (is (= :exit-text-edit
           (k/dispatch (assoc base :key "Escape"
                              :has-selection? true
                              :selection-id   "n_3"
                              :text-editing-id "n_3"
                              :tag-name       "TEXTAREA"))))))

(deftest dispatch-escape-deselects-when-not-editing
  (testing "without text-editing-id, Escape still deselects"
    (is (= :deselect
           (k/dispatch (assoc base :key "Escape"
                              :has-selection? true
                              :selection-id   "n_3"
                              :text-editing-id nil))))))

;; --- deselect -----------------------------------------------------------

(deftest dispatch-escape-with-selection-deselects
  (is (= :deselect
         (k/dispatch (assoc base :key "Escape"
                            :has-selection? true
                            :selection-id   "n_3")))))

(deftest dispatch-escape-deselects-root-too
  (testing "Escape clears the selection even when root is selected —
            root-delete is blocked, but deselect should still work"
    (is (= :deselect
           (k/dispatch (assoc base :key "Escape"
                              :has-selection? true
                              :selection-id   "root"))))))

(deftest dispatch-escape-without-selection-noop
  (is (= :noop
         (k/dispatch (assoc base :key "Escape" :has-selection? false)))))

(deftest dispatch-escape-ignored-in-editable
  (is (= :noop
         (k/dispatch (assoc base :key "Escape"
                            :has-selection? true
                            :selection-id   "n_3"
                            :tag-name       "INPUT")))))

(deftest dispatch-cmd-escape-ignored
  (testing "Cmd+Escape is reserved for OS-level actions, not deselect"
    (is (= :noop
           (k/dispatch (assoc base :key "Escape"
                              :meta?          true
                              :has-selection? true
                              :selection-id   "n_3"))))))

(deftest dispatch-delete-ignored-in-editable
  (is (= :noop
         (k/dispatch (assoc base :key "Backspace"
                            :has-selection? true
                            :selection-id "n_3"
                            :tag-name "x-search-field")))))

(deftest dispatch-delete-ignored-in-every-baredom-form-tag
  (testing "Every BareDOM form tag rendered by the Inspector must
    swallow Backspace/Delete so typing in a value does not delete
    the selected canvas node."
    (doseq [tag ["x-search-field" "x-text-field" "x-text-area"
                 "x-number-field" "x-currency-field"
                 "x-select" "x-combobox"
                 "x-color-picker" "x-date-picker"
                 "x-slider"]]
      (is (= :noop
             (k/dispatch (assoc base :key "Backspace"
                                :has-selection? true
                                :selection-id   "n_3"
                                :tag-name       tag)))
          (str "Backspace inside <" tag "> should be ignored")))))

;; --- arrow-key nudge -----------------------------------------------------

(def ^:private free-selected
  (assoc base
         :has-selection? true
         :selection-id   "n_7"
         :placement      :free))

(deftest dispatch-nudge-free-arrow-left
  (is (= [:nudge -1 0]
         (k/dispatch (assoc free-selected :key "ArrowLeft")))))

(deftest dispatch-nudge-free-arrow-right
  (is (= [:nudge 1 0]
         (k/dispatch (assoc free-selected :key "ArrowRight")))))

(deftest dispatch-nudge-free-arrow-up
  (is (= [:nudge 0 -1]
         (k/dispatch (assoc free-selected :key "ArrowUp")))))

(deftest dispatch-nudge-free-arrow-down
  (is (= [:nudge 0 1]
         (k/dispatch (assoc free-selected :key "ArrowDown")))))

(deftest dispatch-nudge-shift-uses-10px-step
  (is (= [:nudge -10 0]
         (k/dispatch (assoc free-selected :key "ArrowLeft" :shift? true))))
  (is (= [:nudge 10 0]
         (k/dispatch (assoc free-selected :key "ArrowRight" :shift? true))))
  (is (= [:nudge 0 10]
         (k/dispatch (assoc free-selected :key "ArrowDown" :shift? true)))))

(deftest dispatch-nudge-noop-when-selection-is-not-free
  (testing "flow placement: arrow keys fall through to normal scroll"
    (is (= :noop
           (k/dispatch (assoc free-selected :key "ArrowLeft" :placement :flow))))
    (is (= :noop
           (k/dispatch (assoc free-selected :key "ArrowLeft"
                              :placement :background))))))

(deftest dispatch-nudge-noop-without-selection
  (is (= :noop
         (k/dispatch (assoc base :key "ArrowLeft")))))

(deftest dispatch-nudge-ignored-in-editable
  (is (= :noop
         (k/dispatch (assoc free-selected :key "ArrowLeft"
                            :tag-name "INPUT")))))

(deftest dispatch-nudge-ignored-with-meta
  (testing "Cmd+Arrow is reserved for OS-level navigation, not a nudge"
    (is (= :noop
           (k/dispatch (assoc free-selected :key "ArrowLeft" :meta? true))))))

;; --- duplicate ----------------------------------------------------------

(def ^:private with-selection
  (assoc base
         :has-selection? true
         :selection-id   "n_3"))

(deftest dispatch-cmd-d-duplicates
  (is (= :duplicate
         (k/dispatch (assoc with-selection :key "d" :meta? true)))))

(deftest dispatch-cmd-d-noop-without-selection
  (is (= :noop
         (k/dispatch (assoc base :key "d" :meta? true)))))

(deftest dispatch-cmd-d-ignored-in-editable
  (is (= :noop
         (k/dispatch (assoc with-selection :key "d" :meta? true
                                           :tag-name "INPUT")))))

(deftest dispatch-cmd-shift-d-is-noop
  (testing "Cmd+Shift+D is reserved (browser bookmark variants);
            we only bind plain Cmd+D"
    (is (= :noop
           (k/dispatch (assoc with-selection :key "d" :meta? true :shift? true))))))

;; --- wrap-in ------------------------------------------------------------

(deftest dispatch-cmd-g-wraps-in-x-container
  (is (= [:wrap-in "x-container"]
         (k/dispatch (assoc with-selection :key "g" :meta? true)))))

(deftest dispatch-cmd-g-noop-without-selection
  (is (= :noop
         (k/dispatch (assoc base :key "g" :meta? true)))))

(deftest dispatch-cmd-g-noop-on-root
  (testing "wrapping root makes no sense — handled by the dispatch
            guard, mirroring the :delete pattern"
    (is (= :noop
           (k/dispatch (assoc with-selection :key "g" :meta? true
                                             :selection-id "root"))))))

(deftest dispatch-cmd-g-ignored-in-editable
  (is (= :noop
         (k/dispatch (assoc with-selection :key "g" :meta? true
                                           :tag-name "X-SEARCH-FIELD")))))

(deftest dispatch-cmd-shift-g-prompts
  (is (= :wrap-in-prompt
         (k/dispatch (assoc with-selection :key "g" :meta? true :shift? true))))
  (is (= :wrap-in-prompt
         (k/dispatch (assoc with-selection :key "G" :meta? true :shift? true)))))

(deftest dispatch-cmd-shift-g-noop-on-root
  (is (= :noop
         (k/dispatch (assoc with-selection :key "g" :meta? true :shift? true
                                           :selection-id "root")))))

;; --- copy / paste attrs -------------------------------------------------

(deftest dispatch-cmd-opt-c-copies-attrs
  (is (= :copy-attrs
         (k/dispatch (assoc with-selection :key "c" :meta? true :alt? true)))))

(deftest dispatch-cmd-opt-c-noop-on-root
  (is (= :noop
         (k/dispatch (assoc with-selection :key "c" :meta? true :alt? true
                                           :selection-id "root")))))

(deftest dispatch-cmd-opt-c-noop-without-selection
  (is (= :noop
         (k/dispatch (assoc base :key "c" :meta? true :alt? true)))))

(deftest dispatch-plain-cmd-c-is-noop
  (testing "plain Cmd-C still falls through to the browser's native copy
            so users can copy text from inspector inputs / layers panel"
    (is (= :noop
           (k/dispatch (assoc with-selection :key "c" :meta? true))))))

(deftest dispatch-cmd-opt-v-pastes-attrs
  (is (= :paste-attrs
         (k/dispatch (assoc with-selection :key "v" :meta? true :alt? true)))))

(deftest dispatch-cmd-opt-v-noop-without-selection
  (is (= :noop
         (k/dispatch (assoc base :key "v" :meta? true :alt? true)))))

(deftest dispatch-cmd-opt-c-ignored-in-editable
  (is (= :noop
         (k/dispatch (assoc with-selection :key "c" :meta? true :alt? true
                                           :tag-name "INPUT")))))

(deftest dispatch-cmd-opt-v-ignored-in-editable
  (is (= :noop
         (k/dispatch (assoc with-selection :key "v" :meta? true :alt? true
                                           :tag-name "X-TEXT-FIELD")))))

(deftest dispatch-cmd-opt-c-matches-macos-modified-key
  (testing "On macOS US layout Option+C produces 'ç', so dispatch must
            also accept the physical .code 'KeyC' as the letter source.
            We supply the modified key — the matching .code value
            should still resolve the action."
    (is (= :copy-attrs
           (k/dispatch (assoc with-selection
                              :key "ç" :code "KeyC"
                              :meta? true :alt? true))))
    (is (= :paste-attrs
           (k/dispatch (assoc with-selection
                              :key "√" :code "KeyV"
                              :meta? true :alt? true))))))

;; --- coalesce? -----------------------------------------------------------

(def ^:private last-rec
  {:node-id "n_7" :last-ms 1000 :past-count 3})

(defn- attempt
  ([] (attempt {}))
  ([overrides]
   (merge {:node-id    "n_7"
           :now-ms     1200
           :past-count 3
           :window-ms  500}
          overrides)))

(deftest coalesce-yes-within-window-same-node-same-past
  (is (true? (k/coalesce? last-rec (attempt)))))

(deftest coalesce-no-when-no-last-record
  (is (false? (k/coalesce? nil (attempt)))))

(deftest coalesce-no-past-window
  (is (false? (k/coalesce? last-rec (attempt {:now-ms 1600}))))
  (testing "exactly at the edge is treated as stale (strict <)"
    (is (false? (k/coalesce? last-rec (attempt {:now-ms 1500}))))))

(deftest coalesce-no-when-selection-changes
  (is (false? (k/coalesce? last-rec (attempt {:node-id "n_9"})))))

(deftest coalesce-no-when-intervening-commit-grew-past
  (testing "any non-nudge commit between two nudges resets the burst"
    (is (false? (k/coalesce? last-rec (attempt {:past-count 4}))))))
