(ns bareforge.main
  (:require [baredom.exports.all :as baredom]
            [bareforge.doc.ops :as ops]
            [bareforge.state :as state]
            [bareforge.storage.indexeddb :as idb]
            [bareforge.ui.app :as app]
            [bareforge.ui.inspector :as inspector]
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

(defn ^:export init
  []
  (baredom/register!)
  (inspector/install-token-datalists!)
  (app/mount! (u/by-id "app"))
  (-> (idb/restore!)
      (.then (fn [restored?]
               (when-not restored?
                 (seed-document!))
               (idb/install-autosave!)
               ;; First-run only: opens the tour when the seen flag
               ;; is missing. Re-launch lives on the File menu.
               (welcome-tour/maybe-auto-open!)))))
