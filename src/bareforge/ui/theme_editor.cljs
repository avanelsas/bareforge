(ns bareforge.ui.theme-editor
  "Floating panel for live theme editing. Changes the active BareDOM
   preset on the page's `<x-theme>` element, and applies a set of
   custom-property overrides as inline styles on that same element.
   Theme changes are ambient — they do not enter the document history."
  (:require [bareforge.state :as state]
            [bareforge.util.dom :as u]))

;; --- theme data -----------------------------------------------------------

(def presets
  "The eight built-in BareDOM theme presets, in display order."
  ["default" "ocean" "forest" "sunset"
   "neo-brutalist" "aurora" "mono-ai" "warm-mineral"])

(def ^:private color-tokens
  "Tokens exposed in the theme editor. Each entry is
   [css-var  human-label]."
  [["--x-color-primary" "Primary"]
   ["--x-color-bg"      "Background"]
   ["--x-color-surface" "Surface"]
   ["--x-color-text"    "Text"]
   ["--x-color-border"  "Border"]])

(def ^:private radius-tokens
  "[css-var label default-px]. Each radius step has its own slider
   so components that pick different sizes (sm chips vs md cards
   vs lg navbars) can all be reached from the theme panel."
  [["--x-radius-sm" "Radius (sm)" 4]
   ["--x-radius-md" "Radius (md)" 8]
   ["--x-radius-lg" "Radius (lg)" 16]])

;; --- theme application (effectful) ---------------------------------------

(defn- x-theme-el
  "The *canvas* x-theme wrapper — the one that lives inside the
   canvas host, not the outer chrome theme. Bareforge's editor chrome
   uses its own, separate x-theme; the theme editor must not touch it."
  ^js []
  (js/document.getElementById "bareforge-canvas-theme"))

(defn apply-theme!
  "Push the current theme slice of `app-state` onto the page's x-theme
   element. Sets the `preset` attribute and writes each override entry
   as an inline CSS custom property on the element."
  [app-state]
  (when-let [^js el (x-theme-el)]
    (let [{:keys [base-preset overrides]} (:theme app-state)]
      (when base-preset
        (.setAttribute el "preset" base-preset))
      ;; Inline styles are additive — clear any tokens we own first,
      ;; then write the current override set.
      (doseq [[token] color-tokens]
        (.removeProperty (.-style el) token))
      (doseq [[token] radius-tokens]
        (.removeProperty (.-style el) token))
      (doseq [[token value] overrides]
        (when (and token value)
          (.setProperty (.-style el) token value))))))

(defn install-watch!
  "Watch `state/app-state` for theme changes and apply them to the
   x-theme element. Initial state is applied immediately."
  []
  (apply-theme! @state/app-state)
  (add-watch state/app-state ::theme
             (fn [_ _ old-state new-state]
               (when (not= (:theme old-state) (:theme new-state))
                 (apply-theme! new-state)))))

;; --- editor panel ---------------------------------------------------------

(defn- current-token-value
  "Read the computed value of a CSS custom property from the x-theme
   element. Used to seed color pickers with the preset's base colors."
  [token]
  (when-let [^js el (x-theme-el)]
    (let [v (-> (js/window.getComputedStyle el)
                (.getPropertyValue token))]
      (when (and v (not= "" v))
        (.trim v)))))

(defn- preset-select [current]
  (let [sel (u/el :x-select {:class "theme-preset-select"})]
    (doseq [p presets]
      (let [o (u/el :option {:value p})]
        (u/set-text! o p)
        (when (= p current) (.setAttribute o "selected" ""))
        (.appendChild sel o)))
    (u/on! sel :select-change
           (fn [^js e]
             (let [v (some-> e .-detail .-value)]
               (when v (state/set-theme-preset! v)))))
    sel))

(defn- color-row [token label]
  (let [picker (u/el :x-color-picker
                     ;; `alpha ""` enables the alpha strip and makes
                     ;; the picker emit 8-digit hex (#rrggbbaa) when
                     ;; opacity < 1, so theme overrides can round-trip
                     ;; through `:theme :overrides` as inline CSS.
                     {:class "theme-color-picker"
                      :alpha ""})
        row    (u/el :div {:class "theme-row"}
                     [(u/set-text! (u/el :div {:class "theme-row-label"}) label)
                      picker])]
    (when-let [v (or (get-in @state/app-state [:theme :overrides token])
                     (u/css-color->hex (current-token-value token)))]
      (.setAttribute picker "value" v))
    (u/on! picker :x-color-picker-input
           (fn [^js e]
             (let [v (some-> e .-detail .-value)]
               (when v (state/set-theme-override! token v)))))
    row))

(defn- parse-num [s]
  (when s
    (let [n (js/parseFloat s)]
      (when-not (js/isNaN n) n))))

(defn- radius-row [token label default-px]
  (let [slider (u/el :x-slider
                     {:class "theme-row-widget"
                      :min "0" :max "32" :step "1" :show-value ""})
        row    (u/el :div {:class "theme-row"}
                     [(u/set-text! (u/el :div {:class "theme-row-label"}) label)
                      slider])
        cur    (or (parse-num (get-in @state/app-state [:theme :overrides token]))
                   (parse-num (current-token-value token))
                   default-px)]
    (.setAttribute slider "value" (str cur))
    (u/on! slider :x-slider-input
           (fn [^js e]
             (let [v (some-> e .-detail .-value)]
               (when v (state/set-theme-override! token (str v "px"))))))
    row))

(defn- reset-button []
  (let [btn (u/el :x-button
                  {:variant "tertiary" :size "sm" :label "Reset overrides"}
                  [(u/set-text! (u/el :span) "Reset overrides")])]
    (u/on! btn :click
           (fn [_] (state/clear-theme-overrides!)))
    btn))

(defn- toggle-visibility! [^js panel-el]
  (if (.hasAttribute panel-el "data-hidden")
    (.removeAttribute panel-el "data-hidden")
    (.setAttribute panel-el "data-hidden" "")))

(defn create
  "Build the theme editor panel (hidden by default). Returns the DOM
   element. Visibility is toggled externally via `toggle!` so the
   toolbar can open/close it."
  []
  (let [preset-sel (preset-select (get-in @state/app-state [:theme :base-preset]))
        color-rows  (map (fn [[t l]]   (color-row  t l))   color-tokens)
        radius-rows (map (fn [[t l d]] (radius-row t l d)) radius-tokens)
        reset-btn   (reset-button)
        body        (u/el :div {:class "theme-body"}
                          (concat [(u/set-text! (u/el :div {:class "theme-row-label"}) "Preset")
                                   preset-sel]
                                  color-rows
                                  radius-rows
                                  [reset-btn]))
        panel      (u/el :div
                         {:class "theme-panel" :data-hidden ""}
                         [(u/set-text! (u/el :div {:class "theme-panel-title"}) "Theme")
                          body])]
    panel))

(defn toggle!
  "Show/hide the theme editor panel."
  [^js panel-el]
  (toggle-visibility! panel-el))
