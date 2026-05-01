(ns bareforge.ui.state-panel-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.ui.state-panel :as sp]))

;; --- value-label ---------------------------------------------------------

(deftest value-label-stored-field-uses-pr-str
  (testing "stored fields surface their seed value via pr-str so
            strings keep quotes, nil renders as nil, etc."
    (is (= "0"          (sp/value-label {:stored? true :value 0})))
    (is (= "\"hello\""  (sp/value-label {:stored? true :value "hello"})))
    (is (= "nil"        (sp/value-label {:stored? true :value nil})))
    (is (= "[1 2 3]"    (sp/value-label {:stored? true :value [1 2 3]})))))

(deftest value-label-computed-field-uses-pr-str
  (testing "evaluated computed fields render the same way as stored
            fields — the row's value column is value-typed, not
            stored/computed-typed"
    (is (= "3"     (sp/value-label {:computed? true :stored? false :value 3})))
    (is (= "true"  (sp/value-label {:computed? true :stored? false :value true})))))

(deftest value-label-runtime-only-shows-hint
  (testing "ops the design-time evaluator can't resolve render as
            (runtime) so the user knows the value is real but
            unavailable until the exported app is running"
    (is (= "(runtime)"
           (sp/value-label {:computed? true :stored? false
                            :runtime-only true})))))

;; --- type-label ----------------------------------------------------------

(deftest type-label-bare-type
  (is (= "number" (sp/type-label {:type :number})))
  (is (= "string" (sp/type-label {:type :string})))
  (is (= "vector" (sp/type-label {:type :vector}))))

(deftest type-label-computed-gets-fn-glyph
  (testing "computed fields trail with ƒ so the user can spot
            derived rows at a glance"
    (is (= "number ƒ" (sp/type-label {:type :number :computed? true})))))

(deftest type-label-locked-gets-lock-glyph
  (testing "locked fields (the auto-::id row on every named group)
            lead with 🔒 — the inspector's own row already does this"
    (is (= "🔒 number" (sp/type-label {:type :number :locked? true})))))

(deftest type-label-unknown-type-degrades-gracefully
  (testing "a missing :type key shouldn't throw — render `unknown`
            so the row is still legible while a save-format drift
            gets noticed"
    (is (= "unknown" (sp/type-label {})))))
