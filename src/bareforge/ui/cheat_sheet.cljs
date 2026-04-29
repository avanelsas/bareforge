(ns bareforge.ui.cheat-sheet
  "Modal that lists every keyboard shortcut and gesture, grouped by
   category. Triggered by the `?` key (`shortcuts/dispatch` returns
   `:show-shortcuts`) or programmatically via `toggle!`.

   The modal pulls its content from `shortcuts/shortcut-info` — there's
   no separately-maintained list. To add a new shortcut, add a binding
   to `dispatch` AND a row to `shortcut-info`; a unit test asserts the
   two surfaces stay in lockstep."
  (:require [bareforge.ui.shortcuts :as sh]
            [bareforge.util.dom :as u]))

(defonce ^:private modal-state #js {:el nil :open? false})

(defn open?
  "Whether the cheat-sheet modal is currently mounted and visible."
  []
  (.-open? modal-state))

(defn- ^js modal-el [] (.-el modal-state))

(defn- group-rows
  "Pure: split shortcut-info into ordered category groups. Returns
   `[[label-string entries] …]` matching `shortcuts/category-labels`'
   ordering, dropping empty groups."
  [info categories]
  (let [by-cat (group-by :category info)]
    (->> categories
         (keep (fn [[k label]]
                 (when-let [entries (seq (get by-cat k))]
                   [label (vec entries)]))))))

(defn- row-el [{:keys [keys label]}]
  (u/el :div {:class "cheat-row"}
        [(-> (u/el :span {:class "cheat-keys"})
             (u/set-text! keys))
         (-> (u/el :span {:class "cheat-label"})
             (u/set-text! label))]))

(defn- group-block [[label entries]]
  (u/el :div {:class "cheat-group"}
        (cons (-> (u/el :div {:class "cheat-group-label"})
                  (u/set-text! label))
              (mapv row-el entries))))

(declare hide!)

(defn- on-key-capture! [^js e]
  (when (.-open? modal-state)
    (cond
      ;; Esc closes the modal and stops propagation so the
      ;; shortcuts layer's :deselect handler doesn't also fire.
      (= "Escape" (.-key e))
      (do (.stopImmediatePropagation e)
          (.preventDefault e)
          (hide!))

      ;; Pressing '?' a second time toggles the modal off.
      (= "?" (.-key e))
      (do (.stopImmediatePropagation e)
          (.preventDefault e)
          (hide!)))))

(defn- build-overlay! []
  (let [^js close-btn (-> (u/el :x-button
                                {:variant "ghost" :size "sm"
                                 :class "cheat-close"})
                          (u/set-text! "Close"))
        groups (group-rows sh/shortcut-info sh/category-labels)
        body (u/el :div {:class "cheat-body"}
                   (mapv group-block groups))
        header (u/el :div {:class "cheat-header"}
                     [(-> (u/el :div {:class "cheat-title"})
                          (u/set-text! "Keyboard shortcuts & gestures"))
                      close-btn])
        panel (u/el :div {:class "cheat-panel"}
                    [header body])
        overlay (u/el :div {:class "cheat-overlay"} [panel])]
    (u/on! close-btn :press (fn [_] (hide!)))
    ;; Click outside the panel (on the overlay backdrop) closes too.
    (u/on! overlay :click
           (fn [^js e]
             (when (= overlay (.-target e))
               (hide!))))
    overlay))

(defn show!
  "Mount the modal in `document.body` and start intercepting keys."
  []
  (when-not (.-open? modal-state)
    (let [^js overlay (build-overlay!)]
      (.appendChild js/document.body overlay)
      (set! (.-el modal-state) overlay)
      (set! (.-open? modal-state) true)
      ;; Capture phase so Esc / '?' inside the modal beats the
      ;; document-level shortcut handler.
      (.addEventListener js/document "keydown" on-key-capture! true))))

(defn hide!
  "Remove the modal and stop intercepting keys."
  []
  (when (.-open? modal-state)
    (when-let [^js overlay (modal-el)]
      (when-let [^js parent (.-parentNode overlay)]
        (.removeChild parent overlay)))
    (set! (.-el modal-state) nil)
    (set! (.-open? modal-state) false)
    (.removeEventListener js/document "keydown" on-key-capture! true)))

(defn toggle!
  "Show the modal if hidden, hide it if shown."
  []
  (if (.-open? modal-state) (hide!) (show!)))
