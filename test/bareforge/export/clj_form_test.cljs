(ns bareforge.export.clj-form-test
  (:require [cljs.test :refer [deftest is testing]]
            [cljs.reader :as edn]
            [bareforge.export.clj-form :as cf]))

;; --- leaves ---------------------------------------------------------------

(deftest leaf-strings-quote-and-escape
  (is (= "\"hello\""        (cf/format-form "hello")))
  (is (= "\"with \\\"q\\\"\"" (cf/format-form "with \"q\""))))

(deftest leaf-keywords-keep-namespace
  (is (= ":foo"             (cf/format-form :foo)))
  (is (= ":app.cart/items"  (cf/format-form :app.cart/items))))

(deftest leaf-numbers-and-bools-and-nil
  (is (= "42"    (cf/format-form 42)))
  (is (= "3.14"  (cf/format-form 3.14)))
  (is (= "true"  (cf/format-form true)))
  (is (= "false" (cf/format-form false)))
  (is (= "nil"   (cf/format-form nil))))

;; --- :literal / :symbol / :keyword ---------------------------------------

(deftest literal-prints-via-natural-printer
  (is (= "\"x\"" (cf/format-form [:literal "x"])))
  (is (= "42"    (cf/format-form [:literal 42]))))

(deftest symbol-bare-no-quoting
  (is (= "rf/reg-sub" (cf/format-form [:symbol 'rf/reg-sub])))
  (is (= "count"      (cf/format-form [:symbol 'count]))))

(deftest keyword-explicit-form
  (is (= ":->"           (cf/format-form [:keyword :->])))
  (is (= ":app.cart/foo" (cf/format-form [:keyword :app.cart/foo]))))

(deftest auto-keyword-emits-double-colon
  (testing "bare name auto-resolves to consuming file's ns"
    (is (= "::cart-count"
           (cf/format-form [:auto-keyword "cart-count"]))))
  (testing "slash-qualified name uses the file's :require alias"
    (is (= "::cart.db/cart-count"
           (cf/format-form [:auto-keyword "cart.db/cart-count"])))))

;; --- :raw escape hatch ---------------------------------------------------

(deftest raw-passes-through-verbatim
  (is (= "(some/thing :raw)" (cf/format-form [:raw "(some/thing :raw)"]))))

;; --- :comment ------------------------------------------------------------

(deftest comment-prefix
  (is (= ";; the cart count" (cf/format-form [:comment "the cart count"]))))

;; --- :reader-tag ---------------------------------------------------------

(deftest reader-tag-renders-with-hash-prefix
  (is (= "#js {\"id\" \"x\"}"
         (cf/format-form [:reader-tag "js" [:map "id" "x"]]))))

;; --- :vector -------------------------------------------------------------

(deftest vector-inline-when-short
  (is (= "[1 2 3]" (cf/format-form [:vector 1 2 3])))
  (is (= "[]"      (cf/format-form [:vector]))))

(deftest vector-preserves-ordering
  (is (= "[:a :b :c]" (cf/format-form [:vector :a :b :c]))))

;; --- :map ---------------------------------------------------------------

(deftest map-inline-when-short
  (is (= "{:a 1, :b 2}" (cf/format-form [:map :a 1 :b 2]))))

(deftest map-empty
  (is (= "{}" (cf/format-form [:map]))))

;; --- :invoke ------------------------------------------------------------

(deftest invoke-inline
  (is (= "(count xs)"
         (cf/format-form [:invoke [:symbol 'count] [:symbol 'xs]]))))

(deftest invoke-no-args
  (is (= "(now)" (cf/format-form [:invoke [:symbol 'now]]))))

(deftest invoke-block-when-spilling
  (testing "long invocations break, args one per line under (head"
    (let [out (cf/format-form
               [:invoke [:symbol 'rf/reg-sub]
                [:keyword :app.cart.subs/cart-count]
                [:keyword :->]
                [:keyword :app.cart.db/cart-count-very-long-name]])]
      (is (= (str "(rf/reg-sub\n"
                  " :app.cart.subs/cart-count\n"
                  " :->\n"
                  " :app.cart.db/cart-count-very-long-name)")
             out)))))

(deftest invoke-block-always-multiline
  (testing ":invoke-block forces multi-line layout even for short
            forms — used for declarations whose line-by-line shape
            is part of the readable output (reg-sub, reg-event)"
    (is (= "(rf/reg-sub\n ::cart-count\n :->\n ::cart.db/cart-count)"
           (cf/format-form
            [:invoke-block [:symbol 'rf/reg-sub]
             [:auto-keyword "cart-count"]
             [:keyword :->]
             [:auto-keyword "cart.db/cart-count"]])))))

(deftest pair-renders-as-single-arg
  (testing ":pair keeps a directive keyword on the same line as its
            value, so multi-line invoke-block doesn't split a logical
            unit like `:-> ::field` across two lines"
    (is (= ":-> ::cart.db/cart-count"
           (cf/format-form
            [:pair [:keyword :->] [:auto-keyword "cart.db/cart-count"]])))))

(deftest invoke-block-with-pair-children
  (testing "a reg-sub built from a leading id + paired :<-/:-> args
            produces the canonical reg-sub layout used throughout
            the cljs-project sub emitters"
    (is (= (str "(rf/reg-sub\n"
                " ::cart-count\n"
                " :<- [::cart-items]\n"
                " :-> count)")
           (cf/format-form
            [:invoke-block [:symbol 'rf/reg-sub]
             [:auto-keyword "cart-count"]
             [:pair [:keyword :<-]
              [:vector [:auto-keyword "cart-items"]]]
             [:pair [:keyword :->] [:symbol 'count]]])))))

;; --- :def ---------------------------------------------------------------

(deftest def-inline
  (is (= "(def n 42)"
         (cf/format-form [:def [:symbol 'n] [:literal 42]]))))

;; --- :defn --------------------------------------------------------------

(deftest defn-without-docstring
  (let [out (cf/format-form
             [:defn [:symbol 'add]
              [:vector [:symbol 'a] [:symbol 'b]]
              [:invoke [:symbol '+] [:symbol 'a] [:symbol 'b]]])]
    (is (= (str "(defn add\n"
                "  [a b]\n"
                "  (+ a b))")
           out))))

(deftest defn-with-docstring
  (let [out (cf/format-form
             [:defn [:symbol 'add] "Add two numbers."
              [:vector [:symbol 'a] [:symbol 'b]]
              [:invoke [:symbol '+] [:symbol 'a] [:symbol 'b]]])]
    (is (= (str "(defn add\n"
                "  \"Add two numbers.\"\n"
                "  [a b]\n"
                "  (+ a b))")
           out))))

;; --- :let ----------------------------------------------------------------

(deftest let-binding-vector
  (let [out (cf/format-form
             [:let [[:symbol 'x] [:literal 1]
                    [:symbol 'y] [:literal 2]]
              [:invoke [:symbol '+] [:symbol 'x] [:symbol 'y]]])]
    (is (= (str "(let [x 1\n"
                "      y 2]\n"
                "  (+ x y))")
           out))))

(deftest let-accepts-vector-form-bindings
  (let [out (cf/format-form
             [:let [:vector [:symbol 'x] [:literal 1]]
              [:symbol 'x]])]
    (is (= (str "(let [x 1]\n"
                "  x)")
           out))))

;; --- :fn -----------------------------------------------------------------

(deftest fn-inline
  (let [out (cf/format-form
             [:fn [:vector [:symbol 'x]] [:symbol 'x]])]
    (is (= "(fn [x] x)" out))))

;; --- :if / :when --------------------------------------------------------

(deftest if-inline
  (is (= "(if true 1 0)"
         (cf/format-form [:if [:literal true] [:literal 1] [:literal 0]]))))

(deftest when-inline
  (is (= "(when ok? body)"
         (cf/format-form [:when [:symbol 'ok?] [:symbol 'body]]))))

;; --- :cond --------------------------------------------------------------

(deftest cond-block-layout
  (let [out (cf/format-form
             [:cond
              [:invoke [:symbol 'pos?] [:symbol 'x]] [:keyword :pos]
              [:invoke [:symbol 'neg?] [:symbol 'x]] [:keyword :neg]
              [:keyword :else] [:keyword :zero]])]
    (is (re-find #"\(cond" out))
    (is (re-find #":pos" out))
    (is (re-find #":neg" out))
    (is (re-find #":zero" out))))

;; --- :ns + :require ----------------------------------------------------

(deftest ns-with-no-requires
  (is (= "(ns app.cart.views)"
         (cf/format-form [:ns [:symbol 'app.cart.views]]))))

(deftest ns-with-requires
  (let [out (cf/format-form
             [:ns [:symbol 'app.cart.views]
              [:require [:symbol 'app.framework] [:keyword :as] [:symbol 'rf]]
              [:require [:symbol 'app.cart.subs] [:keyword :as] [:symbol 'cart.subs]]])]
    (is (= (str "(ns app.cart.views\n"
                "  (:require [app.framework :as rf]\n"
                "            [app.cart.subs :as cart.subs]))")
           out))))

;; --- :thread / :thread-last --------------------------------------------

(deftest thread-inline
  (is (= "(-> x f g)"
         (cf/format-form [:thread [:symbol 'x] [:symbol 'f] [:symbol 'g]]))))

;; --- format-file --------------------------------------------------------

(deftest format-file-blank-line-between-forms
  (let [out (cf/format-file
             [[:ns [:symbol 'app.foo]]
              [:def [:symbol 'x] [:literal 1]]
              [:def [:symbol 'y] [:literal 2]]])]
    (is (= "(ns app.foo)\n\n(def x 1)\n\n(def y 2)" out))))

;; --- integration: representative cljs-project shapes -------------------

(deftest reg-sub-direct-extraction
  (testing "the simple :-> direct extraction sub shape used everywhere"
    (let [form [:invoke [:symbol 'rf/reg-sub]
                [:keyword ::cart-count]
                [:keyword :->]
                [:keyword :cart.db/cart-count]]]
      (is (re-find #"^\(rf/reg-sub" (cf/format-form form)))
      (is (re-find #":->" (cf/format-form form))))))

(deftest reg-sub-derived
  (testing "the :<- derived sub shape"
    (let [form [:invoke [:symbol 'rf/reg-sub]
                [:keyword ::cart-count]
                [:keyword :<-]
                [:vector [:keyword ::cart-items]]
                [:keyword :->]
                [:symbol 'count]]]
      (is (re-find #":<-" (cf/format-form form))))))

(deftest reg-event-with-interceptors
  (testing "the (rf/reg-event id [interceptors] handler) shape
            with [rf/trim-v (rf/path …)]"
    (let [form [:invoke [:symbol 'rf/reg-event]
                [:keyword ::cart-count-changed]
                [:vector [:symbol 'rf/trim-v]
                 [:invoke [:symbol 'rf/path] [:keyword :cart.db/cart-count]]]
                [:fn [:vector [:symbol '_]
                      [:vector [:symbol 'new-cart-count]]]
                 [:symbol 'new-cart-count]]]
          out  (cf/format-form form)]
      (is (re-find #"rf/reg-event" out))
      (is (re-find #"rf/trim-v" out))
      (is (re-find #"rf/path"   out)))))

;; --- :hiccup ------------------------------------------------------------

(deftest hiccup-no-props-no-children-self-closes
  (is (= "[:x-spacer]"
         (cf/format-form [:hiccup :x-spacer nil nil])))
  (testing "string tag works the same"
    (is (= "[:x-spacer]"
           (cf/format-form [:hiccup "x-spacer" nil nil])))))

(deftest hiccup-inline-text-no-children
  (is (= "[:x-typography \"Hello\"]"
         (cf/format-form [:hiccup :x-typography nil [:literal "Hello"]])))
  (testing "inline text co-exists with a single inline prop"
    (is (= "[:x-typography {:variant \"h1\"} \"Hi\"]"
           (cf/format-form
            [:hiccup :x-typography
             [[[:keyword :variant] [:literal "h1"]]]
             [:literal "Hi"]])))))

(deftest hiccup-one-prop-renders-inline
  (is (= "[:x-icon {:name \"cart\"}]"
         (cf/format-form
          [:hiccup :x-icon
           [[[:keyword :name] [:literal "cart"]]]
           nil]))))

(deftest hiccup-multi-prop-aligns-under-first
  (let [out (cf/format-form
             [:hiccup :x-navbar
              [[[:keyword :elevated] [:literal true]]
               [[:keyword :variant]  [:literal "default"]]]
              nil])]
    (testing "first prop sits next to the open brace; subsequent prop is
              on its own line padded to (count bare-tag) + 3 spaces so
              it lines up under the first prop's `:`"
      (is (= (str "[:x-navbar {:elevated true\n"
                  "           :variant \"default\"}]")
             out)))))

(deftest hiccup-children-render-one-per-line-under-bracket
  (let [out (cf/format-form
             [:hiccup :div nil nil
              [:hiccup :x-icon nil nil]
              [:hiccup :x-typography nil [:literal "Hi"]]])]
    (is (= (str "[:div\n"
                " [:x-icon]\n"
                " [:x-typography \"Hi\"]]")
           out))))

(deftest hiccup-text-with-children-puts-text-on-its-own-line
  (let [out (cf/format-form
             [:hiccup :x-card
              [[[:keyword :variant] [:literal "raised"]]]
              [:literal "Title"]
              [:hiccup :x-typography nil [:literal "body"]]])]
    (is (= (str "[:x-card {:variant \"raised\"}\n"
                " \"Title\"\n"
                " [:x-typography \"body\"]]")
           out))))

(deftest hiccup-nested-multi-prop-preserves-relative-alignment
  ;; The child :hiccup form aligns its multi-prop block at relative col
  ;; `(count bare-tag) + 3` — one column LEFT of the first prop. This
  ;; matches the legacy `format-props-map` alignment exactly so the
  ;; cljs-project view emitter's emitted bytes don't shift after the
  ;; migration. When the parent re-indents the child by one space,
  ;; both lines shift together → relative offset stays the same.
  (let [out (cf/format-form
             [:hiccup :div nil nil
              [:hiccup :x-navbar
               [[[:keyword :elevated] [:literal true]]
                [[:keyword :variant]  [:literal "default"]]]
               nil]])]
    (is (= (str "[:div\n"
                " [:x-navbar {:elevated true\n"
                "            :variant \"default\"}]]")
           out))))

(deftest hiccup-raw-child-passes-through-with-relative-indent
  ;; A :raw child that already has its own multi-line shape gets its
  ;; internal newlines indented by one space when composed under a
  ;; parent — this is what lets multi-line for-loop blocks slot in
  ;; cleanly without re-formatting them as structured forms first.
  (let [for-block (str "(for [p (rf/query [::sub])]\n"
                       "  (something p))")
        out (cf/format-form
             [:hiccup :div
              [[[:keyword :style] [:literal "display: contents"]]]
              nil
              [:raw for-block]])]
    (is (= (str "[:div {:style \"display: contents\"}\n"
                " (for [p (rf/query [::sub])]\n"
                "   (something p))]")
           out))))

;; --- round-trip property ------------------------------------------------

(deftest round-trip-parses-back
  (testing "every formatted form is valid Clojure data — read-string
            does not throw and the result is non-nil"
    (doseq [form [[:def [:symbol 'x] [:literal 1]]
                  [:invoke [:symbol 'count] [:symbol 'xs]]
                  [:vector 1 2 3]
                  [:map :a 1 :b 2]
                  [:if [:literal true] [:literal 1] [:literal 0]]
                  [:invoke [:symbol 'rf/reg-sub]
                   [:keyword ::cart-count]
                   [:keyword :->]
                   [:keyword :cart.db/cart-count]]]]
      (let [s (cf/format-form form)]
        (is (some? (edn/read-string s))
            (str "could not parse: " s))))))
