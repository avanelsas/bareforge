(ns bareforge.export.cljs-project.framework-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [bareforge.export.cljs-project.framework :as rf]))

;; --- fixtures: reset all registries between tests -------------------------

(use-fixtures :each
  {:before (fn [] (rf/reset-all!))})

;; --- init-store! ----------------------------------------------------------

(deftest init-store-sets-initial-state
  (rf/init-store! {:count 0})
  (is (= {:count 0} @(rf/get-store))))

(deftest init-store-is-idempotent
  (rf/init-store! {:count 0})
  (rf/init-store! {:count 99})
  (is (= {:count 0} @(rf/get-store))))

;; --- reg-sub + query ------------------------------------------------------

(deftest reg-sub-and-query
  (rf/init-store! {:user-name "Alice" :count 3})
  (rf/reg-sub :user-name (fn [db] (:user-name db)))
  (rf/reg-sub :count (fn [db] (:count db)))
  (is (= "Alice" (rf/query :user-name)))
  (is (= 3 (rf/query :count))))

(deftest query-returns-nil-for-unknown-sub
  (rf/init-store! {:x 1})
  (is (nil? (rf/query :nonexistent))))

;; --- reg-event + dispatch -------------------------------------------------

(deftest dispatch-updates-store
  (rf/init-store! {:count 0})
  (rf/reg-event :inc (fn [db _] (update db :count inc)))
  (rf/dispatch [:inc])
  (is (= 1 (:count @(rf/get-store)))))

(deftest dispatch-passes-event-vector
  (rf/init-store! {:name ""})
  (rf/reg-event :set-name (fn [db [_ n]] (assoc db :name n)))
  (rf/dispatch [:set-name "Bob"])
  (is (= "Bob" (:name @(rf/get-store)))))

(deftest dispatch-unknown-event-is-noop
  (rf/init-store! {:x 1})
  (rf/dispatch [:bogus])
  (is (= {:x 1} @(rf/get-store))))

;; --- subscribe! -----------------------------------------------------------

(deftest subscribe-calls-back-immediately
  (rf/init-store! {:count 5})
  (rf/reg-sub :count (fn [db] (:count db)))
  (let [received (atom nil)]
    (rf/subscribe! :count (fn [v] (reset! received v)))
    (is (= 5 @received))))

(deftest subscribe-reacts-to-dispatch
  (rf/init-store! {:count 0})
  (rf/reg-sub :count (fn [db] (:count db)))
  (rf/reg-event :inc (fn [db _] (update db :count inc)))
  (let [values (atom [])]
    (rf/subscribe! :count (fn [v] (swap! values conj v)))
    (rf/dispatch [:inc])
    (rf/dispatch [:inc])
    (is (= [0 1 2] @values))))

(deftest subscribe-skips-unchanged-values
  (rf/init-store! {:count 0 :other "x"})
  (rf/reg-sub :count (fn [db] (:count db)))
  (rf/reg-event :touch-other (fn [db _] (assoc db :other "y")))
  (let [calls (atom 0)]
    (rf/subscribe! :count (fn [_] (swap! calls inc)))
    (rf/dispatch [:touch-other])
    (is (= 1 @calls) "only the initial call, no spurious update")))

(deftest subscribe-dispose-stops-notifications
  (rf/init-store! {:count 0})
  (rf/reg-sub :count (fn [db] (:count db)))
  (rf/reg-event :inc (fn [db _] (update db :count inc)))
  (let [values (atom [])
        dispose (rf/subscribe! :count (fn [v] (swap! values conj v)))]
    (rf/dispatch [:inc])
    (dispose)
    (rf/dispatch [:inc])
    (is (= [0 1] @values) "no notification after dispose")))

;; --- :-> sub arms: apply any fn ------------------------------------------

(deftest reg-sub-3-arity-arrow-applies-fn-to-root-db
  (rf/init-store! {:items [1 2 3]})
  (rf/reg-sub :num-items :-> (comp count :items))
  (is (= 3 (rf/query :num-items)))
  (rf/reg-sub :items :-> :items)
  (is (= [1 2 3] (rf/query :items))
      "keyword as :-> fn still works (keywords ARE functions)"))

(deftest reg-sub-5-arity-arrow-applies-fn-to-input-sub
  (rf/init-store! {:items [1 2 3]})
  (rf/reg-sub :items :-> :items)
  (rf/reg-sub :count-items :<- [:items] :-> count)
  (is (= 3 (rf/query :count-items)))
  (rf/reg-sub :sum-items :<- [:items] :-> #(reduce + 0 %))
  (is (= 6 (rf/query :sum-items))))

;; --- multi-signal reg-sub -------------------------------------------------

(deftest reg-sub-multi-signal-with-arrow
  (rf/init-store! {:a 2 :b 3})
  (rf/reg-sub :a :-> :a)
  (rf/reg-sub :b :-> :b)
  (rf/reg-sub :sum :<- [:a] :<- [:b] :-> (fn [[a b]] (+ a b)))
  (is (= 5 (rf/query :sum))))

(deftest reg-sub-multi-signal-with-handler-fn
  (rf/init-store! {:xs [1 2 3] :mult 10})
  (rf/reg-sub :xs   :-> :xs)
  (rf/reg-sub :mult :-> :mult)
  (rf/reg-sub :scaled
    :<- [:xs] :<- [:mult]
    (fn [[xs m] _] (mapv #(* m %) xs)))
  (is (= [10 20 30] (rf/query :scaled))))

(deftest reg-sub-single-signal-unwraps-for-backward-compat
  (rf/init-store! {:items [1 2 3]})
  (rf/reg-sub :items :-> :items)
  (rf/reg-sub :n :<- [:items] :-> count)
  (is (= 3 (rf/query :n)) "single-input :-> passes the unwrapped value"))

;; --- reg-event-fx + reg-fx ------------------------------------------------

(deftest event-fx-updates-db-and-fires-effects
  (rf/init-store! {:loading? false})
  (let [fx-log (atom [])]
    (rf/reg-fx :log (fn [v] (swap! fx-log conj v)))
    (rf/reg-event-fx :start-load
      (fn [db _]
        {:db  (assoc db :loading? true)
         :log "started"}))
    (rf/dispatch [:start-load])
    (is (= true (:loading? @(rf/get-store))))
    (is (= ["started"] @fx-log))))

(deftest event-fx-without-db-key-preserves-state
  (rf/init-store! {:count 42})
  (let [fx-log (atom [])]
    (rf/reg-fx :log (fn [v] (swap! fx-log conj v)))
    (rf/reg-event-fx :side-effect-only
      (fn [_db _]
        {:log "fired"}))
    (rf/dispatch [:side-effect-only])
    (is (= {:count 42} @(rf/get-store)) "db unchanged")
    (is (= ["fired"] @fx-log))))

;; --- multiple subscriptions -----------------------------------------------

(deftest multiple-subs-track-independently
  (rf/init-store! {:a 1 :b 2})
  (rf/reg-sub :a (fn [db] (:a db)))
  (rf/reg-sub :b (fn [db] (:b db)))
  (rf/reg-event :inc-a (fn [db _] (update db :a inc)))
  (let [a-vals (atom [])
        b-vals (atom [])]
    (rf/subscribe! :a (fn [v] (swap! a-vals conj v)))
    (rf/subscribe! :b (fn [v] (swap! b-vals conj v)))
    (rf/dispatch [:inc-a])
    (is (= [1 2] @a-vals))
    (is (= [2] @b-vals) ":b did not change, no extra callback")))
