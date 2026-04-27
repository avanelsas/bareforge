(ns app.navbar.events
  (:require [app.framework :as rf]))

(rf/reg-event :set-search-query
  (fn [db [_ query]]
    (assoc db :search-query query)))
