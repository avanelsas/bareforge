(ns bareforge.meta.registry-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.meta.augment :as aug]
            [bareforge.meta.heuristics :as h]
            [bareforge.meta.public-api :as pa]
            [bareforge.meta.registry :as r]
            [bareforge.meta.slots :as sl]))

(def expected-tag-count
  "BareDOM 3.2.0 exposes 104 components hand-curated into Bareforge
   (3.1.0's 99 + x-split-pane, x-code, x-calendar, x-range-slider,
   x-rating). Bumping this number is intentional — it documents
   a version bump in the public-api require list."
  104)

(deftest api-map-has-expected-tag-count
  (is (= expected-tag-count (count pa/api-map))
      "api-map count drift — did BareDOM add or remove a component?"))

(deftest all-tags-matches-api-map
  (is (= (count pa/api-map) (count (r/all-tags)))))

(deftest every-tag-returns-meta
  (doseq [tag (r/all-tags)]
    (is (some? (r/get-meta tag)) (str "missing meta for " tag))))

(deftest unknown-tag-returns-nil
  (is (nil? (r/get-meta "x-does-not-exist"))))

(deftest augmented-tags-have-rich-properties
  (testing "x-button"
    (let [m (r/get-meta "x-button")]
      (is (true? (:augmented? m)))
      (is (= :form (:category m)))
      (is (= "Button" (:label m)))
      (let [variant (first (filter #(= "variant" (:name %)) (:properties m)))]
        (is (= :enum (:kind variant)))
        (is (contains? (set (:choices variant)) "primary")))))

  (testing "x-navbar"
    (let [m (r/get-meta "x-navbar")]
      (is (true? (:augmented? m)))
      (is (= :navigation (:category m)))
      (is (some #(= "sticky" (:name %)) (:properties m)))))

  (testing "x-typography has all 16 variants"
    (let [m      (r/get-meta "x-typography")
          variant (first (filter #(= "variant" (:name %)) (:properties m)))]
      (is (= 16 (count (:choices variant))))
      (is (contains? (set (:choices variant)) "h1"))
      (is (contains? (set (:choices variant)) "blockquote")))))

(def ^:private allowed-fallback-kinds
  "Kinds the runtime fallback is allowed to emit. `:unknown` is
   explicitly not in the set — the fallback now routes every
   observed attribute through `heuristics/infer-kind`."
  #{:boolean :url :number :string-short})

(deftest unaugmented-tags-fall-back-to-typed-attrs
  ;; Pick any currently-unaugmented tag dynamically so adding more
  ;; Phase B coverage doesn't break this test.
  (when-let [tag (first (r/unaugmented-tags))]
    (let [m (r/get-meta tag)]
      (is (false? (:augmented? m))
          (str "expected " tag " to report :augmented? false"))
      ;; Categorized via meta/categories even though unaugmented.
      (is (not= :other (:category m))
          (str "expected " tag " to have a non-:other category"))
      ;; Fallback label is humanized, never the raw tag.
      (is (= (h/humanize-tag tag) (:label m))
          (str "expected " tag " to humanize its label in the fallback"))
      ;; Every fallback kind is one of the heuristic-inferred set,
      ;; never :unknown.
      (is (every? #(contains? allowed-fallback-kinds (:kind %))
                  (:properties m))
          (str "expected " tag " properties to all be heuristic-typed")))))

(deftest fallback-infers-booleans-and-urls
  (testing "wherever an unaugmented tag has a boolean or url attr,
            the fallback picks the right kind"
    (let [props-by-tag (reduce (fn [acc tag]
                                 (assoc acc tag
                                        (group-by :name (:properties (r/get-meta tag)))))
                               {}
                               (r/unaugmented-tags))]
      ;; At least one unaugmented tag should have a boolean attr
      ;; and it should be typed as such. If none of the unaugmented
      ;; tags have any of our known booleans, skip — the test is a
      ;; sanity check, not a coverage requirement.
      (when-let [hits (seq (for [[tag m] props-by-tag
                                 [name entries] m
                                 :when (contains? #{"disabled" "open" "active"} name)]
                             [tag name (-> entries first :kind)]))]
        (doseq [[tag name kind] hits]
          (is (= :boolean kind)
              (str tag "/" name " should infer as :boolean")))))))

(deftest every-tag-has-non-other-category
  (let [bad (filter #(= :other (:category (r/get-meta %)))
                    (r/all-tags))]
    (is (empty? bad)
        (str "tags with no category assignment: "
             (vec bad)))))

(deftest slots-fallback-to-default
  ;; Any tag without an explicit entry in meta/slots reports the
  ;; single catch-all default slot. Pick one that does NOT have a
  ;; custom slots entry and is unlikely to grow one.
  (let [m (r/get-meta "x-spinner")]
    (is (vector? (:slots m)))
    (is (= 1 (count (:slots m))))
    (is (= "default" (:name (first (:slots m)))))))

(deftest navbar-has-multiple-slots
  (let [m (r/get-meta "x-navbar")]
    (is (>= (count (:slots m)) 4))
    (is (some #(= "brand" (:name %)) (:slots m)))))

(deftest placement-hints
  (testing "flow is the default"
    (is (= :flow (:hint (:placement (r/get-meta "x-button"))))))
  (testing "navbar snaps to top"
    (is (= :top-full-width (:hint (:placement (r/get-meta "x-navbar"))))))
  (testing "decoratives are background layers"
    (is (= :background (:hint (:placement (r/get-meta "x-gaussian-blur"))))))
  (testing "organic shape supports multiple placements"
    (let [hint (:placement (r/get-meta "x-organic-shape"))]
      (is (= :background (:hint hint)))
      (is (contains? (:also hint) :flow)))))

;; --- container? / slots/explicitly-registered? ------------------------

(deftest explicitly-registered-mirrors-slots-map
  (testing "tags with hand-written slot entries return true"
    (doseq [tag ["x-container" "x-grid" "x-card" "x-navbar"
                 "x-modal" "x-drawer" "x-popover"
                 "x-button" "x-typography"]]
      (is (true? (sl/explicitly-registered? tag))
          (str tag " should be explicitly registered"))))
  (testing "fallback tags return false"
    (doseq [tag ["x-avatar" "x-badge" "x-chip" "x-stat" "x-alert"]]
      (is (false? (sl/explicitly-registered? tag))
          (str tag " should not be explicitly registered"))))
  (testing "unknown tags return false"
    (is (false? (sl/explicitly-registered? "x-totally-made-up")))))

(deftest container-true-for-registered-multi-slot-tags
  (doseq [tag ["x-container" "x-grid" "x-card" "x-navbar"
               "x-modal" "x-drawer" "x-popover" "x-sidebar"]]
    (is (true? (r/container? tag))
        (str tag " should be a container"))))

(deftest container-false-for-leaves-in-the-fallback-set
  (testing "these tags used to be wrongly classified as containers
            because of the default-slot fallback and displayed an
            overlayed '(empty)' label on top of their content"
    (doseq [tag ["x-avatar" "x-badge" "x-chip" "x-stat" "x-alert"
                 "x-spinner" "x-progress"]]
      (is (false? (r/container? tag))
          (str tag " should not be a container")))))

(deftest container-false-for-registered-single-slot-tags
  (testing "x-button and x-typography are explicitly registered but
            none of their slots accept multiple children — they
            stay leaves even under the explicit-registration check"
    (is (false? (r/container? "x-button")))
    (is (false? (r/container? "x-typography")))))

(deftest container-false-for-unknown-tag
  (is (false? (r/container? "x-totally-made-up"))))

(deftest raw-html-slot-flag-propagates
  (testing "x-icon opts into the raw-HTML slot affordance"
    (is (true? (:raw-html-slot? (r/get-meta "x-icon")))))
  (testing "components without the flag report false (never nil)"
    (is (false? (:raw-html-slot? (r/get-meta "x-button"))))
    (is (false? (:raw-html-slot? (r/get-meta "x-container"))))))

(deftest unaugmented-tags-lists-non-seeded
  (let [unaug (set (r/unaugmented-tags))]
    (is (not (contains? unaug "x-button")))
    (is (not (contains? unaug "x-navbar")))
    (is (not (contains? unaug "x-checkbox")))
    (is (not (contains? unaug "x-modal")))
    ;; x-theme is the only component intentionally left unaugmented —
    ;; it is managed by the top-level theme editor, not by the
    ;; per-node inspector, and inserting one into the canvas is an
    ;; unusual advanced use case.
    (is (contains? unaug "x-theme"))
    ;; Augmented tag count grows over time as Phase B coverage extends.
    ;; Coverage = (count aug/augment); fallback = total − coverage.
    (is (= (- expected-tag-count (count aug/augment)) (count unaug)))))
