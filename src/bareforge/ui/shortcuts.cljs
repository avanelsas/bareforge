(ns bareforge.ui.shortcuts
  "Keyboard shortcuts. The pure `dispatch` helper projects a native
   KeyboardEvent descriptor into one of a small set of action keywords
   so behaviour is unit-testable without a browser; `install!` wires the
   real listener to `state/*` calls."
  (:require [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
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
   keyword (`:undo` `:redo` `:delete` `:deselect` `:exit-text-edit`
   `:save` `:open` `:new` `:noop`); parameterized actions return a
   vector (`[:nudge dx dy]`). Takes a map shape:
     {:key :meta? :shift? :tag-name :content-editable? :has-selection?
      :selection-id :placement :text-editing-id}

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
   path to these actions."
  [{:keys [key meta? shift? has-selection? selection-id placement
           text-editing-id]
    :as event}]
  (let [editable? (editable-target? event)]
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
     :meta?             (or (.-metaKey e) (.-ctrlKey e))
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

(defn- perform! [action ^js e]
  (cond
    (vector? action)
    (let [[op & args] action]
      (case op
        :nudge (let [[dx dy] args]
                 (.preventDefault e)
                 (nudge! dx dy))))

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
                    (let [doc-ids (->> (state/selected-ids @state/app-state)
                                       (map canvas/canonical-node-id)
                                       distinct
                                       (remove #{"root"})
                                       vec)
                          doc     (:document @state/app-state)
                          doc'    (ops/remove-many doc doc-ids)]
                      (state/commit! doc')
                      (state/select-clear!)))
      :exit-text-edit (do (.preventDefault e) (inline-edit/teardown!))
      :deselect (do (.preventDefault e) (state/select-clear!))
      nil)))

(defn- on-keydown! [^js e]
  (perform! (dispatch (event-descriptor e)) e))

(defn install!
  "Attach the keyboard listener. Call once at mount."
  []
  (.addEventListener js/document "keydown" on-keydown!))
