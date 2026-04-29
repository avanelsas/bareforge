(ns bareforge.ui.command-palette
  "Cmd-K command palette. A searchable modal that runs every action
   the chrome exposes — File menu, panel toggles, selection ops,
   wrap-in choices, and `Insert <Tag>` for every BareDOM tag — from
   one keyboard surface.

   Uses `x-modal` + `x-typography` for theme inheritance. Action
   helpers (`duplicate!`, `wrap-in!`, `copy-attrs!`, `paste-attrs!`)
   are reused from `shortcuts` so command-palette and shortcuts share
   a single source of truth. Chrome-panel toggles are passed in at
   `install!` time to avoid a circular require with the chrome
   bootstrap."
  (:require [bareforge.meta.registry :as registry]
            [bareforge.state :as state]
            [bareforge.storage.project-file :as pf]
            [bareforge.ui.cheat-sheet :as cheat-sheet]
            [bareforge.ui.palette :as palette]
            [bareforge.ui.shortcuts :as sh]
            [bareforge.util.dom :as u]
            [clojure.string :as str]))

;; --- chrome wiring ------------------------------------------------------

(defonce ^:private install-state
  ;; Callbacks that reach into chrome panels (Theme, Templates, Tour).
  ;; Wired from main/init so the palette doesn't have to require the
  ;; panels directly — same indirection as cheat-sheet's `set-show-`
  ;; pattern, just generalised to a small map.
  (atom {:on-theme-toggle     (constantly nil)
         :on-templates-toggle (constantly nil)
         :on-welcome-tour     (constantly nil)}))

(defn install!
  "Register chrome-panel toggle callbacks. `opts` is a map with
   `:on-theme-toggle :on-templates-toggle :on-welcome-tour`. Called
   from main/init alongside the cheat sheet wiring."
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
  "Tags offered as wrap targets — must be a subset of the wrap whitelist
   in shortcuts.cljs so Cmd-Shift-G prompt and the command palette
   both accept the same set."
  ["x-container" "x-grid" "x-card" "x-flex"])

(defn- wrap-commands []
  (mapv (fn [tag]
          {:label (str "Wrap selection in " tag)
           :group "Selection"
           :run!  #(sh/wrap-in! tag)})
        wrap-tags))

(defn- insert-commands
  "One entry per registered BareDOM tag. Hidden behind a non-empty
   query so the empty-query palette stays a curated short list."
  []
  (mapv (fn [tag]
          (let [meta-entry (registry/get-meta tag)]
            {:label (str "Insert " tag
                         (when-let [l (:label meta-entry)]
                           (str "  ·  " l)))
             :group "Insert component"
             :run!  #(palette/insert-at-selection! tag)}))
        (registry/all-tags)))

(defn- curated-commands
  "Commands shown when the query is empty — the muscle-memory short
   list. Per-tag inserts join the pool only after the user starts
   typing."
  []
  (concat
    [{:label "Save project"             :group "File"      :run! pf/save!}
     {:label "Open project…"            :group "File"      :run! pf/open!}
     {:label "New project"              :group "File"      :run! pf/new!}

     {:label "Toggle preview mode"      :group "View"      :run! toggle-preview!}
     {:label "Toggle theme editor"      :group "View"
      :run! #((:on-theme-toggle @install-state))}
     {:label "Open templates…"          :group "View"
      :run! #((:on-templates-toggle @install-state))}
     {:label "Open welcome tour"        :group "View"
      :run! #((:on-welcome-tour @install-state))}
     {:label "Show keyboard shortcuts"  :group "View"
      :run! cheat-sheet/show!}

     {:label "Duplicate selection"      :group "Selection" :run! sh/duplicate!}
     {:label "Copy attributes"          :group "Selection" :run! sh/copy-attrs!}
     {:label "Paste attributes"         :group "Selection" :run! sh/paste-attrs!}]
    (wrap-commands)))

(defn- all-commands []
  (concat (curated-commands) (insert-commands)))

(defn- score
  "Pure: case-insensitive substring scoring. Lower is better.
   Prefix matches beat in-word matches; an empty query matches
   everything with score 0."
  [query label]
  (let [q  (str/lower-case query)
        l  (str/lower-case label)]
    (cond
      (str/blank? q)             0
      (str/starts-with? l q)     0
      :else                      (let [idx (.indexOf l q)]
                                   (if (neg? idx) nil (inc idx))))))

(defn filter-commands
  "Pure: rank `commands` against `query`. Curated commands are kept on
   an empty query; once the user types, every command (curated +
   inserts) is in scope so an unfamiliar component is one keystroke
   away. Returns the filtered seq in score order, ties broken by
   label."
  [commands query]
  (let [q (some-> query str/trim)]
    (if (or (nil? q) (= "" q))
      ;; Empty query: curated only. Falls back to full list when
      ;; commands is the curated subset already.
      (vec commands)
      (->> commands
           (keep (fn [c]
                   (when-let [s (score q (:label c))]
                     (assoc c ::score s))))
           (sort-by (juxt ::score :label))
           vec))))

;; --- modal --------------------------------------------------------------

(defonce ^:private modal-state
  ;; Mutable handles to DOM nodes plus the current command lists.
  ;; Lists are stored as Clojure vectors (NOT clj->js'd) so the
  ;; `:run!` keyword key keeps its meaning — clj->js translates `!`
  ;; into `_BANG_`, which would silently break dispatch via property
  ;; access.
  #js {:el nil :input nil :list nil
       :all-commands []
       :curated      []
       :visible      []
       :index        0})

(defn open?
  "True iff the palette modal is mounted and showing."
  []
  (when-let [^js m (.-el modal-state)]
    (.hasAttribute m "open")))

(declare hide! refresh-list! run-active!)

(defn- ^js theme-host
  "Same theme-inheritance trick as the cheat sheet — mount inside the
   chrome's `<x-theme>` so the active preset's tokens propagate."
  []
  (or (some-> (js/document.getElementById "app") .-parentNode)
      js/document.body))

(defn- on-key-capture! [^js e]
  (when (open?)
    (let [k (.-key e)]
      (cond
        (= k "Escape")
        (do (.stopImmediatePropagation e)
            (.preventDefault e)
            (hide!))

        (= k "ArrowDown")
        (let [v (.-visible modal-state)
              n (count v)]
          (when (pos? n)
            (.preventDefault e)
            (set! (.-index modal-state) (mod (inc (.-index modal-state)) n))
            (refresh-list!)))

        (= k "ArrowUp")
        (let [v (.-visible modal-state)
              n (count v)]
          (when (pos? n)
            (.preventDefault e)
            (set! (.-index modal-state)
                  (mod (dec (.-index modal-state)) n))
            (refresh-list!)))

        (= k "Enter")
        (do (.preventDefault e)
            (run-active!))

        :else nil))))

(defn- typography [variant text]
  (-> (u/el :x-typography {:variant variant})
      (u/set-text! text)))

(defn- row-el [{:keys [label group]} active?]
  (let [row (u/el :div
                  {:class (str "command-row"
                               (when active? " is-active"))}
                  [(typography "body2" label)
                   (typography "caption" group)])]
    row))

(defn- build-input! []
  (let [el (u/el :x-search-field
                 {:class "command-input"
                  :placeholder "Type a command, component, or action…"})]
    (u/on! el :x-search-field-input
           (fn [^js _e]
             (set! (.-index modal-state) 0)
             (refresh-list!)))
    el))

(defn- build-list! []
  (u/el :div {:class "command-list"}))

(defn- current-query []
  (let [^js input (.-input modal-state)]
    (if input
      (or (.-value input) "")
      "")))

(defn- visible-commands
  "Compute the currently-visible filtered commands based on the input
   value. With an empty query we show the curated short list; any
   non-empty query searches the full pool so unfamiliar tags are one
   keystroke away."
  []
  (let [q (current-query)]
    (filter-commands
      (if (str/blank? q)
        (.-curated modal-state)
        (.-all-commands modal-state))
      q)))

(defn- refresh-list! []
  (when-let [^js list-el (.-list modal-state)]
    (let [vis (vec (visible-commands))
          n   (count vis)
          ;; Clamp index to the new length so a shrinking list never
          ;; leaves :index dangling past its end.
          idx (if (zero? n) 0 (min (.-index modal-state) (dec n)))]
      (set! (.-visible modal-state) vis)
      (set! (.-index modal-state) idx)
      (.replaceChildren list-el)
      (doseq [[i c] (map-indexed vector vis)]
        (let [active? (= i idx)
              row     (row-el c active?)]
          (u/on! row :click (fn [_]
                              (set! (.-index modal-state) i)
                              (run-active!)))
          (.appendChild list-el row))))))

(defn- run-active! []
  (let [v (.-visible modal-state)
        i (.-index modal-state)
        n (count v)]
    (when (and (pos? n) (< i n))
      (let [cmd (nth v i)
            f   (:run! cmd)]
        (hide!)
        ;; Defer to next microtask so the modal-close mutations finish
        ;; before any commands that mutate state / focus / DOM run.
        (js/queueMicrotask
          (fn []
            (try (f) (catch :default _ nil))))))))

(defn- build-modal! []
  (let [input  (build-input!)
        list-  (build-list!)
        body   (u/el :div {:class "command-body"} [input list-])
        title  (-> (typography "h3" "Command palette")
                   (u/set-attr! :slot "header"))
        modal  (u/el :x-modal
                     {:size  "md"
                      :label "Command palette"}
                     [title body])]
    (u/on! modal :x-modal-dismiss (fn [^js _e] (hide!)))
    (.appendChild (theme-host) modal)
    (set! (.-el    modal-state) modal)
    (set! (.-input modal-state) input)
    (set! (.-list  modal-state) list-)
    modal))

(defn show!
  "Mount (lazily) and open the palette. Refreshes its command pool
   every time so a dynamic registry (new tags, theme overrides) is
   reflected without a page reload."
  []
  (when-not (open?)
    (let [m (or (.-el modal-state) (build-modal!))]
      (set! (.-curated modal-state)      (vec (curated-commands)))
      (set! (.-all-commands modal-state) (vec (all-commands)))
      (set! (.-index modal-state) 0)
      ;; Clear any stale query from a previous session.
      (when-let [^js inp (.-input modal-state)]
        (.setAttribute inp "value" ""))
      (refresh-list!)
      (u/set-attr! m :open "")
      (.addEventListener js/document "keydown" on-key-capture! true)
      ;; x-modal's focus trap fires inside its own setTimeout(0) and
      ;; targets the host x-search-field, which can't take focus
      ;; itself (no delegatesFocus). Walk into its shadow root and
      ;; focus the real <input part="input"> on the next animation
      ;; frame so the user lands typing without an extra Tab. A retry
      ;; loop covers components whose shadow root is built one frame
      ;; later than expected.
      (letfn [(focus-search! []
                (when-let [^js inp (.-input modal-state)]
                  (if-let [^js sr (.-shadowRoot inp)]
                    (when-let [^js inner (.querySelector sr "[part=input]")]
                      (.focus inner))
                    (js/requestAnimationFrame focus-search!))))]
        (js/requestAnimationFrame focus-search!)))))

(defn hide! []
  (when-let [^js m (.-el modal-state)]
    (.removeAttribute m "open")
    (.removeEventListener js/document "keydown" on-key-capture! true)))

(defn toggle! []
  (if (open?) (hide!) (show!)))
