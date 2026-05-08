(ns bareforge.ui.inspector.model
  "Pure helpers for the inspector panel. Project app-state into a view
   model, decide which widget renders a property, transform attribute
   values for display vs. commit. Unit-tested in node — no DOM, no
   atom reads, no side effects.

   Extracted from `bareforge.ui.inspector` so the file's pure surface
   lives separately from the DOM-heavy widgets."
  (:require [bareforge.doc.model :as m]
            [bareforge.meta.registry :as registry]
            [bareforge.render.canvas :as canvas]
            [bareforge.state :as state]
            [clojure.string :as str]))

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

(defn transform-for-commit
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

(defn transform-for-display
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
