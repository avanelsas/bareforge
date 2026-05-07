(ns bareforge.ui.shortcuts
  "Keyboard shortcuts. The pure `dispatch` helper projects a native
   KeyboardEvent descriptor into one of a small set of action keywords
   so behaviour is unit-testable without a browser; `install!` wires the
   real listener to `state/*` calls."
  (:require [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.meta.registry :as registry]
            [bareforge.render.canvas :as canvas]
            [bareforge.state :as state]
            [bareforge.storage.project-file :as pf]
            [bareforge.ui.inline-edit :as inline-edit]
            [clojure.string :as str]))

;; --- pure -----------------------------------------------------------------

(def ^:private editable-tags
  "Tag names (lower-case) the shortcut layer should leave alone —
   keystrokes here belong to whatever widget the user is typing into.
   Shadow-DOM events retarget to the host custom element, so checking
   the outer BareDOM tag is enough to catch keys typed into its inner
   `<input>`. Keep this list aligned with the form-like components in
   `bareforge.meta.categories` — omitting one (e.g. `x-color-picker`)
   makes Backspace inside the Inspector delete the selected canvas
   node instead of editing the value."
  #{"input" "textarea" "select"
    "x-search-field" "x-text-field" "x-text-area"
    "x-number-field" "x-currency-field"
    "x-select" "x-combobox"
    "x-color-picker" "x-date-picker"
    "x-slider"})

(defn editable-target?
  "True when the keystroke's target is an editable widget (native input,
   textarea, select, one of the BareDOM form components, or any
   contentEditable element). A shape map makes this callable from
   tests without a real Event."
  [{:keys [tag-name content-editable?]}]
  (or (boolean content-editable?)
      (contains? editable-tags (some-> tag-name str/lower-case))))

(def ^:private arrow-deltas
  "Pixel vectors for each arrow key's base (1 px) step."
  {"ArrowLeft"  [-1  0]
   "ArrowRight" [1  0]
   "ArrowUp"    [0 -1]
   "ArrowDown"  [0  1]})

(def shortcut-info
  "Display data for the cheat sheet. Order = display order within
   each category. The keyboard bindings here MUST stay in lockstep
   with `dispatch` — both surfaces evolve together when a shortcut is
   added or moved. The companion test in `shortcuts_test` re-derives
   the binding-string set and asserts coverage so a one-sided edit is
   caught at compile time.

   Categories are rendered as separate groups in the cheat sheet:
   `:editing` `:selection` `:navigation` `:file` `:view`."
  [{:category :editing    :keys "Cmd+Z"          :label "Undo"}
   {:category :editing    :keys "Cmd+Shift+Z"    :label "Redo"}
   {:category :editing    :keys "Delete / Backspace" :label "Delete selection"}
   {:category :editing    :keys "Cmd+D"          :label "Duplicate selection"}
   {:category :editing    :keys "Cmd+G"          :label "Wrap selection in x-container"}
   {:category :editing    :keys "Cmd+Shift+G"    :label "Wrap selection in… (prompt)"}
   {:category :editing    :keys "Cmd+Opt+C"      :label "Copy attributes from selection"}
   {:category :editing    :keys "Cmd+Opt+V"      :label "Paste attributes onto selection"}
   {:category :editing    :keys "Arrow keys"     :label "Nudge by 1px (free placement)"}
   {:category :editing    :keys "Shift+Arrow"    :label "Nudge by 10px (free placement)"}
   {:category :editing    :keys "Drag numeric label"
    :label "Scrub numeric value (Shift × 10)"}

   {:category :selection  :keys "Click"          :label "Select node"}
   {:category :selection  :keys "Shift-click"    :label "Toggle node in multi-selection"}
   {:category :selection  :keys "Drag empty canvas" :label "Marquee select"}
   {:category :selection  :keys "Shift+drag empty canvas"
    :label "Marquee extend selection"}

   {:category :navigation :keys "Esc"            :label "Deselect / exit text edit"}
   {:category :navigation :keys "Arrow keys (in Layers)"
    :label "Walk the document tree"}
   {:category :navigation :keys "Alt+Up / Alt+Down (in Layers)"
    :label "Reorder selection within parent slot"}

   {:category :file       :keys "Cmd+S"          :label "Save project"}
   {:category :file       :keys "Cmd+O"          :label "Open project"}
   {:category :file       :keys "Cmd+N"          :label "New project"}

   {:category :view       :keys "Cmd+K"          :label "Open command palette"}
   {:category :view       :keys "?"              :label "Show this cheat sheet"}
   {:category :view       :keys "Cmd/Ctrl+scroll" :label "Zoom canvas at cursor"}
   {:category :view       :keys "Wheel"          :label "Pan canvas"}
   {:category :view       :keys "Space+drag"     :label "Pan canvas"}
   {:category :view       :keys "Cmd+0"          :label "Reset zoom & pan"}])

(def category-labels
  "Human labels and ordering for the cheat sheet category groups."
  [[:editing    "Editing"]
   [:selection  "Selection"]
   [:navigation "Navigation"]
   [:file       "File"]
   [:view       "View"]])

(def ^:const nudge-coalesce-window-ms
  "How long after a nudge a subsequent nudge on the same node still
   merges into the same undo entry. Picked so that 'hold the arrow
   key for a second' ends up as one undo step, but a deliberate
   pause-and-nudge is preserved as two."
  500)

(defn coalesce?
  "Pure: should the current nudge attempt merge into the previous
   undo entry, or push a new one?

   The nudge coalesces iff:
   - the same node is being nudged (selection id matches), AND
   - the elapsed time since the last nudge is within the window, AND
   - no other commit has landed in between — detected by comparing
     the history `:past` count right now to the count snapshotted
     right after the last nudge.

   `last` is a map `{:node-id :last-ms :past-count}` or nil for the
   first nudge of a session."
  [last {:keys [node-id now-ms past-count window-ms]}]
  (boolean
   (and last
        (= node-id (:node-id last))
        (< (- now-ms (:last-ms last)) window-ms)
        (= past-count (:past-count last)))))

(defn dispatch
  "Project a key event into an action. Simple actions return a
   keyword (`:undo` `:redo` `:delete` `:duplicate` `:wrap-in-prompt`
   `:copy-attrs` `:paste-attrs` `:deselect` `:exit-text-edit` `:save`
   `:open` `:new` `:noop`); parameterized actions return a vector
   (`[:nudge dx dy]`, `[:wrap-in tag]`). Takes a map shape:
     {:key :meta? :alt? :shift? :tag-name :content-editable?
      :has-selection? :selection-id :placement :text-editing-id}

   Arrow keys nudge the selected node's `:layout :x / :y` by 1 px
   (or 10 px with Shift), but only when the selection has `:free`
   placement — otherwise the arrows fall through to normal page
   behaviour so flow-element selections keep scrolling the canvas.

   Escape clears the current selection (`:deselect`). A live drag's
   Escape handler in `dnd/drag.cljs` uses capture-phase and
   `stopImmediatePropagation`, so this dispatch is only reached when
   no drag is in flight.

   Cmd+S / Cmd+O / Cmd+N map to project-file save / open / new so
   the File menu in the toolbar is an affordance, not the only
   path to these actions. Cmd+D duplicates the current selection;
   Cmd+G wraps it in an x-container, with Cmd+Shift+G prompting
   for a wrapper tag from a small whitelist."
  [{:keys [key code meta? alt? shift? has-selection? selection-id placement
           text-editing-id]
    :as event}]
  (let [editable? (editable-target? event)
        ;; macOS US layout: Option+C produces "ç", Option+V produces
        ;; "√" — the literal `key` no longer matches. `code` is the
        ;; physical-key identifier ("KeyC", "KeyV"), unaffected by
        ;; modifiers, so we match either to keep the gesture portable
        ;; across macOS / Linux / Windows.
        c-letter? (or (= "c" key) (= "KeyC" code))
        v-letter? (or (= "v" key) (= "KeyV" code))]
    (cond
      (and meta? (= "z" key) (not shift?) (not editable?))
      :undo

      (and meta? (or (= "Z" key) (and shift? (= "z" key))) (not editable?))
      :redo

      (and meta? (= "s" key) (not shift?) (not editable?))
      :save

      (and meta? (= "o" key) (not shift?) (not editable?))
      :open

      (and meta? (= "n" key) (not shift?) (not editable?))
      :new

      ;; '?' on US layouts is Shift+/ — match the resolved key directly.
      ;; Cheat sheet binding sits outside the meta? group so it works
      ;; without the Cmd modifier; editable-target? still gates it so
      ;; typing '?' into an inspector input doesn't open the modal.
      (and (= "?" key)
           (not meta?)
           (not editable?))
      :show-shortcuts

      (and meta? (= "k" key) (not shift?) (not alt?)
           (not editable?))
      :show-command-palette

      (and meta? (= "0" key) (not shift?) (not alt?)
           (not editable?))
      :reset-zoom

      (and meta? alt? c-letter? (not shift?)
           has-selection?
           (not= "root" selection-id)
           (not editable?))
      :copy-attrs

      (and meta? alt? v-letter? (not shift?)
           has-selection?
           (not editable?))
      :paste-attrs

      (and meta? (= "d" key) (not shift?) (not alt?)
           has-selection?
           (not editable?))
      :duplicate

      ;; Cmd-Shift-G first so the more-specific binding wins over Cmd-G.
      (and meta? (or (= "G" key) (and shift? (= "g" key)))
           has-selection?
           (not= "root" selection-id)
           (not editable?))
      :wrap-in-prompt

      (and meta? (= "g" key) (not shift?)
           has-selection?
           (not= "root" selection-id)
           (not editable?))
      [:wrap-in "x-container"]

      (and (contains? #{"Delete" "Backspace"} key)
           has-selection?
           (not= "root" selection-id)
           (not editable?))
      :delete

      (and (= "Escape" key)
           (some? text-editing-id)
           (not meta?))
      :exit-text-edit

      (and (= "Escape" key)
           has-selection?
           (not meta?)
           (not editable?))
      :deselect

      (and (contains? arrow-deltas key)
           has-selection?
           (= :free placement)
           (not meta?)
           (not editable?))
      (let [[dx dy] (arrow-deltas key)
            step    (if shift? 10 1)]
        [:nudge (* dx step) (* dy step)])

      :else :noop)))

;; --- effectful -----------------------------------------------------------

(defn- event-descriptor [^js e]
  (let [^js t  (.-target e)
        ;; Selection id is the raw DOM id (clone-suffixed for
        ;; template-instance previews). Canonicalise before every
        ;; doc-side lookup so keyboard ops address the template
        ;; node the user intends, not a synthetic clone id.
        ;; Multi-select degrades placement-aware shortcuts (nudge,
        ;; selection-id-aware delete) to no-op via single-selected-id
        ;; → nil; has-selection? still reflects the broader vector.
        single  (state/single-selected-id @state/app-state)
        doc-id  (canvas/canonical-node-id single)
        node    (when doc-id (m/get-node (:document @state/app-state) doc-id))
        any-sel? (seq (state/selected-ids @state/app-state))]
    {:key               (.-key e)
     :code              (.-code e)
     :meta?             (or (.-metaKey e) (.-ctrlKey e))
     :alt?              (.-altKey e)
     :shift?            (.-shiftKey e)
     :tag-name          (some-> t .-tagName)
     :content-editable? (and t (.-isContentEditable t))
     :has-selection?    (boolean any-sel?)
     :selection-id      doc-id
     :placement         (get-in node [:layout :placement])
     :text-editing-id   (get-in @state/app-state [:ui :text-editing-id])}))

(defonce ^:private nudge-session
  #js {:record nil})

(defn- last-nudge  [] (unchecked-get nudge-session "record"))
(defn- set-nudge!  [rec] (unchecked-set nudge-session "record" rec))

(defn- nudge!
  "Apply a pixel nudge to the currently selected node's x/y and
   commit. A rapid burst targeting the same node collapses into a
   single undo entry via `state/commit-coalesced!`; a pause longer
   than `nudge-coalesce-window-ms` (or any intervening commit, or a
   selection change) starts a fresh history entry."
  [dx dy]
  (let [sel-id     (canvas/canonical-node-id
                    (state/single-selected-id @state/app-state))
        doc        (:document @state/app-state)
        node       (m/get-node doc sel-id)
        cur-x      (or (get-in node [:layout :x]) 0)
        cur-y      (or (get-in node [:layout :y]) 0)
        doc'       (cond-> doc
                     (not (zero? dx)) (ops/set-layout sel-id :x (+ cur-x dx))
                     (not (zero? dy)) (ops/set-layout sel-id :y (+ cur-y dy)))
        now-ms     (js/Date.now)
        past-count (count (get-in @state/app-state [:history :past]))
        merge?     (coalesce? (last-nudge)
                              {:node-id    sel-id
                               :now-ms     now-ms
                               :past-count past-count
                               :window-ms  nudge-coalesce-window-ms})]
    (if merge?
      (state/commit-coalesced! doc')
      (state/commit! doc'))
    (set-nudge! {:node-id    sel-id
                 :last-ms    now-ms
                 :past-count (count (get-in @state/app-state [:history :past]))})))

(def ^:private wrap-tag-whitelist
  "Tags accepted by Cmd-Shift-G's prompt as a wrapper. Kept tight on
   purpose: container components that have a `:default` slot accepting
   multiple children. Adding a new tag here requires it to be a real
   container in the registry, otherwise wrap-many's reparent step
   would fail."
  #{"x-container" "x-grid" "x-card" "x-navbar"})

(defn- selected-doc-ids
  "Read the current selection, canonicalise each id, dedupe, and drop
   `\"root\"`. Used by every multi-id action (delete / duplicate /
   wrap) so they all see the same logical id set."
  []
  (->> (state/selected-ids @state/app-state)
       (map canvas/canonical-node-id)
       distinct
       (remove #{"root"})
       (remove nil?)
       vec))

(defn duplicate! []
  (let [ids (selected-doc-ids)]
    (when (seq ids)
      (let [doc (:document @state/app-state)
            {doc' :doc new-ids :ids} (ops/duplicate-many doc ids)]
        (state/commit! doc')
        (state/set-selection! new-ids)))))

(defn- supported-attr-names
  "Set of attribute names the registry advertises for `tag`. Used to
   filter pasted attrs/props to keys the target tag actually accepts —
   pasting an x-button's `variant` onto an x-card should silently
   drop, not stamp an unknown attribute."
  [tag]
  (set (map :name (:properties (registry/get-meta tag)))))

(defn copy-attrs! []
  (when-let [id (some-> (state/single-selected-id @state/app-state)
                        canvas/canonical-node-id)]
    (when-let [n (and (not= "root" id)
                      (m/get-node (:document @state/app-state) id))]
      (state/set-clipboard-attrs!
       {:source-tag (:tag n)
        :attrs      (or (:attrs n) {})
        :props      (or (:props n) {})}))))

(defn paste-attrs! []
  (when-let [{:keys [attrs props]} (state/clipboard-attrs @state/app-state)]
    (let [target-ids (selected-doc-ids)]
      (when (seq target-ids)
        (let [doc  (:document @state/app-state)
              doc' (reduce
                    (fn [d id]
                      (let [tag    (some-> (m/get-node d id) :tag)
                            ok     (when tag (supported-attr-names tag))]
                        (if (and tag (seq ok))
                          (let [attrs* (select-keys attrs ok)
                                 ;; Boolean :props are keyed by keyword;
                                 ;; cross-reference against the string
                                 ;; supported-attrs set via `name`.
                                props* (into {}
                                             (filter (fn [[k _]]
                                                       (contains? ok (name k)))
                                                     props))]
                            (-> d
                                (ops/set-attrs id attrs*)
                                (ops/set-props id props*)))
                          d)))
                    doc
                    target-ids)]
          (when (not= doc doc')
            (state/commit! doc')))))))

(defn wrap-in! [tag]
  (let [ids (selected-doc-ids)]
    (when (and (contains? wrap-tag-whitelist tag) (seq ids))
      (let [doc (:document @state/app-state)
            {doc' :doc wrap-id :id} (ops/wrap-many doc ids tag)]
        (when wrap-id
          (state/commit! doc')
          (state/select-one! wrap-id))))))

(defn- prompt-wrap-tag
  "Prompt the user for a wrapper tag, restricted to `wrap-tag-whitelist`.
   Returns the chosen tag string, or nil when the user cancels or
   types something off-list."
  []
  (let [input (js/window.prompt
               "Wrap selection in: x-container, x-grid, x-card, x-navbar"
               "x-container")]
    (when (and (string? input)
               (contains? wrap-tag-whitelist input))
      input)))

(defn- perform!
  "Perform `action` against the current app state. `actions` carries
   the UI-thunk bindings (`:show-shortcuts`, `:show-command-palette`)
   that this namespace can't require directly without creating a
   cycle — bound by closure at `install!` time, not via mutable
   global state."
  [actions action ^js e]
  (cond
    (vector? action)
    (let [[op & args] action]
      (case op
        :nudge   (let [[dx dy] args]
                   (.preventDefault e)
                   (nudge! dx dy))
        :wrap-in (let [[tag] args]
                   (.preventDefault e)
                   (wrap-in! tag))))

    :else
    (case action
      :undo     (do (.preventDefault e) (state/undo!))
      :redo     (do (.preventDefault e) (state/redo!))
      :save     (do (.preventDefault e) (pf/save!))
      :open     (do (.preventDefault e) (pf/open!))
      :new      (do (.preventDefault e)
                    (when (or (not (:dirty? @state/app-state))
                              (js/window.confirm
                               "Start a new project? Unsaved changes will be lost."))
                      (pf/new!)))
      :delete   (do (.preventDefault e)
                    (let [doc-ids (selected-doc-ids)
                          doc     (:document @state/app-state)
                          doc'    (ops/remove-many doc doc-ids)]
                      (state/commit! doc')
                      (state/select-clear!)))
      :duplicate (do (.preventDefault e) (duplicate!))
      :wrap-in-prompt (do (.preventDefault e)
                          (when-let [tag (prompt-wrap-tag)]
                            (wrap-in! tag)))
      :copy-attrs  (do (.preventDefault e) (copy-attrs!))
      :paste-attrs (do (.preventDefault e) (paste-attrs!))
      :show-shortcuts       (do (.preventDefault e)
                                ((:show-shortcuts actions)))
      :show-command-palette (do (.preventDefault e)
                                ((:show-command-palette actions)))
      :reset-zoom (do (.preventDefault e) (state/reset-canvas-view!))
      :exit-text-edit (do (.preventDefault e) (inline-edit/teardown!))
      :deselect (do (.preventDefault e) (state/select-clear!))
      nil)))

(def ^:private noop-actions
  {:show-shortcuts       (constantly nil)
   :show-command-palette (constantly nil)})

(defn install!
  "Attach the keyboard listener. `actions` is a map of UI-thunk
   bindings:
     {:show-shortcuts       fn   ;; called for the `?` cheat-sheet binding
      :show-command-palette fn}  ;; called for the Cmd-K palette binding
   Bound by closure at install time so no namespace requires the
   modal-owning UI namespaces. Missing entries default to no-op."
  [actions]
  (let [actions' (merge noop-actions actions)]
    (.addEventListener js/document "keydown"
                       (fn [^js e]
                         (perform! actions' (dispatch (event-descriptor e)) e)))))
