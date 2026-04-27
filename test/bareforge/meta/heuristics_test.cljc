(ns bareforge.meta.heuristics-test
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer [deftest is testing]])
            [bareforge.meta.heuristics :as h]))

;; --- infer-kind ---------------------------------------------------------

(deftest infer-kind-url-attrs
  (is (= :url (h/infer-kind "src")))
  (is (= :url (h/infer-kind "href"))))

(deftest infer-kind-boolean-attrs
  (testing "classic HTML-style boolean attrs"
    (is (= :boolean (h/infer-kind "disabled")))
    (is (= :boolean (h/infer-kind "readonly")))
    (is (= :boolean (h/infer-kind "required")))
    (is (= :boolean (h/infer-kind "checked"))))
  (testing "BareDOM state-style boolean attrs"
    (is (= :boolean (h/infer-kind "open")))
    (is (= :boolean (h/infer-kind "pressed")))
    (is (= :boolean (h/infer-kind "loading")))
    (is (= :boolean (h/infer-kind "sticky")))))

(deftest infer-kind-number-attrs
  (is (= :number (h/infer-kind "min")))
  (is (= :number (h/infer-kind "max")))
  (is (= :number (h/infer-kind "step"))))

(deftest infer-kind-millisecond-suffix
  (testing "any attribute ending in -ms is numeric"
    (is (= :number (h/infer-kind "timeout-ms")))
    (is (= :number (h/infer-kind "delay-ms")))
    (is (= :number (h/infer-kind "interval-ms")))))

(deftest infer-kind-unknown-defaults-to-string-short
  (testing "unknown attrs default to :string-short, not :unknown"
    (is (= :string-short (h/infer-kind "label")))
    (is (= :string-short (h/infer-kind "placeholder")))
    (is (= :string-short (h/infer-kind "variant")))
    (is (= :string-short (h/infer-kind "totally-made-up")))))

;; --- humanize-tag -------------------------------------------------------

(deftest humanize-drops-x-prefix
  (is (= "Button" (h/humanize-tag "x-button"))))

(deftest humanize-replaces-hyphens
  (is (= "Bento grid"  (h/humanize-tag "x-bento-grid")))
  (is (= "Cancel dialogue" (h/humanize-tag "x-cancel-dialogue"))))

(deftest humanize-leaves-non-x-tags-alone
  (is (= "Button" (h/humanize-tag "button"))
      "a tag without x- prefix is still capitalized"))

(deftest humanize-handles-empty-input
  (is (= "" (h/humanize-tag ""))))

(deftest humanize-handles-single-segment
  (is (= "Card" (h/humanize-tag "x-card"))))
