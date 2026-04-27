(ns bareforge.ui.welcome-tour
  "First-run welcome tour, plus a re-launch entry point. Built on
   BareDOM 2.4.0's `<x-welcome-tour>` orchestrator and its
   `<x-welcome-tour-step>` slot children.

   The tour is a single mounted element living next to the
   theme-editor / templates panels in the chrome. Its `open` and
   `step` attrs are driven by `[:ui :welcome-tour]` in app-state via
   one `::welcome-tour` watch installed at mount. Step changes from
   the user (next / prev / dot click) push back through
   `x-welcome-tour-step-change`; `complete` and `skip` close the
   tour and persist the seen flag.

   First-run detection is a single boolean key in IndexedDB,
   separate from autosave. `maybe-auto-open!` resolves the flag and
   opens the tour when unset; the File-menu \"Show welcome tour\"
   entry calls `open!` unconditionally so users can re-watch any
   time."
  (:require [bareforge.state :as state]
            [bareforge.storage.indexeddb :as idb]
            [bareforge.util.dom :as u]))

;; --- step list ------------------------------------------------------------

(def tour-steps
  "Pure data. One map per step, ordered. `:target` is a CSS
   selector pointing at a chrome element; every step needs one
   because BareDOM's x-welcome-tour-step doesn't position the
   popover when target is nil. The welcome and done screens anchor
   on the navbar brand logo (`[data-tour=\"brand\"]`) — top-left
   isn't centered but reads as a stable home base. `:body` renders
   into the step's default slot.

   Selectors must match what `palette/create`, `layers/create`,
   `inspector/create`, the canvas-host in `app/build-chrome`, and
   the toolbar build wire up. A pinned-shape unit test guards
   against drift."
  [{:title     "Welcome to Bareforge"
    :target    "[data-tour=\"brand\"]"
    :placement "bottom-start"
    :body      "Bareforge designs landing pages that actually work. You drop components visually, declare what data they show, and wire what happens when users interact — and Bareforge exports a real, interactive page (not a screenshot, not a static mockup). A minute of tour, then you build."}
   {:title     "The palette"
    :target    "#bareforge-palette"
    :placement "right"
    :body      "Every BareDOM component, grouped by category. Drag any onto the canvas to drop a fresh instance — buttons, cards, inputs, layouts, charts. Search at the top filters as you type."}
   {:title     "The canvas"
    :target    "#bareforge-canvas"
    :placement "top"
    :body      "What you're designing. Click to select, drag to rearrange, double-click text to edit inline. Drop hints highlight valid slots while you drag from the palette."}
   {:title     "Layers"
    :target    "#bareforge-layers"
    :placement "right"
    :body      "An outline view of the same canvas. Useful when overlapping elements or popovers make canvas-clicking awkward — click any node to select it, drag to reparent."}
   {:title     "The inspector"
    :target    "#bareforge-inspector"
    :placement "left"
    :body      "The editor for whatever's selected: text, attributes, layout, slot children. Most of your time happens here. The two more advanced things — state and interactivity — come up next."}
   {:title     "File"
    :target    "[data-tour=\"file-menu\"]"
    :placement "bottom-start"
    :body      "Save your project as JSON to share or version, open it back up later, or export a working page (plain HTML, a self-contained zip, vanilla JS, or a ClojureScript project). Each export ships a real, interactive page. The tour relaunches from here too."}
   {:title     "Templates"
    :target    "[data-tour=\"templates-btn\"]"
    :placement "bottom"
    :body      "Eight realistic landing-page starters plus one kinetic-showcase. Skip the blank-canvas problem — pick one and remix to taste."}
   {:title     "Undo and redo"
    :target    "[data-tour=\"undo-btn\"]"
    :placement "bottom"
    :body      "Every change is reversible. Mistakes are cheap. Ctrl/Cmd-Z and Ctrl/Cmd-Shift-Z work too."}
   {:title     "Preview"
    :target    "[data-tour=\"mode-btn\"]"
    :placement "bottom"
    :body      "Toggle the editor chrome to see exactly what your audience will see — no selection outlines, no drop hints, no inspector. Sanity-check layout in one click."}
   {:title     "Theme"
    :target    "[data-tour=\"theme-btn\"]"
    :placement "bottom-end"
    :body      "Every BareDOM component reads its colors, spacing, and type from CSS variables. Pick a base preset, then override any variable to match your brand — globally, in one place."}
   ;; Conceptual heart of the tour. The Inspector's fields/events
   ;; affordances are conditional on selection, so we anchor on the
   ;; panel itself and use the body to teach the model rather than
   ;; point at a button that may not exist yet.
   {:title     "State: groups and fields"
    :target    "#bareforge-inspector"
    :placement "left"
    :body      "A Bareforge design isn't just markup — it can have state. Give any container a name in the Inspector and it becomes a group: a named slice of state your design owns. Add fields to the group: a number (cart count), a string (search term), a list of items. Computed fields derive from other fields — count, sum, filter by another field. List fields hold records, the shape of one item, defined by a smaller named group. The Inspector grows a Fields section once a named container is selected."}
   {:title     "Interactivity: bindings and events"
    :target    "#bareforge-inspector"
    :placement "left"
    :body      "Two ways to make the page reactive. A binding sends a field's value into a component — a badge's text, a search field's value, a card's title (read, write, or both, the data and the page stay in sync). An event lets the user push back: a button press, a switch toggle, a typed search fires an action that mutates a field (set, toggle, increment, add to list, remove). Together, your design becomes a working app — exported as plain code."}
   {:title     "Your turn"
    :target    "[data-tour=\"brand\"]"
    :placement "bottom-start"
    :body      "Pick a template or start blank. Drop components. Name a container, declare a field, bind it, wire an event — in any order. Whatever you build exports as a real page. The tour stays in the File menu if you want it again."}])

;; --- DOM construction -----------------------------------------------------

(defn- build-step
  "Construct one `<x-welcome-tour-step>` element from a step map."
  [{:keys [title target placement body]}]
  (let [attrs (cond-> {:title title}
                target    (assoc :target target)
                placement (assoc :placement placement))
        el    (u/el :x-welcome-tour-step attrs)]
    (u/set-text! el body)
    el))

(defn create
  "Build the `<x-welcome-tour>` element with all step children. The
   tour starts hidden — `maybe-auto-open!` (first run) or the File
   menu (re-launch) opens it. Curve connector matches the launch
   aesthetic and reads as friendlier than a straight arrow."
  []
  (let [tour (u/el :x-welcome-tour
                   {:connector  "curve"
                    :prev-label "Back"
                    :next-label "Next"
                    :done-label "Got it"
                    :skip-label "Skip"
                    :counter    ""
                    :dots       ""})]
    (doseq [s tour-steps]
      (.appendChild tour (build-step s)))
    ;; User-driven step navigation: BareDOM fires step-change with
    ;; `{:step :previousStep}`. Mirror back into state so any other
    ;; subsystem that cares can read `[:ui :welcome-tour :step]`.
    (u/on! tour "x-welcome-tour-step-change"
           (fn [^js e]
             (let [step (some-> e .-detail .-step)]
               (when (number? step)
                 ;; Only mutate :step. :open? is owned by the
                 ;; open!/close! + complete/skip handlers; clobbering
                 ;; it here would race those.
                 (state/update-ui! :welcome-tour assoc :step step)))))
    (u/on! tour "x-welcome-tour-complete"
           (fn [^js _e]
             (state/assoc-ui! :welcome-tour {:open? false :step 0})
             (idb/mark-welcome-tour-seen!)))
    (u/on! tour "x-welcome-tour-skip"
           (fn [^js _e]
             (state/assoc-ui! :welcome-tour {:open? false :step 0})
             (idb/mark-welcome-tour-seen!)))
    tour))

;; --- watch (state → element attrs) ----------------------------------------

(defn- apply-tour-state! [^js tour-el {:keys [open? step]}]
  ;; `open` is a presence-based boolean attr: any value (including
  ;; the string "false") reads as truthy. Pass nil to *remove* the
  ;; attr when closed — `(set-attr! "open" false)` would write
  ;; `open="false"` and reopen the tour straight after BareDOM's
  ;; internal `removeAttribute "open"` in `do-complete!`.
  (u/set-attr! tour-el :open (when open? ""))
  (u/set-attr! tour-el :step (str (or step 0))))

(defn install-watch!
  "Mirror `[:ui :welcome-tour]` onto the live element. Single watch
   keyed by `::welcome-tour`; early-exits when that slice is
   unchanged — every other commit is free."
  [^js tour-el]
  (apply-tour-state! tour-el (get-in @state/app-state [:ui :welcome-tour]))
  (add-watch state/app-state ::welcome-tour
             (fn [_ _ old-state new-state]
               (let [old-t (get-in old-state [:ui :welcome-tour])
                     new-t (get-in new-state [:ui :welcome-tour])]
                 (when (not= old-t new-t)
                   (apply-tour-state! tour-el new-t))))))

;; --- public open / close --------------------------------------------------

(defn open!
  "Open the tour at step 0. Idempotent."
  []
  (state/assoc-ui! :welcome-tour {:open? true :step 0}))

(defn close!
  "Close the tour. Doesn't mark it seen — only `complete` / `skip`
   from the element does that."
  []
  (state/assoc-ui! :welcome-tour {:open? false :step 0}))

;; --- first-run detection --------------------------------------------------

(defn maybe-auto-open!
  "If the user has never seen the tour, open it. Returns a Promise.
   Failing open (showing the tour twice in an edge case) is far less
   bad than failing closed (never showing it on a transient IDB
   error)."
  []
  (-> (idb/welcome-tour-seen?)
      (.then (fn [seen?]
               (when-not seen? (open!))))))
