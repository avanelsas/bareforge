(ns bareforge.export.state-preview
  "Pure helpers powering the State panel (`bareforge.ui.state-panel`).

   Surfaces what an exported app would carry at startup: each named
   group's field shape, the seed/default value of each stored field,
   and the evaluated value of each computed field against those
   seeds. The State panel renders this as a passive, design-time
   preview so the author doesn't have to export to verify their
   app's initial state.

   Pure: no atom reads, no DOM, no side effects. The companion
   effectful module installs the watcher and renders the result.

   Computed-field evaluation is intentionally narrow in v1 — it
   covers the four ops whose evaluation is local to the group
   (`:count-of`, `:sum-of`, `:empty-of`, `:negation`). Multi-signal
   ops (`:any-of`, `:filter-by`, `:join-on`) need cross-group
   resolution and a real reactive subscription graph; they're
   surfaced as `:runtime-only` so the UI can display a hint
   instead of a spurious value."
  (:require [bareforge.doc.actions :as actions]
            [bareforge.doc.model :as m]))

;; --- group enumeration ---------------------------------------------------

(defn named-groups
  "Every named group in `doc`, in document order. A group is a node
   with a non-empty `:name`. The root never qualifies."
  [doc]
  (vec
   (for [n (m/walk-nodes doc)
         :when (and (not= "root" (:id n))
                    (:name n)
                    (seq (:name n)))]
     n)))

;; --- computed evaluation -------------------------------------------------

(def ^:private supported-computed-ops
  "Computed operations whose evaluation is local to the group and
   doesn't need a reactive store. The remaining ops (`:any-of`,
   `:filter-by`, `:join-on`) need a cross-group sub graph and are
   reported as `:runtime-only`."
  #{:count-of :sum-of :empty-of :negation})

(defn- field-by-name
  "First field-def on `group` whose `:name` matches `fname`, or nil."
  [group fname]
  (some #(when (= fname (:name %)) %) (:fields group)))

(declare evaluate-field)

(defn- evaluate-source
  "Return the resolved value of a field referenced as the source of a
   computed op. Recurses through chained computeds on the same group
   (e.g. `has-items = (negation is-empty)`); falls back to nil when
   the source field can't be located."
  [group source-field-name]
  (when-let [src (field-by-name group source-field-name)]
    (if (actions/computed? src)
      (:value (evaluate-field group src))
      (:default src))))

(defn- evaluate-computed
  "Evaluate one computed field's `:computed` map against `group`'s
   seed values. Returns `{:value v}` for supported ops, or
   `{:runtime-only true}` for ops that need a cross-group reactive
   graph."
  [group computed]
  (let [{:keys [operation source-field project-field]} computed]
    (cond
      (not (contains? supported-computed-ops operation))
      {:runtime-only true}

      (= :count-of operation)
      {:value (count (or (evaluate-source group source-field) []))}

      (= :empty-of operation)
      {:value (empty? (or (evaluate-source group source-field) []))}

      (= :negation operation)
      {:value (not (evaluate-source group source-field))}

      (= :sum-of operation)
      (let [src (or (evaluate-source group source-field) [])]
        (if project-field
          ;; Project-field reads a key off each record in the source
          ;; collection. Records are seeded with unqualified keys
          ;; (the export pipeline re-qualifies them at codegen) — so
          ;; we look up by the bare keyword here.
          {:value (reduce + 0
                          (keep #(get % project-field) src))}
          {:value (reduce + 0 (filter number? src))})))))

(defn evaluate-field
  "Evaluate one field-def on `group`, returning a normalised map:

     {:name        :clicks
      :type        :number
      :stored?     true
      :computed?   false
      :locked?     true            ; only when present
      :value       0}              ; or :runtime-only true

   Stored fields surface their `:default` verbatim. Computed fields
   evaluate their op locally; unsupported ops set `:runtime-only`
   instead of `:value` so the renderer can show a hint."
  [group field]
  (let [base {:name      (:name field)
              :type      (:type field)
              :stored?   (not (actions/computed? field))
              :computed? (actions/computed? field)
              :locked?   (boolean (:locked? field))}]
    (cond-> base
      (not (actions/computed? field))
      (assoc :value (:default field))

      (actions/computed? field)
      (merge (evaluate-computed group (:computed field))))))

(defn evaluate-group-state
  "Pure: snapshot of a group's design-time state.

   Returns:
     {:name      \"cart\"
      :template? true|false
      :fields    [{:name :type :stored? :computed? :value …} …]}

   `template?` indicates the group is a record shape — its fields
   describe per-record keys, not stored db state. The renderer uses
   it to label the section accordingly."
  [doc group]
  {:name      (:name group)
   :template? (actions/template-group? doc group)
   :fields    (mapv #(evaluate-field group %) (:fields group))})

(defn snapshot
  "Pure: full design-time state preview for `doc`. One entry per
   named group, in document order. Empty vector when the doc has
   no groups."
  [doc]
  (mapv #(evaluate-group-state doc %) (named-groups doc)))
