(ns app.db
  (:require [app.framework :as rf]
            [app.navbar.db :as navbar]
            [app.cart.db :as cart]))

(rf/init-store! (merge navbar/default-db
                       cart/default-db))
