(ns bareforge.ui.inline-edit
  "Double-click inline text editing on the canvas. Supports two families
   of text-bearing components:

   - *attribute* components (x-alert, x-badge, x-copy, x-kinetic-typography,
     x-kinetic-font) whose text is an HTML attribute named \"text\", committed
     via `ops/set-attr`.
   - *child text node* components (x-typography, x-button) whose text is
     the `:text` field on the node, committed via `ops/set-text`.

   Detection is automatic: if the component's augment properties include a
   property named \"text\", the attribute path is used; otherwise the child
   path is used for known text-child tags."
  (:require [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.meta.registry :as registry]
            [bareforge.render.canvas :as canvas]
            [bareforge.render.selection :as selection]
            [bareforge.state :as state]))

;; --- pure ----------------------------------------------------------------

(def ^:private text-child-tags
  #{"x-typography" "x-button"})

(defn text-editable-mode
  "Returns `:attr` when the component has a \"text\" property in its
   augment (commits via set-attr), `:child` when the tag uses a child
   text node (commits via set-text), or nil when the component is not
   text-editable."
  [tag node]
  (let [meta (registry/get-meta tag)]
    (cond
      (some #(= "text" (:name %)) (:properties meta)) :attr
      (or (some? (:text node))
          (contains? text-child-tags tag))              :child
      :else                                             nil)))

(defn- read-text [mode node]
  (case mode
    :attr  (get-in node [:attrs "text"])
    :child (:text node)))

;; --- effectful -----------------------------------------------------------

;; Live DOM element refs and the in-flight edit's mode/node-id. Kept in a
;; Clojure atom (not a JS object) so the cell is observable and the values
;; behave like Clojure data. The serializable slice (`:text-editing-id`)
;; is mirrored to `state/app-state` under `:ui` so the rest of the app can
;; react to it via the single-atom convention.
(defonce ^:private edit-state
  (atom {:el nil :host nil :node-id nil :mode nil :target nil :saved-color nil}))

(defn- active? []
  (some? (:el @edit-state)))

(defn- bcr->map [^js r]
  {:left (.-left r) :top (.-top r) :width (.-width r) :height (.-height r)})

(defn- commit-text-edit! [node-id mode value]
  (let [doc (:document @state/app-state)]
    (state/commit-coalesced!
     (case mode
       :attr  (ops/set-attr doc node-id "text" value)
       :child (ops/set-text doc node-id value)))))

(declare teardown!)

(defn- on-input! [^js e]
  (let [value                  (.. e -target -value)
        {:keys [node-id mode]} @edit-state]
    (commit-text-edit! node-id mode value)))

(defn- on-keydown! [^js e]
  (when (= "Escape" (.-key e))
    (.stopPropagation e)
    (teardown!)))

(defn- on-blur! [_e]
  (teardown!))

(defn teardown!
  "Exit inline edit mode. Safe to call when not editing (no-op)."
  []
  (when (active?)
    (let [{:keys [^js el ^js host ^js target saved-color]} @edit-state]
      (.removeEventListener el "input" on-input!)
      (.removeEventListener el "keydown" on-keydown!)
      (.removeEventListener el "blur" on-blur!)
      (when (and host (.-parentNode el))
        (.removeChild host el))
      (when target
        (set! (.. target -style -visibility) (or saved-color ""))))
    (reset! edit-state
            {:el nil :host nil :node-id nil :mode nil :target nil :saved-color nil})
    (state/assoc-ui! :text-editing-id nil)))

(defn- apply-font-style! [^js textarea ^js target-el]
  (let [^js cs (js/window.getComputedStyle target-el)
        ^js s  (.-style textarea)]
    (set! (.-fontSize s)      (.-fontSize cs))
    (set! (.-fontFamily s)    (.-fontFamily cs))
    (set! (.-fontWeight s)    (.-fontWeight cs))
    (set! (.-lineHeight s)    (.-lineHeight cs))
    (set! (.-textAlign s)     (.-textAlign cs))
    (set! (.-letterSpacing s) (.-letterSpacing cs))))

(defn- activate!
  [^js canvas-host dom-id]
  (teardown!)
  ;; dom-id is the id stamped on the clicked element — clone-suffixed
  ;; for template-instance previews. Text edits mutate the doc, so
  ;; look the node up by the canonical id; keep the raw dom-id for
  ;; the overlay + focus-state bookkeeping so the textarea lands on
  ;; the specific clicked clone.
  (let [canonical (canvas/canonical-node-id dom-id)
        doc       (:document @state/app-state)
        node      (m/get-node doc canonical)
        mode      (when node (text-editable-mode (:tag node) node))]
    (when mode
      (let [^js target-el (canvas/dom-for-id dom-id)]
        (when target-el
          (let [el-rect   (bcr->map (.getBoundingClientRect target-el))
                host-rect (bcr->map (.getBoundingClientRect canvas-host))
                rect      (selection/overlay-rect
                           el-rect host-rect
                           (.-scrollLeft canvas-host)
                           (.-scrollTop canvas-host))
                ^js ta    (js/document.createElement "textarea")]
            (.setAttribute ta "class" "bareforge-inline-edit")
            (let [^js s (.-style ta)]
              (set! (.-left s)   (str (:left rect) "px"))
              (set! (.-top s)    (str (:top rect) "px"))
              (set! (.-width s)  (str (:width rect) "px"))
              (set! (.-height s) (str (:height rect) "px")))
            (apply-font-style! ta target-el)
            (set! (.-value ta) (or (read-text mode node) ""))
            ;; Hide the element's rendered text so it doesn't show
            ;; through behind the textarea overlay.
            (let [saved (.. target-el -style -visibility)]
              (set! (.. target-el -style -visibility) "hidden")
              (.addEventListener ta "input" on-input!)
              (.addEventListener ta "keydown" on-keydown!)
              (.addEventListener ta "blur" on-blur!)
              (.appendChild canvas-host ta)
              ;; Store the canonical doc id — on-input! commits a
              ;; doc mutation (ops/set-text) and must address the
              ;; template, not the clicked clone.
              (reset! edit-state {:el          ta
                                  :host        canvas-host
                                  :node-id     canonical
                                  :mode        mode
                                  :target      target-el
                                  :saved-color saved}))
            (state/assoc-ui! :text-editing-id canonical)
            (.focus ta)
            (.select ta)))))))

(defn- on-dblclick! [^js canvas-host ^js e]
  (when (not= :preview (:mode @state/app-state))
    (when-let [id (canvas/element->node-id (.-target e))]
      (when (not= id "root")
        (activate! canvas-host id)))))

(defn install!
  "Mount the inline text editor on `canvas-host-el`. Registers a
   dblclick listener and a state watcher that tears down the editor
   when the selection changes. Call once at app startup."
  [^js canvas-host-el]
  (.addEventListener canvas-host-el "dblclick"
                     (fn [^js e] (on-dblclick! canvas-host-el e)))
  (add-watch state/app-state ::inline-edit
             (fn [_ _ old-state new-state]
               (when (and (active?)
                          (not= (:selection old-state)
                                (:selection new-state)))
                 (teardown!)))))
