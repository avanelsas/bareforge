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
            [bareforge.meta.patterns :as patterns]
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
    ;; x-table is a CSS grid; its `columns` attr is a grid-template-columns
    ;; track list that its subgrid rows inherit. Seed a default 3-column
    ;; layout (same shape/convention as x-grid) so a dropped table has a
    ;; real, inspector-editable column config — its `columns` field shows
    ;; "3" via the :grid-columns transform — instead of an empty field and
    ;; cells that depend on the canvas auto-flow fallback.
    "x-table"      {:attrs {"columns" "repeat(3, 1fr)"}}
    ;; Overlay components default to `open=false`, so a bare drop renders
    ;; them closed — zero visible footprint on the canvas (collapsed
    ;; height, full-width host). Seed them open so they're visible and
    ;; selectable the moment they land. Presence-attr convention follows
    ;; the root node's `"fluid" ""` (see doc.model/empty-document).
    ;; A docked sidebar has no intrinsic width in its parent's layout, so
    ;; in plain `:flow` it collapses to a thin strip. Seed `width:100%`
    ;; so it lands as a full-width, droppable block; the empty-container
    ;; affordance supplies the height. (This width was previously a side
    ;; effect of the `:top-full-width` placement snap — now that the
    ;; sidebar is correctly `:flow`, it belongs here as an explicit seed.)
    "x-sidebar"    {:attrs {"open" ""} :layout {:width "100%"}}
    "x-drawer"     {:attrs {"open" ""}}
    "x-modal"      {:attrs {"open" ""}}
    "x-popover"    {:attrs {"open" ""}}
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
   - Else append to root's default slot. Pass nil for `sel-id` when
     no single anchor exists (no selection or multi-select)."
  [doc sel-id]
  (let [sel-node  (when sel-id (m/get-node doc sel-id))
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
  "Public tap-to-insert helper shared by the palette's armed-tap path,
   the dnd layer, and the M3.4 patterns flyout. Reads the current
   selection from the state atom, computes an insertion target,
   inserts, commits, and selects the new node. Multi-select degrades
   to root-append (no single anchor).

   The 2-arity form lets callers override the seeded attrs / props /
   text — used by the patterns flyout to insert a pre-styled variant
   instead of the bare default. When `overrides` is nil the seed
   from `seed-for-tag` is used."
  ([tag] (insert-at-selection! tag nil))
  ([tag overrides]
   (let [doc       (:document @state/app-state)
         sel-id    (state/single-selected-id @state/app-state)
         {:keys [parent-id slot index]} (insertion-target doc sel-id)
         seed      (or overrides (seed-for-tag tag))
         {doc' :doc new-id :id}
         (ops/insert-new doc parent-id slot index tag seed)]
     (state/commit! doc')
     (state/select-one! new-id))))

(defn- pattern-button
  "Inline button inside a palette tile's pattern flyout. Click inserts
   the pattern's pre-styled overrides via `insert-at-selection!`.
   Pointerdown on the button stops propagation so the surrounding
   palette tile's drag-arm doesn't engage on a pattern click."
  [tag {:keys [label overrides]}]
  (let [btn (u/el :div {:class "palette-pattern" :role "button"})]
    (u/set-text! btn label)
    (u/on! btn :pointerdown (fn [^js e] (.stopPropagation e)))
    (u/on! btn :click
           (fn [^js e]
             (.stopPropagation e)
             (insert-at-selection! tag overrides)))
    btn))

(defn- patterns-flyout [tag pats]
  (u/el :div {:class "palette-patterns" :data-hidden ""}
        (mapv #(pattern-button tag %) pats)))

(defn- palette-item
  "Palette items have no :click handler any more — taps and drags both
   flow through the drag layer's pointer state machine, which calls
   `insert-at-selection!` in the :armed phase (tap) and
   `commit-drop!` in the :dragging phase (drag). This eliminates a
   click-fallthrough race where the browser's click event could fire
   after a drag ended over another palette item.

   When the tag has registered patterns (see `meta/patterns.cljs`),
   the tile carries a `▾` caret that toggles an inline flyout of
   pre-styled variants. Clicking a variant inserts it with its
   overrides instead of the bare seed.

   `on-drag-start` is required when the drag layer is wired up — it
   must be non-nil in normal mounts."
  [{:keys [tag label]} on-drag-start]
  (let [pats     (patterns/patterns-for tag)
        label-el (u/set-text! (u/el :div {:class "palette-item-label"}) label)
        tag-el   (u/set-text! (u/el :div {:class "palette-item-tag"}) tag)
        header   (u/el :div {:class "palette-item-header"})
        node     (u/el :div {:class "palette-item"})]
    (.appendChild header label-el)
    (.appendChild header tag-el)
    (when pats
      (let [caret  (-> (u/el :div {:class "palette-item-caret"
                                   :title "Show patterns"
                                   :role  "button"})
                       (u/set-text! "▾"))
            flyout (patterns-flyout tag pats)]
        (.appendChild header caret)
        (.appendChild node header)
        (.appendChild node flyout)
        ;; Caret swallows pointerdown so the palette tile's drag-arm
        ;; (registered below) doesn't engage on the toggle gesture.
        (u/on! caret :pointerdown (fn [^js e] (.stopPropagation e)))
        (u/on! caret :click
               (fn [^js e]
                 (.stopPropagation e)
                 (.preventDefault e)
                 (if (.hasAttribute flyout "data-hidden")
                   (do (.removeAttribute flyout "data-hidden")
                       (.setAttribute caret "data-open" ""))
                   (do (.setAttribute flyout "data-hidden" "")
                       (.removeAttribute caret "data-open")))))))
    (when (nil? pats)
      (.appendChild node header))
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
