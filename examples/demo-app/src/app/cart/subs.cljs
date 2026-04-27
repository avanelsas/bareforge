(ns app.cart.subs
  (:require [app.framework :as rf]))

(rf/reg-sub :cart-count (fn [db] (:cart-count db)))
(rf/reg-sub :last-product (fn [db] (:last-product db)))
