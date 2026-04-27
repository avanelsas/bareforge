(ns bareforge.ui.palette
  "Left-hand component palette. The pure helpers (group-tags, filter-tags,
   seed-for-tag) are unit-tested; the effectful `create` builds the DOM
   and wires search + click-to-insert against the state atom.

   Click-to-insert is a temporary affordance — once build-order step 9
   lands drag-and-drop, both interaction models will coexist. The data
   flow is the same either way: call `doc.ops/insert-new` then
   `state/commit!`."
  (:require [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.meta.registry :as registry]
            [bareforge.state :as state]
            [bareforge.util.dom :as u]
            [clojure.string :as str]))

;; --- category ordering ----------------------------------------------------

(def ^:private common-tags
  "Pinned shortlist — the components a user reaches for most often."
  ["x-button" "x-card" "x-container" "x-grid" "x-typography"
   "x-navbar" "x-alert" "x-divider" "x-switch"])

(def ^:private category-order
  [:common :layout :navigation :form :text
   :data :feedback :overlay :effects :scroll :utility :other])

(def ^:private category-labels
  {:common     "Common"
   :layout     "Layout"
   :navigation "Navigation"
   :form       "Form"
   :text       "Text"
   :data       "Data"
   :feedback   "Feedback"
   :overlay    "Overlay"
   :effects    "Effects"
   :scroll     "Scroll"
   :utility    "Utility"
   :other      "Other"})

;; --- pure ----------------------------------------------------------------

(defn tag-meta-list
  "Return `[{:tag :label :category}]` for every registered tag, using the
   meta registry as the source of truth."
  []
  (into []
        (map (fn [t]
               (let [m (registry/get-meta t)]
                 {:tag      t
                  :label    (:label m)
                  :category (:category m)})))
        (registry/all-tags)))

(defn group-tags
  "Group a seq of tag metas into ordered category buckets.
   The :common bucket mirrors `common-tags` order and contains
   duplicates of entries from other buckets (so 'common' is additive,
   not exclusive)."
  [metas]
  (let [by-tag       (into {} (map (juxt :tag identity)) metas)
        common-items (into []
                           (comp (map by-tag)
                                 (remove nil?))
                           common-tags)
        buckets      (group-by :category metas)]
    (->> category-order
         (map (fn [cat]
                [cat
                 (if (= cat :common)
                   common-items
                   (->> (get buckets cat [])
                        (sort-by :tag)))]))
         (remove (fn [[_ items]] (empty? items)))
         vec)))

(defn filter-tags
  "Filter `metas` by a case-insensitive substring match on :tag or :label.
   An empty or whitespace-only search string returns all entries."
  [metas search]
  (let [q (some-> search str/trim str/lower-case)]
    (if (or (nil? q) (= "" q))
      metas
      (filter (fn [{:keys [tag label]}]
                (or (str/includes? (str/lower-case (or tag "")) q)
                    (str/includes? (str/lower-case (or label "")) q)))
              metas))))

(defn seed-for-tag
  "Default content for a freshly-inserted node so it has something
   visible. Returns an overrides map suitable for `doc.ops/insert-new`."
  [tag]
  (case tag
    "x-typography" {:text  "Text"
                    :attrs {"variant" "body1"}}
    "x-button"     {:text  "Button"
                    :attrs {"label" "Button" "variant" "primary"}}
    "x-alert"      {:attrs {"text" "Alert message" "type" "info"}}
    "x-divider"    {}
    "x-card"       {}
    "x-container"  {}
    ;; `columns` is a CSS grid-template-columns value, not a column
    ;; count. Seed with a valid track list so the canvas renders
    ;; three equal columns immediately on drop — CLAUDE.md's
    ;; integer-string coercion exists as a safety net for legacy
    ;; docs, not as the authoring default.
    "x-grid"       {:attrs {"columns" "repeat(3, 1fr)"}}
    {}))

;; --- effectful ------------------------------------------------------------

(defn- container-slot
  "If `tag`'s meta declares a slot that accepts multiple children,
   return that slot's name (preferring \"default\" when available).
   Returns nil for leaf-like components."
  [tag]
  (let [slots (some-> (registry/get-meta tag) :slots)
        multi (filter :multiple? slots)]
    (when (seq multi)
      (or (some #(when (= "default" (:name %)) (:name %)) multi)
          (:name (first multi))))))

(defn- append-to-root [doc]
  {:parent-id "root"
   :slot      "default"
   :index     (count (get-in doc [:root :slots "default"] []))})

(defn insertion-target
  "Decide where a palette click should insert a new node.
   - If the selected node is a container (has a multi-child slot),
     append inside that slot.
   - Else if a non-container node is selected, insert as a sibling
     immediately after it (same parent, same slot, index + 1).
   - Else append to root's default slot."
  [doc selection]
  (let [sel-id    (:id selection)
        sel-node  (when sel-id (m/get-node doc sel-id))
        container (some-> sel-node :tag container-slot)]
    (cond
      (and sel-node container)
      {:parent-id sel-id
       :slot      container
       :index     (count (get-in sel-node [:slots container] []))}

      sel-node
      (if-let [info (m/parent-of doc sel-id)]
        (update info :index inc)
        (append-to-root doc))

      :else
      (append-to-root doc))))

(defn insert-at-selection!
  "Public tap-to-insert helper shared by the palette's armed-tap path
   and the dnd layer. Reads the current selection from the state atom,
   computes an insertion target, inserts, commits, and selects the new
   node."
  [tag]
  (let [doc       (:document @state/app-state)
        selection (:selection @state/app-state)
        {:keys [parent-id slot index]} (insertion-target doc selection)
        {doc' :doc new-id :id}
        (ops/insert-new doc parent-id slot index tag (seed-for-tag tag))]
    (state/commit! doc')
    (state/set-selection! {:id new-id})))

(defn- palette-item
  "Palette items have no :click handler any more — taps and drags both
   flow through the drag layer's pointer state machine, which calls
   `insert-at-selection!` in the :armed phase (tap) and
   `commit-drop!` in the :dragging phase (drag). This eliminates a
   click-fallthrough race where the browser's click event could fire
   after a drag ended over another palette item.

   `on-drag-start` is required when the drag layer is wired up — it
   must be non-nil in normal mounts."
  [{:keys [tag label]} on-drag-start]
  (let [node (u/el :div {:class "palette-item"}
                   [(u/set-text! (u/el :div {:class "palette-item-label"}) label)
                    (u/set-text! (u/el :div {:class "palette-item-tag"}) tag)])]
    (when on-drag-start
      (u/on! node :pointerdown (fn [^js e] (on-drag-start e tag))))
    node))

(defn- render-category
  "Build an accordion-style section for a category bucket. Uses native
   <details>/<summary> so keyboard access and open/close come free.
   `search-active?` forces every category open so the user sees the
   matching components immediately after typing a search term — no
   extra click to expand each bucket."
  [cat items on-drag-start search-active?]
  (let [summary (u/set-text!
                 (u/el :summary {:class "palette-category-summary"})
                 (str (get category-labels cat) "  · " (count items)))
        list-el (u/el :div {:class "palette-category-items"}
                      (map #(palette-item % on-drag-start) items))
        open?   (or search-active?
                    (contains? #{:common :layout} cat))]
    (u/el :details
          {:class "palette-category"
           :open  (when open? "")}
          [summary list-el])))

(defn- render-grouped!
  "Clear and re-render the category list inside `host-el` for the given
   filter string. When the filter is non-blank, every rendered
   category is expanded so matching components are visible without
   extra clicks."
  [^js host-el all-metas search on-drag-start]
  (let [filtered       (filter-tags all-metas search)
        grouped        (group-tags filtered)
        search-active? (not (str/blank? (or search "")))
        sections       (for [[cat items] grouped]
                         (render-category cat items on-drag-start search-active?))]
    (.replaceChildren host-el)
    (doseq [s sections] (.appendChild host-el s))
    (when (empty? grouped)
      (.appendChild host-el
                    (u/set-text!
                     (u/el :div {:class "palette-empty"})
                     "No components match your search.")))))

(defn create
  "Build the palette DOM. Returns the root element ready to place into
   the chrome. Re-renders the list on every input event in the search
   field.

   `opts` may include `:on-drag-start (fn [event tag] ...)` — a
   pointerdown handler wired by the dnd layer to start a drag. When
   omitted, palette items fall back to click-to-insert only."
  ([] (create nil))
  ([{:keys [on-drag-start]}]
   (let [all-metas  (tag-meta-list)
         search-el  (u/el :x-search-field
                          {:class "palette-search"
                           :placeholder "Search components…"})
         groups-el  (u/el :div {:class "palette-groups"})
         palette-el (u/el :div {:id    "bareforge-palette"
                                :class "panel panel-palette"}
                          [(u/set-text! (u/el :div {:class "palette-label"}) "Palette")
                           search-el
                           groups-el])]
     (render-grouped! groups-el all-metas "" on-drag-start)
     ;; x-search-field emits BareDOM-namespaced custom events, not
     ;; native `input`. Listen for both the typed-input event and
     ;; the clear-button event so hitting the X resets the filter.
     ;; (A plain `:input` listener would still catch typing — native
     ;; input bubbles out of the shadow DOM — but clearing happens
     ;; programmatically and emits only the custom clear event.)
     (u/on! search-el :x-search-field-input
            (fn [^js e]
              (let [v (or (some-> e .-detail .-value) "")]
                (render-grouped! groups-el all-metas v on-drag-start))))
     (u/on! search-el :x-search-field-clear
            (fn [^js _e]
              (render-grouped! groups-el all-metas "" on-drag-start)))
     palette-el)))
