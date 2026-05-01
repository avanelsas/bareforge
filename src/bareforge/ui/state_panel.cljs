(ns bareforge.ui.state-panel
  "State tab in the right-hand panel. Reads the document's named
   groups + their evaluated computed fields via
   `bareforge.export.state-preview` (pure) and renders one section
   per group. Passive surface — no editing, no input; the user just
   sees what their exported app would start with.

   One `add-watch` keyed `::state-panel`, with an early-exit guard
   on `:document` only — selection / hover / palette state changes
   don't perturb this view."
  (:require [bareforge.export.state-preview :as sp]
            [bareforge.state :as state]
            [bareforge.util.dom :as u]))

;; --- pure label helpers --------------------------------------------------

(defn value-label
  "Pure: short string description of a field row's value, used in the
   State panel rows. Stored fields show the value via `pr-str`;
   computed fields show their evaluated value or a `(runtime)` hint
   for ops the design-time evaluator can't resolve.

   Public so unit tests can pin the label format without mounting
   any DOM."
  [{:keys [value runtime-only stored?] :as field}]
  (cond
    runtime-only "(runtime)"
    (and (not stored?) (nil? (find field :value))) "(runtime)"
    :else (pr-str value)))

(defn type-label
  "Pure: short type label rendered after the field name. Computed
   fields get a trailing `ƒ` glyph to signal derivation; locked
   fields get a leading 🔒."
  [{:keys [type computed? locked?]}]
  (let [t (cljs.core/name (or type :unknown))]
    (cond-> t
      computed? (str " ƒ")
      locked?   (->> (str "🔒 ")))))

;; --- DOM rendering -------------------------------------------------------

(defn- field-row [field]
  (let [row    (u/el :div {:class "state-panel-field"})
        nm     (u/set-text!
                (u/el :span {:class "state-panel-name"})
                (cljs.core/name (:name field)))
        ty     (u/set-text!
                (u/el :span {:class "state-panel-type"})
                (type-label field))
        arrow  (u/set-text!
                (u/el :span {:class "state-panel-arrow"})
                "→")
        v      (u/set-text!
                (u/el :code {:class "state-panel-value"})
                (value-label field))]
    (.appendChild row nm)
    (.appendChild row ty)
    (.appendChild row arrow)
    (.appendChild row v)
    row))

(defn- group-section [{:keys [name template? fields] :as _entry}]
  (let [section (u/el :div {:class "state-panel-section"})
        head    (u/el :div {:class "state-panel-section-head"})
        ttl     (u/set-text!
                 (u/el :span {:class "state-panel-section-title"})
                 (str "GROUP \"" name "\""))
        kind    (u/set-text!
                 (u/el :span {:class "state-panel-section-kind"})
                 (if template? "(template — record shape)" "(stateful)"))]
    (.appendChild head ttl)
    (.appendChild head kind)
    (.appendChild section head)
    (doseq [f fields] (.appendChild section (field-row f)))
    section))

(defn- empty-view []
  (let [el (u/el :div {:class "state-panel-empty"})]
    (u/set-text! el "No groups yet. Name an x-card or x-grid in the inspector to declare a group.")
    el))

(defn- render! [^js host snapshot]
  (.replaceChildren host)
  (if (empty? snapshot)
    (.appendChild host (empty-view))
    (doseq [g snapshot] (.appendChild host (group-section g)))))

;; --- public mount --------------------------------------------------------

(defn create
  "Build the State tab body and install the doc-change watcher.
   Returns the body element. The watcher's early-exit guard fires
   only on `:document` changes, so selection / hover / palette
   state never perturbs this view (per CLAUDE.md's one-watch-per-
   subsystem rule)."
  []
  (let [body (u/el :div {:class "state-panel-body" :hidden ""})]
    (render! body (sp/snapshot (:document @state/app-state)))
    (add-watch state/app-state ::state-panel
               (fn [_ _ old-state new-state]
                 (when (not= (:document old-state) (:document new-state))
                   (render! body (sp/snapshot (:document new-state))))))
    body))
