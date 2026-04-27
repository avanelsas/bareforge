(ns app.framework
  "Minimal re-frame-shaped state management. Provides reg-sub,
   reg-event, dispatch, and subscribe using a plain atom — no React,
   no Reagent, no external deps. API mirrors re-frame so migration
   is mechanical: swap the require to `re-frame.core`.")

;; --- store ----------------------------------------------------------------

(defonce ^:private store (atom nil))
(defonce ^:private store-key ::framework-watch)

(defn init-store! [initial-db]
  (when (nil? @store)
    (reset! store initial-db)))

(defn get-store [] store)

;; --- subscriptions --------------------------------------------------------

(defonce ^:private sub-registry (atom {}))
(defonce ^:private sub-cache (atom {}))
(defonce ^:private sub-listeners (atom {}))

(defn reg-sub [id handler-fn]
  (swap! sub-registry assoc id handler-fn))

(defn- compute-sub [id db]
  (when-let [handler (get @sub-registry id)]
    (handler db)))

(defn- notify-sub-listeners! [id db]
  (let [old-val (get @sub-cache id ::not-found)
        new-val (compute-sub id db)]
    (when (not= old-val new-val)
      (swap! sub-cache assoc id new-val)
      (doseq [cb (vals (get @sub-listeners id))]
        (cb new-val)))))

(defn subscribe! [id callback]
  (let [listener-key (gensym "sub-")]
    (swap! sub-listeners update id assoc listener-key callback)
    (let [current (compute-sub id @store)]
      (swap! sub-cache assoc id current)
      (callback current))
    (fn dispose []
      (swap! sub-listeners update id dissoc listener-key))))

(defn query [id]
  (compute-sub id @store))

;; --- events ---------------------------------------------------------------

(defonce ^:private event-registry (atom {}))

(defn reg-event [id handler-fn]
  (swap! event-registry assoc id handler-fn))

(defn dispatch [event-vec]
  (let [id      (first event-vec)
        handler (get @event-registry id)]
    (when handler
      (let [new-db (handler @store event-vec)]
        (reset! store new-db)))))

;; --- effects --------------------------------------------------------------

(defonce ^:private fx-registry (atom {}))

(defn reg-fx [id handler-fn]
  (swap! fx-registry assoc id handler-fn))

(defn reg-event-fx [id handler-fn]
  (swap! event-registry assoc id
         (fn [db event-vec]
           (let [effects (handler-fn db event-vec)]
             (doseq [[fx-id fx-val] (dissoc effects :db)]
               (when-let [fx-handler (get @fx-registry fx-id)]
                 (fx-handler fx-val)))
             (get effects :db db)))))

;; --- store watcher --------------------------------------------------------

(defn- on-store-change [_ _ old-db new-db]
  (when (not= old-db new-db)
    (doseq [id (keys @sub-registry)]
      (notify-sub-listeners! id new-db))))

(defonce ^:private watcher-installed?
  (do (add-watch store store-key on-store-change)
      true))
