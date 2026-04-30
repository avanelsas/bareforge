(ns bareforge.ui.cheat-sheet
  "Modal listing every keyboard shortcut and gesture, grouped by
   category. Triggered by `?` (`shortcuts/dispatch` returns
   `:show-shortcuts`) or programmatically via `toggle!`.

   Built on the `x-modal` BareDOM component so theming, focus trap,
   backdrop click, and ARIA roles come for free. All textual content
   uses `x-typography` so type scale and palette inherit from the
   active theme. Source of truth for shortcuts is
   `shortcuts/shortcut-info`; this namespace is purely presentational."
  (:require [bareforge.ui.shortcuts :as sh]
            [bareforge.util.dom :as u]))

(defonce ^:private modal-state #js {:el nil})

(defn- ^js modal-el [] (.-el modal-state))

(defn open?
  "True iff the modal is mounted and showing."
  []
  (when-let [m (modal-el)]
    (.hasAttribute m "open")))

(declare hide!)

(defn- typography
  "Build an `x-typography` element with `variant` and the given text."
  [variant text]
  (-> (u/el :x-typography {:variant variant})
      (u/set-text! text)))

(defn- row-el [{:keys [keys label]}]
  (u/el :div {:class "cheat-row"}
        [(typography "kbd" keys)
         (typography "body2" label)]))

(defn- group-block [[label entries]]
  (u/el :div {:class "cheat-group"}
        (cons (typography "overline" label)
              (mapv row-el entries))))

(defn group-rows
  "Pure: split shortcut-info into ordered category groups. Returns
   `[[label-string entries] …]` matching `category-labels`'
   ordering, dropping empty groups. Public so unit tests can pin
   the grouping shape without going through the modal lifecycle."
  [info categories]
  (let [by-cat (group-by :category info)]
    (->> categories
         (keep (fn [[k label]]
                 (when-let [entries (seq (get by-cat k))]
                   [label (vec entries)]))))))

(defn- on-key-capture!
  "Esc closes the modal at capture phase so neither x-modal's internal
   dismiss handler nor the document-level shortcut layer's :deselect
   handler fire on the same keystroke. We own the close path, the
   canvas selection stays untouched, and the keydown event ends here."
  [^js e]
  (when (and (open?) (= "Escape" (.-key e)))
    (.stopImmediatePropagation e)
    (.preventDefault e)
    (hide!)))

(defn- ^js theme-host
  "Find the chrome's `<x-theme>` element so the modal mounts inside
   it and inherits the active preset's CSS custom properties.
   Falling back to `document.body` keeps the modal usable even if
   the index.html shape changes."
  []
  (or (some-> (js/document.getElementById "app") .-parentNode)
      js/document.body))

(defn- build-modal! []
  (let [groups (group-rows sh/shortcut-info sh/category-labels)
        body   (u/el :div {:class "cheat-body"}
                     (mapv group-block groups))
        title  (-> (typography "h3" "Keyboard shortcuts & gestures")
                   (u/set-attr! :slot "header"))
        modal  (u/el :x-modal
                     {:size  "lg"
                      :label "Keyboard shortcuts & gestures"}
                     [title body])]
    ;; x-modal emits x-modal-dismiss on backdrop click and Esc. Our
    ;; capture-phase keydown short-circuits the Esc path before
    ;; x-modal sees it; backdrop clicks still flow through this
    ;; handler so `hide!` keeps modal-state in sync.
    (u/on! modal :x-modal-dismiss (fn [^js _e] (hide!)))
    (.appendChild (theme-host) modal)
    (set! (.-el modal-state) modal)
    modal))

(defn show!
  "Mount (lazily) and open the modal. Idempotent."
  []
  (when-not (open?)
    (let [m (or (modal-el) (build-modal!))]
      (u/set-attr! m :open "")
      (.addEventListener js/document "keydown" on-key-capture! true))))

(defn hide!
  "Close the modal if open. Leaves the element mounted so a re-show
   can flip the `open` attribute without rebuilding the tree."
  []
  (when-let [m (modal-el)]
    (.removeAttribute m "open")
    (.removeEventListener js/document "keydown" on-key-capture! true)))

(defn toggle!
  "Show the modal if hidden, hide it if shown."
  []
  (if (open?) (hide!) (show!)))
