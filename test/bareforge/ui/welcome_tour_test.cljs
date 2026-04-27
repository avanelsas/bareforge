(ns bareforge.ui.welcome-tour-test
  "Pure tests on the tour-steps def — pin every step's shape so a
   typo in `:target` (CSS selector) or a missing `:body` text
   surfaces here instead of as a silently-broken tour at first
   run."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bareforge.ui.welcome-tour :as wt]))

(deftest tour-step-count
  (is (= 13 (count wt/tour-steps))
      "13 = welcome + 4 panels + 5 toolbar buttons + 2 inspector affordances + done"))

(deftest every-step-has-title-and-body
  (doseq [{:keys [title body] :as s} wt/tour-steps]
    (is (string? title) (str "step missing :title — " (pr-str s)))
    (is (not (str/blank? title)))
    (is (string? body) (str "step missing :body — " (pr-str s)))
    (is (not (str/blank? body)))))

(deftest every-step-has-target-and-placement
  (testing "BareDOM's x-welcome-tour-step doesn't position the "
    "popover when target is nil — every step needs a "
    "target. Welcome / done anchor on the brand logo"
    (doseq [s wt/tour-steps]
      (is (string? (:target s))
          (str "step missing :target selector — " (pr-str (:title s))))
      (is (string? (:placement s))
          (str "step missing :placement — " (pr-str (:title s)))))))

(deftest welcome-and-done-anchor-on-brand
  (testing "first and last steps target the navbar brand logo so "
    "they have a stable, always-present home base"
    (is (= "[data-tour=\"brand\"]" (:target (first wt/tour-steps))))
    (is (= "[data-tour=\"brand\"]" (:target (last wt/tour-steps))))))

(deftest conceptual-steps-anchor-on-inspector-panel
  (testing "the state and interactivity steps teach the model rather "
    "than point at a button that may not exist yet — both "
    "anchor on the always-present inspector panel"
    (let [step-by-title (into {} (map (juxt :title identity) wt/tour-steps))]
      (is (= "#bareforge-inspector"
             (:target (step-by-title "State: groups and fields"))))
      (is (= "#bareforge-inspector"
             (:target (step-by-title "Interactivity: bindings and events")))))))

(deftest panel-targets-use-stable-ids
  (testing "panel-pointing steps use the stable ids added to "
    "palette/layers/canvas/inspector — must stay aligned with "
    "what the chrome actually renders"
    (let [targets (set (keep :target wt/tour-steps))]
      (is (contains? targets "#bareforge-palette"))
      (is (contains? targets "#bareforge-canvas"))
      (is (contains? targets "#bareforge-layers"))
      (is (contains? targets "#bareforge-inspector")))))

(deftest toolbar-targets-use-data-tour
  (testing "toolbar buttons + brand logo use [data-tour=…] so the "
    "welcome tour can match without claiming new ids"
    (let [data-tour-targets (filter #(re-find #"\[data-tour=" %)
                                    (keep :target wt/tour-steps))
          tokens            (set (map #(second (re-find #"\[data-tour=\"([^\"]+)\"\]" %))
                                      data-tour-targets))]
      (is (contains? tokens "brand"))
      (is (contains? tokens "file-menu"))
      (is (contains? tokens "templates-btn"))
      (is (contains? tokens "undo-btn"))
      (is (contains? tokens "mode-btn"))
      (is (contains? tokens "theme-btn")))))

(deftest every-placement-is-a-known-baredom-value
  (let [allowed #{"top" "bottom" "left" "right"
                  "top-start" "top-end" "bottom-start" "bottom-end"}]
    (doseq [s wt/tour-steps
            :when (:placement s)]
      (is (contains? allowed (:placement s))
          (str "placement " (pr-str (:placement s))
               " on step " (pr-str (:title s))
               " is outside x-welcome-tour-step's allowed set")))))
