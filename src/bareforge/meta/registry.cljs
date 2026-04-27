(ns bareforge.meta.registry
  "Single lookup point for component metadata. Merges four sources:

   - public-api (from bareforge.meta.public-api) — the authoritative tag list
     and property/observed-attribute facts reported by BareDOM itself.
   - augment — hand-curated enum domains, defaults, categories, labels.
   - slots   — hand-curated slot descriptors per tag.
   - placement — canvas placement hints per tag.

   Consumers of this namespace should only call `get-meta`, `all-tags`, or
   `unaugmented-tags`. Do not reach directly into the sub-namespaces."
  (:require [bareforge.meta.augment :as aug]
            [bareforge.meta.categories :as cat]
            [bareforge.meta.heuristics :as h]
            [bareforge.meta.placement :as pl]
            [bareforge.meta.public-api :as pa]
            [bareforge.meta.slots :as sl]))

(defn all-tags
  "Sorted seq of every registered BareDOM tag name."
  []
  (sort (keys pa/api-map)))

(defn registered?
  "True if the tag exists in the BareDOM public-api map."
  [tag]
  (contains? pa/api-map tag))

(defn- fallback-properties
  "When no augmentation exists, emit one property per observed
   attribute, routing each through `heuristics/infer-kind` so the
   inspector picks a plausible widget (boolean / url / number /
   string-short) instead of falling all the way back to a raw
   `:unknown` text field."
  [public-api]
  (let [observed (:observed-attributes public-api)]
    (into []
          (map (fn [attr]
                 {:name attr
                  :kind (h/infer-kind attr)}))
          (array-seq (or observed #js [])))))

(defn get-meta
  "Return the merged meta for a tag, or nil if the tag isn't registered.
   Unaugmented tags still get a humanized label (via
   `heuristics/humanize-tag`) and type-inferred fallback properties so
   a freshly added BareDOM component is usable in the inspector without
   a hand-written augment entry."
  [tag]
  (when-let [api (get pa/api-map tag)]
    (let [aug-entry (get aug/augment tag)]
      {:tag        tag
       :label      (or (:label aug-entry) (h/humanize-tag tag))
       :category   (or (:category aug-entry) (cat/category-for tag))
       :properties (or (:properties aug-entry)
                       (fallback-properties api))
       :css-vars       (:css-vars aug-entry)
       :raw-html-slot? (boolean (:raw-html-slot? aug-entry))
       :slots      (sl/slots-for tag)
       :placement  (pl/hint-for tag)
       :public-api api
       :augmented? (some? aug-entry)})))

(defn unaugmented-tags
  "Sorted seq of tags that only have fallback (raw-attribute) inspector
   coverage. Useful for tracking augmentation progress over time."
  []
  (sort (remove (set (keys aug/augment)) (all-tags))))

(defn container?
  "True when `tag` has an EXPLICIT slot registration in
   `meta/slots.cljs` *and* at least one of those slots accepts
   multiple children. Drives two things:

   - The canvas `(empty)` affordance stamped by `render/canvas`
     via `data-bareforge-container`.
   - The drag layer's `classify-position` before/inside/after
     split (containers get a 25/50/25 band; leaves get 50/50).

   Tags without an explicit entry fall through and return false,
   even though `slots-for` still returns a fallback descriptor for
   the inspector's slot UI — this avoids tagging leaf web components
   whose host element is empty in the light DOM (content rendered
   via shadow DOM) as droppable containers."
  [tag]
  (and (sl/explicitly-registered? tag)
       (boolean (some :multiple? (:slots (get-meta tag))))))
