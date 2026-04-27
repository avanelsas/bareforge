(ns app.core
  (:require [baredom.exports.all :as baredom]
            [app.db]
            [app.navbar.subs]
            [app.navbar.events]
            [app.navbar.views :as navbar]
            [app.cart.subs]
            [app.cart.events]
            [app.cart.views :as cart]))

(defn ^:export init []
  (baredom/register!)
  (let [^js app (js/document.getElementById "app")]
    (navbar/render app)
    (cart/render app)))
