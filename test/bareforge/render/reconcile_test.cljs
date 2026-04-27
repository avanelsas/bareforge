(ns bareforge.render.reconcile-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.render.reconcile :as r]))

(deftest attr-diff-empty
  (is (= {:set {} :unset #{}} (r/attr-diff {} {}))))

(deftest attr-diff-adds
  (is (= {:set {"variant" "primary"} :unset #{}}
         (r/attr-diff {} {"variant" "primary"}))))

(deftest attr-diff-removes
  (is (= {:set {} :unset #{"variant"}}
         (r/attr-diff {"variant" "primary"} {}))))

(deftest attr-diff-changes
  (is (= {:set {"variant" "secondary"} :unset #{}}
         (r/attr-diff {"variant" "primary"} {"variant" "secondary"}))))

(deftest attr-diff-mixed
  (is (= {:set {"size" "lg" "variant" "ghost"} :unset #{"disabled"}}
         (r/attr-diff {"variant" "primary" "disabled" ""}
                      {"variant" "ghost" "size" "lg"}))))

(deftest attr-diff-skips-equal
  (is (empty? (:set (r/attr-diff {"variant" "primary"}
                                  {"variant" "primary"})))))

(deftest prop-diff-mixed
  (is (= {:set {:disabled true :loading true}
          :unset #{:pressed}}
         (r/prop-diff {:disabled false :pressed true}
                      {:disabled true  :loading true}))))

(deftest prop-diff-noop-when-equal
  (let [m {:disabled true :loading false}]
    (is (= {:set {} :unset #{}} (r/prop-diff m m)))))

(deftest prop-diff-nil-values-are-kept-in-set
  (testing "explicitly setting a prop to nil appears in :set, not :unset"
    (is (= {:set {:disabled nil} :unset #{}}
           (r/prop-diff {:disabled true} {:disabled nil})))))

;; --- layout->css ---------------------------------------------------------

(deftest layout-css-empty-flow
  (is (nil? (r/layout->css {:placement :flow}))))

(deftest layout-css-width-only
  (is (= "width:200px;"
         (r/layout->css {:placement :flow :width "200px"}))))

(deftest layout-css-padding-and-margin
  (is (= "padding:1rem;margin:0 auto;"
         (r/layout->css {:placement :flow :padding "1rem" :margin "0 auto"}))))

(deftest layout-css-background-placement
  (is (= "position:absolute;inset:0;"
         (r/layout->css {:placement :background}))))

(deftest layout-css-background-plus-dimensions
  (is (= "position:absolute;inset:0;width:300px;"
         (r/layout->css {:placement :background :width "300px"}))))

(deftest layout-css-empty-string-fields-skipped
  (is (nil? (r/layout->css {:placement :flow :width "" :height "" :padding nil :margin nil}))))

(deftest layout-css-extra-style-alone
  (is (= "color:red;font-weight:800;"
         (r/layout->css {:placement :flow :extra-style "color:red;font-weight:800"}))))

(deftest layout-css-extra-style-trims-trailing-semicolon
  (is (= "color:red;"
         (r/layout->css {:placement :flow :extra-style "  color:red;  "}))))

(deftest layout-css-extra-style-after-named-fields
  (is (= "width:200px;color:red;"
         (r/layout->css {:placement   :flow
                          :width       "200px"
                          :extra-style "color:red"}))))

(deftest layout-css-extra-style-empty-skipped
  (is (nil? (r/layout->css {:placement :flow :extra-style ""})))
  (is (nil? (r/layout->css {:placement :flow :extra-style "   "})))
  (is (nil? (r/layout->css {:placement :flow :extra-style nil}))))

(deftest layout-css-extra-style-with-background
  (is (= "position:absolute;inset:0;opacity:0.4;"
         (r/layout->css {:placement :background :extra-style "opacity:0.4"}))))

;; --- free placement ------------------------------------------------------

(deftest layout-css-free-empty
  (is (= "position:absolute;z-index:2;"
         (r/layout->css {:placement :free}))))

(deftest layout-css-free-numeric-coords
  (is (= "position:absolute;z-index:2;left:120px;top:80px;"
         (r/layout->css {:placement :free :x 120 :y 80}))))

(deftest layout-css-free-with-size
  (is (= "position:absolute;z-index:2;left:0px;top:0px;width:200px;height:150px;"
         (r/layout->css {:placement :free :x 0 :y 0 :w 200 :h 150}))))

(deftest layout-css-free-string-lengths-pass-through
  (is (= "position:absolute;z-index:2;left:50%;top:10rem;width:25vw;"
         (r/layout->css {:placement :free :x "50%" :y "10rem" :w "25vw"}))))

(deftest layout-css-free-ignores-generic-width-height
  (testing ":free owns the size axis via :w/:h, so :width/:height are skipped"
    (is (= "position:absolute;z-index:2;width:300px;"
           (r/layout->css {:placement :free :w 300 :width "999px" :height "888px"})))))

(deftest layout-css-free-still-emits-padding-and-margin
  (is (= "position:absolute;z-index:2;left:10px;padding:8px;margin:4px;"
         (r/layout->css {:placement :free :x 10 :padding "8px" :margin "4px"}))))

(deftest layout-css-free-with-extra-style
  (is (= "position:absolute;z-index:2;left:5px;opacity:0.5;"
         (r/layout->css {:placement :free :x 5 :extra-style "opacity:0.5"}))))

;; --- css-vars ------------------------------------------------------------

(deftest layout-css-css-vars-alone
  (is (= "--x-button-bg:#ff00aa;--x-button-fg:white;"
         (r/layout->css {:placement :flow
                          :css-vars {"--x-button-fg" "white"
                                     "--x-button-bg" "#ff00aa"}}))))

(deftest layout-css-css-vars-skip-empty-values
  (is (= "--x-button-fg:white;"
         (r/layout->css {:placement :flow
                          :css-vars {"--x-button-fg" "white"
                                     "--x-button-bg" ""
                                     "--x-button-padding" nil}}))))

(deftest layout-css-css-vars-after-named-fields-before-extra
  (is (= "width:200px;--x-button-fg:white;color:red;"
         (r/layout->css {:placement   :flow
                         :width       "200px"
                         :css-vars    {"--x-button-fg" "white"}
                         :extra-style "color:red"}))))

(deftest layout-css-empty-css-vars-skipped
  (is (nil? (r/layout->css {:placement :flow :css-vars {}})))
  (is (nil? (r/layout->css {:placement :flow :css-vars nil}))))
