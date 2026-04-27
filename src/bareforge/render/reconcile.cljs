(ns bareforge.render.reconcile
  "Node-level DOM diff primitives. Pure helpers (attr-diff, prop-diff) are
   unit-testable; the `apply-*!` functions mutate the DOM and are driven
   from render/canvas."
  (:require [baredom.utils.dom :as bdu]
            [clojure.set :as set]
            [clojure.string :as str]))

;; --- pure diff helpers ----------------------------------------------------

(def ^:private missing ::missing)

(defn- diff-maps
  "Compute {:set {k v} :unset #{k}} between two maps. :set contains
   entries that must be written (added or changed); :unset lists keys
   present in old but absent in new."
  [old-m new-m]
  (let [old-keys (set (keys old-m))
        new-keys (set (keys new-m))]
    {:set (reduce-kv (fn [acc k v]
                       (if (= v (get old-m k missing))
                         acc
                         (assoc acc k v)))
                     {}
                     new-m)
     :unset (set/difference old-keys new-keys)}))

(defn attr-diff
  "Diff two HTML-attribute maps (keys are strings, values are strings or nil)."
  [old-attrs new-attrs]
  (diff-maps old-attrs new-attrs))

(defn prop-diff
  "Diff two JS-property maps (keys are keywords, values are JS values)."
  [old-props new-props]
  (diff-maps old-props new-props))

;; --- imperative DOM primitives --------------------------------------------

(defn create-element
  "Create a DOM element with the given tag name."
  ^js [tag]
  (js/document.createElement tag))

(defn apply-attrs!
  "Apply an attr diff to `el`. Nil values in :set are treated as removals."
  [^js el {:keys [set unset]}]
  (doseq [k unset] (.removeAttribute el k))
  (doseq [[k v] set]
    (if (nil? v)
      (.removeAttribute el k)
      (.setAttribute el k (str v)))))

(defn apply-props!
  "Apply a prop diff to `el`. Keyword keys are converted to strings via `name`
   before passing to `baredom.utils.dom/setv!`. Boolean-looking props also
   reflect through `set-bool-attr!` so BareDOM's observed-attribute path
   picks them up."
  [^js el {:keys [set unset]}]
  (doseq [k unset]
    (bdu/setv! el (name k) nil))
  (doseq [[k v] set]
    (bdu/setv! el (name k) v)
    (when (boolean? v)
      (bdu/set-bool-attr! el (name k) v))))

(defn set-text-child!
  "Manage a single leading text node for a node's :text field.
   No-op when old and new are equal."
  [^js el old-text new-text]
  (when (not= old-text new-text)
    (let [first-child (.-firstChild el)
          is-text?    (and first-child (= 3 (.-nodeType first-child)))
          blank?      (or (nil? new-text) (= "" new-text))]
      (cond
        (and is-text? blank?)
        (.removeChild el first-child)

        is-text?
        (set! (.-nodeValue first-child) new-text)

        (not blank?)
        (.insertBefore el
                       (js/document.createTextNode new-text)
                       (or first-child nil))))))

(defn set-inner-html!
  "Apply a node's `:inner-html` string to `el`. Used by raw-HTML
   components (see `:raw-html-slot?` in meta/augment). Compared
   against the current innerHTML to stay idempotent on repeated
   renders. Nil clears to an empty string."
  [^js el s]
  (let [new-html (or s "")]
    (when (not= (.-innerHTML el) new-html)
      (set! (.-innerHTML el) new-html))))

(defn set-slot-attr!
  "Set the `slot=` attribute on a child element. The default slot uses no
   attribute at all."
  [^js el slot-name]
  (if (or (nil? slot-name) (= slot-name "default"))
    (.removeAttribute el "slot")
    (.setAttribute el "slot" slot-name)))

(defn remove-el!
  "Remove `el` from its parent. No-op if already detached."
  [^js el]
  (when-let [parent (.-parentNode el)]
    (.removeChild parent el)))

;; --- layout-driven inline style -------------------------------------------

(defn- normalize-extra
  "Trim `extra-style`, returning nil for empty input. Strips a
   leading/trailing semicolon so we can append a clean `;` ourselves
   and avoid `;;` doubles."
  [extra]
  (when extra
    (let [trimmed (str/trim extra)
          trimmed (if (str/ends-with? trimmed ";")
                    (subs trimmed 0 (dec (count trimmed)))
                    trimmed)
          trimmed (str/trim trimmed)]
      (when-not (= "" trimmed)
        trimmed))))

(defn- css-var-decls
  "Pure: render the per-instance CSS custom property overrides from
   `:layout :css-vars`, sorted by key for deterministic output."
  [css-vars]
  (when (seq css-vars)
    (for [[k v] (sort-by key css-vars)
          :when (and k v (not= "" v))]
      (str k ":" v))))

(defn- as-length
  "Render a pixel length from either a number (→ 'Npx') or a string
   like '50%' / '10rem' (passed through). Returns nil for nil or
   empty input."
  [v]
  (cond
    (nil? v)                          nil
    (number? v)                       (str v "px")
    (and (string? v) (not= "" v))     v
    :else                             nil))

(defn layout->css
  "Pure: build the inline style string for a node from its `:layout`
   map. Returns nil when the layout produces no visual output (the
   reconciler then drops the `style` attribute entirely). Handles:

   - `:background` placement (absolute-fill block)
   - `:free` placement (absolute + left/top/width/height from x/y/w/h,
     with z-index:2 so it paints above flow siblings)
   - the four generic dimension fields `:width :height :padding :margin`
     (applied unless `:free` owns the width/height axis)
   - per-instance `:css-vars` overrides (CSS custom properties)
   - a free-form `:extra-style` string the user types verbatim

   Order: placement block → named dimensions → CSS variables →
   extra-style. Each later group can override earlier ones.

   Note: `pointer-events` is not set inline for background placement —
   it's handled at the stylesheet level in `public/index.html` so edit
   mode keeps backgrounds clickable while preview mode lets clicks
   fall through to underlying content."
  [layout]
  (let [{:keys [placement width height padding margin extra-style css-vars
                x y w h]}
        layout

        ;; Placement block
        base (case placement
               :background ["position:absolute" "inset:0"]
               :free       (cond-> ["position:absolute" "z-index:2"]
                             (as-length x) (conj (str "left:"   (as-length x)))
                             (as-length y) (conj (str "top:"    (as-length y)))
                             (as-length w) (conj (str "width:"  (as-length w)))
                             (as-length h) (conj (str "height:" (as-length h))))
               [])

        ;; :free owns the width/height axis via :w/:h, so skip the
        ;; generic :width/:height fields when placement is :free to
        ;; avoid double-setting. Padding / margin still apply.
        named (cond-> base
                (and (not= :free placement) width   (not= "" width))
                (conj (str "width:"   width))

                (and (not= :free placement) height  (not= "" height))
                (conj (str "height:"  height))

                (and padding (not= "" padding)) (conj (str "padding:" padding))
                (and margin  (not= "" margin))  (conj (str "margin:"  margin)))
        with-vars (into named (css-var-decls css-vars))
        extra     (normalize-extra extra-style)
        all       (cond-> with-vars extra (conj extra))]
    (when (seq all)
      (str (str/join ";" all) ";"))))

(defn apply-placement-attr!
  "Stamp `data-bareforge-placement` onto `el` so the stacking CSS in
   public/index.html can distinguish background layers and free-
   positioned decoratives from flow siblings. `:background` and
   `:free` each get a marker; everything else clears it."
  [^js el layout]
  (case (:placement layout)
    :background (.setAttribute el "data-bareforge-placement" "background")
    :free       (.setAttribute el "data-bareforge-placement" "free")
    (.removeAttribute el "data-bareforge-placement")))

(defn apply-layout-style!
  "Diff the layout-derived inline style and write it onto `el`. A
   missing/empty result removes the `style` attribute entirely."
  [^js el old-layout new-layout]
  (let [old-css (layout->css old-layout)
        new-css (layout->css new-layout)]
    (when (not= old-css new-css)
      (if (or (nil? new-css) (= "" new-css))
        (.removeAttribute el "style")
        (.setAttribute el "style" new-css)))))

(defn ensure-parent-positioned!
  "Add a `bareforge-positioned` class to a parent so its absolute
   `:background` child is contained. The class drives a CSS rule
   (`position: relative`) so we never touch the parent's inline
   style and never collide with the parent's own layout-driven
   style attribute."
  [^js parent-el]
  (.. parent-el -classList (add "bareforge-positioned")))

(defn revert-parent-positioned!
  "Remove the `bareforge-positioned` class. Called when the last
   `:background` child is removed from a parent."
  [^js parent-el]
  (.. parent-el -classList (remove "bareforge-positioned")))
