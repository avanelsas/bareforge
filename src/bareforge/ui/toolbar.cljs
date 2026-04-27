(ns bareforge.ui.toolbar
  "Top-of-chrome toolbar. Built on BareDOM's `x-navbar` shell with a
   brand slot (Bareforge logo) and an actions slot (the six top-level
   controls: File dropdown, Templates, Undo, Redo, Preview, Theme).
   The File dropdown collapses New / Open / Save / Export / Export-
   bundle into a single `x-dropdown` to keep the top row compact.

   The pure `toolbar-state` derivation is unit-tested; the effectful
   parts wire BareDOM custom-element events to state mutations and a
   watcher keeps the undo / redo / preview buttons in sync with the
   atom."
  (:require [bareforge.export.registry :as exports]
            [bareforge.state :as state]
            [bareforge.storage.project-file :as pf]
            [bareforge.util.dom :as u]))

;; --- pure derivation ------------------------------------------------------

(defn toolbar-state
  "Project app-state into the subset of values the toolbar cares about."
  [app-state]
  {:undo-disabled? (empty? (get-in app-state [:history :past]))
   :redo-disabled? (empty? (get-in app-state [:history :future]))
   :preview?       (= :preview (:mode app-state))})

;; --- effectful ------------------------------------------------------------

(defn- button
  "Create an x-button with visible text (default slot) and a click handler.
   Uses the tertiary variant so buttons are visible at rest without drawing
   as much attention as primary."
  [label handler]
  (let [text (u/set-text! (u/el :span) label)]
    (-> (u/el :x-button
              {:variant "tertiary" :size "sm" :label label}
              [text])
        (u/on! :click handler))))

(defn- button+text
  "Like `button`, but also returns the inner span so the toolbar
   watcher can swap the visible label later (e.g. Preview ↔ Edit)."
  [label handler]
  (let [text (u/set-text! (u/el :span) label)
        btn  (-> (u/el :x-button
                       {:variant "tertiary" :size "sm" :label label}
                       [text])
                 (u/on! :click handler))]
    {:el btn :text-el text}))

(defn- icon-button
  "A text-free button carrying a single glyph, with the accessible
   name set via `title` and `aria-label` so screen readers and hover
   tooltips both get it. Used for Undo / Redo to compact the toolbar."
  [glyph accessible-name handler]
  (let [text (u/set-text! (u/el :span) glyph)]
    (-> (u/el :x-button
              {:variant    "tertiary"
               :size       "sm"
               :label      accessible-name
               :title      accessible-name
               :aria-label accessible-name
               :class      "toolbar-icon-button"}
              [text])
        (u/on! :click handler))))

(defn- apply-toolbar-state!
  "Push derived state onto the toolbar buttons."
  [{:keys [^js undo-btn ^js redo-btn ^js preview-btn ^js preview-text]}
   {:keys [undo-disabled? redo-disabled? preview?]}]
  (u/set-attr! undo-btn :disabled (when undo-disabled? ""))
  (u/set-attr! redo-btn :disabled (when redo-disabled? ""))
  (u/set-attr! preview-btn :pressed (when preview? ""))
  (let [label (if preview? "Edit" "Preview")]
    (u/set-attr! preview-btn :label label)
    (when preview-text (u/set-text! preview-text label))))

(defn- toggle-preview! [_]
  (state/set-mode! (if (= :preview (:mode @state/app-state))
                     :edit
                     :preview)))

(defn- on-new! [_]
  (when (or (not (:dirty? @state/app-state))
            (js/window.confirm "Start a new project? Unsaved changes will be lost."))
    (pf/new!)))

(defn- on-open!   [_] (pf/open!))
(defn- on-save!   [_] (pf/save!))

(defn- export-filename [ext]
  (str (pf/project-basename @state/app-state) "." ext))

(defn- export-handler
  "Thunk that invokes a plugin's `:download!` with a basename-derived
   filename. The plugin knows its own extension."
  [{:keys [extension download!]}]
  (fn [_] (download! (export-filename extension))))

(defn- export-menu-value
  "Stable x-menu-item :value for a plugin. Keyword name (without the
   colon) is a natural, readable token. Bareforge's own handlers use
   'new' / 'open' / 'save' — plugins namespace theirs under 'export-'
   to avoid clashes."
  [plugin]
  (str "export-" (name (:id plugin))))

(defn- file-menu-rows
  "Return [label value handler] triples for every row in the File
   menu — built-in project controls (New / Open / Save) first,
   plugin-driven export rows next (in registry order), and the
   re-launch entry for the welcome tour last."
  [{:keys [on-welcome-tour]}]
  (concat
    [["New"  "new"  on-new!]
     ["Open" "open" on-open!]
     ["Save" "save" on-save!]]
    (for [p (exports/validated-plugins)]
      [(:label p) (export-menu-value p) (export-handler p)])
    (when on-welcome-tour
      [["Show welcome tour" "welcome-tour" (fn [_] (on-welcome-tour))]])))

(defn- file-menu-actions
  "Map from x-menu-item value → effectful handler, derived live
   from `file-menu-rows`."
  [opts]
  (into {} (for [[_ v h] (file-menu-rows opts)] [v h])))

(defn- file-menu-items
  "[label value] pairs for the File dropdown, derived live from
   `file-menu-rows`."
  [opts]
  (vec (for [[l v _] (file-menu-rows opts)] [l v])))

(defn- file-menu
  "Build the File dropdown using BareDOM's `x-menu` / `x-menu-item`
   components. Unlike `x-dropdown`, `x-menu` does NOT auto-render a
   trigger from its `label` attribute — it has a `slot=\"trigger\"`
   for a user-supplied trigger element. The first appended child is
   an `x-button` with `slot=\"trigger\"` that x-menu's click handler
   uses to toggle `open`. A single `x-menu-select` listener picks
   the handler from `file-menu-actions` by the selected item's
   value, so each row is plain declarative markup."
  [opts]
  (let [menu         (u/el :x-menu
                           {:label     "File"
                            :placement "bottom-start"
                            :class     "toolbar-file-menu"})
        trigger-text (u/set-text! (u/el :span) "File")
        trigger      (u/el :x-button
                           {:slot    "trigger"
                            :variant "tertiary"
                            :size    "sm"
                            :label   "File"}
                           [trigger-text])]
    (.appendChild menu trigger)
    (doseq [[label value] (file-menu-items opts)]
      (.appendChild menu
                    (u/set-text!
                      (u/el :x-menu-item {:value value}) label)))
    (let [actions (file-menu-actions opts)]
      (u/on! menu :x-menu-select
             (fn [^js e]
               (when-let [v (some-> e .-detail .-value)]
                 (when-let [handler (get actions v)]
                   (handler e))))))
    menu))

(defn create
  "Build the toolbar element and install its state watcher. Returns
   the root `<x-navbar>` DOM node ready to place into the chrome.
   Options:
     :on-theme-toggle      thunk called when the Theme button is clicked.
     :on-templates-toggle  thunk called when the Templates button is clicked.
     :on-welcome-tour      thunk called when the File-menu \"Show
                           welcome tour\" entry is selected.
   All callbacks close over the panels they toggle — the caller is
   responsible for creating those panels before the toolbar and
   passing in the thunks here, so the toolbar carries no mutable
   cross-namespace coupling of its own."
  [{:keys [on-theme-toggle on-templates-toggle on-welcome-tour] :as opts}]
  (let [file-btn      (file-menu opts)
        templates-btn (button "Templates" (fn [_] (when on-templates-toggle (on-templates-toggle))))
        undo-btn      (icon-button "↶" "Undo" (fn [_] (state/undo!)))
        redo-btn      (icon-button "↷" "Redo" (fn [_] (state/redo!)))
        {preview-btn :el preview-text :text-el}
                      (button+text "Preview" toggle-preview!)
        theme-btn     (button "Theme" (fn [_] (when on-theme-toggle (on-theme-toggle))))
        ;; data-tour selectors so the welcome tour can target each
        ;; control individually. Avoids polluting the id namespace
        ;; for what are otherwise unique-but-anonymous buttons.
        _             (do (.setAttribute file-btn      "data-tour" "file-menu")
                          (.setAttribute templates-btn "data-tour" "templates-btn")
                          (.setAttribute undo-btn      "data-tour" "undo-btn")
                          (.setAttribute redo-btn      "data-tour" "redo-btn")
                          (.setAttribute preview-btn   "data-tour" "mode-btn")
                          (.setAttribute theme-btn     "data-tour" "theme-btn"))
        divider       (u/el :div {:class "toolbar-divider"})
        ;; Native light/dark swap via <picture>. No JS — the browser
        ;; picks the source matching the OS color-scheme preference.
        ;; Slotted into the x-navbar's "brand" slot.
        brand         (u/el :picture
                            {:class     "toolbar-brand"
                             :slot      "brand"
                             :data-tour "brand"}
                            [(u/el :source
                                   {:srcset "/assets/bareforge_darkmode.png"
                                    :media  "(prefers-color-scheme: dark)"})
                             (u/el :img
                                   {:src "/assets/bareforge_lightmode.png"
                                    :alt "Bareforge"})])
        ;; The six top-level controls go into the navbar's "actions"
        ;; slot. Row order: File, Templates, ↶, ↷, │, Preview, Theme.
        actions       (u/el :div
                            {:class "toolbar-actions"
                             :slot  "actions"}
                            [file-btn templates-btn
                             undo-btn redo-btn divider
                             preview-btn theme-btn])
        ;; Document title between brand and actions — goes into the
        ;; navbar's default slot. Reflects the loaded project filename
        ;; (without .json), or "untitled" when nothing is loaded.
        title         (u/set-text!
                        (u/el :div {:class "toolbar-title"})
                        (pf/project-basename @state/app-state))
        toolbar-el  (u/el :x-navbar
                          {:class     "toolbar"
                           :variant   "default"
                           :alignment "space-between"}
                          [brand title actions])
        buttons     {:undo-btn      undo-btn
                     :redo-btn      redo-btn
                     :preview-btn   preview-btn
                     :preview-text  preview-text}]
    (apply-toolbar-state! buttons (toolbar-state @state/app-state))
    (add-watch state/app-state ::toolbar
               (fn [_ _ old-state new-state]
                 (let [old-t (toolbar-state old-state)
                       new-t (toolbar-state new-state)]
                   (when (not= old-t new-t)
                     (apply-toolbar-state! buttons new-t)))
                 (let [old-name (pf/project-basename old-state)
                       new-name (pf/project-basename new-state)]
                   (when (not= old-name new-name)
                     (u/set-text! title new-name)))))
    toolbar-el))
