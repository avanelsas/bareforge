(ns bareforge.ui.inspector
  "Right-hand inspector panel. Auto-generates editable form fields for
   the currently-selected node by consuming `meta/registry/get-meta`.

   Pure helpers (inspector-model, editor-spec, current-value) are
   unit-tested; the effectful `create` builds the DOM, installs a
   watcher on `:document`/`:selection`, and wires each editor's input
   event back to `doc.ops/*` plus `state/commit!`.

   v1 scope: attributes section (full editing) + slots section
   (read-only summary) + layout section (read-only placement).
   Drag-drop slot targets land in build-order step 9."
  (:require [bareforge.doc.actions :as actions]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.meta.design-tokens :as tokens]
            [bareforge.meta.events :as meta-events]
            [bareforge.meta.registry :as registry]
            [bareforge.render.canvas :as canvas]
            [bareforge.state :as state]
            [bareforge.util.dom :as u]
            [clojure.string :as str]))

;; --- pure ----------------------------------------------------------------

(defn- aria-attr?
  "Attributes that BareDOM maps to aria-* (either an explicit `aria-*`
   prefix or the conventional `label` attribute, which every seeded
   component uses as aria-label)."
  [attr-name]
  (and (string? attr-name)
       (or (= "label" attr-name)
           (str/starts-with? attr-name "aria-"))))

(defn display-label
  "Human-readable field label for a property. Rules:
   1. If the property has `:display-name`, use it verbatim.
   2. If the attribute is aria-related, suffix with ` (aria)`.
   3. Otherwise use the raw attribute name."
  [{:keys [name display-name]}]
  (cond
    display-name         display-name
    (aria-attr? name)    (str name " (aria)")
    :else                name))

(defn editor-spec
  "Decide which BareDOM element renders a given property descriptor.
   Returns `{:kind :widget-tag :options}`. Unknown / unsupported kinds
   fall back to a plain text search field."
  [{:keys [kind choices]}]
  (case kind
    :boolean     {:kind :boolean     :widget-tag "x-switch"}
    :enum        {:kind :enum        :widget-tag "x-select"  :choices choices}
    :string-long {:kind :string-long :widget-tag "x-text-area"}
    :number      {:kind :number      :widget-tag "x-search-field" :type "number"}
    :string-short {:kind :string-short :widget-tag "x-search-field"}
    ;; :unknown / :color / :url / :date / :string-short fallback
    {:kind (or kind :unknown) :widget-tag "x-search-field"}))

(defn- transform-for-commit
  "Apply a named transform to a user-entered value before committing
   it as an HTML attribute. Returns the value unchanged when no
   transform matches."
  [transform v]
  (case transform
    :grid-columns (when (and (string? v) (not= "" v))
                    (if (re-matches #"\d+" v)
                      (str "repeat(" v ", 1fr)")
                      v))
    v))

(defn- transform-for-display
  "Reverse a named transform so the stored attribute value is shown
   in a human-friendly form in the inspector widget."
  [transform v]
  (case transform
    :grid-columns (if-let [[_ n] (and (string? v) (re-matches #"repeat\((\d+),\s*1fr\)" v))]
                    n
                    v)
    v))

(defn current-value
  "Return the current attr or prop value for a given node + property
   descriptor. Booleans read from :props (keywordised name); everything
   else reads from :attrs (string name). Values with a `:transform`
   are reverse-transformed for display."
  [node {:keys [name kind transform]}]
  (let [raw (if (= :boolean kind)
              (get-in node [:props (keyword name)])
              (get-in node [:attrs name]))]
    (if transform
      (transform-for-display transform raw)
      raw)))

(defn inspector-model
  "Project app-state into the view model the inspector needs. Returns
   nil when no meaningful selection exists. The selection id may be a
   template-instance clone's DOM id (`__seed<N>` suffix); canonicalise
   before looking it up in the doc so canvas-tap on a clone reveals
   the underlying template node.

   Single-select returns `{:node :meta}`. Multi-select (>1 distinct
   doc nodes) returns `{:multi true :nodes [...] :tags #{...}}`; the
   renderer uses that to show shared-attribute editors."
  [app-state]
  (let [ids     (state/selected-ids app-state)
        doc-ids (->> ids (map canvas/canonical-node-id) (remove nil?) distinct)]
    (cond
      (empty? doc-ids)
      nil

      (> (count doc-ids) 1)
      (let [nodes (->> doc-ids
                       (keep #(m/get-node (:document app-state) %))
                       vec)]
        (when (seq nodes)
          {:multi true
           :nodes nodes
           :tags  (set (map :tag nodes))}))

      :else
      (let [doc-id (first doc-ids)
            node   (m/get-node (:document app-state) doc-id)]
        (when node
          {:node node
           :meta (registry/get-meta (:tag node))})))))

;; --- effectful: editor widgets -------------------------------------------

(declare build-editor-row)

(defn- commit-with!
  "Apply a `doc.ops/*` fn to the current document (as the first arg)
   with `args` trailing, and commit the result. Every inspector-side
   doc mutation flows through this so the single-write-path
   discipline stays visible in a grep."
  [op-fn & args]
  (state/commit! (apply op-fn (:document @state/app-state) args)))

(defn- commit-attr!
  "Set an attribute when `v` is non-nil, unset it when `v` is nil —
   an empty / cleared input value removes the attribute rather than
   assigning the empty string. `ops/set-attr` and `ops/unset-attr`
   have different arities, so this wrapper picks the right op."
  [node-id attr-name v]
  (if (nil? v)
    (commit-with! ops/unset-attr node-id attr-name)
    (commit-with! ops/set-attr   node-id attr-name v)))

(defn- commit-prop!
  "Set a JS property after keywordising the name, so callers can
   pass a string from a data-attribute without coupling to the
   ops API's keyword key shape."
  [node-id prop-name v]
  (commit-with! ops/set-prop node-id (keyword prop-name) v))

(defn- read-event-value ^js [^js e]
  (or (some-> e .-detail (.-value))
      (some-> e .-target .-value)))

;; --- CSS-var autocomplete (M2.4) ----------------------------------------

(def ^:private color-datalist-id  "bareforge-tokens-color")
(def ^:private length-datalist-id "bareforge-tokens-length")

(defn- ^js build-token-datalist!
  "Build a `<datalist>` element populated with `var(--x-…)` options
   for every token entry. Called once at install time per category."
  [^js doc id entries]
  (let [^js dl (.createElement doc "datalist")]
    (.setAttribute dl "id" id)
    (doseq [{:keys [name]} entries]
      (let [^js o (.createElement doc "option")]
        (.setAttribute o "value" (tokens/var-of name))
        (.appendChild dl o)))
    dl))

(defn install-token-datalists!
  "Mount one shared `<datalist>` per token category at the top of the
   document body. Inspector widgets reference these via `list=` so
   typing `var(` in a colour or length field surfaces native browser
   autocomplete with theme tokens. Idempotent — calling twice replaces
   the existing datalists rather than duplicating them."
  []
  (let [^js doc js/document
        ^js body (.-body doc)]
    (doseq [id [color-datalist-id length-datalist-id]]
      (when-let [^js prev (.getElementById doc id)]
        (.removeChild (.-parentNode prev) prev)))
    (.appendChild body (build-token-datalist! doc color-datalist-id
                                              (tokens/tokens-for :color)))
    (.appendChild body (build-token-datalist! doc length-datalist-id
                                              (tokens/tokens-for :length)))))

(defn- attach-datalist-to-shadow-input!
  "BareDOM's `x-search-field` wraps a real `<input part=\"input\">` in
   its open shadow root. Two things are needed for native datalist
   autocomplete to work in that setup:

   1. `list=` has to be on the actual `<input>`, not the custom-element
      host (BareDOM doesn't observe / forward `list`).
   2. The referenced `<datalist>` has to live in the same tree as the
      input. The HTML spec scopes the `list` lookup to the input's
      containing root, so a body-level datalist is invisible to an
      input inside a shadow root.

   We clone the global datalist (mounted by `install-token-datalists!`)
   into the field's shadow root on the next animation frame, and set
   `list=` on the inner input. Idempotent — the clone only happens
   when the shadow root doesn't already carry a datalist with the
   target id."
  [^js host datalist-id]
  (letfn [(try-attach! []
            (if-let [^js sr (.-shadowRoot host)]
              (when-let [^js inner (.querySelector sr "[part=input]")]
                (when-not (.querySelector sr
                            (str "datalist[id='" datalist-id "']"))
                  (when-let [^js src (js/document.getElementById datalist-id)]
                    (.appendChild sr (.cloneNode src true))))
                (.setAttribute inner "list" datalist-id))
              (js/requestAnimationFrame try-attach!)))]
    (js/requestAnimationFrame try-attach!)))

;; --- numeric drag (M2.1) -----------------------------------------------

(defn- ^js attach-scrub-meta!
  "Stash a scrub spec on a widget element. `field-row` reads it back
   to wire a horizontal-drag scrubber on the row's label. Spec map:
   `{:read-fn :commit-fn! :step}`. Returns the element for thread-
   friendly use in builder pipelines."
  [^js el spec]
  (set! (.-bareforgeScrub el) spec)
  el)

(defn- read-scrub-meta [^js el]
  (when el (.-bareforgeScrub el)))

(defonce ^:private scrub-state
  ;; One global drag-in-flight tracker is enough — only one inspector
  ;; row can be scrubbed at a time, and a label captures the pointer
  ;; so other handlers don't compete.
  #js {:active? false
       :start-x 0
       :start-val 0
       :first? true
       :pointer-id nil
       :label nil
       :input nil
       :commit-fn nil
       :step 1})

(defn- on-scrub-move! [^js e]
  (when (.-active? scrub-state)
    (.preventDefault e)
    (let [dx        (- (.-clientX e) (.-start-x scrub-state))
          step-px   (.-step scrub-state)
          mult      (if (.-shiftKey e) 10 1)
          start-val (.-start-val scrub-state)
          new-val   (+ start-val (* dx step-px mult))
          ;; Snap to an integer when the step is integer-valued; for
          ;; sub-unit steps fall through with the raw float.
          rounded   (if (zero? (mod step-px 1))
                      (js/Math.round new-val)
                      new-val)
          ^js input (.-input scrub-state)
          first?    (.-first? scrub-state)
          commit-fn (.-commit-fn scrub-state)]
      (commit-fn rounded first?)
      (when input (u/set-attr! input :value (str rounded)))
      (when first? (set! (.-first? scrub-state) false)))))

(defn- end-scrub! []
  (let [^js label (.-label scrub-state)
        pid       (.-pointer-id scrub-state)]
    (when (and label pid)
      (try (.releasePointerCapture label pid) (catch :default _ nil)))
    (set! (.-active? scrub-state) false)
    (set! (.-label scrub-state) nil)
    (set! (.-input scrub-state) nil)
    (set! (.-commit-fn scrub-state) nil)
    (set! (.-pointer-id scrub-state) nil)
    (.removeEventListener js/window "pointermove" on-scrub-move!)
    (.removeEventListener js/window "pointerup"   end-scrub!)
    (.removeEventListener js/window "pointercancel" end-scrub!)))

(defn- pointer-scrub!
  "Wire pointerdown on `label-el` so a horizontal drag scrubs the
   numeric value of `input-el`. `read-fn` returns the current numeric
   value (nil to suppress the gesture). `commit-fn!` is called as
   `(new-val first?)`; the first call pushes a fresh history entry
   via `state/commit!`, every later call uses `state/commit-coalesced!`
   so the whole drag undoes as a single step. Shift multiplies the
   step by 10."
  [^js label-el ^js input-el read-fn commit-fn! step]
  (.. label-el -classList (add "is-scrubbable"))
  (u/on! label-el :pointerdown
         (fn [^js e]
           (let [v (read-fn)]
             (when (number? v)
               (.preventDefault e)
               (try (.setPointerCapture label-el (.-pointerId e))
                    (catch :default _ nil))
               (set! (.-active? scrub-state)    true)
               (set! (.-start-x scrub-state)    (.-clientX e))
               (set! (.-start-val scrub-state)  v)
               (set! (.-first? scrub-state)     true)
               (set! (.-pointer-id scrub-state) (.-pointerId e))
               (set! (.-label scrub-state)      label-el)
               (set! (.-input scrub-state)      input-el)
               (set! (.-commit-fn scrub-state)  commit-fn!)
               (set! (.-step scrub-state)       (or step 1))
               ;; Window-level move/up means the drag survives the
               ;; pointer leaving the label, which is the natural
               ;; gesture (drag continues until release).
               (.addEventListener js/window "pointermove"   on-scrub-move!)
               (.addEventListener js/window "pointerup"     end-scrub!)
               (.addEventListener js/window "pointercancel" end-scrub!))))))

(defn- read-event-checked ^js [^js e]
  (let [d (.-detail e)]
    (cond
      (and d (not (undefined? (.-checked d)))) (.-checked d)
      :else (-> e .-target .-checked))))

(defn- tag-widget!
  ([^js el prop-name kind-str]
   (tag-widget! el prop-name kind-str nil))
  ([^js el prop-name kind-str transform]
   (.setAttribute el "data-prop-name" (or prop-name ""))
   (.setAttribute el "data-prop-kind" kind-str)
   (when transform
     (.setAttribute el "data-prop-transform" (name transform)))
   el))

(defn- build-boolean [node prop]
  (let [el       (-> (u/el :x-switch {:class "inspector-field-widget"})
                     (tag-widget! (:name prop) "boolean"))
        current? (boolean (current-value node prop))]
    (when current? (u/set-attr! el :checked ""))
    (u/on! el :x-switch-change
           (fn [^js e]
             (commit-prop! (:id node) (:name prop) (read-event-checked e))))
    el))

(defn- build-enum [node prop]
  (let [sel-el  (-> (u/el :x-select {:class "inspector-field-widget"})
                    (tag-widget! (:name prop) "enum"))
        current (current-value node prop)
        options (for [choice (:choices prop)]
                  (let [o (u/el :option {:value choice})]
                    (u/set-text! o choice)
                    (when (= choice current) (u/set-attr! o :selected ""))
                    o))]
    (doseq [o options] (.appendChild sel-el o))
    (u/on! sel-el :select-change
           (fn [^js e]
             (commit-attr! (:id node) (:name prop) (read-event-value e))))
    sel-el))

(defn- build-text-area [node prop]
  (let [el      (-> (u/el :x-text-area {:class "inspector-field-widget"})
                    (tag-widget! (:name prop) "text-long"))
        current (current-value node prop)]
    (when current (u/set-attr! el :value current))
    (u/on! el :x-text-area-input
           (fn [^js e]
             (commit-attr! (:id node) (:name prop) (read-event-value e))))
    el))

(defn- build-search-field [node prop spec]
  (let [transform (:transform prop)
        numeric?  (= "number" (:type spec))
        ;; Surface BareDOM theme tokens via a native datalist when the
        ;; field is colour-shaped. The list attribute has to be on the
        ;; shadow inner `<input>`, not the custom-element host — see
        ;; `attach-datalist-to-shadow-input!`.
        color?    (= :color (:kind prop))
        el        (-> (u/el :x-search-field
                            (cond-> {:class "inspector-field-widget"}
                              numeric? (assoc :type "number")))
                      (tag-widget! (:name prop) "text" transform))
        current   (current-value node prop)
        node-id   (:id node)
        attr-name (:name prop)]
    (when color?
      (attach-datalist-to-shadow-input! el color-datalist-id))
    (when current (u/set-attr! el :value current))
    (u/on! el :x-search-field-input
           (fn [^js e]
             (let [v (read-event-value e)
                   v (if transform (transform-for-commit transform v) v)]
               (commit-attr! node-id attr-name v))))
    (cond-> el
      numeric?
      (attach-scrub-meta!
        {:read-fn   (fn []
                      ;; Empty / non-numeric attr starts the drag at 0
                      ;; so unset fields are still scrubbable from
                      ;; nothing — otherwise the gesture would silently
                      ;; do nothing on a fresh component.
                      (let [doc    (:document @state/app-state)
                            n      (m/get-node doc node-id)
                            raw    (current-value n prop)
                            parsed (when (and raw (not= "" raw))
                                     (let [p (js/parseFloat raw)]
                                       (when-not (js/isNaN p) p)))]
                        (or parsed 0)))
         :commit-fn! (fn [new-val first?]
                       (let [doc  (:document @state/app-state)
                             doc' (ops/set-attr doc node-id attr-name (str new-val))]
                         (if first?
                           (state/commit! doc')
                           (state/commit-coalesced! doc'))))
         :step       1}))))

(defn- build-widget [node prop]
  (let [spec (editor-spec prop)]
    (case (:kind spec)
      :boolean     (build-boolean node prop)
      :enum        (build-enum node prop)
      :string-long (build-text-area node prop)
      (build-search-field node prop spec))))

(defn- build-inner-html-field
  "Special-case editor for the `:inner-html` pseudo-property — raw HTML
   that becomes the element's default-slot content via innerHTML. Used
   by components that opt in with `:raw-html-slot?` in their augment
   entry (see `x-icon`)."
  [node]
  (let [el      (-> (u/el :x-text-area
                          {:class "inspector-field-widget"
                           :placeholder "Paste SVG markup here"})
                    (tag-widget! "__inner_html__" "inner-html"))
        current (:inner-html node)]
    (when current (u/set-attr! el :value current))
    (u/on! el :x-text-area-input
           (fn [^js e]
             (commit-with! ops/set-inner-html (:id node) (read-event-value e))))
    el))

(defn- build-text-field
  "Special-case editor for the `:text` pseudo-property — the plain text
   child of nodes like x-typography. Uses a single-line search field."
  [node]
  (let [el      (-> (u/el :x-search-field
                          {:class "inspector-field-widget"
                           :placeholder "Text content"})
                    (tag-widget! "__text__" "text-content"))
        current (:text node)]
    (when current (u/set-attr! el :value current))
    (u/on! el :x-search-field-input
           (fn [^js e]
             (commit-with! ops/set-text (:id node) (read-event-value e))))
    el))

(defn- build-layout-field
  "Editor for one of the generic dimension layout fields
   (:width / :height / :padding / :margin). Stored in the node's
   :layout map; reconciler turns it into inline style. Surfaces the
   length-tokens datalist so `var(--x-space-…)` autocompletes."
  [node layout-key placeholder]
  (let [el      (-> (u/el :x-search-field
                          {:class "inspector-field-widget"
                           :placeholder placeholder})
                    (tag-widget! (str "__layout__/" (name layout-key)) "layout"))
        current (get-in node [:layout layout-key])]
    (attach-datalist-to-shadow-input! el length-datalist-id)
    (when current (u/set-attr! el :value current))
    (u/on! el :x-search-field-input
           (fn [^js e]
             (let [v (read-event-value e)]
               (commit-with! ops/set-layout (:id node)
                             layout-key
                             (when (and v (not= "" v)) v)))))
    el))

(defn- build-placement-field
  "Editable x-select for the node's `:layout :placement`. Currently
   exposes `flow`, `background`, and `free`. Top/bottom-full-width
   hints are reserved for a later snap track."
  [node]
  (let [el (-> (u/el :x-select {:class "inspector-field-widget"})
               (tag-widget! "__layout__/placement" "layout"))
        current (get-in node [:layout :placement])]
    (doseq [choice [:flow :background :free]]
      (let [o (u/el :option {:value (name choice)})]
        (u/set-text! o (name choice))
        (when (= choice current) (.setAttribute o "selected" ""))
        (.appendChild el o)))
    (u/on! el :select-change
           (fn [^js e]
             (let [v (some-> e .-detail .-value)]
               (when v
                 (commit-with! ops/set-layout (:id node) :placement (keyword v))))))
    el))

(defn- parse-length-value
  "Coerce an x-search-field string into a number when it parses
   cleanly, otherwise pass the string through (so users can type
   '50%' or '10rem' if they want). Empty string → nil (clear)."
  [v]
  (cond
    (or (nil? v) (= "" v)) nil
    :else                  (let [n (js/parseFloat v)]
                             (if (and (number? n) (not (js/isNaN n))
                                      (= (str n) v))
                               n
                               v))))

(defn- build-free-coord-field
  "Numeric/length editor for one of the :layout :x :y :w :h fields.
   Most useful when the node's placement is :free, but shown
   unconditionally so users can pre-fill coordinates before toggling.
   Free-coord fields always store numbers (the reconciler turns them
   into px lengths), so the row is uniformly scrubbable."
  [node layout-key placeholder]
  (let [el      (-> (u/el :x-search-field
                          {:class "inspector-field-widget"
                           :placeholder placeholder})
                    (tag-widget! (str "__layout__/" (name layout-key)) "layout"))
        raw     (get-in node [:layout layout-key])
        node-id (:id node)]
    (when raw (u/set-attr! el :value (str raw)))
    (u/on! el :x-search-field-input
           (fn [^js e]
             (let [v (read-event-value e)]
               (commit-with! ops/set-layout node-id layout-key (parse-length-value v)))))
    (attach-scrub-meta!
      el
      {:read-fn    (fn []
                     ;; Default to 0 when the field is empty so the
                     ;; gesture engages immediately — useful when
                     ;; placement is being changed to :free and the
                     ;; user wants to scrub the new coord into shape.
                     (let [doc (:document @state/app-state)
                           v   (get-in (m/get-node doc node-id)
                                       [:layout layout-key])]
                       (cond
                         (number? v) v
                         (string? v) (let [parsed (js/parseFloat v)]
                                       (if (js/isNaN parsed) 0 parsed))
                         :else       0)))
       :commit-fn! (fn [new-val first?]
                     (let [doc  (:document @state/app-state)
                           doc' (ops/set-layout doc node-id layout-key new-val)]
                       (if first?
                         (state/commit! doc')
                         (state/commit-coalesced! doc'))))
       :step       1})))

(defn- build-layout-textarea
  "Multi-line editor for the free-form `:extra-style` layout field.
   Whatever the user types is concatenated into the element's inline
   style by the reconciler — handy for one-off CSS overrides
   (`color: red; --x-button-fg: lime`) that don't have a dedicated
   editor."
  [node]
  (let [el      (-> (u/el :x-text-area
                          {:class "inspector-field-widget"
                           :placeholder "color: red; --x-button-fg: lime;"
                           :rows "3"})
                    (tag-widget! "__layout__/extra-style" "layout"))
        current (get-in node [:layout :extra-style])]
    (when current (u/set-attr! el :value current))
    (u/on! el :x-text-area-input
           (fn [^js e]
             (let [v (read-event-value e)]
               (commit-with! ops/set-layout (:id node)
                             :extra-style
                             (when (and v (not= "" v)) v)))))
    el))

(defn- resolve-css-var
  "Pure: given an augment :css-vars `entry` and a `node`, return the
   actual CSS custom property name to read/write. Plain entries with
   `:var` use it directly; entries with `:vars-by-attr` look up the
   var name from the node's current attribute value (with
   `:default-attr-value` as fallback)."
  [entry node]
  (or (:var entry)
      (when-let [vbatts (:vars-by-attr entry)]
        (let [[attr lookup] (first vbatts)
              current (or (get-in node [:attrs attr])
                          (:default-attr-value entry))]
          (get lookup current)))))

(defn- find-css-var-entry-by-label
  "Look up a :css-vars entry on the given tag by its label. Used by the
   in-place updater so it can re-resolve the var name on every refresh
   (in case the user changed the variant/size attribute that selects
   which var the entry maps to)."
  [tag label]
  (some #(when (= label (:label %)) %)
        (:css-vars (registry/get-meta tag))))

(defn- commit-css-var-from-event! [node entry value]
  ;; Re-read the node from app-state so the resolver always sees the
  ;; latest variant / size attributes, not the snapshot taken when
  ;; the widget was first built.
  (let [doc        (:document @state/app-state)
        fresh-node (or (m/get-node doc (:id node)) node)
        var-name   (resolve-css-var entry fresh-node)]
    (when var-name
      (commit-with! ops/set-css-var (:id node) var-name value))))

(defn- probe-resolved-css-var
  "Resolve a custom property's *rendered* value by painting it through
   a hidden probe child. For un-registered custom properties (which is
   most BareDOM tokens), `getComputedStyle(host).getPropertyValue` hands
   back the unresolved specified value — e.g. `var(--x-color-primary,
   #6366f1)` — because `var()` substitution happens on *used* value,
   not on computed value. Applying the variable to a registered
   property (`color`) on a child forces the browser to resolve the
   whole `var()` chain; reading it back yields a concrete `rgb(...)`
   string. The probe inherits the host's custom properties through
   normal CSS inheritance (light-DOM descendants inherit from the
   host including anything set by `:host` rules) so this works even
   when the variable's default lives only in the shadow DOM."
  [^js host var-name]
  (when host
    (let [^js probe (js/document.createElement "span")]
      (try
        (set! (.. probe -style -display) "none")
        (set! (.. probe -style -color) (str "var(" var-name ")"))
        (.appendChild host probe)
        (let [resolved (.-color (js/window.getComputedStyle probe))]
          (when (and resolved (not= "" resolved))
            resolved))
        (finally
          (when (.-parentNode probe)
            (.removeChild (.-parentNode probe) probe)))))))

(defn- live-css-var-value
  "Resolved value of `var-name` on the live DOM element for `node-id`.
   Tries the direct `getComputedStyle` read first (which works when
   the property is set inline or registered), then falls back to a
   probe child when the browser hands back a `var(...)` expression —
   which is what happens for BareDOM tokens declared in a component's
   `:host` block."
  [node-id var-name]
  (when-let [^js el (canvas/dom-for-id node-id)]
    (let [raw (-> (js/window.getComputedStyle el)
                  (.getPropertyValue var-name)
                  (or "")
                  str/trim)]
      (cond
        (= "" raw)                 (probe-resolved-css-var el var-name)
        (str/starts-with? raw "var(") (probe-resolved-css-var el var-name)
        :else                      raw))))

(defn- build-css-var-color [node entry]
  (let [el       (-> (u/el :x-color-picker
                           ;; `alpha ""` flips the boolean attribute on
                           ;; (parse-bool-present treats any non-nil
                           ;; string as true), adding the alpha strip
                           ;; and making the picker emit 8-digit hex
                           ;; (#rrggbbaa) so opacity round-trips
                           ;; through the layout :css-vars.
                           {:class "inspector-field-widget inspector-color-picker"
                            :alpha ""})
                     (tag-widget! (str "__css-var__/" (:label entry)) "css-var-color"))
        var-name (resolve-css-var entry node)
        ;; Prefer the user's per-node override if they've already
        ;; edited this var, otherwise seed the picker from whatever
        ;; colour the component is currently painting (theme or its
        ;; own :host default). The picker only understands hex, so
        ;; the live value is normalized via the Canvas round-trip.
        current  (when var-name
                   (or (get-in node [:layout :css-vars var-name])
                       (u/css-color->hex
                        (live-css-var-value (:id node) var-name))))]
    (when current (u/set-attr! el :value current))
    (u/on! el :x-color-picker-input
           (fn [^js e]
             (let [v (some-> e .-detail .-value)]
               (commit-css-var-from-event! node entry
                                           (when (and v (not= "" v)) v)))))
    el))

(defn- build-css-var-text [node {:keys [kind] :as entry}]
  (let [placeholder (case kind
                      :length "12px / 1rem / 50%"
                      :string "")
        el       (-> (u/el :x-search-field
                           {:class "inspector-field-widget"
                            :placeholder placeholder})
                     (tag-widget! (str "__css-var__/" (:label entry)) "css-var-text"))
        var-name (resolve-css-var entry node)
        current  (when var-name (get-in node [:layout :css-vars var-name]))]
    (when current (u/set-attr! el :value current))
    (u/on! el :x-search-field-input
           (fn [^js e]
             (let [v (read-event-value e)]
               (commit-css-var-from-event! node entry
                                           (when (and v (not= "" v)) v)))))
    el))

(defn- build-css-var-widget [node {:keys [kind] :as entry}]
  (case kind
    :color (build-css-var-color node entry)
    (build-css-var-text node entry)))

;; --- binding helpers ------------------------------------------------------

(defn- field-name-str [v]
  (cond
    (keyword? v) (name v)
    (string? v)  v
    :else        (str v)))

(defn- read-event-value*
  "Read the current value from a BareDOM form element. Tries .-value
   property first, then the value attribute."
  [^js el]
  (let [v (.-value el)]
    (if (or (nil? v) (undefined? v))
      (.getAttribute el "value")
      v)))

(defn collect-all-fields
  "Scan the document for all fields — both group-level definitions and
   property bindings. Returns a vector of maps with :field :owner-id
   :owner-name and optionally :prop :direction :bound-by. Declared
   fields carry :computed? true when they have a :computed spec."
  [doc]
  (into []
        (concat
         (for [node      (m/walk-nodes doc)
               field-def (:fields node)
               :let [{:keys [name type default computed]} field-def]]
           {:field      name
            :owner-id   (:id node)
            :owner-name (or (:name node) (:tag node))
            :type       type
            :default    default
            :computed?  (some? computed)
            :source     :field-def})
         (for [node (m/walk-nodes doc)
               [prop-name {:keys [field direction owner]}] (:bindings node)]
           {:field      field
            :owner-id   (or owner (:id node))
            :owner-name (or (:name node) (:tag node))
            :prop       prop-name
            :direction  direction
            :bound-by   (:id node)
            :source     :binding}))))

(defn- infer-direction
  "Infer binding direction from property kind and component tag."
  [kind tag]
  (cond
    (and (= kind :boolean)
         (contains? #{"x-switch" "x-checkbox"} tag)) :read-write
    (contains? #{"x-search-field" "x-text-field"
                 "x-number-field" "x-text-area"} tag) :write
    :else :read))

(defn- commit-binding!
  "Persist a property binding. `owner` is the name of the group the
   user picked the field from (or nil if not known); stored so two
   groups that declare the same field name don't collapse to the
   same ownership at display/export time."
  ([node-id prop-name field direction]
   (commit-binding! node-id prop-name field nil direction))
  ([node-id prop-name field owner direction]
   (let [doc  (:document @state/app-state)
         doc' (ops/set-binding doc node-id prop-name
                               (cond-> {:field     (keyword field)
                                        :direction direction}
                                 owner (assoc :owner owner)))]
     (state/commit! doc'))))

(defn- commit-unbind! [node-id prop-name]
  (let [doc  (:document @state/app-state)
        doc' (ops/unset-binding doc node-id prop-name)]
    (state/commit! doc')))

(declare enclosing-template-group)

(defn- type-compatible?
  "Lenient check: boolean props only accept boolean fields. Everything
   else is permitted (values render as strings). Incompatible fields
   are dimmed in the picker but remain selectable."
  [field-type prop-kind]
  (if (= :boolean prop-kind)
    (= :boolean field-type)
    true))

(defn- picker-sections
  "Structured input for the bind picker: a sequence of
   `{:title <group-name> :pinned? bool :fields [...]}` sections.
   Record fields (fields on the enclosing template group) are pinned
   at the top. Fields are de-duplicated per group."
  [_doc all-fields tmpl-name]
  (let [field-defs (->> all-fields
                        (filter #(= :field-def (:source %)))
                        (distinct))
        by-group   (group-by :owner-name field-defs)
        ordered    (sort-by key (dissoc by-group tmpl-name))
        record    (when (and tmpl-name (contains? by-group tmpl-name))
                    [{:title    tmpl-name
                      :pinned?  true
                      :fields   (sort-by #(name (:field %))
                                         (get by-group tmpl-name))}])
        others    (for [[gname fields] ordered]
                    {:title    gname
                     :pinned?  false
                     :fields   (sort-by #(name (:field %)) fields)})]
    (vec (concat (or record []) others))))

(defn- build-bind-picker-panel
  "Render the grouped bind picker: search box + one section per group.
   Record fields section pinned first when inside a template. Items
   show field name + type in parens + `fx` marker for computed fields.
   Type-incompatible items are dimmed but still clickable. `on-pick`
   is called with the picked field keyword."
  [doc tmpl-name prop-kind on-pick]
  (let [wrap    (u/el :div {:class "inspector-bind-picker"})
        search  (u/el :x-search-field
                      {:class       "inspector-bind-input"
                       :placeholder "search fields…"})
        body    (u/el :div {:class "inspector-bind-picker-body"})
        all     (collect-all-fields doc)
        sects   (picker-sections doc all tmpl-name)
        render! (fn [query]
                  (.replaceChildren body)
                  (doseq [{:keys [title pinned? fields]} sects
                          :let [matches (if (seq query)
                                          (filter #(str/includes?
                                                    (str/lower-case (name (:field %)))
                                                    query)
                                                  fields)
                                          fields)]
                          :when (seq matches)]
                    (let [header (u/set-text!
                                  (u/el :div {:class "inspector-bind-section"})
                                  (if pinned?
                                    (str "Record fields — " title)
                                    title))]
                      (.appendChild body header)
                      (doseq [{:keys [field type computed?]} matches]
                        (let [compat?  (type-compatible? type prop-kind)
                              cls      (cond-> "inspector-bind-suggestion"
                                         (not compat?) (str " inspector-bind-suggestion--dim"))
                              label    (str (name field)
                                            " (" (field-name-str type) ")"
                                            (when computed? " fx"))
                              item     (u/set-text! (u/el :div {:class cls
                                                                :title (when-not compat?
                                                                         (str "type mismatch: "
                                                                              (field-name-str prop-kind)
                                                                              " expects "
                                                                              (field-name-str prop-kind)))})
                                                    label)]
                          (u/on! item :click (fn [_] (on-pick field title)))
                          (.appendChild body item))))))]
    (render! "")
    (u/on! search :x-search-field-input
           (fn [^js e]
             (render! (str/lower-case (or (read-event-value e) "")))))
    (.appendChild wrap search)
    (.appendChild wrap body)
    wrap))

(defn- build-bind-toggle
  "Bind UI for a property. Three states:
   - Unbound: 🔗 icon opens a grouped picker panel.
   - Picking: panel expanded in place with search + grouped sections.
   - Bound:   shows qualified field label + × to unbind."
  [node prop _all-fields]
  (let [prop-name (:name prop)
        doc       (:document @state/app-state)
        tmpl      (enclosing-template-group doc (:id node))
        tmpl-name (:name tmpl)
        binding   (get-in node [:bindings prop-name])
        container (u/el :div {:class "inspector-bind-row"})]
    (if binding
      (let [owner-name   (or (:owner binding)
                             (some (fn [n]
                                     (when (some #(= (:field binding) (:name %))
                                                 (:fields n))
                                       (:name n)))
                                   (m/walk-nodes doc)))
            label-text   (if owner-name
                           (str "↔ " owner-name "." (field-name-str (:field binding)))
                           (str "↔ " (field-name-str (:field binding))))
            field-label  (u/set-text!
                          (u/el :span {:class "inspector-bind-field"})
                          label-text)
            unbind-btn   (u/set-text!
                          (u/el :x-button {:variant "ghost" :size "sm"
                                           :class "inspector-bind-btn"})
                          "×")]
        (u/on! unbind-btn :press
               (fn [_] (commit-unbind! (:id node) prop-name)))
        (.appendChild container field-label)
        (.appendChild container unbind-btn))
      (let [btn (u/set-text!
                 (u/el :x-button {:variant "ghost" :size "sm"
                                  :class "inspector-bind-btn"
                                  :title "Bind to a field"})
                 "🔗")]
        (u/on! btn :press
               (fn [_]
                 (.replaceChildren container)
                 (let [pick! (fn [field-kw owner]
                               (commit-binding! (:id node) prop-name
                                                (name field-kw)
                                                owner
                                                (infer-direction (:kind prop)
                                                                 (:tag node))))
                       panel (build-bind-picker-panel doc tmpl-name
                                                      (:kind prop) pick!)]
                   (.appendChild container panel))))
        (.appendChild container btn)))
    container))

(defn- field-row-with-binding [label widget node prop all-fields]
  (let [label-el (u/set-text! (u/el :div {:class "inspector-field-label"}) label)]
    (when-let [scrub (read-scrub-meta widget)]
      (pointer-scrub! label-el widget
                      (:read-fn scrub) (:commit-fn! scrub) (:step scrub)))
    (u/el :div {:class "inspector-field"}
          [label-el
           widget
           (build-bind-toggle node prop all-fields)])))

;; --- section builders ----------------------------------------------------

(def ^:private default-collapsed
  #{"Attributes" "Text" "Content" "Slots" "Layout" "Component variables"})

(defn- section-collapsed? [label]
  (let [collapsed (get-in @state/app-state [:ui :inspector-collapsed])]
    (if (some? collapsed)
      (contains? collapsed label)
      (contains? default-collapsed label))))

(defn- toggle-section! [label]
  (let [collapsed (or (get-in @state/app-state [:ui :inspector-collapsed])
                      default-collapsed)]
    (state/assoc-ui! :inspector-collapsed
                     (if (contains? collapsed label)
                       (disj collapsed label)
                       (conj collapsed label)))))

(defn- section
  [label children]
  (let [collapsed? (section-collapsed? label)
        indicator  (if collapsed? "▸ " "▾ ")
        label-el   (u/el :div {:class "inspector-section-label"
                               :style "cursor:pointer;user-select:none;"})
        body-el    (u/el :div {:class "inspector-section-body"})]
    (u/set-text! label-el (str indicator label))
    (when collapsed?
      (u/set-attr! body-el :style "display:none;"))
    (u/on! label-el :click (fn [_] (toggle-section! label)))
    (doseq [c children] (.appendChild body-el c))
    (u/el :div {:class "inspector-section"}
          [label-el body-el])))

(defn- field-row [label widget]
  (let [label-el (u/set-text! (u/el :div {:class "inspector-field-label"}) label)]
    (when-let [scrub (read-scrub-meta widget)]
      (pointer-scrub! label-el widget
                      (:read-fn scrub) (:commit-fn! scrub) (:step scrub)))
    (u/el :div {:class "inspector-field"}
          [label-el widget])))

(defn- attributes-section [{:keys [node meta]} all-fields]
  (let [props (:properties meta)
        rows  (for [p props]
                (field-row-with-binding
                 (display-label p) (build-widget node p)
                 node p all-fields))]
    (if (seq props)
      (section "Attributes" rows)
      (section "Attributes"
               [(u/set-text! (u/el :div {:class "inspector-empty"})
                             "No attributes")]))))

(defn- enclosing-template-group
  "The nearest named-ancestor group (inclusive) whose fields describe a
   record shape — i.e. another group's collection field targets it
   via `:of-group`. Nil when the node is outside any template group."
  [doc node-id]
  (loop [id node-id]
    (when id
      (let [n (m/get-node doc id)]
        (cond
          (nil? n) nil
          (and (:name n) (actions/template-group? doc n)) n
          :else
          (when-let [p (m/parent-of doc id)]
            (recur (:parent-id p))))))))

(defn- commit-text-field!
  ([node-id field-kw] (commit-text-field! node-id field-kw nil))
  ([node-id field-kw owner]
   (state/commit! (ops/set-text-field (:document @state/app-state)
                                      node-id field-kw owner))))

(defn- build-text-content-bind
  "🔗 bind UI for a node's text slot content. Mirrors build-bind-toggle
   but reads/writes :text-field instead of the attribute :bindings map.
   Opens the same grouped picker as attribute bindings — pinned
   'Record fields' when the node is inside a template, plus a section
   per named group that declares fields. `:text-field-owner` records
   which group the user picked from so the display label is stable
   when two groups share a field name."
  [node]
  (let [doc       (:document @state/app-state)
        tmpl      (enclosing-template-group doc (:id node))
        tmpl-name (:name tmpl)
        bound     (:text-field node)
        container (u/el :div {:class "inspector-bind-row"})]
    (if bound
      (let [owner-name (or (:text-field-owner node)
                           (some (fn [n]
                                   (when (some #(= bound (:name %)) (:fields n))
                                     (:name n)))
                                 (m/walk-nodes doc)))
            label-text (if owner-name
                         (str "↔ " owner-name "." (field-name-str bound))
                         (str "↔ " (field-name-str bound)))
            field-lbl  (u/set-text!
                        (u/el :span {:class "inspector-bind-field"})
                        label-text)
            unbind-btn (u/set-text!
                        (u/el :x-button {:variant "ghost" :size "sm"
                                         :class "inspector-bind-btn"})
                        "×")]
        (u/on! unbind-btn :press
               (fn [_] (commit-text-field! (:id node) nil)))
        (.appendChild container field-lbl)
        (.appendChild container unbind-btn))
      (let [btn (u/set-text!
                 (u/el :x-button {:variant "ghost" :size "sm"
                                  :class "inspector-bind-btn"
                                  :title "Bind text to a field"})
                 "🔗")]
        (u/on! btn :press
               (fn [_]
                 (.replaceChildren container)
                 (let [pick! (fn [field-kw owner]
                               (commit-text-field! (:id node) field-kw owner))
                       ;; :string-short keeps type-compatibility permissive —
                       ;; every scalar field can render as text.
                       panel (build-bind-picker-panel doc tmpl-name
                                                      :string-short pick!)]
                   (.appendChild container panel))))
        (.appendChild container btn)))
    container))

(defn- text-section [{:keys [node]}]
  (when (or (some? (:text node))
            ;; show the text editor for typography even if empty
            (contains? #{"x-typography" "x-button"} (:tag node)))
    (section "Text"
             [(field-row "text" (build-text-field node))
              (field-row "bind" (build-text-content-bind node))])))

(defn- inner-html-section
  "Textarea editor for components whose default slot is raw HTML
   (flagged via `:raw-html-slot?` in the augment map)."
  [{:keys [node]}]
  (section "Content"
           [(field-row "inner HTML" (build-inner-html-field node))]))

(defn- slots-section [{:keys [node meta]}]
  (let [slots (:slots meta)
        rows  (for [{:keys [name label multiple?]} slots]
                (let [children (get-in node [:slots name] [])]
                  ;; data-bareforge-slot-* tags turn each row into a
                  ;; drop target the dnd layer can recognise.
                  (u/el :div {:class "inspector-slot"
                              :data-bareforge-slot-node (:id node)
                              :data-bareforge-slot-name name}
                        [(u/set-text! (u/el :span {:class "inspector-slot-name"})
                                      (or label name))
                         (u/set-text! (u/el :span {:class "inspector-slot-meta"})
                                      (str (count children)
                                           (if multiple? " children" " child")))])))]
    (section "Slots"
             (if (seq rows)
               rows
               [(u/set-text! (u/el :div {:class "inspector-empty"}) "No slots")]))))

(defn- layout-section [{:keys [node]}]
  (section "Layout"
           [(field-row "placement"   (build-placement-field node))
            (field-row "width"       (build-layout-field node :width   "auto / 200px / 50%"))
            (field-row "height"      (build-layout-field node :height  "auto / 80px"))
            (field-row "padding"     (build-layout-field node :padding "12px / 1rem 2rem"))
            (field-row "margin"      (build-layout-field node :margin  "0 auto / 16px"))
            ;; Free-position coordinates. Ignored unless placement
            ;; is :free; shown unconditionally so users can pre-fill.
            (field-row "x (free)"    (build-free-coord-field node :x "0 / 100"))
            (field-row "y (free)"    (build-free-coord-field node :y "0 / 100"))
            (field-row "w (free)"    (build-free-coord-field node :w "auto"))
            (field-row "h (free)"    (build-free-coord-field node :h "auto"))
            (field-row "extra style" (build-layout-textarea node))]))

(defn- css-vars-section [{:keys [node meta]}]
  (when-let [css-vars (seq (:css-vars meta))]
    (section "Component variables"
             (for [{:keys [var label] :as entry} css-vars]
               (field-row (or label var)
                          (build-css-var-widget node entry))))))

(defn- commit-name! [node-id name]
  (state/commit! (ops/set-name (:document @state/app-state) node-id name)))

(defn- header [{:keys [node]}]
  (let [tag-seg (-> (:tag node)
                    (str/replace #"^x-" "")
                    (str/replace #"-" "_"))
        name-el (u/el :x-search-field
                      {:class "inspector-field-widget"
                       :placeholder (str "e.g. " tag-seg)
                       :value (or (:name node) "")})]
    (u/on! name-el :x-search-field-input
           (fn [^js e]
             (commit-name! (:id node) (read-event-value e))))
    (u/on! name-el :x-search-field-clear
           (fn [_]
             (commit-name! (:id node) nil)))
    (tag-widget! name-el "__name__" "name")
    (u/el :div {:class "inspector-header"}
          [(u/set-text! (u/el :div {:class "inspector-header-tag"})
                        (:tag node))
           (field-row "name" name-el)])))

(defn- empty-view []
  (u/el :div {:class "inspector-empty-state"}
        [(u/set-text!
          (u/el :div {:class "inspector-empty"})
          "No component selected. Click a layer or a palette item.")]))

;; --- multi-select shared editors (M2.3) ---------------------------------

(defn shared-properties
  "Pure: properties that exist on every tag in `tags` with the same
   `:name` and `:kind`. Returns the descriptor from the first tag
   (the others are equivalent on the dimensions the inspector cares
   about). Used by the multi-select attribute section to decide
   which rows to render."
  [tags]
  (let [meta-list  (mapv registry/get-meta tags)
        first-list (-> meta-list first :properties)]
    (if (or (empty? meta-list) (empty? first-list))
      []
      (filterv
        (fn [prop]
          (every?
            (fn [m]
              (some #(and (= (:name prop) (:name %))
                          (= (:kind prop) (:kind %)))
                    (:properties m)))
            (rest meta-list)))
        first-list))))

(defn joint-attr-value
  "Pure: read attribute `prop` across `nodes` and return
   `{:value :mixed?}`. `:value` is the common value when every node
   agrees, the first node's value otherwise. `:mixed?` is true iff
   the values disagree."
  [nodes prop]
  (let [vs (mapv #(current-value % prop) nodes)]
    {:value  (first vs)
     :mixed? (not (apply = vs))}))

(defn- multi-set-attr! [ids attr-name v]
  (let [doc  (:document @state/app-state)
        v    (if (or (nil? v) (= "" v)) nil v)
        doc' (ops/set-attrs-many doc ids {attr-name v})]
    (state/commit! doc')))

(defn- multi-set-prop! [ids prop-name v]
  (let [doc  (:document @state/app-state)
        doc' (ops/set-props-many doc ids {(keyword prop-name) v})]
    (state/commit! doc')))

(defn- build-multi-search-field [nodes prop spec]
  (let [{:keys [value mixed?]} (joint-attr-value nodes prop)
        transform (:transform prop)
        numeric?  (= "number" (:type spec))
        color?    (= :color (:kind prop))
        el        (-> (u/el :x-search-field
                            (cond-> {:class "inspector-field-widget"}
                              numeric? (assoc :type "number")
                              mixed?   (assoc :placeholder "Mixed")))
                      (tag-widget! (:name prop) "text" transform))
        ids       (mapv :id nodes)]
    (when color?
      (attach-datalist-to-shadow-input! el color-datalist-id))
    (when (and (not mixed?) value (not= "" value))
      (u/set-attr! el :value value))
    (when mixed?
      (.. el -classList (add "is-mixed")))
    (u/on! el :x-search-field-input
           (fn [^js e]
             (let [v (read-event-value e)
                   v (if transform (transform-for-commit transform v) v)]
               (multi-set-attr! ids (:name prop) v))))
    el))

(defn- build-multi-text-area [nodes prop]
  (let [{:keys [value mixed?]} (joint-attr-value nodes prop)
        el  (-> (u/el :x-text-area
                      (cond-> {:class "inspector-field-widget"}
                        mixed? (assoc :placeholder "Mixed")))
                (tag-widget! (:name prop) "text"))
        ids (mapv :id nodes)]
    (when (and (not mixed?) value (not= "" value))
      (u/set-attr! el :value value))
    (when mixed?
      (.. el -classList (add "is-mixed")))
    (u/on! el :x-text-area-input
           (fn [^js e]
             (multi-set-attr! ids (:name prop) (read-event-value e))))
    el))

(defn- build-multi-enum [nodes prop]
  (let [{:keys [value mixed?]} (joint-attr-value nodes prop)
        sel-el  (-> (u/el :x-select {:class "inspector-field-widget"})
                    (tag-widget! (:name prop) "enum"))
        ids     (mapv :id nodes)
        ;; Prepend a "—" sentinel only in mixed mode so the user can
        ;; see "values differ" without an explicit choice having been
        ;; made; selecting a concrete option commits to all nodes.
        opts    (cond-> (vec (:choices prop))
                  mixed? (->> (cons "—") vec))]
    (doseq [choice opts]
      (let [o (u/el :option {:value choice})]
        (u/set-text! o choice)
        (when (or (and (not mixed?) (= choice value))
                  (and mixed? (= choice "—")))
          (u/set-attr! o :selected ""))
        (.appendChild sel-el o)))
    (when mixed?
      (.. sel-el -classList (add "is-mixed")))
    (u/on! sel-el :select-change
           (fn [^js e]
             (let [v (read-event-value e)]
               (when (and v (not= v "—"))
                 (multi-set-attr! ids (:name prop) v)))))
    sel-el))

(defn- build-multi-boolean [nodes prop]
  (let [vs       (mapv #(boolean (get-in % [:props (keyword (:name prop))])) nodes)
        mixed?   (not (apply = vs))
        value    (first vs)
        el       (-> (u/el :x-switch (cond-> {:class "inspector-field-widget"}
                                       value (assoc :checked "")))
                     (tag-widget! (:name prop) "boolean"))
        ids      (mapv :id nodes)]
    (when mixed?
      (.. el -classList (add "is-mixed"))
      ;; Indeterminate-ish: render off so the first toggle force-
      ;; sets all to true, the next toggle force-sets all to false.
      (.removeAttribute el "checked"))
    (u/on! el :x-switch-change
           (fn [^js e]
             (multi-set-prop! ids (:name prop) (read-event-checked e))))
    el))

(defn- build-multi-widget [nodes prop]
  (let [spec (editor-spec prop)]
    (case (:kind spec)
      :boolean     (build-multi-boolean   nodes prop)
      :enum        (build-multi-enum      nodes prop)
      :string-long (build-multi-text-area nodes prop)
      (build-multi-search-field nodes prop spec))))

(defn- multi-attributes-section [{:keys [nodes tags]}]
  (let [props (shared-properties tags)
        body  (if (seq props)
                (for [p props]
                  (field-row (display-label p) (build-multi-widget nodes p)))
                [(u/set-text! (u/el :div {:class "inspector-empty"})
                              "No shared attributes between the selected components.")])]
    (section (str (count nodes) " components selected — Shared attributes")
             body)))

(defn- widget-value [^js el]
  (let [v (.-value el)] (if (nil? v) "" v)))

(defn- desired-widget-value
  "Pure: derive the value the widget tagged with `prop-name` + `kind`
   should currently show, given the canonical `node` state. Returns
   either a string (for value-like widgets) or a boolean (for
   `kind = \"boolean\"`). The CSS-var branch resolves the entry's
   label → var-name on every call so a variant / size change
   redirects the widget without rebuilding it."
  [node prop-name kind transform]
  (case kind
    "boolean"
    (boolean (get-in node [:props (keyword prop-name)]))

    "text-content"
    (or (:text node) "")

    "inner-html"
    (or (:inner-html node) "")

    "layout"
    (let [k   (-> prop-name (str/split #"/" 2) second keyword)
          raw (get-in node [:layout k])]
      (cond
        (nil? raw)     ""
        (keyword? raw) (name raw)
        :else          raw))

    ("css-var-color" "css-var-text")
    (let [label    (-> prop-name (str/split #"/" 2) second)
          entry    (find-css-var-entry-by-label (:tag node) label)
          var-name (when entry (resolve-css-var entry node))]
      (or (when var-name (get-in node [:layout :css-vars var-name])) ""))

    "name"
    (or (:name node) "")

    ;; enum / text / text-long / unknown — read from :attrs, applying
    ;; the optional display transform (e.g. grid-columns repeat → N).
    (let [raw (or (get-in node [:attrs prop-name]) "")]
      (if transform
        (or (transform-for-display transform raw) "")
        raw))))

(defn- sync-widget-value!
  "Effectful: write `new-v` to `el` if it differs from the current
   widget value. Booleans go through the `checked` attr (BareDOM
   boolean controls observe presence/absence); every other kind
   goes through the `value` attr."
  [^js el kind new-v]
  (if (= "boolean" kind)
    (let [cur-v (boolean (.-checked el))]
      (when (not= new-v cur-v)
        (if new-v
          (.setAttribute el "checked" "")
          (.removeAttribute el "checked"))))
    (let [cur-v (widget-value el)]
      (when (not= new-v cur-v)
        (.setAttribute el "value" new-v)))))

(defn- update-field-in-place! [^js el node]
  (let [prop-name (.getAttribute el "data-prop-name")
        kind      (.getAttribute el "data-prop-kind")
        transform (when-let [t (.getAttribute el "data-prop-transform")]
                    (keyword t))]
    (sync-widget-value! el kind
                        (desired-widget-value node prop-name kind transform))))

(defn- update-fields-in-place!
  "Push fresh attribute / prop / text values onto every field widget
   inside the inspector body without rebuilding the DOM. Preserves
   focus and cursor position on the element the user is editing."
  [^js host-el node]
  (let [^js widgets (.querySelectorAll host-el ".inspector-field-widget")]
    (dotimes [i (.-length widgets)]
      (update-field-in-place! (.item widgets i) node))))

;; --- group-level fields section -------------------------------------------

(def ^:private field-types
  [{:id :string  :label "string"  :default ""}
   {:id :number  :label "number"  :default 0}
   {:id :boolean :label "boolean" :default false}
   {:id :keyword :label "keyword" :default nil}
   {:id :vector  :label "vector"  :default []}])

(defn- commit-add-field! [node-id field-def]
  (state/commit! (ops/add-field (:document @state/app-state) node-id field-def)))

(defn- commit-remove-field! [node-id idx]
  (state/commit! (ops/remove-field (:document @state/app-state) node-id idx)))

(defn- format-default [v]
  (cond
    (nil? v)     "nil"
    (string? v)  (str "\"" v "\"")
    :else        (str v)))

(def ^:private computed-op-phrase
  {:count-of  "count of"
   :sum-of    "sum of"
   :empty-of  "empty?"
   :negation  "not"
   :join-on   "join"
   :any-of    "any of"
   :filter-by "filter"})

;; :any-of stays in the spec and renders correctly on existing fields
;; (computed-op-phrase) but is not offered in the picker — it needs a
;; multi-source UI we have not designed yet. For v1, any-of fields are
;; authored by editing the doc JSON directly.
(def ^:private computed-op-options
  [{:id :count-of  :label "count of"}
   {:id :sum-of    :label "sum of"}
   {:id :empty-of  :label "empty?"}
   {:id :negation  :label "not"}
   {:id :join-on   :label "join-on"}
   {:id :filter-by :label "filter"}])

(defn- computed-rhs-phrase
  "Right-hand-side phrase for a computed field's label, tailored per op."
  [c]
  (let [op  (:operation c)
        src (field-name-str (:source-field c))]
    (case op
      :sum-of    (if-let [pf (:project-field c)]
                   (str src "." (field-name-str pf))
                   src)
      :join-on   (let [jt (:join-target c)]
                   (str src " with " (:group-name jt) "." (field-name-str (:match-field jt))))
      :any-of    (str/join "," (map field-name-str (:source-fields c)))
      :filter-by (let [fs (:filter-spec c)]
                   (str src " where "
                        (field-name-str (:match-field fs))
                        " ~ "
                        (field-name-str (:search-field fs))))
      src)))

(defn- field-def-label
  "Two-part label for a single field-def. Returns
   `{:head head-text :summary summary-or-nil}`. The head is always
   present; the summary appears on a second line for collection fields
   so a long seed doesn't produce one awkward wrapping line.
   Stored: head `name (type) = default`.
   Computed: head `name (type) = <op-phrase> <source>`.
   Collection (`:of-group`): head `name (vector of <group>)`,
   summary `N records` / `1 record` / `empty`."
  [fd]
  (let [nm   (field-name-str (:name fd))
        ty   (field-name-str (:type fd))
        og   (:of-group fd)]
    (cond
      (:computed fd)
      (let [c (:computed fd)
            op-phrase (get computed-op-phrase (:operation c)
                           (field-name-str (:operation c)))]
        {:head (str nm " (" ty ") = " op-phrase " " (computed-rhs-phrase c))})

      og
      (let [n (count (:default fd))]
        {:head    (str nm " (vector of " og ")")
         :summary (case n 0 "empty" 1 "1 record" (str n " records"))})

      :else
      {:head (str nm " (" ty ") = " (format-default (:default fd)))})))

(defn- commit-field-default! [node-id fname v]
  (state/commit! (ops/set-field-default (:document @state/app-state) node-id fname v)))

(defn- parse-seed-cell
  "Parse a seed-table cell's text value into the right scalar for the
   target field's type."
  [type-kw s]
  (case type-kw
    :number  (let [p (js/parseFloat (or s "0"))]
               (if (js/isNaN p) 0 p))
    :boolean (= "true" s)
    :keyword (when (and s (not= "" s)) (keyword s))
    :vector  []
    (or s "")))

(defn- seed-cell-display
  "String form of a record value, suitable for an input's :value."
  [v]
  (cond
    (nil? v) ""
    (keyword? v) (cljs.core/name v)
    :else (str v)))

(defn- next-seed-id
  "Next :id for a new seed record — max existing :id + 1, starting at
   1. Keeps records uniquely identifiable even when the user adds
   several in a row without typing each id by hand."
  [records]
  (inc (reduce max 0 (keep #(when (number? (:id %)) (:id %)) records))))

(defn- blank-record-for-group
  "Empty record shaped for `group-node`'s fields. The locked ::id is
   auto-assigned by the caller via `next-seed-id`; other fields get a
   type-appropriate empty value."
  [group-node records]
  (into {}
        (for [fd (:fields group-node)
              :when (not (actions/computed? fd))]
          [(:name fd)
           (cond
             (= :id (:name fd)) (next-seed-id records)
             :else              (case (:type fd)
                                  :number 0 :boolean false :keyword nil
                                  :vector [] ""))])))

(defn- build-seed-records-editor
  "Inline editor for a collection field's `:default` vector. Renders a
   small table with one row per record and one cell per target-group
   field, plus a ✕ per record and a '+ Add record' button. Commits
   directly through `ops/set-field-default`."
  [group-node target-group fd]
  (let [;; Hide the locked ::id column — Bareforge auto-assigns it on
        ;; new records and the user shouldn't be editing it by hand.
        editable-fds (->> (:fields target-group)
                          (remove actions/computed?)
                          (remove :locked?))
        fd-name      (:name fd)
        group-id     (:id group-node)
        ;; Read the CURRENT records vector from app-state. The editor
        ;; DOM isn't rebuilt on intra-record edits (to preserve input
        ;; focus), so each input handler needs a fresh view of the
        ;; vector instead of a stale closure snapshot.
        fresh-records (fn []
                        (or (->> (-> (:document @state/app-state)
                                     (m/get-node group-id)
                                     :fields)
                                 (some #(when (= fd-name (:name %)) %))
                                 :default)
                            []))
        wrap        (u/el :div {:class "inspector-seed-editor"})
        table       (u/el :div {:class "inspector-seed-table"})
        commit-v!   (fn [v] (commit-field-default! group-id fd-name (vec v)))]
    (doseq [[ridx rec] (map-indexed vector (fresh-records))]
      (let [row        (u/el :div {:class "inspector-seed-row"})
            cells-wrap (u/el :div {:class "inspector-seed-cells"})]
        (doseq [tf editable-fds
                :let [fname    (cljs.core/name (:name tf))
                      wrap-el  (u/el :div {:class "inspector-seed-cell-wrap"})
                      caption  (u/set-text!
                                (u/el :small {:class "inspector-seed-caption"})
                                (field-name-str (:name tf)))
                      cell     (u/el :x-search-field
                                     {:class       "inspector-seed-cell"
                                      :placeholder fname
                                      :value       (seed-cell-display (get rec (:name tf)))})]]
          (u/on! cell :x-search-field-input
                 (fn [^js e]
                   (let [v      (read-event-value e)
                         parsed (parse-seed-cell (:type tf) v)
                         cur    (fresh-records)
                         cur-rec (or (nth cur ridx nil) rec)]
                     (commit-v! (assoc cur ridx
                                       (assoc cur-rec (:name tf) parsed))))))
          (.appendChild wrap-el caption)
          (.appendChild wrap-el cell)
          (.appendChild cells-wrap wrap-el))
        (.appendChild row cells-wrap)
        (let [rm (u/set-text!
                  (u/el :x-button {:variant "ghost" :size "sm"
                                   :class "inspector-seed-remove"})
                  "×")]
          (u/on! rm :press
                 (fn [_]
                   (let [cur (fresh-records)]
                     (commit-v! (vec (concat (subvec cur 0 ridx)
                                             (subvec cur (inc ridx))))))))
          (.appendChild row rm))
        (.appendChild table row)))
    (let [add-btn (u/set-text!
                   (u/el :x-button {:variant "secondary" :size "sm"})
                   "+ Add record")
          add-wrap (u/el :div {:class "inspector-seed-add"})]
      (u/on! add-btn :press
             (fn [_]
               (let [cur (fresh-records)]
                 (commit-v! (conj cur
                                  (blank-record-for-group target-group cur))))))
      (.appendChild add-wrap add-btn)
      (.appendChild wrap table)
      (.appendChild wrap add-wrap)
      wrap)))

(defn- seed-expand-key [node fd]
  [(:id node) (:name fd)])

(defn- seeds-expanded? [node fd]
  (contains? (get-in @state/app-state [:ui :expanded-seeds])
             (seed-expand-key node fd)))

(defn- toggle-seeds-expanded! [node fd]
  (let [k (seed-expand-key node fd)]
    (state/update-ui! :expanded-seeds
                      (fn [s] (if (contains? s k) (disj s k) (conj (or s #{}) k))))))

(defn- build-field-def-row [node fd idx]
  (let [locked?    (:locked? fd)
        row-cls    (cond-> "inspector-data-row"
                     locked? (str " inspector-data-row--locked"))
        header     (u/el :div {:class row-cls})
        {:keys [head summary]} (field-def-label fd)
        label-cls  (cond-> "inspector-data-field"
                     summary (str " inspector-data-field--stacked"))
        label-el   (u/el :span {:class label-cls})
        _          (.appendChild label-el
                                 (u/set-text!
                                  (u/el :span {:class "inspector-data-field-head"})
                                  head))
        _          (when summary
                     (.appendChild label-el
                                   (u/set-text!
                                    (u/el :span {:class "inspector-data-field-summary"})
                                    summary)))
        of-group   (:of-group fd)
        target     (when of-group
                     (actions/group-by-name (:document @state/app-state) of-group))
        expanded?  (and target (seeds-expanded? node fd))
        container  (u/el :div {:class "inspector-field-def"})]
    (when target
      (let [expand-btn (u/set-text!
                        (u/el :x-button {:variant "ghost" :size "sm"
                                         :class "inspector-seed-toggle"})
                        (if expanded? "▾" "▸"))]
        (u/on! expand-btn :press
               (fn [_] (toggle-seeds-expanded! node fd)))
        (.appendChild header expand-btn)))
    (.appendChild header label-el)
    (when-not locked?
      (let [remove-btn (u/set-text!
                        (u/el :x-button {:variant "ghost" :size "sm"
                                         :class "inspector-bind-btn"})
                        "×")]
        (u/on! remove-btn :press (fn [_] (commit-remove-field! (:id node) idx)))
        (.appendChild header remove-btn)))
    (.appendChild container header)
    (when (and target expanded?)
      (.appendChild container
                    (build-seed-records-editor node target fd)))
    container))

(defn- numeric-fields-of-group
  "Stored (non-computed) numeric fields of the group named `gname`.
   Used by the sum-of project-field picker."
  [doc gname]
  (when-let [g (actions/group-by-name doc gname)]
    (->> (:fields g)
         (remove actions/computed?)
         (filter #(= :number (:type %))))))

(defn- field-of-group-name
  "If the field-def is a collection pointing at a named group, return
   that group-name. Otherwise nil."
  [_doc fname group-node]
  (when-let [fd (first (filter #(= fname (:name %)) (:fields group-node)))]
    (:of-group fd)))

;; --- add-field form helpers -----------------------------------------------
;; The pickers are the source of truth. Every predicate / rebuild /
;; commit reads `.-value` / `.-checked` off the DOM element at the
;; moment it needs the value — no shadow atoms, no reset! bookkeeping.

(defn- non-empty-value
  "Current value of `el`, or nil when blank/undefined."
  [^js el]
  (let [v (.-value el)]
    (when (and v (not= "" v)) v)))

(defn- kw-value
  "Current value of `el` as a keyword, or nil when blank."
  [^js el]
  (when-let [v (non-empty-value el)] (keyword v)))

(defn- populate-select!
  "Clear `sel` and append one `{:value :label}` option per row,
   preceded by a placeholder option when `placeholder` is non-nil.
   `rows` may be nil / empty. The host `value` attribute is reset
   to \"\" so a stale selection from a previous population is not
   carried across the rebuild — callers that want to pre-pick can
   `(u/set-attr! sel :value …)` afterwards."
  [^js sel placeholder rows]
  (u/set-text! sel "")
  (.setAttribute sel "value" "")
  (when placeholder
    (.appendChild sel
                  (u/set-text! (u/el :option {:value ""}) placeholder)))
  (doseq [{:keys [value label]} rows]
    (let [o (u/el :option {:value value})]
      (u/set-text! o (or label value))
      (.appendChild sel o))))

(defn- on-select-change!
  "Install a `select-change` handler on an `x-select` that first
   mirrors `event.detail.value` onto the host's `value` attribute
   (x-select does NOT reflect user picks onto its own attribute —
   it only fires the event), then runs `f` with the event. Without
   this mirror, later `.-value` reads would see the attribute from
   form construction, not the live pick."
  [^js sel f]
  (u/on! sel :select-change
         (fn [^js e]
           (.setAttribute sel "value" (or (some-> e .-detail .-value) ""))
           (f e))))

(defn- group-option-rows
  "Option rows for a picker-ready group seq (expects `:owner-name`)."
  [groups]
  (for [{:keys [owner-name]} groups :when owner-name]
    {:value owner-name}))

(defn- local-field-option-rows
  "Option rows built from the fields of `node` matched by `pred`."
  [node pred]
  (for [{nm :name :as fd} (:fields node)
        :when (pred fd)]
    {:value (cljs.core/name nm)}))

(defn- rebuild-project-options!
  "Populate `project-sel` with the numeric fields of the group pointed
   at by `src-sel`'s current :of-group."
  [^js project-sel doc node ^js src-sel]
  (populate-select!
   project-sel "— field —"
   (when-let [sog (when-let [s (non-empty-value src-sel)]
                    (field-of-group-name doc (keyword s) node))]
     (for [{nm :name} (numeric-fields-of-group doc sog)]
       {:value (cljs.core/name nm)}))))

(defn- rebuild-jt-match-options!
  "Populate `jt-match-sel` with the non-computed fields of the group
   selected in `jt-group-sel`."
  [^js jt-match-sel doc ^js jt-group-sel]
  (populate-select!
   jt-match-sel "— match field —"
   (when-let [gname (non-empty-value jt-group-sel)]
     (when-let [g (actions/group-by-name doc gname)]
       (for [{nm :name :as fd} (:fields g)
             :when (not (actions/computed? fd))]
         {:value (cljs.core/name nm)})))))

(defn- rebuild-fb-match-options!
  "Populate `fb-match-sel` with the non-computed fields of the
   template group pointed at by `src-sel`'s :of-group."
  [^js fb-match-sel doc node ^js src-sel]
  (populate-select!
   fb-match-sel "— match field —"
   (when-let [sog (when-let [s (non-empty-value src-sel)]
                    (field-of-group-name doc (keyword s) node))]
     (when-let [g (actions/group-by-name doc sog)]
       (for [{nm :name :as fd} (:fields g)
             :when (not (actions/computed? fd))]
         {:value (cljs.core/name nm)})))))

(defn- update-form-visibility!
  "Show / hide rows based on the current computed? / type / op / src
   picker values. Reads everything directly from the DOM."
  [rows ^js type-sel ^js computed-sw ^js op-sel ^js src-sel doc node]
  (let [show!      (fn [^js row show?]
                     (set! (.. row -style -display) (if show? "" "none")))
        computed?  (boolean (.-checked computed-sw))
        vector?    (= "vector" (.-value type-sel))
        op         (kw-value op-sel)
        src-of-grp (when-let [s (non-empty-value src-sel)]
                     (field-of-group-name doc (keyword s) node))
        {:keys [default-row op-row src-row of-group-row project-row
                jt-group-row jt-match-row fb-match-row fb-search-row
                fb-kind-row]} rows]
    (show! default-row   (not computed?))
    (show! op-row        computed?)
    (show! src-row       computed?)
    (show! of-group-row  (and vector? (not computed?)))
    (show! project-row   (and computed? (= op :sum-of) (some? src-of-grp)))
    (show! jt-group-row  (and computed? (= op :join-on)))
    (show! jt-match-row  (and computed? (= op :join-on)))
    (show! fb-match-row  (and computed? (= op :filter-by)))
    (show! fb-search-row (and computed? (= op :filter-by)))
    (show! fb-kind-row   (and computed? (= op :filter-by)))))

(defn- stored-field-default
  "Parse a raw input string as the default value for a stored field of
   `type-kw`. Pure."
  [type-kw raw]
  (case type-kw
    :number  (let [p (js/parseFloat (or raw "0"))]
               (if (js/isNaN p) 0 p))
    :boolean (= "true" raw)
    :keyword (when (and raw (not= "" raw)) (keyword raw))
    :vector  []
    (or raw "")))

(defn- computed-map
  "Assemble the `:computed` sub-map from picker values already
   normalised into keywords / strings. Returns nil when the chosen op
   needs picker state that isn't filled in. Pure."
  [op-k src-k proj-k jtg-str jtm-k fbm-k fbs-k]
  (when (and op-k src-k)
    (let [base {:operation op-k :source-field src-k}]
      (case op-k
        :sum-of    (cond-> base
                     proj-k (assoc :project-field proj-k))
        :join-on   (when (and jtg-str jtm-k)
                     (assoc base :join-target
                            {:group-name  jtg-str
                             :match-field jtm-k}))
        :filter-by (when (and fbm-k fbs-k)
                     (assoc base :filter-spec
                            {:search-field fbs-k
                             :match-field  fbm-k
                             :match-kind   :contains-ci}))
        base))))

(defn- commit-new-field!
  "Read every picker, assemble the field-def, and commit it. Returns
   truthy on commit, nil on refusal (blank name, name collision, or
   required picker missing)."
  [node doc ^js name-in ^js default-in ^js type-sel ^js computed-sw
   ^js op-sel ^js src-sel ^js project-sel ^js of-group-sel
   ^js jt-group-sel ^js jt-match-sel ^js fb-match-sel ^js fb-search-sel]
  (when-let [nm (read-event-value* name-in)]
    (when (not= "" nm)
      (let [kw       (keyword nm)
            existing (into #{} (map :name) (:fields node))]
        (when-not (contains? existing kw)
          (let [type-kw   (or (kw-value type-sel) :string)
                computed? (boolean (.-checked computed-sw))]
            (if computed?
              (when-let [c (computed-map
                            (kw-value op-sel)
                            (kw-value src-sel)
                            (kw-value project-sel)
                            (non-empty-value jt-group-sel)
                            (kw-value jt-match-sel)
                            (kw-value fb-match-sel)
                            (kw-value fb-search-sel))]
                (let [op         (:operation c)
                      src        (:source-field c)
                      ;; :filter-by always produces a vector of the
                      ;; template's records — the stored field must be
                      ;; :vector so the template-instance source picker
                      ;; (which filters on :type :vector) offers it.
                      field-type (if (= op :filter-by) :vector type-kw)
                      of-group   (when (= op :filter-by)
                                   (field-of-group-name doc src node))]
                  (commit-add-field! (:id node)
                                     (cond-> {:name kw :type field-type :computed c}
                                       (= op :filter-by) (assoc :of-group of-group)))
                  (u/set-attr! name-in :value "")
                  true))
              (let [default  (stored-field-default
                              type-kw (read-event-value* default-in))
                    of-group (non-empty-value of-group-sel)
                    fd       (cond-> {:name kw :type type-kw :default default}
                               (and (= type-kw :vector) of-group)
                               (assoc :of-group of-group))]
                (commit-add-field! (:id node) fd)
                (u/set-attr! name-in :value "")
                (u/set-attr! default-in :value "")
                true))))))))

(defn- build-add-field-widgets
  "Construct the bag of DOM widgets the add-field form is wired
   over. Pure construction — no options populated, no handlers
   installed. Returns the map every other stage destructures."
  [doc node]
  (let [other-groups   (->> (actions/field-groups-for-picker doc (:name node))
                            (remove :enclosing?))
        name-in        (u/el :x-search-field
                             {:class "inspector-bind-input"
                              :placeholder "field name"})
        default-in     (u/el :x-search-field
                             {:class "inspector-bind-input"
                              :placeholder "default value"})
        type-sel       (u/el :x-select {:class "inspector-field-widget"})
        computed-sw    (u/el :x-switch {:class "inspector-field-widget"})
        op-sel         (u/el :x-select {:class "inspector-field-widget"})
        src-sel        (u/el :x-select {:class "inspector-field-widget"})
        project-sel    (u/el :x-select {:class "inspector-field-widget"})
        of-group-sel   (u/el :x-select {:class "inspector-field-widget"})
        jt-group-sel   (u/el :x-select {:class "inspector-field-widget"})
        jt-match-sel   (u/el :x-select {:class "inspector-field-widget"})
        fb-match-sel   (u/el :x-select {:class "inspector-field-widget"})
        fb-search-sel  (u/el :x-select {:class "inspector-field-widget"})
        fb-kind-widget (u/set-text! (u/el :span {:class "inspector-empty"})
                                    "case-insensitive contains")
        add-btn        (u/set-text!
                        (u/el :x-button {:variant   "secondary"
                                         :size      "sm"
                                         :data-tour "add-field"})
                        "Add field")
        rows           {:default-row   (field-row "default" default-in)
                        :op-row        (field-row "operation" op-sel)
                        :src-row       (field-row "source field" src-sel)
                        :of-group-row  (field-row "of group" of-group-sel)
                        :project-row   (field-row "project field" project-sel)
                        :jt-group-row  (field-row "against" jt-group-sel)
                        :jt-match-row  (field-row "match on" jt-match-sel)
                        :fb-match-row  (field-row "match on" fb-match-sel)
                        :fb-search-row (field-row "search field" fb-search-sel)
                        :fb-kind-row   (field-row "how" fb-kind-widget)}]
    {:doc doc :node node :other-groups other-groups
     :name-in name-in :default-in default-in :type-sel type-sel
     :computed-sw computed-sw :op-sel op-sel :src-sel src-sel
     :project-sel project-sel :of-group-sel of-group-sel
     :jt-group-sel jt-group-sel :jt-match-sel jt-match-sel
     :fb-match-sel fb-match-sel :fb-search-sel fb-search-sel
     :fb-kind-widget fb-kind-widget :add-btn add-btn :rows rows}))

(defn- populate-add-field-options!
  "Populate the static option lists on each select. Dependent picks
   (project-field, jt-match, fb-match) get their starting populate
   too; their handlers refresh them as the user adjusts the source
   picks. Default `type=string` is set last so the default-value row
   is visible from first paint."
  [{:keys [doc node other-groups type-sel of-group-sel op-sel src-sel
           jt-group-sel fb-search-sel project-sel jt-match-sel fb-match-sel]}]
  (populate-select! type-sel "— type —"
                    (for [{:keys [id label]} field-types]
                      {:value (cljs.core/name id) :label label}))
  (populate-select! of-group-sel "— of group —"
                    (group-option-rows other-groups))
  (populate-select! op-sel "— operation —"
                    (for [{:keys [id label]} computed-op-options]
                      {:value (cljs.core/name id) :label label}))
  (populate-select! src-sel "— source field —"
                    (local-field-option-rows node #(not (actions/computed? %))))
  (populate-select! jt-group-sel "— target group —"
                    (group-option-rows other-groups))
  (populate-select! fb-search-sel "— search field —"
                    (local-field-option-rows node
                                             #(and (= :string (:type %))
                                                   (not (actions/computed? %))
                                                   (not (:locked? %)))))
  (rebuild-project-options! project-sel doc node src-sel)
  (rebuild-jt-match-options! jt-match-sel doc jt-group-sel)
  (rebuild-fb-match-options! fb-match-sel doc node src-sel)
  (u/set-attr! type-sel :value "string"))

(defn- wire-add-field-handlers!
  "Install change/press handlers and trigger the initial visibility
   refresh. `on-select-change!` mirrors each user pick back onto the
   select's `value` attribute (x-select doesn't) so subsequent
   `.-value` reads inside the visibility refresh and final commit
   see the live selection — pickers without extra logic still get a
   mirror handler with a no-op body."
  [{:keys [doc node rows type-sel computed-sw op-sel src-sel
           project-sel of-group-sel jt-group-sel jt-match-sel
           fb-match-sel fb-search-sel name-in default-in add-btn]}]
  (let [refresh! (fn []
                   (update-form-visibility!
                    rows type-sel computed-sw op-sel src-sel doc node))]
    (on-select-change! type-sel      (fn [_] (refresh!)))
    (on-select-change! op-sel        (fn [_] (refresh!)))
    (on-select-change! src-sel
                       (fn [_]
                         (rebuild-project-options! project-sel doc node src-sel)
                         (rebuild-fb-match-options! fb-match-sel doc node src-sel)
                         (refresh!)))
    (on-select-change! jt-group-sel
                       (fn [_]
                         (rebuild-jt-match-options! jt-match-sel doc jt-group-sel)))
    (on-select-change! of-group-sel  (fn [_] nil))
    (on-select-change! project-sel   (fn [_] nil))
    (on-select-change! jt-match-sel  (fn [_] nil))
    (on-select-change! fb-match-sel  (fn [_] nil))
    (on-select-change! fb-search-sel (fn [_] nil))
    (u/on! computed-sw :x-switch-change (fn [^js _e] (refresh!)))
    (refresh!)
    (u/on! add-btn :press
           (fn [_]
             (commit-new-field!
              node doc name-in default-in type-sel computed-sw
              op-sel src-sel project-sel of-group-sel
              jt-group-sel jt-match-sel fb-match-sel fb-search-sel)))))

(defn- assemble-add-field-form
  "Append the widgets in display order under a fresh form container."
  [{:keys [name-in type-sel computed-sw rows add-btn]}]
  (let [form (u/el :div {:class "inspector-event-form"})]
    (.appendChild form (field-row "name" name-in))
    (.appendChild form (field-row "type" type-sel))
    (.appendChild form (field-row "computed" computed-sw))
    (.appendChild form (:default-row rows))
    (.appendChild form (:of-group-row rows))
    (.appendChild form (:op-row rows))
    (.appendChild form (:src-row rows))
    (.appendChild form (:project-row rows))
    (.appendChild form (:jt-group-row rows))
    (.appendChild form (:jt-match-row rows))
    (.appendChild form (:fb-match-row rows))
    (.appendChild form (:fb-search-row rows))
    (.appendChild form (:fb-kind-row rows))
    (.appendChild form add-btn)
    form))

(defn- build-add-field-form [node]
  (let [widgets (build-add-field-widgets (:document @state/app-state) node)]
    (populate-add-field-options! widgets)
    (wire-add-field-handlers! widgets)
    (assemble-add-field-form widgets)))

(defn- fields-section [{:keys [node]}]
  (when (registry/container? (:tag node))
    (let [fields     (or (:fields node) [])
          field-rows (map-indexed
                      (fn [idx fd] (build-field-def-row node fd idx))
                      fields)
          add-form   (build-add-field-form node)]
      (section "Fields"
               (remove nil?
                       (concat field-rows [add-form]))))))

;; --- template instance source picker ------------------------------------

(defn- collection-fields-targeting
  "Every collection field in the doc whose `:of-group` matches gname,
   returned as `[{:owner <group-name> :field <field-keyword>}, ...]`."
  [doc gname]
  (for [g (->> (m/walk-nodes doc)
               (filter #(and (:name %) (seq (:name %)))))
        fd (:fields g)
        :when (and (= :vector (:type fd))
                   (= gname (:of-group fd)))]
    {:owner (:name g)
     :field (:name fd)}))

(defn- commit-source-field! [node-id fkw]
  (state/commit! (ops/set-source-field (:document @state/app-state) node-id fkw)))

(defn- commit-source-sub! [node-id skw]
  (state/commit! (ops/set-source-sub (:document @state/app-state) node-id skw)))

(defn- build-source-field-row [node candidates]
  (let [sel     (u/el :x-select {:class "inspector-field-widget"})
        current (some-> (:source-field node) cljs.core/name)]
    (.appendChild sel
                  (u/set-text! (u/el :option {:value ""}) "— none —"))
    (doseq [{:keys [owner field]} candidates
            :let [fname (cljs.core/name field)
                  label (str owner " / " fname)]]
      (let [o (u/el :option {:value fname})]
        (u/set-text! o label)
        (.appendChild sel o)))
    (when current (set! (.-value sel) current))
    (u/on! sel :select-change
           (fn [^js e]
             (let [v (read-event-value e)]
               (commit-source-field! (:id node)
                                     (when (and v (not= "" v)) (keyword v))))))
    (field-row "source field" sel)))

(defn- build-source-sub-row [node]
  (let [in    (u/el :x-search-field
                    {:class "inspector-bind-input"
                     :placeholder ":app.cart.subs/cart-with-products"})
        curr  (some-> (:source-sub node) str)]
    (when curr (u/set-attr! in :value curr))
    (u/on! in :input
           (fn [^js e]
             (let [v (read-event-value e)
                   kw (when (and v (not= "" v))
                        (try (keyword (subs v 1)) (catch :default _ nil)))]
               (when (or (nil? v) (= "" v) kw)
                 (commit-source-sub! (:id node) kw)))))
    (field-row "source sub" in)))

(defn- template-source-section [{:keys [node]}]
  (let [doc        (:document @state/app-state)
        gname      (:name node)
        candidates (when gname (collection-fields-targeting doc gname))]
    (when (and gname (or (seq candidates)
                         (:source-field node)
                         (:source-sub node)))
      (section "Rendered from"
               [(build-source-field-row node candidates)
                (build-source-sub-row node)]))))

;; --- triggers (per-node DOM event → action dispatch) ---------------------

;; Action operations that require a payload record at dispatch time.
;; Everything else (toggle/increment/decrement/clear) is payload-free.
(def ^:private payload-required-ops
  #{:set :add :remove})

(defn- commit-add-trigger! [node-id t]
  (state/commit! (ops/add-trigger (:document @state/app-state) node-id t)))

(defn- commit-remove-trigger! [node-id idx]
  (state/commit! (ops/remove-trigger (:document @state/app-state) node-id idx)))

(defn- action-ref-label
  "Turn a fully qualified action-ref like :app.cart.events/add-to-cart
   into the short `group/action-name` form displayed in UI rows."
  [aref]
  (let [ns  (namespace aref)
        nm  (name aref)
        ns* (if (str/ends-with? ns ".events")
              (subs ns 0 (- (count ns) (count ".events")))
              ns)
        grp (let [i (.lastIndexOf ns* ".")]
              (if (neg? i) ns* (subs ns* (inc i))))]
    (str grp "/" nm)))

(defn- action-needs-record?
  "True when the action's operation requires a record payload at
   dispatch time. Used to dim actions whose implicit payload can't be
   resolved at the selected node."
  [action-entry]
  (contains? payload-required-ops (:operation action-entry)))

(defn- build-action-picker-panel
  "Grouped picker panel for selecting an action-ref for an event row.
   Mirrors the bind picker: search, sections per group, Setters
   subsection dimmed, actions dimmed (with tooltip) when their
   implicit payload can't be resolved at the selected node. Calls
   `on-pick` with the chosen action-ref keyword."
  [all-actions tmpl-name on-pick]
  (let [wrap    (u/el :div {:class "inspector-bind-picker"})
        search  (u/el :x-search-field
                      {:class       "inspector-bind-input"
                       :placeholder "search actions…"})
        body    (u/el :div {:class "inspector-bind-picker-body"})
        by-grp  (group-by :group-name all-actions)
        grp-ord (sort (keys by-grp))
        render! (fn [query]
                  (.replaceChildren body)
                  (doseq [gname grp-ord
                          :let [entries  (get by-grp gname)
                                declared (filter #(= :declared (:source %)) entries)
                                setters  (filter #(= :auto-setter (:source %)) entries)
                                f        (fn [xs]
                                           (if (seq query)
                                             (filter #(str/includes?
                                                       (str/lower-case (name (:action-name %)))
                                                       query)
                                                     xs)
                                             xs))
                                d-match  (f declared)
                                s-match  (f setters)]
                          :when (or (seq d-match) (seq s-match))]
                    (let [header (u/set-text!
                                  (u/el :div {:class "inspector-bind-section"})
                                  gname)]
                      (.appendChild body header)
                      (doseq [entry d-match]
                        (let [needs?   (action-needs-record? entry)
                              gap?     (and needs? (nil? tmpl-name))
                              cls      (cond-> "inspector-bind-suggestion"
                                         gap? (str " inspector-bind-suggestion--dim"))
                              item     (u/set-text!
                                        (u/el :div {:class cls
                                                    :title (when gap?
                                                             "needs an enclosing record ancestor")})
                                        (name (:action-name entry)))]
                          (u/on! item :click (fn [_] (on-pick (:action-ref entry))))
                          (.appendChild body item)))
                      (when (seq s-match)
                        (let [sub (u/set-text!
                                   (u/el :div {:class "inspector-bind-subsection"})
                                   "Setters")]
                          (.appendChild body sub))
                        (doseq [entry s-match]
                          (let [needs?   (action-needs-record? entry)
                                gap?     (and needs? (nil? tmpl-name))
                                cls      (cond-> "inspector-bind-suggestion inspector-bind-suggestion--setter"
                                           gap? (str " inspector-bind-suggestion--dim"))
                                item     (u/set-text!
                                          (u/el :div {:class cls
                                                      :title (when gap?
                                                               "needs an enclosing record ancestor")})
                                          (name (:action-name entry)))]
                            (u/on! item :click (fn [_] (on-pick (:action-ref entry))))
                            (.appendChild body item)))))))]
    (render! "")
    (u/on! search :x-search-field-input
           (fn [^js e]
             (render! (str/lower-case (or (read-event-value e) "")))))
    (.appendChild wrap search)
    (.appendChild wrap body)
    wrap))

(defn- build-event-row
  "Single row for one DOM event on the selected node. Shows the
   currently-bound action (if any) with a ✕ to unbind, or an action
   picker button to bind. A passive hint below reports the implicit
   payload the dispatch will receive."
  [node event-name _doc all-actions tmpl-name]
  (let [existing-idx (first (keep-indexed (fn [i t]
                                            (when (= event-name (:trigger t)) i))
                                          (or (:events node) [])))
        existing    (when existing-idx
                      (nth (:events node) existing-idx))
        wrap        (u/el :div {:class "inspector-event-row-wrap"})
        row         (u/el :div {:class "inspector-event-row"})
        label-el    (u/set-text! (u/el :span {:class "inspector-event-name"})
                                 event-name)
        hint        (u/el :div {:class "inspector-event-hint"})]
    (.appendChild row label-el)
    (if existing
      (let [picked (u/set-text!
                    (u/el :span {:class "inspector-bind-field"})
                    (str "→ " (action-ref-label (:action-ref existing))))
            unbind (u/set-text!
                    (u/el :x-button {:variant "ghost" :size "sm"
                                     :class "inspector-bind-btn"})
                    "×")]
        (u/on! unbind :press
               (fn [_] (commit-remove-trigger! (:id node) existing-idx)))
        (.appendChild row picked)
        (.appendChild row unbind)
        (u/set-text! hint
                     (if tmpl-name
                       (str "payload: enclosing " tmpl-name)
                       "payload: none")))
      (let [btn (u/set-text!
                 (u/el :x-button {:variant "ghost" :size "sm"
                                  :class "inspector-bind-btn"
                                  :title "Pick an action"
                                  :data-tour "add-event"})
                 "🔗")]
        (u/on! btn :press
               (fn [_]
                 (.replaceChildren row)
                 (.appendChild row label-el)
                 (let [pick! (fn [action-ref]
                               (commit-add-trigger! (:id node)
                                                    {:trigger event-name :action-ref action-ref}))
                       panel (build-action-picker-panel all-actions tmpl-name pick!)]
                   (.appendChild row panel))))
        (.appendChild row btn)
        (u/set-text! hint
                     (if tmpl-name
                       (str "payload: enclosing " tmpl-name " (when action needs one)")
                       "payload: none (no template ancestor)"))))
    (.appendChild wrap row)
    (.appendChild wrap hint)
    wrap))

(defn- triggers-section [{:keys [node]}]
  (when-let [events (meta-events/events-for (:tag node))]
    (let [doc         (:document @state/app-state)
          tmpl        (enclosing-template-group doc (:id node))
          tmpl-name   (:name tmpl)
          all-actions (actions/all-actions doc "app")
          rows        (for [ev events]
                        (build-event-row node ev doc all-actions tmpl-name))]
      (section "Events" rows))))

;; --- actions (group-level declarations) ----------------------------------

(def ^:private action-operations
  "Every action op the framework understands. Pick one paired with a
   compatible target field: scalar ops on scalar/computed fields,
   :add/:remove on vector fields."
  [{:id :set        :label "set"}
   {:id :toggle     :label "toggle"}
   {:id :increment  :label "increment"}
   {:id :decrement  :label "decrement"}
   {:id :clear      :label "clear"}
   {:id :add        :label "add (conj)"}
   {:id :remove     :label "remove (filter out)"}])

(defn- commit-add-action! [node-id action]
  (state/commit! (ops/add-action (:document @state/app-state) node-id action)))

(defn- commit-remove-action! [node-id idx]
  (state/commit! (ops/remove-action (:document @state/app-state) node-id idx)))

(def ^:private operation-verb
  "English-verb phrasing for each operation. The verb is conjugated for
   an implied third-person subject (`<action-name>` adds to …) so the
   row reads like a sentence."
  {:add       "adds to"
   :remove    "removes from"
   :set       "sets"
   :toggle    "toggles"
   :increment "increments"
   :decrement "decrements"
   :clear     "clears"})

(defn- build-action-row [node a idx]
  (let [row        (u/el :div {:class "inspector-data-row"})
        verb       (get operation-verb (:operation a)
                        (field-name-str (:operation a)))
        label      (str (field-name-str (:name a))
                        " (" verb " :" (field-name-str (:target-field a)) ")")
        label-el   (u/set-text! (u/el :span {:class "inspector-data-field"})
                                label)
        remove-btn (u/set-text!
                    (u/el :x-button {:variant "ghost" :size "sm"
                                     :class "inspector-bind-btn"})
                    "×")]
    (u/on! remove-btn :press (fn [_] (commit-remove-action! (:id node) idx)))
    (.appendChild row label-el)
    (.appendChild row remove-btn)
    row))

(defn- build-add-action-form [node]
  (let [form          (u/el :div {:class "inspector-event-form"})
        name-in       (u/el :x-search-field
                            {:class "inspector-bind-input"
                             :placeholder "action name, e.g. add-to-cart"})
        op-sel        (u/el :x-select {:class "inspector-field-widget"})
        target-sel    (u/el :x-select {:class "inspector-field-widget"})
        ;; Actions can only write to STORED or COLLECTION fields —
        ;; computed fields are derived and excluded from the picker.
        target-fields (remove actions/computed? (:fields node))
        single-field? (= 1 (count target-fields))
        add-btn       (u/set-text!
                       (u/el :x-button {:variant "secondary" :size "sm"})
                       "Add action")
        target-rows   (for [{nm :name} target-fields]
                        {:value (cljs.core/name nm)})]
    (populate-select! op-sel "— operation —"
                      (for [{:keys [id label]} action-operations]
                        {:value (cljs.core/name id) :label label}))
    ;; Single target → no placeholder, pre-select that one option so
    ;; .-value reads the auto-pick at commit time. Multi-target →
    ;; placeholder + options, user must pick.
    (if single-field?
      (do (populate-select! target-sel nil target-rows)
          (u/set-attr! target-sel :value
                       (cljs.core/name (:name (first target-fields)))))
      (populate-select! target-sel "— target field —" target-rows))
    ;; Mirror picks onto the host value attribute (x-select doesn't
    ;; reflect select-change back automatically — see Audit 03).
    (on-select-change! op-sel     (fn [_] nil))
    (on-select-change! target-sel (fn [_] nil))
    (u/on! add-btn :press
           (fn [_]
             (let [n   (read-event-value* name-in)
                   op  (kw-value op-sel)
                   tgt (kw-value target-sel)]
               (when (and n (not= "" n) op tgt)
                 (commit-add-action! (:id node)
                                     {:name (keyword n) :operation op :target-field tgt})
                 (u/set-attr! name-in :value "")
                 (u/set-attr! op-sel :value "")
                 (when-not single-field?
                   (u/set-attr! target-sel :value ""))))))
    (.appendChild form (field-row "name" name-in))
    (.appendChild form (field-row "operation" op-sel))
    (.appendChild form (field-row "target field" target-sel))
    (.appendChild form add-btn)
    form))

(defn- group-actions-section [{:keys [node]}]
  (when (and (registry/container? (:tag node))
             (some? (:name node))
             (seq (:fields node)))
    (let [actions-vec (or (:actions node) [])
          rows        (map-indexed
                       (fn [idx a] (build-action-row node a idx))
                       actions-vec)
          add-form    (build-add-action-form node)]
      (section "Actions" (concat rows [add-form])))))

(def ^:private direction-verb
  {:read       "shows"
   :write      "updates"
   :read-write "syncs with"})

(defn- field-ref-label
  "Turn a field keyword + its owner-name into `owner / field`.
   Falls back to just the field name if owner is unknown."
  [field owner-name]
  (let [fname (name field)]
    (if (and owner-name (not= "" owner-name))
      (str owner-name " / " fname)
      fname)))

(defn- binding-sentence
  "`<prop> <verb> <owner / field>`."
  [prop-name field direction owner-name]
  (str prop-name " "
       (get direction-verb direction (name direction))
       " "
       (field-ref-label field owner-name)))

(defn- field-def-owner-index
  "Map of field-keyword -> declaring group's owner-name, derived from
   the `:source :field-def` entries of `collect-all-fields`. Bindings
   reference a field by keyword; the declaring group is what we want
   to show in the `owner / field` label."
  [all-fields]
  (into {}
        (for [{:keys [field owner-name source]} all-fields
              :when (= source :field-def)]
          [field owner-name])))

(defn- data-summary-section [{:keys [node]} all-fields]
  (when-let [bindings (seq (:bindings node))]
    (let [owner-of (field-def-owner-index all-fields)
          rows (for [[prop-name {:keys [field direction]}] bindings]
                 (u/el :div {:class "inspector-data-row"}
                       [(u/set-text!
                         (u/el :span {:class "inspector-data-field"})
                         (binding-sentence prop-name field direction
                                           (get owner-of field)))]))]
      (section "Data bindings" rows))))

(defn- render!
  [^js host-el model]
  (let [sections (cond
                   (and model (:multi model))
                   [(multi-attributes-section model)]

                   model
                   (let [doc        (:document @state/app-state)
                         all-fields (collect-all-fields doc)
                         t          (text-section model)
                         cv         (css-vars-section model)
                         flds       (fields-section model)
                         tpl-src    (template-source-section model)
                         grp-act    (group-actions-section model)
                         trg        (triggers-section model)
                         ds         (data-summary-section model all-fields)
                         raw-html?  (boolean (:raw-html-slot? (:meta model)))
                         slot-block (if raw-html?
                                      (inner-html-section model)
                                      (slots-section model))]
                     (cond-> [(header model) (attributes-section model all-fields)]
                       t            (conj t)
                       :always      (conj slot-block)
                       :always      (conj (layout-section model))
                       cv           (conj cv)
                       flds         (conj flds)
                       tpl-src      (conj tpl-src)
                       grp-act      (conj grp-act)
                       trg          (conj trg)
                       ds           (conj ds)))
                   [(empty-view)])
        ^js scroll-el (or (.-parentElement host-el) host-el)
        scroll-top    (.-scrollTop scroll-el)]
    (.replaceChildren host-el)
    (doseq [s sections] (.appendChild host-el s))
    (set! (.-scrollTop scroll-el) scroll-top)))

(defn create
  "Build the inspector panel. Returns the panel element ready to place
   into the chrome. Re-renders on every :document or :selection change."
  []
  (let [body    (u/el :div {:class "inspector-body"})
        panel   (u/el :div {:id    "bareforge-inspector"
                            :class "panel panel-inspector"}
                      [(u/set-text! (u/el :div {:class "inspector-label"}) "Inspector")
                       body])]
    (render! body (inspector-model @state/app-state))
    (add-watch state/app-state ::inspector
               (fn [_ _ old-state new-state]
                 (let [sel-changed? (not= (:selection old-state)
                                          (:selection new-state))
                       doc-changed? (not= (:document old-state)
                                          (:document new-state))
                       ui-changed?  (or (not= (get-in old-state [:ui :inspector-collapsed])
                                              (get-in new-state [:ui :inspector-collapsed]))
                                        (not= (get-in old-state [:ui :expanded-seeds])
                                              (get-in new-state [:ui :expanded-seeds])))
                       new-model    (when (or sel-changed? doc-changed? ui-changed?)
                                      (inspector-model new-state))]
                   (cond
                     (or sel-changed? ui-changed?)
                     (render! body new-model)

                     (and doc-changed? (nil? new-model))
                     (render! body nil)

                     ;; Multi-select: doc changed (most likely from
                     ;; the user editing a shared-attr field). Skip
                     ;; the rebuild — every keystroke would otherwise
                     ;; throw away the focused input. The committed
                     ;; values are correct in the doc; the panel's
                     ;; visual "Mixed" markers may lag until the user
                     ;; re-enters multi-select, which is acceptable.
                     (and doc-changed? (:multi new-model))
                     nil

                     doc-changed?
                     (let [old-model   (inspector-model old-state)
                           old-node    (:node old-model)
                           new-node    (:node new-model)
                           ;; Compare fields with auto-locked entries
                           ;; stripped AND vector :defaults reduced to
                           ;; their count. Together these keep the
                           ;; panel stable while the user types into
                           ;; the Name input (auto-::id insertion) or
                           ;; a seed-record cell (intra-record edit),
                           ;; while still rebuilding when fields or
                           ;; record counts actually change.
                           struct-shape #(cond-> (dissoc % :default :locked?)
                                           (vector? (:default %))
                                           (assoc ::default-count (count (:default %))))
                           user-fields #(->> (:fields %)
                                             (remove :locked?)
                                             (mapv struct-shape))
                           structural? (or (nil? old-node)
                                           (not= (user-fields old-node) (user-fields new-node))
                                           (not= (:actions old-node) (:actions new-node))
                                           (not= (:bindings old-node) (:bindings new-node))
                                           (not= (:events old-node) (:events new-node))
                                           (not= (:text-field old-node) (:text-field new-node))
                                           (not= (:source-field old-node) (:source-field new-node))
                                           (not= (:source-sub old-node) (:source-sub new-node)))]
                       (if structural?
                         (render! body new-model)
                         (update-fields-in-place! body new-node)))))))
    panel))
