(ns bareforge.util.dom
  "Tiny imperative DOM helpers used by ui/* and render/* code. Pure-zone
   namespaces must not depend on this file."
  (:require [clojure.string :as str]))

(defn el
  "Create a DOM element. `attrs` is a map of string/keyword → value;
   `children` is a seq of DOM nodes (or nil)."
  ([tag] (el tag nil nil))
  ([tag attrs] (el tag attrs nil))
  ([tag attrs children]
   (let [^js e (js/document.createElement (name tag))]
     (doseq [[k v] attrs]
       (when (some? v)
         (.setAttribute e (name k) (str v))))
     (doseq [c children]
       (when (some? c)
         (.appendChild e c)))
     e)))

(defn on!
  "Attach an event listener. Returns the element for threading."
  [^js element event-name handler]
  (.addEventListener element (name event-name) handler)
  element)

(defn set-attr!
  "Set or remove an attribute on an element. Nil value removes."
  [^js element k v]
  (if (nil? v)
    (.removeAttribute element (name k))
    (.setAttribute element (name k) (str v)))
  element)

(defn set-text!
  "Replace all content with a single text node."
  [^js element text]
  (set! (.-textContent element) (or text ""))
  element)

(defn by-id
  "Shortcut for document.getElementById."
  ^js [id]
  (js/document.getElementById id))

;; --- colour normalization -------------------------------------------------
;; `x-color-picker` only accepts hex (`#rgb`, `#rrggbb`, `#rrggbbaa`);
;; values read from `getComputedStyle` come out as `rgb(...)` /
;; `rgba(...)` / named / hex. These helpers coerce anything the
;; browser understands into hex so the picker opens pre-populated.

(defn- byte->hex2 [n]
  (let [b (-> n js/Math.round (max 0) (min 255))
        s (.toString b 16)]
    (if (< (count s) 2) (str "0" s) s)))

(defn- rgba-str->hex
  "Parse `rgb(r, g, b)` / `rgba(r, g, b, a)` into `#rrggbb` or
   `#rrggbbaa`. Returns nil when the string doesn't match."
  [s]
  (when-let [m (re-find #"(?i)^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)(?:\s*[,/]\s*([\d.]+))?\s*\)$"
                       s)]
    (let [[_ r g b a] m
          hex6 (str "#" (byte->hex2 (js/parseFloat r))
                    (byte->hex2 (js/parseFloat g))
                    (byte->hex2 (js/parseFloat b)))]
      (if (and a (< (js/parseFloat a) 1))
        (str hex6 (byte->hex2 (* 255 (js/parseFloat a))))
        hex6))))

(defn css-color->hex
  "Best-effort convert any CSS colour (`rgb(...)`, `hsl(...)`, named,
   hex) into `#rrggbb` or `#rrggbbaa`. Uses a Canvas 2D fillStyle
   round-trip — the browser parses the colour and returns a
   normalized form (either `#rrggbb` for opaque colours or
   `rgba(r,g,b,a)` for anything with transparency). Returns nil when
   the value is blank or the browser can't parse it."
  [s]
  (when (and (string? s) (not= "" (str/trim s)))
    (try
      (let [^js ctx (.getContext (js/document.createElement "canvas") "2d")]
        (set! (.-fillStyle ctx) "#000000")
        (set! (.-fillStyle ctx) s)
        (let [norm (.-fillStyle ctx)]
          (cond
            (str/starts-with? norm "#") norm
            (str/starts-with? norm "rgb") (rgba-str->hex norm)
            :else nil)))
      (catch :default _ nil))))
