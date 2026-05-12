(ns bareforge.ui.inspector
  "Right-hand inspector panel. Auto-generates editable form fields for
   the currently-selected node by consuming `meta/registry/get-meta`.

   Pure helpers (inspector-model, editor-spec, current-value, …) live
   in `bareforge.ui.inspector.model` and are unit-tested. The effectful
   `create` here builds the DOM, installs a watcher on
   `:document`/`:selection`, and wires each editor's input event back
   to `doc.ops/*` plus `state/commit!`.

   v1 scope: attributes section (full editing) + slots section
   (read-only summary) + layout section (read-only placement).
   Drag-drop slot targets land in build-order step 9."
  (:require [bareforge.doc.actions :as actions]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.meta.events :as meta-events]
            [bareforge.meta.registry :as registry]
            [bareforge.render.canvas :as canvas]
            [bareforge.state :as state]
            [bareforge.ui.inspector.model :as model]
            [bareforge.ui.inspector.scrub :as scrub]
            [bareforge.ui.inspector.tokens :as tokens]
            [bareforge.ui.state-panel :as state-panel]
            [bareforge.util.coerce :as c]
            [bareforge.util.dom :as u]
            [clojure.string :as str]))

;; --- effectful: editor widgets -------------------------------------------

(declare build-editor-row)

(defn- search-field
  "Wrapper around `(u/el :x-search-field attrs)` that also blocks the
   browser's native Escape-clear behaviour on the inner
   `<input type=\"search\">`. Without this, pressing Escape inside any
   inspector text input clears the field AND fires
   `x-search-field-input` with `value=\"\"`, which the inspector
   commits to the doc — silently erasing the user's text on every
   accidental Esc press. Returns the x-search-field element."
  ^js [attrs]
  (let [^js el (u/el :x-search-field attrs)]
    (.addEventListener el "keydown"
                       (fn [^js e]
                         (when (= "Escape" (.-key e))
                           (.preventDefault e))))
    el))

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

(defn- build-widget-shell
  "Shared shape behind every `build-*` row builder: create the widget
   element, tag it for the test harness, set the current value on the
   widget, and wire its change event to a commit. Returns the element
   so widget-specific specialisations (enum's `<option>` children,
   search-field's datalist / scrub) can attach to it without forking
   the shared boilerplate.

   `spec` keys:
     :widget-tag    — keyword tag passed to `u/el` (or `search-field`
                      when `:x-search-field`).
     :widget-attrs  — extra attrs merged over `{:class \"inspector-field-widget\"}`.
     :prop-kind     — string for `data-prop-kind` on the host.
     :transform     — optional transform keyword forwarded to
                      `tag-widget!` (only the search-field row uses it).
     :event         — event name to wire on the host.
     :value-attr    — attr to receive the current value. Defaults to
                      `:value`; widgets like `x-switch` use `:checked`
                      (set to the empty string when truthy).
     :event-reader  — `(fn [^js e] → v)`. Defaults to `read-event-value`.
     :commit-fn!    — `(fn [node-id prop-name v])` invoked with the
                      reader's output. The shell never calls
                      `commit-attr!` / `commit-prop!` directly so the
                      transform-aware search-field can wrap its own."
  [node prop {:keys [widget-tag widget-attrs prop-kind transform
                     event value-attr event-reader commit-fn!]}]
  (let [attrs   (merge {:class "inspector-field-widget"} widget-attrs)
        el      (-> (if (= :x-search-field widget-tag)
                      (search-field attrs)
                      (u/el widget-tag attrs))
                    (tag-widget! (:name prop) prop-kind transform))
        current (model/current-value node prop)
        reader  (or event-reader read-event-value)]
    (case (or value-attr :value)
      :checked (when current (u/set-attr! el :checked ""))
      :value   (when current (u/set-attr! el :value current)))
    (u/on! el event
           (fn [^js e]
             (commit-fn! (:id node) (:name prop) (reader e))))
    el))

(defn- build-boolean [node prop]
  (build-widget-shell node prop
                      {:widget-tag   :x-switch
                       :prop-kind    "boolean"
                       :event        :x-switch-change
                       :value-attr   :checked
                       :event-reader read-event-checked
                       :commit-fn!   commit-prop!}))

(defn- append-enum-options!
  "Append one `<option>` per `:choices` entry, marking the entry that
   matches `current` as `selected`. Separate fn so `build-enum` stays
   a thin spec call plus this small DOM-decoration step."
  [^js sel-el prop current]
  (doseq [choice (:choices prop)]
    (let [^js o (u/el :option {:value choice})]
      (u/set-text! o choice)
      (when (= choice current) (u/set-attr! o :selected ""))
      (.appendChild sel-el o)))
  sel-el)

(defn- build-enum [node prop]
  (-> (build-widget-shell node prop
                          {:widget-tag :x-select
                           :prop-kind  "enum"
                           :event      :select-change
                           :commit-fn! commit-attr!})
      (append-enum-options! prop (model/current-value node prop))))

(defn- build-text-area [node prop]
  (build-widget-shell node prop
                      {:widget-tag :x-text-area
                       :prop-kind  "text-long"
                       :event      :x-text-area-input
                       :commit-fn! commit-attr!}))

(defn- attach-numeric-scrub!
  "Wire the numeric-drag scrub gesture on a search-field configured
   for `:type \"number\"`. Reads the live attribute value through the
   model so a fresh / non-numeric attr starts the drag at 0 instead
   of silently doing nothing; commits coalesce after the first step."
  [^js el node prop]
  (let [node-id   (:id node)
        attr-name (:name prop)]
    (scrub/attach-scrub-meta!
     el
     {:read-fn    (fn []
                    (let [doc (:document @state/app-state)
                          n   (m/get-node doc node-id)]
                      (c/parse-number-or-zero (model/current-value n prop))))
      :commit-fn! (fn [new-val first?]
                    (let [doc  (:document @state/app-state)
                          doc' (ops/set-attr doc node-id attr-name (str new-val))]
                      (if first?
                        (state/commit! doc')
                        (state/commit-coalesced! doc'))))
      :step       1})))

(defn- build-search-field [node prop spec]
  (let [transform (:transform prop)
        numeric?  (= "number" (:type spec))
        ;; Surface BareDOM theme tokens via a native datalist when the
        ;; field is colour-shaped. The list attribute has to be on the
        ;; shadow inner `<input>`, not the custom-element host — see
        ;; `attach-datalist-to-shadow-input!`.
        color?    (= :color (:kind prop))
        node-id   (:id node)
        attr-name (:name prop)
        commit!   (fn [_id _name v]
                    (let [v (if transform
                              (model/transform-for-commit transform v)
                              v)]
                      (commit-attr! node-id attr-name v)))
        el        (build-widget-shell
                   node prop
                   {:widget-tag   :x-search-field
                    :widget-attrs (cond-> {}
                                    numeric? (assoc :type "number"))
                    :prop-kind    "text"
                    :transform    transform
                    :event        :x-search-field-input
                    :commit-fn!   commit!})]
    (when color?
      (tokens/attach-datalist-to-shadow-input! el tokens/color-datalist-id))
    (cond-> el
      numeric? (attach-numeric-scrub! node prop))))

(defn- build-widget [node prop]
  (let [spec (model/editor-spec prop)]
    (case (:kind spec)
      :boolean     (build-boolean node prop)
      :enum        (build-enum node prop)
      :string-long (build-text-area node prop)
      (build-search-field node prop spec))))

(defn render-field
  "Pure-ish: build a widget element from a `field-spec` data map.
   Calling out three concerns the audit's Step 6 wanted unbraided
   from each `build-*-field` closure:

     :read-fn / :read-path  — how to pull the current value from a
                              node. `:read-fn` is preferred when the
                              accessor is non-trivial; `:read-path`
                              shorthand resolves via `get-in`.
     :write-fn              — `(fn [doc node-id v] → doc')`. The
                              interpreter calls it through
                              `commit-with!` so the single-write
                              discipline stays grep-visible.
     :coerce-fn             — applied to the raw input value before
                              `:write-fn` sees it. Default `identity`;
                              point at one of `bareforge.util.coerce`'s
                              helpers (`nil-if-empty`,
                              `keyword-or-nil`, `parse-length-value`)
                              for the common shapes.

   Other spec keys: `:widget-tag :widget-attrs :prop-name :prop-kind
   :event` (per the existing `tag-widget!` / event-binding contract)
   and an optional `:datalist-id` for shadow-input autocomplete.
   `:event-value-reader` defaults to `read-event-value`; replace it
   with `read-event-checked` for boolean widgets that report
   `:detail.checked` instead of `:detail.value`.

   Public so unit tests can pin spec → element shape without going
   through one of the wrapping `build-*-field` helpers."
  [node {:keys [widget-tag widget-attrs prop-name prop-kind
                event read-fn read-path write-fn coerce-fn
                event-value-reader datalist-id]}]
  (let [coerce  (or coerce-fn identity)
        reader  (or event-value-reader read-event-value)
        getter  (or read-fn (fn [n] (get-in n read-path)))
        el      (-> (if (= :x-search-field widget-tag)
                      (search-field widget-attrs)
                      (u/el widget-tag widget-attrs))
                    (tag-widget! prop-name prop-kind))
        current (getter node)]
    (when datalist-id
      (tokens/attach-datalist-to-shadow-input! el datalist-id))
    (when current (u/set-attr! el :value current))
    (u/on! el event
           (fn [^js e]
             (commit-with! write-fn (:id node) (coerce (reader e)))))
    el))

(defn- build-inner-html-field
  "Special-case editor for the `:inner-html` pseudo-property — raw HTML
   that becomes the element's default-slot content via innerHTML. Used
   by components that opt in with `:raw-html-slot?` in their augment
   entry (see `x-icon`)."
  [node]
  (render-field node
                {:widget-tag    :x-text-area
                 :widget-attrs  {:class "inspector-field-widget"
                                 :placeholder "Paste SVG markup here"}
                 :prop-name     "__inner_html__"
                 :prop-kind     "inner-html"
                 :event         :x-text-area-input
                 :read-path     [:inner-html]
                 :write-fn      ops/set-inner-html}))

(defn- build-text-field
  "Special-case editor for the `:text` pseudo-property — the plain text
   child of nodes like x-typography. Uses a single-line search field."
  [node]
  (render-field node
                {:widget-tag    :x-search-field
                 :widget-attrs  {:class "inspector-field-widget"
                                 :placeholder "Text content"}
                 :prop-name     "__text__"
                 :prop-kind     "text-content"
                 :event         :x-search-field-input
                 :read-path     [:text]
                 :write-fn      ops/set-text}))

(defn- build-layout-field
  "Editor for one of the generic dimension layout fields
   (:width / :height / :padding / :margin). Stored in the node's
   :layout map; reconciler turns it into inline style. Surfaces the
   length-tokens datalist so `var(--x-space-…)` autocompletes."
  [node layout-key placeholder]
  (render-field node
                {:widget-tag    :x-search-field
                 :widget-attrs  {:class "inspector-field-widget"
                                 :placeholder placeholder}
                 :prop-name     (str "__layout__/" (name layout-key))
                 :prop-kind     "layout"
                 :event         :x-search-field-input
                 :read-path     [:layout layout-key]
                 :write-fn      (fn [doc id v]
                                  (ops/set-layout doc id layout-key v))
                 :coerce-fn     c/nil-if-empty
                 :datalist-id   tokens/length-datalist-id}))

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

(defn- build-free-coord-field
  "Numeric/length editor for one of the :layout :x :y :w :h fields.
   Most useful when the node's placement is :free, but shown
   unconditionally so users can pre-fill coordinates before toggling.
   Free-coord fields always store numbers (the reconciler turns them
   into px lengths), so the row is uniformly scrubbable."
  [node layout-key placeholder]
  (let [el      (-> (search-field
                     {:class "inspector-field-widget"
                      :placeholder placeholder})
                    (tag-widget! (str "__layout__/" (name layout-key)) "layout"))
        raw     (get-in node [:layout layout-key])
        node-id (:id node)]
    (when raw (u/set-attr! el :value (str raw)))
    (u/on! el :x-search-field-input
           (fn [^js e]
             (let [v (read-event-value e)]
               (commit-with! ops/set-layout node-id layout-key (c/parse-length-value v)))))
    (scrub/attach-scrub-meta!
     el
     {:read-fn    (fn []
                     ;; Default to 0 when the field is empty so the
                     ;; gesture engages immediately — useful when
                     ;; placement is being changed to :free and the
                     ;; user wants to scrub the new coord into shape.
                    (let [doc (:document @state/app-state)
                          v   (get-in (m/get-node doc node-id)
                                      [:layout layout-key])]
                      (c/parse-number-or-zero v)))
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
  (render-field node
                {:widget-tag    :x-text-area
                 :widget-attrs  {:class "inspector-field-widget"
                                 :placeholder "color: red; --x-button-fg: lime;"
                                 :rows "3"}
                 :prop-name     "__layout__/extra-style"
                 :prop-kind     "layout"
                 :event         :x-text-area-input
                 :read-path     [:layout :extra-style]
                 :write-fn      (fn [doc id v]
                                  (ops/set-layout doc id :extra-style v))
                 :coerce-fn     c/nil-if-empty}))

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
          (c/nil-if-empty resolved))
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
               (commit-css-var-from-event! node entry (c/nil-if-empty v)))))
    el))

(defn- build-css-var-text [node {:keys [kind] :as entry}]
  (let [placeholder (case kind
                      :length "12px / 1rem / 50%"
                      :string "")
        el       (-> (search-field
                      {:class "inspector-field-widget"
                       :placeholder placeholder})
                     (tag-widget! (str "__css-var__/" (:label entry)) "css-var-text"))
        var-name (resolve-css-var entry node)
        current  (when var-name (get-in node [:layout :css-vars var-name]))]
    (when current (u/set-attr! el :value current))
    (u/on! el :x-search-field-input
           (fn [^js e]
             (let [v (read-event-value e)]
               (commit-css-var-from-event! node entry (c/nil-if-empty v)))))
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
        search  (search-field
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

(defn- bind-chip-and-popover
  "Returns `[chip-el popover-el]`. The chip lives inline on the
   field-label row (rounded 🔗 button when unbound, pill with the
   bound field + × when bound). The popover is the picker panel —
   hidden by default, expanded below the widget when the chip is
   clicked, dismissed when a field is picked. Used for both attribute
   bindings and text-content bindings, so the visual language is one.
   `opts` carries `:bound? :label-text :prop-kind :on-pick :on-unbind
   :title`; `on-pick` is invoked with `[field-kw owner-name]`."
  [doc tmpl-name {:keys [bound? label-text prop-kind on-pick on-unbind title]}]
  (let [popover (u/el :div {:class "inspector-bind-popover" :hidden ""})
        close!  (fn [] (.setAttribute popover "hidden" ""))
        open!   (fn []
                  (.replaceChildren popover)
                  (let [pick! (fn [field-kw owner]
                                (close!)
                                (on-pick field-kw owner))
                        panel (build-bind-picker-panel doc tmpl-name
                                                       prop-kind pick!)]
                    (.appendChild popover panel)
                    (.removeAttribute popover "hidden")))
        toggle! (fn []
                  (if (.hasAttribute popover "hidden")
                    (open!)
                    (close!)))]
    (if bound?
      (let [chip   (u/el :span {:class "inspector-bind-chip inspector-bind-chip--bound"
                                :title (or title "Edit binding")})
            label  (u/set-text!
                    (u/el :span {:class "inspector-bind-chip-label"})
                    label-text)
            unbind (u/set-text!
                    (u/el :button {:class "inspector-bind-chip-x"
                                   :type  "button"
                                   :title "Unbind"})
                    "×")]
        (u/on! label :click (fn [_] (toggle!)))
        (u/on! unbind :click (fn [^js e]
                               (.stopPropagation e)
                               (close!)
                               (on-unbind)))
        (.appendChild chip label)
        (.appendChild chip unbind)
        [chip popover])
      (let [chip (u/set-text!
                  (u/el :button {:class "inspector-bind-chip"
                                 :type  "button"
                                 :title (or title "Bind to a field")})
                  "🔗")]
        (u/on! chip :click (fn [_] (toggle!)))
        [chip popover]))))

(defn attr-binding-label
  "Display text for a bound attribute. Walks `doc` to recover the
   owning group name when the binding doesn't carry one (legacy
   docs). Public so unit tests can pin the label format without
   building a chip."
  [doc binding]
  (let [owner (or (:owner binding)
                  (some (fn [n]
                          (when (some #(= (:field binding) (:name %))
                                      (:fields n))
                            (:name n)))
                        (m/walk-nodes doc)))]
    (if owner
      (str "↔ " owner "." (field-name-str (:field binding)))
      (str "↔ " (field-name-str (:field binding))))))

(defn- field-row-with-binding [label widget node prop _all-fields]
  (let [doc       (:document @state/app-state)
        prop-name (:name prop)
        tmpl      (enclosing-template-group doc (:id node))
        binding   (get-in node [:bindings prop-name])
        [chip popover]
        (bind-chip-and-popover
         doc (:name tmpl)
         {:bound?     (some? binding)
          :label-text (when binding (attr-binding-label doc binding))
          :prop-kind  (:kind prop)
          :title      (when-not binding "Bind to a field")
          :on-pick    (fn [field-kw owner]
                        (commit-binding! (:id node) prop-name
                                         (name field-kw) owner
                                         (infer-direction (:kind prop)
                                                          (:tag node))))
          :on-unbind  #(commit-unbind! (:id node) prop-name)})
        label-el  (u/set-text! (u/el :div {:class "inspector-field-label"}) label)
        head      (u/el :div {:class "inspector-field-head"} [label-el chip])]
    (when-let [scrub (scrub/read-scrub-meta widget)]
      (scrub/pointer-scrub! label-el widget
                            (:read-fn scrub) (:commit-fn! scrub) (:step scrub)))
    (u/el :div {:class "inspector-field"} [head widget popover])))

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
    (when-let [scrub (scrub/read-scrub-meta widget)]
      (scrub/pointer-scrub! label-el widget
                            (:read-fn scrub) (:commit-fn! scrub) (:step scrub)))
    (u/el :div {:class "inspector-field"}
          [label-el widget])))

(defn- attributes-section [{:keys [node meta]} all-fields]
  (let [props (:properties meta)
        rows  (for [p props]
                (field-row-with-binding
                 (model/display-label p) (build-widget node p)
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

(defn text-bind-label
  "Display text for a bound text-field. Walks `doc` to recover the
   owning group name when the node doesn't carry one (legacy docs).
   Public so unit tests can pin the label format without building
   a chip."
  [doc node bound]
  (let [owner (or (:text-field-owner node)
                  (some (fn [n]
                          (when (some #(= bound (:name %)) (:fields n))
                            (:name n)))
                        (m/walk-nodes doc)))]
    (if owner
      (str "↔ " owner "." (field-name-str bound))
      (str "↔ " (field-name-str bound)))))

(defn- text-row
  "Text editor + inline binding chip on the same label row. The chip
   uses :string-short for type compatibility (every scalar renders as
   text) and writes via `set-text-field` instead of the attribute
   `:bindings` map."
  [node]
  (let [doc        (:document @state/app-state)
        tmpl       (enclosing-template-group doc (:id node))
        bound      (:text-field node)
        [chip popover]
        (bind-chip-and-popover
         doc (:name tmpl)
         {:bound?     (some? bound)
          :label-text (when bound (text-bind-label doc node bound))
          :prop-kind  :string-short
          :title      "Bind text to a field"
          :on-pick    (fn [field-kw owner]
                        (commit-text-field! (:id node) field-kw owner))
          :on-unbind  #(commit-text-field! (:id node) nil)})
        widget   (build-text-field node)
        label-el (u/set-text! (u/el :div {:class "inspector-field-label"}) "text")
        head     (u/el :div {:class "inspector-field-head"} [label-el chip])]
    (when-let [scrub (scrub/read-scrub-meta widget)]
      (scrub/pointer-scrub! label-el widget
                            (:read-fn scrub) (:commit-fn! scrub) (:step scrub)))
    (u/el :div {:class "inspector-field"} [head widget popover])))

(defn- text-section [{:keys [node]}]
  (when (or (some? (:text node))
            ;; show the text editor for typography even if empty
            (contains? #{"x-typography" "x-button"} (:tag node)))
    (section "Text" [(text-row node)])))

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
        name-el (search-field
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
  (let [vs (mapv #(model/current-value % prop) nodes)]
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
        el        (-> (search-field
                       (cond-> {:class "inspector-field-widget"}
                         numeric? (assoc :type "number")
                         mixed?   (assoc :placeholder "Mixed")))
                      (tag-widget! (:name prop) "text" transform))
        ids       (mapv :id nodes)]
    (when color?
      (tokens/attach-datalist-to-shadow-input! el tokens/color-datalist-id))
    (when (and (not mixed?) value (not= "" value))
      (u/set-attr! el :value value))
    (when mixed?
      (.. el -classList (add "is-mixed")))
    (u/on! el :x-search-field-input
           (fn [^js e]
             (let [v (read-event-value e)
                   v (if transform (model/transform-for-commit transform v) v)]
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
  (let [spec (model/editor-spec prop)]
    (case (:kind spec)
      :boolean     (build-multi-boolean   nodes prop)
      :enum        (build-multi-enum      nodes prop)
      :string-long (build-multi-text-area nodes prop)
      (build-multi-search-field nodes prop spec))))

(defn- multi-attributes-section [{:keys [nodes tags]}]
  (let [props (shared-properties tags)
        body  (if (seq props)
                (for [p props]
                  (field-row (model/display-label p) (build-multi-widget nodes p)))
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
        (or (model/transform-for-display transform raw) "")
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
    :number  (c/parse-number-or-zero s)
    :boolean (= "true" s)
    :keyword (c/keyword-or-nil s)
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
                      cell     (search-field
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
  (c/nil-if-empty (.-value el)))

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
    :number  (c/parse-number-or-zero raw)
    :boolean (= "true" raw)
    :keyword (c/keyword-or-nil raw)
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
        name-in        (search-field
                        {:class "inspector-bind-input"
                         :placeholder "field name"})
        default-in     (search-field
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
             (commit-source-field! (:id node)
                                   (c/keyword-or-nil (read-event-value e)))))
    (field-row "source field" sel)))

(defn- build-source-sub-row [node]
  (let [in    (search-field
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
  "True when ANY step on the action consumes a payload record at
   dispatch time. Used to dim actions whose implicit payload can't be
   resolved at the selected node. Multi-step actions surface as
   payload-needing iff at least one step actually consumes the
   trigger's positional arg."
  [action-entry]
  (some #(contains? payload-required-ops (:operation %))
        (:steps action-entry)))

(defn- build-action-picker-panel
  "Grouped picker panel for selecting an action-ref for an event row.
   Mirrors the bind picker: search, sections per group, Setters
   subsection dimmed, actions dimmed (with tooltip) when their
   implicit payload can't be resolved at the selected node. Calls
   `on-pick` with the chosen action-ref keyword."
  [all-actions tmpl-name on-pick]
  (let [wrap    (u/el :div {:class "inspector-bind-picker"})
        search  (search-field
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

(defn resolve-payload-entries
  "Pure: per-entry description of what a bound trigger will dispatch
   at runtime.

   Returns a vector of `{:source :label :detail}` maps:

   - **Implicit payload** (the v1 default — no `:payload` on the
     trigger, sitting inside a template instance): one
     `:implicit-record` entry summarising the enclosing template's
     record shape.
   - **Explicit payload** (legacy / hand-edited docs): one entry
     per `:payload` item — `{:literal v}`, `{:event-detail :k}`, or
     `{:field :f :owner? \"g\"}`. The `:owner` is recovered via a
     doc walk if absent.
   - **No resolvable payload** (no `:payload`, no template ancestor):
     empty vector.

   Public so unit tests can pin the resolved shape without spinning
   up a bound row."
  [doc trigger tmpl]
  (let [payload (:payload trigger)]
    (cond
      (seq payload)
      (mapv
       (fn [pe]
         (cond
           (contains? pe :literal)
           {:source :literal
            :label  "literal"
            :detail (pr-str (:literal pe))}

           (contains? pe :event-detail)
           {:source :event-detail
            :label  "event"
            :detail (str "event.detail." (name (:event-detail pe)))}

           (contains? pe :field)
           (let [field-kw (:field pe)
                 owner    (or (:owner pe)
                              (some (fn [n]
                                      (when (some #(= field-kw (:name %))
                                                  (:fields n))
                                        (:name n)))
                                    (m/walk-nodes doc)))]
             {:source :field
              :label  "field"
              :detail (if owner
                        (str owner "." (name field-kw))
                        (name field-kw))})

           :else
           {:source :unknown
            :label  "unknown"
            :detail (pr-str pe)}))
       payload)

      tmpl
      (let [fields (vec (:fields tmpl))]
        [{:source :implicit-record
          :label  (str (:name tmpl) " record")
          :detail (if (seq fields)
                    (str/join ", " (map #(name (:name %)) fields))
                    "(no fields declared)")}])

      :else
      [])))

(defn- build-payload-preview
  "Read-only block beneath a bound trigger row. Lists each resolved
   payload entry as `<label>: <detail>`. Hidden when there's
   nothing to show — keeps the row compact for triggers that fire
   into actions outside any template ancestor."
  [entries]
  (when (seq entries)
    (let [block (u/el :div {:class "inspector-payload-preview"})
          head  (u/set-text! (u/el :div {:class "inspector-payload-head"})
                             "Resolved payload")]
      (.appendChild block head)
      (doseq [{:keys [label detail]} entries]
        (let [row (u/el :div {:class "inspector-payload-entry"})
              k   (u/set-text! (u/el :span {:class "inspector-payload-label"})
                               (str label ":"))
              v   (u/set-text! (u/el :code {:class "inspector-payload-detail"})
                               detail)]
          (.appendChild row k)
          (.appendChild row v)
          (.appendChild block row)))
      block)))

(defn- build-event-row
  "Single row for one DOM event on the selected node. Shows the
   currently-bound action (if any) with a ✕ to unbind, or an action
   picker button to bind. A `Resolved payload` block below the row
   (when bound) lists each entry the dispatch will carry — implicit
   record fields when inside a template, or explicit `:literal` /
   `:event-detail` / `:field` entries from a hand-edited doc."
  [node event-name doc all-actions tmpl-name]
  (let [existing-idx (first (keep-indexed (fn [i t]
                                            (when (= event-name (:trigger t)) i))
                                          (or (:events node) [])))
        existing     (when existing-idx
                       (nth (:events node) existing-idx))
        tmpl         (enclosing-template-group doc (:id node))
        wrap         (u/el :div {:class "inspector-event-row-wrap"})
        row          (u/el :div {:class "inspector-event-row"})
        label-el     (u/set-text! (u/el :span {:class "inspector-event-name"})
                                  event-name)]
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
        (.appendChild wrap row)
        (when-let [block (build-payload-preview
                          (resolve-payload-entries doc existing tmpl))]
          (.appendChild wrap block)))
      (let [btn  (u/set-text!
                  (u/el :x-button {:variant "ghost" :size "sm"
                                   :class "inspector-bind-btn"
                                   :title "Pick an action"
                                   :data-tour "add-event"})
                  "🔗")
            hint (u/set-text!
                  (u/el :div {:class "inspector-event-hint"})
                  (if tmpl-name
                    (str "payload: enclosing " tmpl-name " (when action needs one)")
                    "payload: none (no template ancestor)"))]
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
        (.appendChild wrap row)
        (.appendChild wrap hint)))
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

(defn- commit-add-action-step! [node-id action-idx step]
  (state/commit! (ops/add-action-step (:document @state/app-state)
                                      node-id action-idx step)))

(defn- commit-remove-action-step! [node-id action-idx step-idx]
  (state/commit! (ops/remove-action-step (:document @state/app-state)
                                         node-id action-idx step-idx)))

(defn- commit-move-action-step! [node-id action-idx step-idx delta]
  (state/commit! (ops/move-action-step (:document @state/app-state)
                                       node-id action-idx step-idx delta)))

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

(defn step-summary
  "Plain-English description of one step: `<verb> :<target>` plus an
   inline literal value when the step pins one (e.g. `sets :open
   = false`). Pure — used by the inspector row + exposed for tests."
  [step]
  (let [verb   (get operation-verb (:operation step)
                    (field-name-str (:operation step)))
        target (field-name-str (:target-field step))
        lit    (some-> step :payload first :literal pr-str)]
    (cond-> (str verb " :" target)
      (some? lit) (str " = " lit))))

(defn- build-action-step-row
  "One row for a step inside an action. Shows the step summary plus
   ↑/↓ reorder buttons and a × that removes the step. Reorder buttons
   are disabled (visually + semantically) at the bounds. The remove
   × is disabled when the action has only one step — the data layer
   refuses to drop the last step, so the UI mirrors that constraint."
  [node action-idx step-idx step n-steps]
  (let [row     (u/el :div {:class "inspector-action-step"})
        first?  (zero? step-idx)
        last?   (= step-idx (dec n-steps))
        only?   (= 1 n-steps)
        label   (u/set-text! (u/el :span {:class "inspector-action-step-label"})
                             (step-summary step))
        up      (u/set-text!
                 (u/el :x-button (cond-> {:variant "ghost" :size "sm"
                                          :class "inspector-bind-btn"
                                          :title "Move step up"}
                                   first? (assoc :disabled "")))
                 "↑")
        down    (u/set-text!
                 (u/el :x-button (cond-> {:variant "ghost" :size "sm"
                                          :class "inspector-bind-btn"
                                          :title "Move step down"}
                                   last? (assoc :disabled "")))
                 "↓")
        remove* (u/set-text!
                 (u/el :x-button (cond-> {:variant "ghost" :size "sm"
                                          :class "inspector-bind-btn"
                                          :title "Remove step"}
                                   only? (assoc :disabled "")))
                 "×")]
    (when-not first?
      (u/on! up :press
             (fn [_] (commit-move-action-step! (:id node) action-idx step-idx -1))))
    (when-not last?
      (u/on! down :press
             (fn [_] (commit-move-action-step! (:id node) action-idx step-idx +1))))
    (when-not only?
      (u/on! remove* :press
             (fn [_] (commit-remove-action-step! (:id node) action-idx step-idx))))
    (.appendChild row label)
    (.appendChild row up)
    (.appendChild row down)
    (.appendChild row remove*)
    row))

(defn- build-add-step-form
  "Compact `+ add step` form for an existing action. Operation +
   target-field pickers, mirroring the add-action form. On commit,
   appends a step (which also normalises the action to multi-step
   shape on first edit)."
  [node action-idx]
  (let [form        (u/el :div {:class "inspector-action-add-step"})
        op-sel      (u/el :x-select {:class "inspector-field-widget"})
        target-sel  (u/el :x-select {:class "inspector-field-widget"})
        target-fields (remove actions/computed? (:fields node))
        add-btn     (u/set-text!
                     (u/el :x-button {:variant "secondary" :size "sm"})
                     "+ add step")
        target-rows (for [{nm :name} target-fields]
                      {:value (cljs.core/name nm)})]
    (populate-select! op-sel "— operation —"
                      (for [{:keys [id label]} action-operations]
                        {:value (cljs.core/name id) :label label}))
    (populate-select! target-sel "— target field —" target-rows)
    (on-select-change! op-sel     (fn [_] nil))
    (on-select-change! target-sel (fn [_] nil))
    (u/on! add-btn :press
           (fn [_]
             (let [op  (kw-value op-sel)
                   tgt (kw-value target-sel)]
               (when (and op tgt)
                 (commit-add-action-step! (:id node) action-idx
                                          {:operation op :target-field tgt})
                 (u/set-attr! op-sel :value "")
                 (u/set-attr! target-sel :value "")))))
    (.appendChild form (field-row "operation" op-sel))
    (.appendChild form (field-row "target field" target-sel))
    (.appendChild form add-btn)
    form))

(defn- build-action-row
  "Render one action: a header row with the action name + a × to
   remove the whole action, then a step list (one row per step), then
   an inline `+ add step` form. Single-step actions read as a
   one-element step list — same UI either way."
  [node a idx]
  (let [steps    (actions/step-list a)
        n        (count steps)
        wrap     (u/el :div {:class "inspector-action"})
        header   (u/el :div {:class "inspector-data-row"})
        title    (u/set-text!
                  (u/el :span {:class "inspector-data-field inspector-action-name"})
                  (str (field-name-str (:name a))
                       (when (> n 1) (str "  ·  " n " steps"))))
        rm-act   (u/set-text!
                  (u/el :x-button {:variant "ghost" :size "sm"
                                   :class "inspector-bind-btn"
                                   :title "Remove action"})
                  "×")]
    (u/on! rm-act :press (fn [_] (commit-remove-action! (:id node) idx)))
    (.appendChild header title)
    (.appendChild header rm-act)
    (.appendChild wrap header)
    (doseq [[i s] (map-indexed vector steps)]
      (.appendChild wrap (build-action-step-row node idx i s n)))
    (.appendChild wrap (build-add-step-form node idx))
    wrap))

(defn- build-add-action-form [node]
  (let [form          (u/el :div {:class "inspector-event-form"})
        name-in       (search-field
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

                   :else
                   [(empty-view)])
        ^js scroll-el (or (.-parentElement host-el) host-el)
        scroll-top    (.-scrollTop scroll-el)]
    (.replaceChildren host-el)
    (doseq [s sections] (.appendChild host-el s))
    (set! (.-scrollTop scroll-el) scroll-top)))

(defn- build-tab-bar
  "Two-tab control at the top of the right panel: Inspector / State.
   Clicking a tab toggles `[hidden]` on each body element so the
   layout swap is pure DOM — no app-state churn, no second watcher.
   The active tab carries `data-active=\"true\"` for CSS styling."
  [inspector-body state-body]
  (let [bar     (u/el :div {:class "right-panel-tabs" :role "tablist"})
        ins-tab (u/set-text!
                 (u/el :button {:class "right-panel-tab" :type "button"
                                :role  "tab"
                                :data-active "true"})
                 "Inspector")
        st-tab  (u/set-text!
                 (u/el :button {:class "right-panel-tab" :type "button"
                                :role  "tab"})
                 "State")
        show!   (fn [active hidden active-tab inactive-tab]
                  (.removeAttribute active "hidden")
                  (.setAttribute    hidden  "hidden" "")
                  (.setAttribute    active-tab   "data-active" "true")
                  (.removeAttribute inactive-tab "data-active"))]
    (u/on! ins-tab :click
           (fn [_] (show! inspector-body state-body ins-tab st-tab)))
    (u/on! st-tab :click
           (fn [_] (show! state-body inspector-body st-tab ins-tab)))
    (.appendChild bar ins-tab)
    (.appendChild bar st-tab)
    bar))

(defn create
  "Build the right-hand panel: a two-tab control (Inspector / State)
   with both bodies mounted, only one visible at a time. Inspector
   is the default tab. Each tab body owns its own watcher; the tab
   control itself is pure DOM, no app-state.

   Returns the outer panel element ready to place into the chrome."
  []
  (let [body       (u/el :div {:class "inspector-body"})
        state-body (state-panel/create)
        tabs       (build-tab-bar body state-body)
        panel      (u/el :div {:id    "bareforge-inspector"
                               :class "panel panel-inspector"}
                         [tabs body state-body])]
    (render! body (model/inspector-model @state/app-state))
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
                                      (model/inspector-model new-state))]
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
                     (let [old-model   (model/inspector-model old-state)
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
