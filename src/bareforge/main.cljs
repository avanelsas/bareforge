(ns bareforge.main
  (:require [baredom.exports.all :as baredom]
            [bareforge.doc.ops :as ops]
            [bareforge.state :as state]
            [bareforge.storage.indexeddb :as idb]
            [bareforge.storage.project-file :as pf]
            [bareforge.ui.app :as app]
            [bareforge.ui.inspector.tokens :as inspector-tokens]
            [bareforge.ui.welcome-tour :as welcome-tour]
            [bareforge.util.dom :as u]))

(defn- seed-document!
  "Temporary placeholder content so the reconciler has something to show
   before the palette / templates arrive in later build-order steps."
  []
  (let [doc          (:document @state/app-state)
        {doc-1 :doc} (ops/insert-new doc "root" "default" 0
                                     "x-typography"
                                     {:text "Bareforge"
                                      :attrs {"variant" "h1"}})
        {doc-2 :doc} (ops/insert-new doc-1 "root" "default" 1
                                     "x-typography"
                                     {:text "Drop web components from the PALETTE panel on the left to get started with your design"
                                      :attrs {"variant" "body1"}})]
    (state/commit! doc-2)))

(defn- finish-init! [restored?]
  (when-not restored? (seed-document!))
  (idb/install-autosave!)
  ;; First-run only: opens the tour when the seen flag is missing.
  ;; Re-launch lives on the File menu.
  (welcome-tour/maybe-auto-open!))

(defn ^:export init
  []
  (baredom/register!)
  (inspector-tokens/install-token-datalists!)
  (app/mount! (u/by-id "app"))
  (-> (idb/restore!)
      (.then (fn [parsed]
               ;; `parsed` is the raw IDB payload (or nil). The
               ;; load-boundary policy lives in project-file/apply-
               ;; autosave!, which runs spec + sanitiser before
               ;; touching state.
               (let [restored? (boolean (and parsed (pf/apply-autosave! parsed)))]
                 (finish-init! restored?))))
      (.catch (fn [^js err]
                ;; A rejection in the chain (deserialize, validation,
                ;; or install) shouldn't strand the user on a blank
                ;; page. Seed the document, install the watcher, and
                ;; log enough detail for a dev to follow up.
                (js/console.error "init failed; falling back to empty doc" err)
                (finish-init! false)))))
