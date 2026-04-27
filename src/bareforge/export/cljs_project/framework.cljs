(ns bareforge.export.cljs-project.framework
  "Minimal re-frame-shaped state management for exported Bareforge
   projects. Provides reg-sub, reg-event, dispatch, and subscribe
   using a plain atom — no React, no Reagent, no external deps.

   This file is exported verbatim into generated projects as
   `app.framework`. The API mirrors re-frame so migration is
   mechanical: swap the require to `re-frame.core`.")

;; --- store ----------------------------------------------------------------

(defonce ^:private store (atom nil))
(defonce ^:private store-key ::framework-watch)

(defn init-store!
  "Initialize the global store with `initial-db`. Idempotent — only
   the first call sets state; subsequent calls are no-ops so
   namespace reloads are safe."
  [initial-db]
  (when (nil? @store)
    (reset! store initial-db)))

(defn get-store
  "Return the store atom. For advanced use only — prefer subscriptions."
  []
  store)

;; --- subscriptions --------------------------------------------------------

(defonce ^:private sub-registry (atom {}))
(defonce ^:private sub-cache (atom {}))
(defonce ^:private sub-listeners (atom {}))

(defn- compute-sub
  "Compute the current value of a subscription."
  [id db]
  (when-let [handler (get @sub-registry id)]
    (handler db)))

(defn- sub-id
  "Unwrap a `:<-` ref: either a qualified keyword or a single-element
   vector (`[::sub-id]`) per re-frame's sugar."
  [ref]
  (if (vector? ref) (first ref) ref))

(defn- parse-sub-args
  "Split variadic `reg-sub` args into [input-sub-ids, tail] where tail
   is either `[:-> extract-fn]`, `[handler-fn]`, or `[]` (for the
   2-arity plain handler path)."
  [args]
  (loop [inputs [] xs args]
    (cond
      (empty? xs)              [inputs xs]
      (= :<- (first xs))       (recur (conj inputs (sub-id (second xs)))
                                      (drop 2 xs))
      :else                    [inputs xs])))

(defn reg-sub
  "Register a named subscription. Supports:

     ;; 2-arity — plain handler receives the whole db.
     (reg-sub ::name (fn [db] (:name db)))

     ;; 3-arity — :-> applies a fn to the root db.
     (reg-sub ::name :-> ::ns/field)   ;; keywords are fns
     (reg-sub ::name :-> count)

     ;; 5-arity — derived from one input sub; :-> applies a fn to it.
     (reg-sub ::name :<- [::src] :-> ::ns/field)

     ;; Multi-signal — any number of :<- pairs. Final arg is either
     ;; :-> extract-fn (applied to a vector of input values), OR a
     ;; handler-fn (receives [vec-of-values event-vec] — event-vec is
     ;; nil for subscriptions, matching re-frame).
     (reg-sub ::name :<- [::a] :<- [::b] :-> (fn [[a b]] (+ a b)))
     (reg-sub ::name :<- [::a] :<- [::b] (fn [[a b] _] (+ a b)))

   Single-input :-> unwraps the value for backward compat; multi-input
   always passes a vector."
  [id & args]
  (let [[inputs tail] (parse-sub-args args)
        extract?      (= :-> (first tail))]
    (cond
      ;; 2-arity: plain handler taking db.
      (and (empty? inputs) (not extract?))
      (swap! sub-registry assoc id (first tail))

      ;; 3-arity: :-> applied to root db.
      (and (empty? inputs) extract?)
      (let [f (second tail)]
        (swap! sub-registry assoc id (fn [db] (f db))))

      ;; Derived + :-> — unwrap single input for backward compat.
      extract?
      (let [f (second tail)]
        (swap! sub-registry assoc id
               (fn [db]
                 (let [vs (mapv #(compute-sub % db) inputs)]
                   (f (if (= 1 (count vs)) (first vs) vs))))))

      ;; Derived + plain handler (no :->). Handler receives [vs event-vec].
      :else
      (let [handler-fn (first tail)]
        (swap! sub-registry assoc id
               (fn [db]
                 (let [vs (mapv #(compute-sub % db) inputs)]
                   (handler-fn (if (= 1 (count vs)) (first vs) vs) nil))))))))

(defn qualify-map
  "Recursively re-keys a map (and nested maps / seqs of maps) with
   fully-qualified keywords in namespace `ns`. Non-map values pass
   through. Handy when a join sub pulls records from one group and
   needs to present them under another group's key shape."
  [m ns]
  (when (map? m)
    (into {}
          (for [[k v] m]
            (cond
              (and (sequential? v) (map? (first v)))
              [(keyword ns (name k)) (mapv #(qualify-map % ns) v)]

              (map? v)
              [(keyword ns (name k)) (qualify-map v ns)]

              :else
              [(keyword ns (name k)) v])))))

(defn- notify-sub-listeners!
  "Recompute a subscription and notify its listeners if the value changed."
  [id db]
  (let [old-val (get @sub-cache id ::not-found)
        new-val (compute-sub id db)]
    (when (not= old-val new-val)
      (swap! sub-cache assoc id new-val)
      (doseq [cb (vals (get @sub-listeners id))]
        (cb new-val)))))

(defn subscribe!
  "Subscribe to changes on a named subscription. Calls `callback` with
   the derived value immediately and again whenever it changes. Returns
   a dispose function.

     (subscribe! :user-name
       (fn [name] (set! (.-textContent el) name)))"
  [id callback]
  (let [listener-key (gensym "sub-")]
    (swap! sub-listeners update id assoc listener-key callback)
    (let [current (compute-sub id @store)]
      (swap! sub-cache assoc id current)
      (callback current))
    (fn dispose []
      (swap! sub-listeners update id dissoc listener-key))))

(defn query
  "Synchronously read the current value of a subscription. Accepts
   either a keyword or a vector (re-frame style) like `[::field-name]`."
  [id-or-vec]
  (let [id (if (vector? id-or-vec) (first id-or-vec) id-or-vec)]
    (compute-sub id @store)))

;; --- events ---------------------------------------------------------------

(defonce ^:private event-registry (atom {}))

;; Interceptors operate on a `context` map `{:db <db> :event <event-vec>}`.
;; Each interceptor may define `:before` and/or `:after`, each of which is
;; `ctx -> ctx`. Before-phase runs interceptors in order; after-phase runs
;; them in reverse order (classic interceptor-chain semantics).

(def trim-v
  "Interceptor that strips the event id from the event vector before the
   handler runs, so handlers can destructure positionally:
   `(fn [db [value]] ...)` instead of `(fn [db [_ value]] ...)`."
  {:id     :trim-v
   :before (fn [ctx] (update ctx :event (comp vec rest)))})

(defn path
  "Interceptor factory that narrows `db` to the subtree at `ks` before
   the handler runs, then writes the handler's return value back at that
   path. Use to simplify setter handlers:

     (reg-event ::cart-items-changed
       [trim-v (path ::cart.db/cart-items)]
       (fn [_ [new-items]] new-items))

   `ks` accepts any number of key-path elements, like `get-in`/`assoc-in`."
  [& ks]
  (let [path-ks (vec ks)]
    {:id     :path
     :before (fn [ctx]
               (-> ctx
                   (assoc ::original-db (:db ctx))
                   (assoc :db (get-in (:db ctx) path-ks))))
     :after  (fn [ctx]
               (let [narrowed (:db ctx)
                     original (::original-db ctx)]
                 (-> ctx
                     (assoc :db (assoc-in original path-ks narrowed))
                     (dissoc ::original-db))))}))

(defn- run-chain [ctx interceptors phase]
  (let [ordered (if (= phase :after) (reverse interceptors) interceptors)]
    (reduce (fn [c i]
              (if-let [f (get i phase)]
                (f c)
                c))
            ctx
            ordered)))

(defn reg-event
  "Register a named event handler. Supports two forms:

     ;; Plain — handler sees the full event vector (id included).
     (reg-event ::set-user-name
       (fn [db [_ name]] (assoc db :user-name name)))

     ;; With interceptors. The chain runs :before in order, then the
     ;; handler, then :after in reverse. Handler returns a new db (or,
     ;; under `path`, a new narrowed value that the interceptor widens
     ;; back).
     (reg-event ::cart-items-changed
       [trim-v (path ::cart.db/cart-items)]
       (fn [_ [new-items]] new-items))"
  ([id handler-fn]
   (swap! event-registry assoc id handler-fn))
  ([id interceptors handler-fn]
   (swap! event-registry assoc id
          (fn [db event-vec]
            (let [ctx        (run-chain {:db db :event event-vec}
                                        interceptors :before)
                  handler-db (handler-fn (:db ctx) (:event ctx))
                  final-ctx  (run-chain (assoc ctx :db handler-db)
                                        interceptors :after)]
              (:db final-ctx))))))

(defn dispatch
  "Dispatch an event vector. Calls the registered handler, updates
   the store, and notifies affected subscriptions.

     (dispatch [:set-user-name \"Alice\"])"
  [event-vec]
  (let [id      (first event-vec)
        handler (get @event-registry id)]
    (when handler
      (let [new-db (handler @store event-vec)]
        (reset! store new-db)))))

;; --- effects --------------------------------------------------------------

(defonce ^:private fx-registry (atom {}))

(defn reg-fx
  "Register a named effect handler. `handler-fn` receives the effect
   value and performs a side effect (API call, localStorage, etc.).

     (reg-fx :http
       (fn [{:keys [url on-success]}]
         (-> (js/fetch url)
             (.then #(.json %))
             (.then on-success))))"
  [id handler-fn]
  (swap! fx-registry assoc id handler-fn))

(defn reg-event-fx
  "Register an event handler that returns an effects map instead of
   a new db. The `:db` key updates the store; other keys dispatch
   to registered effect handlers.

     (reg-event-fx :fetch-user
       (fn [db [_ user-id]]
         {:db   (assoc db :loading? true)
          :http {:url (str \"/api/users/\" user-id)
                 :on-success #(dispatch [:user-loaded %])}}))"
  [id handler-fn]
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

;; --- testing --------------------------------------------------------------

(defn reset-all!
  "Reset all registries and the store. For testing only."
  []
  (remove-watch store store-key)
  (reset! store nil)
  (reset! sub-registry {})
  (reset! sub-cache {})
  (reset! sub-listeners {})
  (reset! event-registry {})
  (reset! fx-registry {})
  (add-watch store store-key on-store-change))
