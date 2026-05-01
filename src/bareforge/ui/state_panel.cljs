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
            [bareforge.util.dom :as u]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

;; --- pure label helpers --------------------------------------------------

(defn type-label
  "Pure: short type label rendered after the field name. Computed
   fields get a trailing `ƒ` glyph to signal derivation; locked
   fields get a leading 🔒."
  [{:keys [type computed? locked?]}]
  (let [t (cljs.core/name (or type :unknown))]
    (cond-> t
      computed? (str " ƒ")
      locked?   (->> (str "🔒 ")))))

(defn- collection-value? [v]
  (or (vector? v) (map? v) (set? v)))

(defn- summary-text
  "Compact one-liner shown alongside the field name when a value
   spans multiple pretty-printed lines. Counts entries so the row
   is informative even when the detail block is collapsed."
  [v]
  (cond
    (vector? v) (str "[" (count v) (if (= 1 (count v)) " item]" " items]"))
    (set?    v) (str "#{" (count v) (if (= 1 (count v)) " item}" " items}"))
    (map?    v) (str "{" (count v) (if (= 1 (count v)) " key}" " keys}"))
    :else       "?"))

(defn- pprint-detail
  "Pretty-print `v` to a string with one entry per line and a
   trailing newline trimmed. `pprint` already wraps lines at a
   sensible width — for vectors of records this gives one record
   per line, for maps one key/value pair per line."
  [v]
  (str/trim-newline (with-out-str (pprint/pprint v))))

(defn value-display
  "Pure: classify a field value's render shape.

   Returns one of:
   - `{:kind :inline    :text \"42\"}` — short scalar; render in
     the row's value column verbatim.
   - `{:kind :runtime}`               — computed op the design-time
     evaluator can't resolve; renderer shows a `(runtime)` hint.
   - `{:kind :expanded  :summary \"[3 items]\" :detail \"…\"}`
     — collection; the renderer puts the summary in the row and
     the pretty-printed detail in a scrollable block beneath.

   Public so unit tests can pin the shape decisions without
   building DOM."
  [{:keys [value runtime-only stored?] :as field}]
  (let [no-value? (and (not stored?) (nil? (find field :value)))]
    (cond
      (or runtime-only no-value?)
      {:kind :runtime}

      (collection-value? value)
      (if (zero? (count value))
        {:kind :inline :text (pr-str value)}
        {:kind    :expanded
         :summary (summary-text value)
         :detail  (pprint-detail value)})

      :else
      {:kind :inline :text (pr-str value)})))

;; --- DOM rendering -------------------------------------------------------

(defn- field-row [field]
  (let [display (value-display field)
        row     (u/el :div {:class "state-panel-field"})
        head    (u/el :div {:class "state-panel-field-head"})
        nm      (u/set-text!
                 (u/el :span {:class "state-panel-name"})
                 (cljs.core/name (:name field)))
        ty      (u/set-text!
                 (u/el :span {:class "state-panel-type"})
                 (type-label field))
        arrow   (u/set-text!
                 (u/el :span {:class "state-panel-arrow"})
                 "→")
        head-v  (u/set-text!
                 (u/el :code {:class "state-panel-value"})
                 (case (:kind display)
                   :runtime  "(runtime)"
                   :inline   (:text display)
                   :expanded (:summary display)))]
    (.appendChild head nm)
    (.appendChild head ty)
    (.appendChild head arrow)
    (.appendChild head head-v)
    (.appendChild row head)
    (when (= :expanded (:kind display))
      (let [detail (u/set-text!
                    (u/el :pre {:class "state-panel-detail"})
                    (:detail display))]
        (.appendChild row detail)))
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
