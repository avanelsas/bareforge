(ns bareforge.ui.command-palette
  "Cmd-K command palette built on the `x-command-palette` BareDOM
   component. The component owns the modal chrome, focus, fuzzy
   filtering, keyboard navigation, and ARIA roles; this namespace
   contributes the static + dynamic command list and the dispatch
   layer that maps a selected item back to its handler.

   Action helpers (`duplicate!`, `wrap-in!`, `copy-attrs!`,
   `paste-attrs!`) are reused from `shortcuts` so the palette and
   the keyboard surface share a single source of truth. Chrome-panel
   toggles are passed in at `install!` time to avoid a circular
   require with the chrome bootstrap."
  (:require [bareforge.meta.registry :as registry]
            [bareforge.state :as state]
            [bareforge.storage.project-file :as pf]
            [bareforge.ui.cheat-sheet :as cheat-sheet]
            [bareforge.ui.palette :as palette]
            [bareforge.ui.shortcuts :as sh]
            [bareforge.util.dom :as u]))

;; --- chrome wiring ------------------------------------------------------

(defonce ^:private install-state
  ;; Callbacks reaching into chrome panels (Theme, Templates, Tour).
  ;; Wired from main/init via `install!` so the palette doesn't have
  ;; to require the panels directly — keeps the namespace graph
  ;; acyclic.
  (atom {:on-theme-toggle     (constantly nil)
         :on-templates-toggle (constantly nil)
         :on-welcome-tour     (constantly nil)}))

(defn install!
  "Register chrome-panel toggle callbacks. `opts` is a map with
   `:on-theme-toggle :on-templates-toggle :on-welcome-tour`. Called
   from `ui.app/build-chrome` alongside the cheat-sheet wiring."
  [opts]
  (reset! install-state (merge {:on-theme-toggle     (constantly nil)
                                :on-templates-toggle (constantly nil)
                                :on-welcome-tour     (constantly nil)}
                               opts)))

;; --- commands -----------------------------------------------------------

(defn- toggle-preview! []
  (state/set-mode! (if (= :preview (:mode @state/app-state))
                     :edit
                     :preview)))

(def ^:private wrap-tags
  "Wrap-in tags offered by the palette — must be a subset of
   shortcuts/wrap-tag-whitelist so the keyboard prompt and the
   palette accept the same set."
  ["x-container" "x-grid" "x-card" "x-flex"])

(defn- wrap-commands []
  (mapv (fn [tag]
          {:label    (str "Wrap selection in " tag)
           :group    "Selection"
           :keywords (str "wrap container " tag)
           :run!     #(sh/wrap-in! tag)})
        wrap-tags))

(defn- insert-commands
  "One entry per registered BareDOM tag. Keywords pull in the human
   label so 'btn' / 'card' / 'navbar' all surface the right tag even
   when the literal `x-*` name isn't typed."
  []
  (mapv (fn [tag]
          (let [meta-entry (registry/get-meta tag)
                hum        (:label meta-entry)]
            {:label    (str "Insert " tag
                            (when hum (str "  ·  " hum)))
             :group    "Insert component"
             :keywords (str "insert " tag (when hum (str " " hum)))
             :run!     #(palette/insert-at-selection! tag)}))
        (registry/all-tags)))

(defn- curated-commands []
  [{:label "Save project"             :group "File"      :keywords "save"
    :run! pf/save!}
   {:label "Open project…"            :group "File"      :keywords "open load"
    :run! pf/open!}
   {:label "New project"              :group "File"      :keywords "new"
    :run! pf/new!}

   {:label "Toggle preview mode"      :group "View"      :keywords "preview"
    :run! toggle-preview!}
   {:label "Toggle theme editor"      :group "View"      :keywords "theme color"
    :run! #((:on-theme-toggle @install-state))}
   {:label "Open templates…"          :group "View"      :keywords "templates starter"
    :run! #((:on-templates-toggle @install-state))}
   {:label "Open welcome tour"        :group "View"      :keywords "tour onboarding"
    :run! #((:on-welcome-tour @install-state))}
   {:label "Show keyboard shortcuts"  :group "View"      :keywords "shortcuts cheat sheet help"
    :run! cheat-sheet/show!}

   {:label "Duplicate selection"      :group "Selection" :keywords "duplicate copy clone"
    :run! sh/duplicate!}
   {:label "Copy attributes"          :group "Selection" :keywords "copy attrs styles"
    :run! sh/copy-attrs!}
   {:label "Paste attributes"         :group "Selection" :keywords "paste attrs styles"
    :run! sh/paste-attrs!}])

(defn- all-commands
  "Curated commands followed by the per-tag insert commands. The
   palette's built-in fuzzy filter ranks them all together — the
   curated ones tend to win on shorter labels."
  []
  (concat (curated-commands) (wrap-commands) (insert-commands)))

;; --- modal --------------------------------------------------------------

(defonce ^:private modal-state
  ;; :el        the x-command-palette host
  ;; :run-by-id map from each item's synthetic id back to its run!
  ;;            thunk. The select event hands us the id only.
  #js {:el nil :run-by-id {}})

(defn- ^js theme-host
  "Same trick as the cheat sheet: mount inside the chrome's
   `<x-theme>` element so the active preset's tokens propagate.
   Falls back to `document.body` if the host isn't found."
  []
  (or (some-> (js/document.getElementById "app") .-parentNode)
      js/document.body))

(defn- ->item
  "Pure: project a Clojure command map into the JS shape
   `x-command-palette` consumes. The synthetic `id` is the index
   string; `keywords` flow through the component's filter so labels
   stay short while typed terms resolve broadly."
  [i {:keys [label group keywords]}]
  #js {:id    (str "cmd-" i)
       :label label
       :group group
       :keywords (or keywords "")})

(defn- build-pool
  "Returns `[js-items run-by-id]` from the current command pool."
  []
  (let [cmds (vec (all-commands))]
    [(into-array
       (map-indexed ->item cmds))
     (into {}
           (map-indexed (fn [i c] [(str "cmd-" i) (:run! c)]))
           cmds)]))

(defn- on-select! [^js e]
  (let [^js detail (.-detail e)
        ^js item   (.-item detail)
        id         (.-id item)
        f          (get (.-run-by-id modal-state) id)]
    (when f
      ;; Defer to next microtask so the palette finishes its own
      ;; close-on-select work before we mutate state / focus.
      (js/queueMicrotask
        (fn [] (try (f) (catch :default _ nil)))))))

(defn- build-modal! []
  (let [el (u/el :x-command-palette
                 {:label       "Command palette"
                  :placeholder "Search commands, components, or actions…"
                  :empty-text  "No matching commands"
                  :modal       ""})]
    (u/on! el "x-command-palette-select" on-select!)
    (.appendChild (theme-host) el)
    (set! (.-el modal-state) el)
    el))

(defn open?
  "True iff the palette is currently mounted and showing."
  []
  (when-let [^js m (.-el modal-state)]
    (.hasAttribute m "open")))

(defn show!
  "Refresh the command pool and open the palette. Items are
   recomputed each call so a newly registered chrome thunk or BareDOM
   tag joins the list without restarting the app."
  []
  (let [^js el (or (.-el modal-state) (build-modal!))
        [items run-by-id] (build-pool)]
    (set! (.-items el) items)
    (set! (.-run-by-id modal-state) run-by-id)
    (.open el)))

(defn hide! []
  (when-let [^js m (.-el modal-state)]
    (.close m)))

(defn toggle!
  "Show the palette if hidden, hide it if shown."
  []
  (if (open?) (hide!) (show!)))
