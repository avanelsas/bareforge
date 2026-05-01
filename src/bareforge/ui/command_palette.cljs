(ns bareforge.ui.command-palette
  "Cmd-K command palette built on the `x-command-palette` BareDOM
   component. The component owns the modal chrome, focus, fuzzy
   filtering, keyboard navigation, and ARIA roles; this namespace
   contributes the static + dynamic command list and the dispatch
   layer that maps a selected item back to its handler.

   Action helpers (`duplicate!`, `wrap-in!`, `copy-attrs!`,
   `paste-attrs!`) are reused from `shortcuts` so the palette and
   the keyboard surface share a single source of truth. Chrome-panel
   toggles are passed in at `install!` time and bound by closure;
   `install!` returns a handle map (`{:show! :hide! :toggle! :open?}`)
   that callers thread to keyboard / toolbar surfaces — there is no
   global registry of palette callbacks."
  (:require [bareforge.meta.registry :as registry]
            [bareforge.state :as state]
            [bareforge.storage.project-file :as pf]
            [bareforge.ui.cheat-sheet :as cheat-sheet]
            [bareforge.ui.palette :as palette]
            [bareforge.ui.shortcuts :as sh]
            [bareforge.util.dom :as u]))

;; --- commands -----------------------------------------------------------

(defn- toggle-preview! []
  (state/set-mode! (if (= :preview (:mode @state/app-state))
                     :edit
                     :preview)))

(def ^:private wrap-tags
  "Wrap-in tags offered by the palette — must be a subset of
   shortcuts/wrap-tag-whitelist so the keyboard prompt and the
   palette accept the same set."
  ["x-container" "x-grid" "x-card" "x-navbar"])

(defn wrap-commands
  "One entry per `wrap-tags` tag. Public so unit tests can pin the
   shape (count, fields, callable `:run!`) without spinning up the
   modal lifecycle."
  []
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

(defn curated-commands
  "Static short-list shown when the query is empty. `chrome-thunks`
   carries the panel-toggle callbacks bound at `install!` time
   (`:on-theme-toggle :on-templates-toggle :on-welcome-tour`). Public
   so unit tests can assert the shape (label / group / run! present,
   no duplicate labels) without going through the modal."
  [{:keys [on-theme-toggle on-templates-toggle on-welcome-tour]}]
  [{:label "Save project"             :group "File"      :keywords "save"
    :run! pf/save!}
   {:label "Open project…"            :group "File"      :keywords "open load"
    :run! pf/open!}
   {:label "New project"              :group "File"      :keywords "new"
    :run! pf/new!}

   {:label "Toggle preview mode"      :group "View"      :keywords "preview"
    :run! toggle-preview!}
   {:label "Toggle theme editor"      :group "View"      :keywords "theme color"
    :run! on-theme-toggle}
   {:label "Open templates…"          :group "View"      :keywords "templates starter"
    :run! on-templates-toggle}
   {:label "Open welcome tour"        :group "View"      :keywords "tour onboarding"
    :run! on-welcome-tour}
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
  [chrome-thunks]
  (concat (curated-commands chrome-thunks)
          (wrap-commands)
          (insert-commands)))

;; --- modal --------------------------------------------------------------

(defn- ^js theme-host
  "Same trick as the cheat sheet: mount inside the chrome's
   `<x-theme>` element so the active preset's tokens propagate.
   Falls back to `document.body` if the host isn't found."
  []
  (or (some-> (js/document.getElementById "app") .-parentNode)
      js/document.body))

(defn ->item
  "Pure: project a Clojure command map into the JS shape
   `x-command-palette` consumes. The synthetic `id` is the index
   string; `keywords` flow through the component's filter so labels
   stay short while typed terms resolve broadly. Public so unit
   tests can pin the field mapping."
  [i {:keys [label group keywords]}]
  #js {:id    (str "cmd-" i)
       :label label
       :group group
       :keywords (or keywords "")})

(defn- build-pool
  "Returns `[js-items run-by-id]` from the current command pool,
   resolved against `chrome-thunks`."
  [chrome-thunks]
  (let [cmds (vec (all-commands chrome-thunks))]
    [(into-array
      (map-indexed ->item cmds))
     (into {}
           (map-indexed (fn [i c] [(str "cmd-" i) (:run! c)]))
           cmds)]))

(def ^:private noop-thunks
  {:on-theme-toggle     (constantly nil)
   :on-templates-toggle (constantly nil)
   :on-welcome-tour     (constantly nil)})

(defn install!
  "Build the palette and return a handle map
   `{:show! :hide! :toggle! :open?}` whose entries close over
   `chrome-thunks` (`{:on-theme-toggle :on-templates-toggle
   :on-welcome-tour}`) and a private DOM cache. The handle is the
   single point of contact — there is no global lookup, no setter."
  [chrome-thunks]
  (let [thunks (merge noop-thunks chrome-thunks)
        ;; :el        the x-command-palette host (lazily mounted)
        ;; :run-by-id map from each item's synthetic id back to its
        ;;            run! thunk. The select event hands us the id only.
        cache  #js {:el nil :run-by-id {}}
        on-select! (fn [^js e]
                     (let [^js detail (.-detail e)
                           ^js item   (.-item detail)
                           id         (.-id item)
                           f          (get (.-run-by-id cache) id)]
                       (when f
                         ;; Defer to next microtask so the palette
                         ;; finishes its own close-on-select work
                         ;; before we mutate state / focus.
                         (js/queueMicrotask
                          (fn [] (try (f) (catch :default _ nil)))))))
        build-modal! (fn []
                       (let [el (u/el :x-command-palette
                                      {:label       "Command palette"
                                       :placeholder "Search commands, components, or actions…"
                                       :empty-text  "No matching commands"
                                       :modal       ""})]
                         (u/on! el "x-command-palette-select" on-select!)
                         (.appendChild (theme-host) el)
                         (set! (.-el cache) el)
                         el))
        open? (fn []
                (when-let [^js m (.-el cache)]
                  (.hasAttribute m "open")))
        show! (fn []
                ;; Refresh the command pool each call so a newly
                ;; registered BareDOM tag joins the list without
                ;; restarting the app.
                (let [^js el (or (.-el cache) (build-modal!))
                      [items run-by-id] (build-pool thunks)]
                  (set! (.-items el) items)
                  (set! (.-run-by-id cache) run-by-id)
                  (.open el)))
        hide! (fn []
                (when-let [^js m (.-el cache)]
                  (.close m)))
        toggle! (fn []
                  (if (open?) (hide!) (show!)))]
    {:show! show! :hide! hide! :toggle! toggle! :open? open?}))
