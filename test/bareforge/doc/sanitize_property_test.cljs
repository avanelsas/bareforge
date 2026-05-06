(ns bareforge.doc.sanitize-property-test
  "Properties that should hold for `bareforge.doc.sanitize/sanitize-doc`
   over any valid document, generated rather than hand-written. The
   sanitizer is defence-in-depth on commit; if it's not idempotent or
   stable under generation, repeated commits could thrash a doc.

   Property count is intentionally modest (100 trials per check) — the
   test runs in CI on every PR. Crank up locally when investigating a
   specific property."
  (:require [bareforge.doc.gen :as dgen]
            [bareforge.doc.sanitize :as sn]
            [cljs.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.properties :as prop :include-macros true]))

(def ^:private trials 100)

(defn- check [property]
  (tc/quick-check trials property))

(deftest sanitize-doc-is-idempotent
  ;; (sanitize ∘ sanitize) = sanitize. A second pass over already-clean
  ;; output must be a no-op; otherwise repeated commits would mutate
  ;; the document forever.
  (let [result (check
                (prop/for-all [doc dgen/document-gen]
                  (= (sn/sanitize-doc (sn/sanitize-doc doc))
                     (sn/sanitize-doc doc))))]
    (is (:result result)
        (str "Counterexample: "
             (pr-str (-> result :shrunk :smallest))))))

(deftest sanitize-doc-output-is-clean
  ;; After one pass, `unsafe-findings` returns an empty vector — i.e.
  ;; the strict load-boundary scanner agrees that the soft sanitizer's
  ;; output is shippable. If this fails, the soft sanitizer is leaving
  ;; payloads the strict scanner would refuse on load.
  (let [result (check
                (prop/for-all [doc dgen/document-gen]
                  (empty? (sn/unsafe-findings (sn/sanitize-doc doc)))))]
    (is (:result result)
        (str "Counterexample: "
             (pr-str (-> result :shrunk :smallest))))))

(deftest sanitize-doc-preserves-structure
  ;; Sanitisation strips unsafe content but never the structural keys
  ;; (`:root`, `:canvas`, `:next-id`). A doc is valid input to the
  ;; reconciler before and after.
  (let [result (check
                (prop/for-all [doc dgen/document-gen]
                  (let [out (sn/sanitize-doc doc)]
                    (and (= (set (keys doc)) (set (keys out)))
                         (= (:canvas doc) (:canvas out))
                         (= (:next-id doc) (:next-id out))))))]
    (is (:result result)
        (str "Counterexample: "
             (pr-str (-> result :shrunk :smallest))))))
