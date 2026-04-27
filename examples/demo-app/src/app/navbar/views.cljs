(ns app.navbar.views
  (:require [app.framework :as rf]))

(defn render [^js parent]
  (let [^js navbar (js/document.createElement "x-navbar")]
    (.setAttribute navbar "variant" "default")
    (.setAttribute navbar "elevated" "")

    ;; brand — static, inline
    (let [^js brand (js/document.createElement "x-typography")]
      (.setAttribute brand "slot" "brand")
      (.setAttribute brand "variant" "h4")
      (set! (.-textContent brand) "Demo Store")
      (.appendChild navbar brand))

    ;; search field — writes :search-query
    (let [^js search (js/document.createElement "x-search-field")]
      (.setAttribute search "placeholder" "Search products...")
      (.setAttribute search "aria-label" "Search products")
      (.addEventListener search "input"
        (fn [^js e]
          (rf/dispatch [:set-search-query (.. e -target -value)])))
      (.appendChild navbar search))

    ;; cart icon + badge — reads :cart-count (from cart module)
    (let [^js icon (js/document.createElement "x-icon")]
      (.setAttribute icon "slot" "actions")
      (set! (.-innerHTML icon)
            "<svg xmlns=\"http://www.w3.org/2000/svg\" fill=\"none\" viewBox=\"0 0 24 24\" stroke-width=\"1.5\" stroke=\"currentColor\" class=\"size-6\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M2.25 3h1.386c.51 0 .955.343 1.087.835l.383 1.437M7.5 14.25a3 3 0 0 0-3 3h15.75m-12.75-3h11.218c1.121-2.3 2.1-4.684 2.924-7.138a60.114 60.114 0 0 0-16.536-1.84M7.5 14.25 5.106 5.272M6 20.25a.75.75 0 1 1-1.5 0 .75.75 0 0 1 1.5 0Zm12.75 0a.75.75 0 1 1-1.5 0 .75.75 0 0 1 1.5 0Z\"/></svg>")
      (.appendChild navbar icon))

    (let [^js badge (js/document.createElement "x-badge")]
      (.setAttribute badge "slot" "actions")
      (.setAttribute badge "variant" "info")
      (.setAttribute badge "pill" "")
      (rf/subscribe! :cart-count
        (fn [n]
          (.setAttribute badge "text" (str n))))
      (.appendChild navbar badge))

    (.appendChild parent navbar)
    navbar))
