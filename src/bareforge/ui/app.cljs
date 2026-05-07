(ns bareforge.ui.app
  "Top-level chrome: toolbar on top, palette / canvas / inspector grid
   below. The inspector is a placeholder until build-order step 8.
   The canvas area hosts the DOM reconciler."
  (:require [bareforge.dnd.drag :as drag]
            [bareforge.render.canvas :as canvas]
            [bareforge.render.canvas-view :as canvas-view]
            [bareforge.render.selection :as selection]
            [bareforge.render.slot-strips :as slot-strips]
            [bareforge.state :as state]
            [bareforge.ui.cheat-sheet :as cheat-sheet]
            [bareforge.ui.command-palette :as command-palette]
            [bareforge.ui.inspector :as inspector]
            [bareforge.ui.layers :as layers]
            [bareforge.ui.palette :as palette]
            [bareforge.ui.inline-edit :as inline-edit]
            [bareforge.ui.shortcuts :as shortcuts]
            [bareforge.ui.templates :as templates]
            [bareforge.ui.theme-editor :as theme-editor]
            [bareforge.ui.toolbar :as toolbar]
            [bareforge.ui.welcome-tour :as welcome-tour]
            [bareforge.util.dom :as u]))

(defn- build-chrome
  "Construct the chrome subtree. Returns the outer chrome element,
   the canvas host (the drop region the dnd layer guards), the
   inner `<x-theme>` wrapper the reconciler renders into, and the
   command-palette handle so `mount!` can wire it into the keyboard
   layer."
  []
  ;; Panels are created before the toolbar so the toolbar's click
  ;; handlers can close over them directly. No global registry, no
  ;; mutable cross-namespace coupling.
  (let [theme-panel     (theme-editor/create)
        templates-panel (templates/create)
        tour-el         (welcome-tour/create)
        ;; Thunks shared between the toolbar buttons and the Cmd-K
        ;; command palette. Define once so a future binding
        ;; (e.g. closing both panels) doesn't drift between surfaces.
        chrome-thunks   {:on-theme-toggle     #(theme-editor/toggle! theme-panel)
                         :on-templates-toggle #(templates/toggle! templates-panel)
                         :on-welcome-tour     welcome-tour/open!}
        cmd-palette     (command-palette/install! chrome-thunks)
        toolbar-el      (toolbar/create chrome-thunks)
        palette-el      (palette/create {:on-drag-start drag/start-from-palette!})
        layers-el       (layers/create)
        canvas-host     (u/el :div {:id    "bareforge-canvas"
                                    :class "canvas-host"})
        canvas-theme    (u/el :x-theme {:id    "bareforge-canvas-theme"
                                        :preset "ocean"})
        _               (.appendChild canvas-host canvas-theme)
        inspector-el    (inspector/create)
        chrome-el       (u/el :div
                              {:class "chrome"}
                              [toolbar-el palette-el layers-el
                               canvas-host inspector-el
                               theme-panel templates-panel tour-el])]
    {:chrome       chrome-el
     :canvas-host  canvas-host
     :canvas-theme canvas-theme
     :tour-el      tour-el
     :cmd-palette  cmd-palette}))

(defn- install-mode-watch!
  "Reflect `:mode` from app-state onto a `data-mode` attribute on the
   chrome element so CSS can hide / show panels for preview mode."
  [^js chrome-el]
  (let [apply! (fn [mode]
                 (.setAttribute chrome-el "data-mode"
                                (name (or mode :edit))))]
    (apply! (:mode @state/app-state))
    (add-watch state/app-state ::mode
               (fn [_ _ old-state new-state]
                 (when (not= (:mode old-state) (:mode new-state))
                   (apply! (:mode new-state)))))))

(defn mount!
  "Build the chrome inside `mount-el` and mount the canvas reconciler
   inside the inner canvas theme wrapper."
  [^js mount-el]
  (let [{:keys [chrome canvas-host canvas-theme tour-el cmd-palette]}
        (build-chrome)]
    (.replaceChildren mount-el chrome)
    (canvas/mount! canvas-theme)
    (canvas-view/install! canvas-host canvas-theme)
    (selection/install! canvas-host)
    (slot-strips/install! canvas-host)
    (theme-editor/install-watch!)
    (welcome-tour/install-watch! tour-el)
    (drag/install-window-listeners! canvas-host)
    (shortcuts/install! {:show-shortcuts       cheat-sheet/toggle!
                         :show-command-palette (:toggle! cmd-palette)})
    (inline-edit/install! canvas-host)
    (install-mode-watch! chrome)))
