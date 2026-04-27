(ns bareforge.export.html-to-hiccup-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bareforge.export.html-to-hiccup :as h2h]))

;; --- string output (existing CLJS-source path) ----------------------------

(deftest simple-element
  (is (= "[:div]" (h2h/html->hiccup-str "<div></div>"))))

(deftest element-with-text
  (is (= "[:p \"Hello\"]" (h2h/html->hiccup-str "<p>Hello</p>"))))

(deftest element-with-attributes
  (testing "single attribute stays inline"
    (let [result (h2h/html->hiccup-str "<div class=\"foo\"></div>")]
      (is (= "[:div {:class \"foo\"}]" result))))
  (testing "two or more attributes go one-per-line with aligned indent"
    (let [result (h2h/html->hiccup-str "<div class=\"foo\" id=\"bar\"></div>")]
      (is (= "[:div {:class \"foo\"\n      :id \"bar\"}]" result)))))

(deftest self-closing-tag
  (is (= "[:br]" (h2h/html->hiccup-str "<br/>")))
  (is (= "[:img {:src \"a.png\"}]" (h2h/html->hiccup-str "<img src=\"a.png\">"))))

(deftest nested-elements
  (let [result (h2h/html->hiccup-str "<div><span>Hi</span></div>")]
    (is (= "[:div\n [:span \"Hi\"]]" result))))

(deftest svg-icon-round-trips
  (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" fill=\"none\" viewBox=\"0 0 24 24\" stroke-width=\"1.5\" stroke=\"currentColor\"><path stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M2.25 3h1.386\"/></svg>"
        result (h2h/html->hiccup-str svg)]
    (testing "produces :svg root"
      (is (str/starts-with? result "[:svg")))
    (testing "contains :path child"
      (is (str/includes? result "[:path")))
    (testing "preserves stroke attribute"
      (is (str/includes? result ":stroke \"currentColor\"")))
    (testing "preserves d attribute"
      (is (str/includes? result ":d \"M2.25 3h1.386\"")))))

(deftest boolean-attributes
  (let [result (h2h/html->hiccup-str "<input disabled>")]
    (is (str/includes? result ":disabled true"))))

(deftest indentation-at-depth
  (let [result (h2h/html->hiccup-str "<p>Hi</p>" 4)]
    (is (str/starts-with? result "    [:p"))))

(deftest multiple-root-elements
  (let [result (h2h/html->hiccup-str "<span>A</span><span>B</span>")]
    (is (str/includes? result "[:span \"A\"]"))
    (is (str/includes? result "[:span \"B\"]"))))

;; --- data output (new vanilla-JS path) ------------------------------------

(deftest parse-html-empty-input-returns-empty-vector
  (is (= [] (h2h/parse-html "")))
  (is (= [] (h2h/parse-html nil))))

(deftest parse-html-text-only
  (is (= ["hello"] (h2h/parse-html "hello"))))

(deftest parse-html-simple-element
  (is (= [{:tag "div" :attrs nil :children []}]
         (h2h/parse-html "<div></div>"))))

(deftest parse-html-element-with-text
  (is (= [{:tag "p" :attrs nil :children ["Hello"]}]
         (h2h/parse-html "<p>Hello</p>"))))

(deftest parse-html-attributes-stay-as-data
  (let [[node] (h2h/parse-html "<div class=\"foo\" id=\"bar\"></div>")]
    (is (= "div" (:tag node)))
    (is (= {"class" "foo" "id" "bar"} (:attrs node)))))

(deftest parse-html-self-closing
  (is (= [{:tag "br" :attrs nil :children []}]
         (h2h/parse-html "<br/>")))
  (is (= [{:tag "img" :attrs {"src" "a.png"} :children []}]
         (h2h/parse-html "<img src=\"a.png\">"))))

(deftest parse-html-nested
  (let [[node] (h2h/parse-html "<div><span>Hi</span></div>")]
    (is (= "div" (:tag node)))
    (is (= 1 (count (:children node))))
    (is (= {:tag "span" :attrs nil :children ["Hi"]}
           (first (:children node))))))

(deftest parse-html-svg-icon-data-shape
  (let [[svg] (h2h/parse-html
                "<svg viewBox=\"0 0 24 24\"><path d=\"M0 0\"/></svg>")]
    (is (= "svg" (:tag svg)))
    (is (= "0 0 24 24" (get (:attrs svg) "viewBox")))
    (is (= 1 (count (:children svg))))
    (is (= "path" (-> svg :children first :tag)))
    (is (= "M0 0" (-> svg :children first :attrs (get "d"))))))

(deftest parse-html-multiple-roots
  (is (= 2 (count (h2h/parse-html "<span>A</span><span>B</span>")))))

(deftest parse-html-boolean-attribute
  (let [[node] (h2h/parse-html "<input disabled>")]
    (is (true? (get (:attrs node) "disabled")))))
