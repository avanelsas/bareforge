(ns app.cart.views
  (:require [app.framework :as rf]))

(defn- product-card
  [^js parent product-name price]
  (let [^js card (js/document.createElement "x-card")]
    (.setAttribute card "variant" "outlined")
    (.setAttribute card "padding" "md")

    (let [^js title (js/document.createElement "x-typography")]
      (.setAttribute title "variant" "h5")
      (set! (.-textContent title) product-name)
      (.appendChild card title))

    (let [^js price-el (js/document.createElement "x-typography")]
      (.setAttribute price-el "variant" "body1")
      (set! (.-textContent price-el) (str "$" price))
      (.appendChild card price-el))

    (let [^js btn (js/document.createElement "x-button")]
      (.setAttribute btn "variant" "primary")
      (let [^js label (js/document.createElement "x-typography")]
        (set! (.-textContent label) "Add to cart")
        (.appendChild btn label))
      (.addEventListener btn "click"
        (fn [_] (rf/dispatch [:add-to-cart product-name])))
      (.appendChild card btn))

    (.appendChild parent card)
    card))

(defn- status-bar
  [^js parent]
  (let [^js bar (js/document.createElement "x-card")]
    (.setAttribute bar "variant" "filled")
    (.setAttribute bar "padding" "sm")

    (let [^js msg (js/document.createElement "x-typography")]
      (.setAttribute msg "variant" "body2")
      (rf/subscribe! :last-product
        (fn [product]
          (set! (.-textContent msg)
                (if (seq product)
                  (str "Last added: " product)
                  "No items in cart yet"))))
      (.appendChild bar msg))

    (.appendChild parent bar)
    bar))

(defn render [^js parent]
  (let [^js container (js/document.createElement "x-container")]
    (status-bar container)

    (let [^js spacer (js/document.createElement "x-spacer")]
      (.setAttribute spacer "size" "1rem")
      (.appendChild container spacer))

    (let [^js grid (js/document.createElement "x-grid")]
      (.setAttribute grid "columns" "1")
      (.setAttribute grid "row-gap" "0.5rem")
      (product-card grid "Widget" "9.99")
      (product-card grid "Gadget" "24.99")
      (product-card grid "Gizmo" "14.99")
      (.appendChild container grid))

    (.appendChild parent container)
    container))
