(ns app.navbar.subs
  (:require [app.framework :as rf]))

(rf/reg-sub :search-query (fn [db] (:search-query db)))
