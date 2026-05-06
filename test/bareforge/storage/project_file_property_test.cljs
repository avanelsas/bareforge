(ns bareforge.storage.project-file-property-test
  "Properties for the `serialize` / `deserialize` round-trip used by
   the autosave path and the explicit Save / Open flow. A drift here
   would mean saving a project and reopening it returns something
   subtly different from what was saved — exactly the bug the
   load-boundary `s/explain-data` check exists to prevent, but only
   for shapes the spec covers."
  (:require [bareforge.doc.gen :as dgen]
            [bareforge.storage.indexeddb :as idb]
            [cljs.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop :include-macros true]))

(def ^:private trials 100)

(defn- check [property]
  (tc/quick-check trials property))

(def ^:private theme-gen
  ;; The theme slice is opaque to storage — it's stored verbatim and
  ;; restored verbatim. A small handful of shapes is enough to catch
  ;; serialisation surprises (nested maps, keyword keys, mixed
  ;; values).
  (gen/one-of
   [(gen/return nil)
    (gen/return {})
    (gen/return {:preset :default})
    (gen/return {:preset :neon :overrides {:--x-color-primary "#0ff"}})]))

(deftest serialize-deserialize-round-trips-document
  (let [result (check
                (prop/for-all [doc   dgen/document-gen
                               theme theme-gen]
                  (let [packed     (idb/serialize {:document doc :theme theme})
                        round-trip (idb/deserialize (pr-str packed))]
                    (= doc (:document round-trip)))))]
    (is (:result result)
        (str "Counterexample: "
             (pr-str (-> result :shrunk :smallest))))))

(deftest serialize-deserialize-round-trips-theme
  (let [result (check
                (prop/for-all [doc   dgen/document-gen
                               theme theme-gen]
                  (let [packed     (idb/serialize {:document doc :theme theme})
                        round-trip (idb/deserialize (pr-str packed))]
                    (= theme (:theme round-trip)))))]
    (is (:result result)
        (str "Counterexample: "
             (pr-str (-> result :shrunk :smallest))))))

(deftest serialize-stamps-format-and-version
  ;; Every serialized payload identifies itself so a future format
  ;; change can branch on `:version` without re-walking the doc.
  (let [result (check
                (prop/for-all [doc   dgen/document-gen
                               theme theme-gen]
                  (let [packed (idb/serialize {:document doc :theme theme})]
                    (and (= "bareforge-project" (:format packed))
                         (= 1 (:version packed))))))]
    (is (:result result))))
