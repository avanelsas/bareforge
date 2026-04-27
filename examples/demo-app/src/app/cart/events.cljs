(ns app.cart.events
  (:require [app.framework :as rf]))

(rf/reg-event :add-to-cart
  (fn [db [_ product-name]]
    (-> db
        (update :cart-count inc)
        (assoc :last-product product-name))))
