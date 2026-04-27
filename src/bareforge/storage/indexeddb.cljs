(ns bareforge.storage.indexeddb
  "IndexedDB-backed autosave. Serializes the document + theme slice of
   `state/app-state` into an EDN string and stores it under a single
   'autosave' key in a single-store IndexedDB database. Writes are
   debounced (750 ms after the last change) so typing into the
   inspector doesn't thrash the disk.

   The pure `serialize` / `deserialize` pair is unit-tested; the
   effectful IDB wrappers are exercised in the browser."
  (:require [bareforge.state :as state]
            [cljs.reader :as edn]))

;; --- pure ----------------------------------------------------------------

(defn serialize
  "Project the persistable slice of app-state. Only :document and
   :theme survive a reload — history, selection, mode, and ui are
   all reset when the page loads."
  [app-state]
  {:format   "bareforge-project"
   :version  1
   :document (:document app-state)
   :theme    (:theme app-state)})

(defn deserialize
  "Parse an EDN string previously written by `serialize`. Returns nil
   for anything that fails to parse or fails the format check."
  [raw]
  (try
    (let [v (edn/read-string raw)]
      (when (and (map? v)
                 (= "bareforge-project" (:format v))
                 (contains? v :document))
        v))
    (catch :default _ nil)))

;; --- IDB primitives -------------------------------------------------------

(def ^:private db-name   "bareforge")
(def ^:private store     "projects")
(def ^:private autosave-key "autosave")

(defonce ^:private db-ref #js {:db nil})

(defn- open-db []
  (js/Promise.
   (fn [resolve reject]
     (let [req (js/indexedDB.open db-name 1)]
       (set! (.-onupgradeneeded req)
             (fn [^js e]
               (let [^js db (.. e -target -result)]
                 (when-not (.. db -objectStoreNames (contains store))
                   (.createObjectStore db store)))))
       (set! (.-onsuccess req)
             (fn [_] (resolve (.-result req))))
       (set! (.-onerror req)
             (fn [_] (reject (.-error req))))))))

(defn- with-db [f]
  (if-let [^js db (unchecked-get db-ref "db")]
    (f db)
    (-> (open-db)
        (.then (fn [db]
                 (unchecked-set db-ref "db" db)
                 (f db))))))

(defn- write-value [^js db k v]
  (js/Promise.
   (fn [resolve reject]
     (let [tx    (.transaction db #js [store] "readwrite")
           st    (.objectStore tx store)
           req   (.put st v k)]
       (set! (.-onsuccess req) (fn [_] (resolve nil)))
       (set! (.-onerror   req) (fn [_] (reject (.-error req))))))))

(defn- read-value [^js db k]
  (js/Promise.
   (fn [resolve reject]
     (let [tx  (.transaction db #js [store] "readonly")
           st  (.objectStore tx store)
           req (.get st k)]
       (set! (.-onsuccess req) (fn [_] (resolve (.-result req))))
       (set! (.-onerror   req) (fn [_] (reject (.-error req))))))))

(defn- delete-value [^js db k]
  (js/Promise.
   (fn [resolve reject]
     (let [tx  (.transaction db #js [store] "readwrite")
           st  (.objectStore tx store)
           req (.delete st k)]
       (set! (.-onsuccess req) (fn [_] (resolve nil)))
       (set! (.-onerror   req) (fn [_] (reject (.-error req))))))))

;; --- debounced autosave ---------------------------------------------------

(defonce ^:private autosave-timer #js {:id nil})
(def     ^:private autosave-delay-ms 750)

(defn- cancel-timer! []
  (when-let [id (unchecked-get autosave-timer "id")]
    (js/clearTimeout id)
    (unchecked-set autosave-timer "id" nil)))

(defn- save-now! []
  (unchecked-set autosave-timer "id" nil)
  ;; Autosave writes to IDB as a safety net — it intentionally does
  ;; NOT clear `:dirty?`. That flag tracks unsaved changes relative
  ;; to the last explicit Save to file, so the New / Close prompts
  ;; can warn the user about losing work that has never been saved.
  (-> (with-db
        (fn [^js db]
          (let [payload (pr-str (serialize @state/app-state))]
            (write-value db autosave-key payload))))
      (.catch (fn [^js err] (js/console.warn "autosave failed" err)))))

(defn- schedule-save! []
  (cancel-timer!)
  (unchecked-set autosave-timer "id"
                 (js/setTimeout save-now! autosave-delay-ms)))

(defn install-autosave!
  "Install the autosave watcher on `state/app-state`. Writes trigger
   on `:document` or `:theme` changes, debounced by 750 ms."
  []
  (add-watch state/app-state ::autosave
             (fn [_ _ old-state new-state]
               (when (or (not= (:document old-state) (:document new-state))
                         (not= (:theme    old-state) (:theme    new-state)))
                 (schedule-save!)))))

;; --- restore --------------------------------------------------------------

(defn restore!
  "Attempt to restore the last autosave into `state/app-state`. Returns
   a JS Promise resolving to true when content was restored, false
   otherwise (no autosave present or parse/read failure)."
  []
  (-> (open-db)
      (.then (fn [db]
               (unchecked-set db-ref "db" db)
               (read-value db autosave-key)))
      (.then (fn [raw]
               (if-let [parsed (and raw (deserialize raw))]
                 (do
                   (swap! state/app-state
                          (fn [s]
                            (-> s
                                (assoc :document (:document parsed))
                                (assoc :theme    (or (:theme parsed) (:theme s)))
                                (assoc :dirty?   false))))
                   true)
                 false)))
      (.catch (fn [^js err]
                (js/console.warn "autosave restore failed" err)
                false))))

(defn clear-autosave!
  "Remove the autosave key. Called when a fresh project is opened from
   a file so the stale autosave can't clobber the just-opened doc on
   the next reload."
  []
  (with-db (fn [^js db] (delete-value db autosave-key))))

;; --- welcome-tour seen flag -----------------------------------------------

(def ^:private welcome-tour-key "welcome-tour-seen")

(defn welcome-tour-seen?
  "Resolve to true when the welcome tour has been completed or
   dismissed at least once in this browser. Resolves false on a
   fresh install or on read failure (failing open is intentional —
   the tour is harmless to show twice in an edge case, never
   showing it would defeat the purpose)."
  []
  (-> (with-db (fn [^js db] (read-value db welcome-tour-key)))
      (.then (fn [v] (= "true" v)))
      (.catch (fn [^js err]
                (js/console.warn "welcome-tour-seen? read failed" err)
                false))))

(defn mark-welcome-tour-seen!
  "Persist the welcome-tour-seen flag. Called when the tour completes
   or the user dismisses it. Returns a Promise."
  []
  (with-db (fn [^js db] (write-value db welcome-tour-key "true"))))
