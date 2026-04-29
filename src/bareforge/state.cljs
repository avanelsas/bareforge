(ns bareforge.state
  (:require [bareforge.doc.model :as m]))

(def ^:const history-limit 100)

(defn initial-state
  "Pure constructor for a fresh application state."
  []
  {:document     (m/empty-document)
   ;; Vector of selected node ids. Empty = nothing selected. Multi-select
   ;; (Shift-click, marquee) extends this; single-node consumers route
   ;; through `single-selected-id` which returns nil under multi-select.
   :selection    []
   :history      {:past [] :future []}
   :mode         :edit
   :theme        {:base-preset "ocean" :overrides {}}
   :ui           {:palette-search ""
                  :open-categories #{}
                  :expanded-seeds #{}
                  ;; First-run welcome tour. {:open? :step} mirrored
                  ;; onto the live `<x-welcome-tour>` element via a
                  ;; single `::welcome-tour` watch installed at mount.
                  :welcome-tour   {:open? false :step 0}}
   :dirty?       false
   :project-file {:handle nil :name "untitled.json"}})

(defn- cap-past [past]
  (if (> (count past) history-limit)
    (vec (take-last history-limit past))
    past))

(defn apply-commit
  "Pure: install `new-doc` as the current document, push the previous document
   onto :past (capped), and clear :future."
  [state new-doc]
  (-> state
      (update-in [:history :past]
                 (fn [past] (cap-past (conj past (:document state)))))
      (assoc-in  [:history :future] [])
      (assoc     :document new-doc)
      (assoc     :dirty? true)))

(defn apply-coalesce
  "Pure: install `new-doc` without pushing the previous document onto
   `:past`. Used to collapse a rapid burst of related edits (arrow-key
   nudges, drag micro-adjustments) into a single undo entry so the
   history stack stays sensible. Still clears `:future` and marks
   the project dirty — a coalesced edit is still an edit."
  [state new-doc]
  (-> state
      (assoc-in [:history :future] [])
      (assoc    :document new-doc)
      (assoc    :dirty? true)))

(defn apply-undo
  "Pure: pop :past into :document, push the previous current onto :future.
   No-op when :past is empty."
  [state]
  (let [past (get-in state [:history :past])]
    (if (empty? past)
      state
      (-> state
          (assoc-in  [:history :past]   (vec (pop past)))
          (update-in [:history :future] conj (:document state))
          (assoc     :document (peek past))
          (assoc     :dirty? true)))))

(defn apply-redo
  "Pure: pop :future into :document, push previous current onto :past (capped).
   No-op when :future is empty."
  [state]
  (let [future (get-in state [:history :future])]
    (if (empty? future)
      state
      (-> state
          (assoc-in  [:history :future] (vec (pop future)))
          (update-in [:history :past]
                     (fn [past] (cap-past (conj past (:document state)))))
          (assoc     :document (peek future))
          (assoc     :dirty? true)))))

;; --- effectful zone ---------------------------------------------------------

(defonce app-state (atom (initial-state)))

(defn reset-state!
  "Replace the atom with a fresh initial state. Primarily for tests."
  []
  (reset! app-state (initial-state)))

(defn commit!
  "Install a new document (produced by `doc.ops/*`), update history,
   and mark the project dirty. Does not render — rendering is driven by a
   watcher installed elsewhere."
  [new-doc]
  (swap! app-state apply-commit new-doc))

(defn commit-coalesced!
  "Like `commit!` but merges into the previous history entry instead
   of creating a new one. Use for bursts of rapid, related edits
   (e.g. consecutive arrow-key nudges) that should undo as a unit."
  [new-doc]
  (swap! app-state apply-coalesce new-doc))

(defn undo! [] (swap! app-state apply-undo))
(defn redo! [] (swap! app-state apply-redo))

(defn assoc-ui!
  "Update a key under :ui without affecting history."
  [k v]
  (swap! app-state assoc-in [:ui k] v))

(defn update-ui!
  "Apply `f` (with optional args) to the value at `[:ui k]` without
   affecting history. Companion to `assoc-ui!` for cases that need
   read-modify-write — e.g. toggling membership in a set under :ui.
   UI code should reach for this rather than `swap!` on `app-state`."
  [k f & args]
  (apply swap! app-state update-in [:ui k] f args))

;; --- selection helpers (pure) -------------------------------------------

(defn selected-ids
  "Pure: the current selection as a vector of node ids. Empty vector
   when nothing is selected."
  [state]
  (or (:selection state) []))

(defn selected?
  "Pure: true iff `id` is in the current selection."
  [state id]
  (boolean (some #(= id %) (selected-ids state))))

(defn single-selected-id
  "Pure: the lone selected id, or nil if zero or 2+ are selected.
   Single-node consumers (resize handles, nudge, inspector lookup,
   inline edit teardown comparison) read selection through this so
   they degrade gracefully when the user multi-selects."
  [state]
  (let [ids (selected-ids state)]
    (when (= 1 (count ids)) (first ids))))

;; --- selection mutators (effectful) -------------------------------------

(defn set-selection!
  "Replace the current selection with `ids` (a coll of node ids). nil
   or an empty coll clears the selection. Order is preserved — the
   most-recently added id ends up last, which is the natural anchor
   for future range / shift-extend behaviour."
  [ids]
  (swap! app-state assoc :selection (vec ids)))

(defn select-one!
  "Replace the selection with a single id, or clear when nil."
  [id]
  (set-selection! (when id [id])))

(defn select-clear!
  "Drop the selection entirely. Equivalent to `(set-selection! nil)`."
  []
  (set-selection! nil))

(defn select-toggle!
  "Toggle membership of `id` in the current selection. Adds when
   absent, removes when present. Used by Shift-click and the layers
   panel's Shift-click row toggle."
  [id]
  (swap! app-state update :selection
         (fn [current]
           (let [ids (or current [])]
             (if (some #(= id %) ids)
               (vec (clojure.core/remove #(= id %) ids))
               (conj (vec ids) id))))))

(defn set-mode! [mode]
  (swap! app-state assoc :mode mode))

(defn set-theme-preset!
  "Change the base preset. Theme changes are ambient — not in history."
  [preset]
  (swap! app-state assoc-in [:theme :base-preset] preset))

(defn set-theme-override!
  "Set an individual theme token override. Ambient — not in history."
  [token value]
  (swap! app-state assoc-in [:theme :overrides token] value))

(defn clear-theme-overrides! []
  (swap! app-state assoc-in [:theme :overrides] {}))

(defn mark-saved! []
  (swap! app-state assoc :dirty? false))
