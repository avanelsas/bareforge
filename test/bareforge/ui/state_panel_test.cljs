(ns bareforge.ui.state-panel-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bareforge.ui.state-panel :as sp]))

;; --- value-display ------------------------------------------------------

(deftest value-display-scalar-stays-inline
  (testing "scalars render inline via pr-str — no truncation, no
            multi-line block. The renderer drops :text into the row's
            value column."
    (is (= {:kind :inline :text "0"}
           (sp/value-display {:stored? true :value 0})))
    (is (= {:kind :inline :text "\"hello\""}
           (sp/value-display {:stored? true :value "hello"})))
    (is (= {:kind :inline :text "nil"}
           (sp/value-display {:stored? true :value nil})))
    (is (= {:kind :inline :text "true"}
           (sp/value-display {:stored? true :value true})))))

(deftest value-display-empty-collection-stays-inline
  (testing "empty vector / map / set render inline — no detail block
            for `[]` or `{}` since the one-liner is exhaustive"
    (is (= {:kind :inline :text "[]"}
           (sp/value-display {:stored? true :value []})))
    (is (= {:kind :inline :text "{}"}
           (sp/value-display {:stored? true :value {}})))))

(deftest value-display-non-empty-vector-expands
  (testing "a non-empty vector reports a count summary + a pretty-
            printed detail block; ellipsis is gone, the user sees
            every element on its own line"
    (let [out (sp/value-display {:stored? true :value [1 2 3]})]
      (is (= :expanded (:kind out)))
      (is (= "[3 items]" (:summary out)))
      (is (str/includes? (:detail out) "1"))
      (is (str/includes? (:detail out) "3"))))
  (testing "singular count uses `item` (not `items`)"
    (is (= "[1 item]"
           (:summary (sp/value-display {:stored? true :value [{:id 1}]}))))))

(deftest value-display-non-empty-map-expands
  (testing "maps surface a `{N keys}` summary"
    (let [out (sp/value-display {:stored? true :value {:a 1 :b 2}})]
      (is (= :expanded (:kind out)))
      (is (= "{2 keys}" (:summary out)))
      (is (str/includes? (:detail out) ":a")))))

(deftest value-display-runtime-only-flagged
  (testing "computed ops the evaluator can't resolve return the
            :runtime kind — the renderer shows `(runtime)` rather
            than a misleading value"
    (is (= {:kind :runtime}
           (sp/value-display {:computed? true :stored? false
                              :runtime-only true})))))

(deftest value-display-vector-of-records-detail-format
  (testing "the canonical vector-of-records case (cart-items) renders
            one record per line — that's the whole point of dropping
            ellipsis: the user sees all the seed records pretty-printed"
    (let [v   [{:id 1 :title "Widget" :price 9.99}
               {:id 2 :title "Gadget" :price 8.75}]
          out (sp/value-display {:stored? true :value v})]
      (is (= :expanded (:kind out)))
      (is (str/includes? (:detail out) "Widget"))
      (is (str/includes? (:detail out) "Gadget"))
      (is (>= (count (str/split-lines (:detail out))) 2)
          "at least two lines — pretty-print breaks records apart"))))

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
