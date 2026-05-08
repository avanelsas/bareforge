(ns bareforge.storage.project-file-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.doc.model :as m]
            [bareforge.storage.project-file :as pf]))

(defn- well-formed-project [doc]
  {:format "bareforge-project" :version 1 :document doc})

(deftest project-basename-strips-json-extension
  (testing "raw name forms"
    (is (= "demo-store"              (pf/project-basename "demo-store.json")))
    (is (= "untitled"                (pf/project-basename "untitled.json")))
    (is (= "untitled"                (pf/project-basename "")))
    (is (= "untitled"                (pf/project-basename nil)))
    (is (= "name-without-extension"  (pf/project-basename "name-without-extension")))
    (is (= "UPPER"                   (pf/project-basename "UPPER.JSON"))
        "extension strip is case-insensitive")
    (is (= "looks.like.json.thing"   (pf/project-basename "looks.like.json.thing"))
        "only the trailing .json is stripped")))

(deftest project-basename-reads-from-state
  (is (= "demo-store"
         (pf/project-basename {:project-file {:name "demo-store.json"}})))
  (is (= "untitled"
         (pf/project-basename {:project-file {:name "untitled.json"}})))
  (is (= "untitled"
         (pf/project-basename {}))
      "no :project-file slot → untitled fallback"))

;; --- validate-project: spec + xss scanner --------------------------------

(deftest validate-project-passes-clean-document
  (is (nil? (pf/validate-project (well-formed-project (m/empty-document))))))

(deftest validate-project-rejects-icon-with-script
  (let [doc  (assoc-in (m/empty-document)
                       [:root :slots "default"]
                       [{:id "i" :tag "x-icon" :attrs {} :props {} :slots {}
                         :inner-html "<svg><script>alert(1)</script></svg>"
                         :layout {:placement :flow}}])
        out  (pf/validate-project (well-formed-project doc))]
    (is (= :unsafe (:kind out))
        "an inner-html script payload is refused with :kind :unsafe — distinct from spec failures so the UI can surface a security-shaped message")))

(deftest validate-project-rejects-javascript-href
  (let [doc  (assoc-in (m/empty-document)
                       [:root :slots "default"]
                       [{:id "l" :tag "x-link"
                         :attrs {"href" "javascript:alert(1)"}
                         :props {} :slots {}
                         :layout {:placement :flow}}])
        out  (pf/validate-project (well-formed-project doc))]
    (is (= :unsafe (:kind out)))))

(deftest validate-project-spec-failure-distinguished-from-unsafe
  (let [out (pf/validate-project {:format "bareforge-project"})]
    (is (= :spec (:kind out))
        ":spec :unsafe distinction lets the UI explain *why* a file was refused")))

;; --- classify-payload: load-decision dispatch ---------------------------

(deftest classify-payload-ok-on-clean-document
  (is (= {:status :ok}
         (pf/classify-payload (well-formed-project (m/empty-document))))))

(deftest classify-payload-unparseable-on-nil
  (testing "deserialize failure (nil) classifies distinctly from spec
            failure — the user-visible alert wording differs"
    (is (= {:status :unparseable} (pf/classify-payload nil)))))

(deftest classify-payload-invalid-on-spec-failure
  (let [out (pf/classify-payload {:format "bareforge-project"})]
    (is (= :invalid (:status out)))
    (is (some? (:explain out))
        "the spec explanation is forwarded so console.error has detail")))

(deftest classify-payload-unsafe-on-script-payload
  (let [doc (assoc-in (m/empty-document)
                      [:root :slots "default"]
                      [{:id "i" :tag "x-icon" :attrs {} :props {} :slots {}
                        :inner-html "<svg><script>alert(1)</script></svg>"
                        :layout {:placement :flow}}])
        out (pf/classify-payload (well-formed-project doc))]
    (is (= :unsafe (:status out)))
    (is (seq (:findings out))
        "findings vector is forwarded so console.error names the offending paths")))
