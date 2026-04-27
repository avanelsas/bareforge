(ns bareforge.export.html-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.export.html :as html]))

;; --- escape ---------------------------------------------------------------

(deftest escape-attr-basic
  (is (= "plain" (html/escape-attr "plain")))
  (is (= "a &amp; b" (html/escape-attr "a & b")))
  (is (= "&quot;hi&quot;" (html/escape-attr "\"hi\"")))
  (is (= "&lt;x&gt;" (html/escape-attr "<x>"))))

(deftest escape-text-does-not-quote
  (is (= "\"fine\"" (html/escape-text "\"fine\"")))
  (is (= "a &amp; b" (html/escape-text "a & b"))))

;; --- serialize-node -------------------------------------------------------

(deftest serialize-leaf
  (let [n (m/make-node "n1" "x-button"
                       {:attrs {"variant" "primary"}
                        :text  "Click me"})]
    (is (= "<x-button variant=\"primary\">Click me</x-button>"
           (html/serialize-node n)))))

(deftest serialize-sorts-attrs-deterministically
  (let [n (m/make-node "n1" "x-button"
                       {:attrs {"size" "sm" "variant" "primary"}})]
    (is (= "<x-button size=\"sm\" variant=\"primary\"></x-button>"
           (html/serialize-node n)))))

(deftest serialize-with-slot
  (let [n (m/make-node "n1" "x-typography"
                       {:text "Hi"})]
    (is (str/includes? (html/serialize-node n "brand")
                       "slot=\"brand\""))
    (is (not (str/includes? (html/serialize-node n "default")
                            "slot=")))))

(deftest serialize-nested-children
  (let [d0 (m/empty-document)
        {d1 :doc id :id} (ops/insert-new d0 "root" "default" 0 "x-card")
        {d2 :doc}        (ops/insert-new d1 id "default" 0 "x-typography"
                                         {:text "Hello"
                                          :attrs {"variant" "h1"}})
        html (html/serialize-node (get-in d2 [:root]))]
    (is (str/includes? html "<x-container"))
    (is (str/includes? html "<x-card>"))
    (is (str/includes? html "<x-typography variant=\"h1\">Hello</x-typography>"))))

(deftest serialize-inner-html-emits-unescaped
  (testing "raw SVG markup round-trips byte-for-byte through :inner-html
            with no escaping, taking precedence over :text and children"
    (let [svg "<svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"/></svg>"
          n   (m/make-node "n1" "x-icon"
                           {:inner-html svg
                            :text       "ignored"})]
      (is (= (str "<x-icon>" svg "</x-icon>")
             (html/serialize-node n))))))

(deftest serialize-escapes-text-and-attrs
  (let [n (m/make-node "n1" "x-typography"
                       {:text "a <b> & \"c\""
                        :attrs {"label" "hi \"world\""}})
        out (html/serialize-node n)]
    (is (str/includes? out "a &lt;b&gt; &amp; \"c\""))
    (is (str/includes? out "label=\"hi &quot;world&quot;\""))))

;; --- collect-tags ---------------------------------------------------------

(deftest collect-tags-returns-sorted-unique
  (let [d0 (m/empty-document)
        {d1 :doc} (ops/insert-new d0 "root" "default" 0 "x-button")
        {d2 :doc} (ops/insert-new d1 "root" "default" 1 "x-typography")
        {d3 :doc} (ops/insert-new d2 "root" "default" 2 "x-button")
        tags (html/collect-tags d3)]
    (is (= #{"x-container" "x-button" "x-typography"} (set tags)))
    ;; sorted: x-button x-container x-typography
    (is (= ["x-button" "x-container" "x-typography"] (vec tags)))))

;; --- render-html ----------------------------------------------------------

(defn- simple-snapshot []
  (let [d0 (m/empty-document)
        {d1 :doc} (ops/insert-new d0 "root" "default" 0 "x-typography"
                                  {:text "Hello"
                                   :attrs {"variant" "h1"}})]
    {:document d1
     :theme    {:base-preset "ocean"
                :overrides   {"--x-color-primary" "#ff00aa"
                              "--x-radius-lg"     "24px"}}}))

(deftest render-html-shell
  (let [out (html/render-html (simple-snapshot) {:title "My page"})]
    (testing "doctype and title"
      (is (str/starts-with? out "<!doctype html>"))
      (is (str/includes? out "<title>My page</title>")))
    (testing "every used tag appears in the dynamic-import tag list"
      (is (str/includes? out "\"x-container\""))
      (is (str/includes? out "\"x-typography\"")))
    (testing "init() is called after each module import"
      (is (str/includes? out "mod.init()")))
    (testing "x-theme wrapper with preset and overrides"
      (is (str/includes? out "<x-theme preset=\"ocean\""))
      (is (str/includes? out "--x-color-primary:#ff00aa;"))
      (is (str/includes? out "--x-radius-lg:24px;")))
    (testing "serialized body"
      (is (str/includes? out "<x-typography variant=\"h1\">Hello</x-typography>")))))

(deftest render-html-cdn-version-honoured
  (let [out (html/render-html (simple-snapshot) {:cdn-version "2.1.5"})]
    (is (str/includes? out "npm/@vanelsas/baredom@2.1.5/dist/"))))

(deftest render-html-imports-x-theme-wrapper
  (let [out (html/render-html (simple-snapshot) nil)]
    ;; The x-theme wrapper in the shell must always be included in the
    ;; dynamic-import tag list even though it is not a document node.
    (is (str/includes? out "\"x-theme\""))))

(deftest render-html-default-preset-when-missing
  (let [snap (assoc-in (simple-snapshot) [:theme :base-preset] nil)
        out  (html/render-html snap nil)]
    (is (str/includes? out "<x-theme preset=\"default\""))))

(deftest render-html-includes-canvas-width-constraint
  (testing "the exported style block constrains the root to the canvas width"
    (let [out (html/render-html (simple-snapshot) nil)]
      (is (str/includes? out "max-width: 1200px"))
      (is (str/includes? out "margin-left: auto"))
      (is (str/includes? out "body > x-theme > :first-child")))))

(deftest render-html-import-base-override-rewrites-cdn-url
  (testing "a custom :import-base (bundle-mode) replaces the CDN base entirely"
    (let [out (html/render-html (simple-snapshot)
                                {:import-base "./vendor/baredom/"})]
      (is (str/includes? out "const __base = \"./vendor/baredom/\";"))
      (is (not (str/includes? out "cdn.jsdelivr.net"))
          "CDN URL must not appear when an import-base override is supplied"))))

(deftest render-html-default-csp-allows-jsdelivr
  (testing "default CDN-mode export carries a Content-Security-Policy "
    "that whitelists jsDelivr — Bareforge's audit gate against "
    "third-party script hijacking via supply-chain compromise"
    (let [out (html/render-html (simple-snapshot) nil)]
      (is (str/includes? out "Content-Security-Policy"))
      (is (str/includes? out "default-src 'self'"))
      (is (str/includes? out "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net"))
      (is (str/includes? out "object-src 'none'")))))

(deftest render-html-bundle-csp-excludes-jsdelivr
  (testing "when :import-base is set (bundle export), the CSP narrows to 'self' only"
    (let [out (html/render-html (simple-snapshot)
                                {:import-base "./vendor/baredom/"})]
      (is (str/includes? out "Content-Security-Policy"))
      (is (not (str/includes? out "cdn.jsdelivr.net"))
          "self-hosted bundle has no business referencing the CDN"))))

(deftest render-html-without-manifest-omits-preload-block
  (testing "default render (no manifest passed) ships no <link rel=modulepreload> "
    "block — matches today's behaviour exactly until BareDOM publishes integrity.json"
    (let [out (html/render-html (simple-snapshot) nil)]
      (is (not (str/includes? out "rel=\"modulepreload\""))))))

(deftest render-html-with-manifest-emits-sri-preload-for-each-tag
  (testing "passing :integrity-manifest causes every BareDOM tag the doc loads "
    "to gain a <link rel=modulepreload integrity=…> entry — SRI-binding "
    "the dynamic import() to the bytes BareDOM published"
    (let [manifest {:version "2.4.0" :algorithm "sha384"
                    :files {"x-container.js"  "sha384-AAA"
                            "x-typography.js" "sha384-BBB"
                            "x-theme.js"      "sha384-CCC"}}
          out (html/render-html (simple-snapshot)
                                {:integrity-manifest manifest})]
      (is (str/includes? out "rel=\"modulepreload\""))
      (is (str/includes? out "integrity=\"sha384-AAA\""))
      (is (str/includes? out "integrity=\"sha384-BBB\""))
      (is (str/includes? out "integrity=\"sha384-CCC\""))
      (is (str/includes? out "x-container.js"))
      (is (str/includes? out "x-typography.js"))
      (is (str/includes? out "x-theme.js"))
      (is (str/includes? out "crossorigin=\"anonymous\"")))))
