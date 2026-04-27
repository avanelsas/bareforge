(ns bareforge.doc.sanitize-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bareforge.doc.model :as m]
            [bareforge.doc.sanitize :as sn]))

;; --- safe-url? ------------------------------------------------------------

(deftest safe-url-passes-empty-and-nil
  (is (true? (sn/safe-url? nil)))
  (is (true? (sn/safe-url? "")))
  (is (true? (sn/safe-url? "   "))))

(deftest safe-url-passes-relative-and-http
  (is (true? (sn/safe-url? "/foo")))
  (is (true? (sn/safe-url? "../bar")))
  (is (true? (sn/safe-url? "https://example.com/x")))
  (is (true? (sn/safe-url? "mailto:a@b.com")))
  (is (true? (sn/safe-url? "tel:+15551234"))))

(deftest safe-url-rejects-script-schemes
  (is (false? (sn/safe-url? "javascript:alert(1)")))
  (is (false? (sn/safe-url? "JavaScript:alert(1)")) "case-insensitive")
  (is (false? (sn/safe-url? "  javascript:alert(1)")) "leading whitespace stripped")
  (is (false? (sn/safe-url? "java\tscript:alert(1)")) "embedded tab stripped")
  (is (false? (sn/safe-url? "vbscript:msgbox(1)")))
  (is (false? (sn/safe-url? "livescript:1")))
  (is (false? (sn/safe-url? "mocha:1"))))

(deftest safe-url-data-images-only
  (is (true?  (sn/safe-url? "data:image/png;base64,iVBORw0KGgo")))
  (is (true?  (sn/safe-url? "data:image/svg+xml;utf8,<svg/>")))
  (is (false? (sn/safe-url? "data:text/html,<script>alert(1)</script>")))
  (is (false? (sn/safe-url? "data:application/javascript,alert(1)"))))

;; --- safe-svg-fragment? ---------------------------------------------------

(deftest svg-fragment-recognises-clean-input
  (is (true? (sn/safe-svg-fragment? "")))
  (is (true? (sn/safe-svg-fragment? "<svg viewBox=\"0 0 1 1\"><path d=\"M0 0\"/></svg>")))
  (is (true? (sn/safe-svg-fragment? "<g><circle cx=\"5\" cy=\"5\" r=\"3\"/></g>"))))

(deftest svg-fragment-rejects-script-elements
  (is (false? (sn/safe-svg-fragment? "<svg><script>alert(1)</script></svg>")))
  (is (false? (sn/safe-svg-fragment? "<svg><SCRIPT>alert(1)</SCRIPT></svg>")))
  (is (false? (sn/safe-svg-fragment? "<svg><foreignObject><div>x</div></foreignObject></svg>")))
  (is (false? (sn/safe-svg-fragment? "<iframe src=\"//evil/\"></iframe>"))))

(deftest svg-fragment-rejects-event-attrs
  (is (false? (sn/safe-svg-fragment? "<svg onload=\"alert(1)\"></svg>")))
  (is (false? (sn/safe-svg-fragment? "<svg onLoad=\"alert(1)\"></svg>")))
  (is (false? (sn/safe-svg-fragment? "<image onerror='x'/>")))
  (is (false? (sn/safe-svg-fragment? "<svg onload=alert(1)></svg>")) "unquoted handler"))

(deftest svg-fragment-rejects-js-url-attrs
  (is (false? (sn/safe-svg-fragment? "<a href=\"javascript:alert(1)\">x</a>")))
  (is (false? (sn/safe-svg-fragment? "<image xlink:href=\"javascript:alert(1)\"/>"))))

;; --- sanitize-svg-fragment ------------------------------------------------

(deftest sanitize-svg-strips-script-blocks
  (let [out (sn/sanitize-svg-fragment
              "<svg><script>alert(1)</script><path d=\"M0 0\"/></svg>")]
    (is (not (str/includes? out "<script")))
    (is (str/includes? out "<path"))))

(deftest sanitize-svg-strips-foreignObject
  (let [out (sn/sanitize-svg-fragment
              "<svg><foreignObject><div>html</div></foreignObject></svg>")]
    (is (not (str/includes? out "foreignObject")))
    (is (not (str/includes? out "<div")))))

(deftest sanitize-svg-strips-event-attrs
  (let [out (sn/sanitize-svg-fragment "<svg onload=\"alert(1)\"></svg>")]
    (is (not (str/includes? out "onload"))))
  (let [out (sn/sanitize-svg-fragment "<image onerror='alert(1)'/>")]
    (is (not (str/includes? out "onerror")))))

(deftest sanitize-svg-strips-js-url-attrs
  (let [out (sn/sanitize-svg-fragment "<a href=\"javascript:alert(1)\">x</a>")]
    (is (not (str/includes? out "javascript")))
    (is (str/includes? out "<a"))
    (is (str/includes? out ">x</a>"))))

(deftest sanitize-svg-leaves-clean-input-unchanged
  (let [clean "<svg viewBox=\"0 0 24 24\"><path d=\"M0 0\" stroke=\"#000\"/></svg>"]
    (is (= clean (sn/sanitize-svg-fragment clean)))))

(deftest sanitize-svg-is-idempotent
  (let [bad  "<svg><script>x</script><path onclick='y' d='M0 0'/></svg>"
        once (sn/sanitize-svg-fragment bad)]
    (is (= once (sn/sanitize-svg-fragment once)))))

;; --- url-attrs / url-attr? -----------------------------------------------

(deftest url-attr-set-includes-known-keys
  (is (sn/url-attr? "href"))
  (is (sn/url-attr? "src"))
  (is (sn/url-attr? "xlink:href"))
  (is (sn/url-attr? "formaction"))
  (is (not (sn/url-attr? "alt")))
  (is (not (sn/url-attr? "aria-label"))))

;; --- doc-level findings + sanitisation -----------------------------------

(defn- doc-with-icon [inner-html]
  (let [doc  (m/empty-document)
        icon {:id    "icon-1"
              :tag   "x-icon"
              :attrs {}
              :props {}
              :slots {}
              :inner-html inner-html
              :layout {:placement :flow}}]
    (assoc-in doc [:root :slots "default"] [icon])))

(defn- doc-with-attr [k v]
  (let [doc  (m/empty-document)
        link {:id    "lnk-1"
              :tag   "x-link"
              :attrs {k v}
              :props {}
              :slots {}
              :layout {:placement :flow}}]
    (assoc-in doc [:root :slots "default"] [link])))

(deftest unsafe-findings-flags-malicious-icon
  (let [doc (doc-with-icon "<svg><script>alert(1)</script></svg>")
        f   (sn/unsafe-findings doc)]
    (is (= 1 (count f)))
    (is (= :inner-html (last (:path (first f)))))
    (is (str/includes? (:reason (first f)) "script"))))

(deftest unsafe-findings-flags-javascript-url-attr
  (let [doc (doc-with-attr "href" "javascript:alert(1)")
        f   (sn/unsafe-findings doc)]
    (is (= 1 (count f)))
    (is (= "href" (last (:path (first f)))))))

(deftest unsafe-findings-clean-doc-returns-empty
  (is (= [] (sn/unsafe-findings (m/empty-document))))
  (is (= [] (sn/unsafe-findings (doc-with-icon "<svg viewBox=\"0 0 1 1\"/>"))))
  (is (= [] (sn/unsafe-findings (doc-with-attr "href" "/page")))))

(deftest sanitize-doc-strips-icon-script
  (let [doc      (doc-with-icon
                   "<svg><script>alert(1)</script><path d=\"M0\"/></svg>")
        cleaned  (sn/sanitize-doc doc)
        new-html (-> cleaned :root :slots (get "default") first :inner-html)]
    (is (not (str/includes? new-html "<script")))
    (is (str/includes? new-html "<path"))))

(deftest sanitize-doc-drops-javascript-href
  (let [doc     (doc-with-attr "href" "javascript:alert(1)")
        cleaned (sn/sanitize-doc doc)
        attrs   (-> cleaned :root :slots (get "default") first :attrs)]
    (is (not (contains? attrs "href")))))

(deftest sanitize-doc-leaves-clean-doc-unchanged
  (let [doc (doc-with-attr "href" "/page")]
    (is (= doc (sn/sanitize-doc doc)))))
