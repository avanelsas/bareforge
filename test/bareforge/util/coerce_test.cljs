(ns bareforge.util.coerce-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.util.coerce :as c]))

;; --- nil-if-empty ---------------------------------------------------------

(deftest nil-if-empty-passes-non-empty-string
  (is (= "ghost" (c/nil-if-empty "ghost"))))

(deftest nil-if-empty-rejects-empty-string
  (is (nil? (c/nil-if-empty ""))))

(deftest nil-if-empty-rejects-nil
  (is (nil? (c/nil-if-empty nil))))

(deftest nil-if-empty-rejects-non-string
  (testing "non-string values are nil — the helper guards the
            common DOM-input case where a missing value arrives as
            nil and a present value arrives as a string"
    (is (nil? (c/nil-if-empty 42)))
    (is (nil? (c/nil-if-empty :keyword)))
    (is (nil? (c/nil-if-empty true)))))

(deftest nil-if-empty-preserves-whitespace-only-strings
  (testing "no implicit trim — `\" \"` is non-empty and survives.
            Trimming is a separate decision and shouldn't be
            silently bundled into the empty-check"
    (is (= " " (c/nil-if-empty " ")))))

;; --- keyword-or-nil ------------------------------------------------------

(deftest keyword-or-nil-projects-non-empty
  (is (= :flow (c/keyword-or-nil "flow"))))

(deftest keyword-or-nil-namespaced
  (is (= :app/foo (c/keyword-or-nil "app/foo"))))

(deftest keyword-or-nil-empty-and-nil
  (is (nil? (c/keyword-or-nil "")))
  (is (nil? (c/keyword-or-nil nil))))

;; --- parse-number --------------------------------------------------------

(deftest parse-number-integer-string
  (is (= 42 (c/parse-number "42"))))

(deftest parse-number-decimal-string
  (is (= 3.14 (c/parse-number "3.14"))))

(deftest parse-number-negative-string
  (is (= -7 (c/parse-number "-7"))))

(deftest parse-number-passes-actual-numbers-through
  (is (= 42  (c/parse-number 42)))
  (is (= 0.5 (c/parse-number 0.5))))

(deftest parse-number-nil-on-blank
  (is (nil? (c/parse-number nil)))
  (is (nil? (c/parse-number ""))))

(deftest parse-number-nil-on-non-numeric
  (testing "leading non-numeric input → nil (parseFloat returns NaN)"
    (is (nil? (c/parse-number "abc")))
    (is (nil? (c/parse-number "px100")))))

(deftest parse-number-strips-trailing-suffix
  (testing "parseFloat eats a leading number and ignores the rest —
            documented quirk callers should be aware of when a
            trailing unit matters; use `parse-length-value` for the
            preserve-unit case instead"
    (is (= 50 (c/parse-number "50px")))
    (is (= 1.5 (c/parse-number "1.5em")))))

;; --- parse-number-or-zero -----------------------------------------------

(deftest parse-number-or-zero-defaults
  (is (= 0 (c/parse-number-or-zero nil)))
  (is (= 0 (c/parse-number-or-zero "")))
  (is (= 0 (c/parse-number-or-zero "abc"))))

(deftest parse-number-or-zero-passes-through-good-input
  (is (= 42  (c/parse-number-or-zero "42")))
  (is (= 3.14 (c/parse-number-or-zero "3.14"))))

;; --- parse-length-value -------------------------------------------------

(deftest parse-length-empty-and-nil
  (is (nil? (c/parse-length-value nil)))
  (is (nil? (c/parse-length-value ""))))

(deftest parse-length-pure-number-string-becomes-number
  (is (= 50 (c/parse-length-value "50")))
  (is (= 0  (c/parse-length-value "0"))))

(deftest parse-length-with-unit-passes-through-as-string
  (testing "exact-representation guard: `\"50%\"` is not equal to
            `(str 50)` so the original string survives. The
            reconciler treats strings as raw CSS, numbers as px"
    (is (= "50%"   (c/parse-length-value "50%")))
    (is (= "10rem" (c/parse-length-value "10rem")))
    (is (= "auto"  (c/parse-length-value "auto")))))

(deftest parse-length-decimal-pure-number
  (is (= 1.5 (c/parse-length-value "1.5"))))

;; --- format-decimal -----------------------------------------------------

(deftest format-decimal-nil
  (is (nil? (c/format-decimal nil))))

(deftest format-decimal-rounds-long-float
  (testing "float-math artifacts like the drag-math example get
            trimmed to two decimals by default"
    (is (= "939.87" (c/format-decimal 939.8698120117188)))
    (is (= "257.29" (c/format-decimal 257.29168701171875)))))

(deftest format-decimal-drops-trailing-zeros
  (testing "crisp integers stay crisp; .toFixed(2) artefacts go away"
    (is (= "50"   (c/format-decimal 50)))
    (is (= "0"    (c/format-decimal 0)))
    (is (= "-3"   (c/format-decimal -3)))
    (is (= "1.5"  (c/format-decimal 1.5)))
    (is (= "0.25" (c/format-decimal 0.25)))))

(deftest format-decimal-respects-custom-precision
  (testing "callers that need a different precision can ask"
    (is (= "3.142" (c/format-decimal 3.14159265 3)))
    (is (= "3"     (c/format-decimal 3.14159265 0)))))

(deftest format-decimal-numeric-strings-round
  (testing "purely numeric strings get the same treatment so attrs
            stored as `(str <float>)` still display cleanly"
    (is (= "50"     (c/format-decimal "50")))
    (is (= "50"     (c/format-decimal "50.00")))
    (is (= "1.23"   (c/format-decimal "1.23")))
    (is (= "1.23"   (c/format-decimal "1.230")))
    (is (= "939.87" (c/format-decimal "939.8698120117188")))))

(deftest format-decimal-non-numeric-strings-pass-through
  (testing "CSS lengths and compound forms must survive unchanged
            so authoring intent isn't silently rewritten"
    (is (= "50%"       (c/format-decimal "50%")))
    (is (= "10rem"     (c/format-decimal "10rem")))
    (is (= "auto"      (c/format-decimal "auto")))
    (is (= "10px 20px" (c/format-decimal "10px 20px")))
    (is (= ""          (c/format-decimal "")))))
