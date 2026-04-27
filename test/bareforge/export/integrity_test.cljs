(ns bareforge.export.integrity-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bareforge.export.integrity :as int]))

(def ^:private fake-manifest
  {:version    "2.4.0"
   :algorithm  "sha384"
   :generated-at "2026-04-27T10:00:00Z"
   :files      {"x-button.js" "sha384-AAA"
                "x-card.js"   "sha384-BBB"
                "x-theme.js"  "sha384-CCC"}})

(def ^:private fake-manifest-json
  (str "{\"version\":\"2.4.0\",\"algorithm\":\"sha384\","
       "\"generated-at\":\"2026-04-27T10:00:00Z\","
       "\"files\":{\"x-button.js\":\"sha384-AAA\","
       "\"x-card.js\":\"sha384-BBB\","
       "\"x-theme.js\":\"sha384-CCC\"}}"))

;; --- manifest-url --------------------------------------------------------

(deftest manifest-url-composes
  (is (= "https://cdn.jsdelivr.net/npm/@vanelsas/baredom@2.4.0/dist/integrity.json"
         (int/manifest-url
          "https://cdn.jsdelivr.net/npm/@vanelsas/baredom"
          "2.4.0"))))

;; --- parse-manifest ------------------------------------------------------

(deftest parse-manifest-accepts-well-formed-json
  (let [m (int/parse-manifest fake-manifest-json)]
    (is (= "2.4.0" (:version m)))
    (is (= "sha384" (:algorithm m)))
    (is (= "sha384-AAA" (get-in m [:files "x-button.js"])))))

(deftest parse-manifest-rejects-malformed
  (is (nil? (int/parse-manifest nil)))
  (is (nil? (int/parse-manifest "")))
  (is (nil? (int/parse-manifest "not-json")))
  (is (nil? (int/parse-manifest "[]"))
      "top-level must be an object, not an array")
  (is (nil? (int/parse-manifest "{\"files\":{}}"))
      "missing required version/algorithm keys")
  (is (nil? (int/parse-manifest
             "{\"version\":\"x\",\"algorithm\":\"sha384\",\"files\":\"not-a-map\"}"))
      ":files must be an object"))

(deftest parse-manifest-rejects-malformed-sri-values
  (is (nil? (int/parse-manifest
             "{\"version\":\"x\",\"algorithm\":\"sha384\",\"files\":{\"x.js\":\"plain\"}}"))
      "SRI values without an algorithm prefix are rejected — they'd render an unusable integrity= attribute"))

(deftest parse-manifest-tolerates-extra-keys
  (let [m (int/parse-manifest
           "{\"version\":\"x\",\"algorithm\":\"sha384\",\"files\":{},\"extraField\":42}")]
    (is (some? m)
        "manifest evolution shouldn't force a Bareforge bump — unknown top-level keys pass")))

;; --- integrity-for -------------------------------------------------------

(deftest integrity-for-returns-known-hash
  (is (= "sha384-AAA" (int/integrity-for fake-manifest "x-button.js")))
  (is (= "sha384-CCC" (int/integrity-for fake-manifest "x-theme.js"))))

(deftest integrity-for-missing-tag-returns-nil
  (is (nil? (int/integrity-for fake-manifest "x-not-shipped.js"))))

(deftest integrity-for-tolerates-nil-manifest
  (is (nil? (int/integrity-for nil "x-button.js"))
      "callers can chain without nil-checking the manifest first"))

;; --- modulepreload-block -------------------------------------------------

(deftest modulepreload-block-is-empty-when-no-manifest
  (is (= "" (int/modulepreload-block nil ["x-button"] "https://cdn/")))
  (is (= "" (int/modulepreload-block fake-manifest [] "https://cdn/"))))

(deftest modulepreload-block-emits-link-per-tag
  (let [out (int/modulepreload-block fake-manifest ["x-button" "x-card"]
                                     "https://cdn/baredom/")]
    (is (str/includes? out "rel=\"modulepreload\""))
    (is (str/includes? out "href=\"https://cdn/baredom/x-button.js\""))
    (is (str/includes? out "href=\"https://cdn/baredom/x-card.js\""))
    (is (str/includes? out "integrity=\"sha384-AAA\""))
    (is (str/includes? out "integrity=\"sha384-BBB\""))
    (is (str/includes? out "crossorigin=\"anonymous\"")
        "SRI requires crossorigin on cross-origin resources")))

(deftest modulepreload-block-skips-tags-missing-from-manifest
  (let [out (int/modulepreload-block fake-manifest
                                     ["x-button" "x-not-in-manifest" "x-card"]
                                     "https://cdn/baredom/")]
    (is (str/includes? out "x-button.js"))
    (is (str/includes? out "x-card.js"))
    (is (not (str/includes? out "x-not-in-manifest")))))

(deftest modulepreload-block-deterministic-ordering
  (testing "tags are sorted so a doc with the same tag set always emits the same head block"
    (let [out-1 (int/modulepreload-block fake-manifest ["x-card" "x-button"]
                                         "https://cdn/")
          out-2 (int/modulepreload-block fake-manifest ["x-button" "x-card"]
                                         "https://cdn/")]
      (is (= out-1 out-2)))))
