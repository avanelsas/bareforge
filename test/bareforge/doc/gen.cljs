(ns bareforge.doc.gen
  "Bounded test.check generators for `bareforge.doc.spec/::document`
   shapes. Lives under `test/` because the only consumers are
   property tests; the production tree is unaffected.

   Why hand-rolled instead of `s/gen ::spec/document`:
   - `::action` uses `(s/and keys-spec mutual-exclusion-pred)` for
     single-step / multi-step exclusivity; spec's default generator
     hits the `such-that` retry limit on the predicate.
   - `::node` references `::slots` references `(s/coll-of ::node)` —
     mutually recursive, so default generation produces deeply
     nested trees that explode shrink time.

   The generators here cover the surface property tests need and
   stop there; they're not a substitute for a full document model."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]))

;; --- primitives ----------------------------------------------------------

(def small-string
  (gen/fmap str/join (gen/vector gen/char-alphanumeric 1 6)))

;; --- attrs: mix of safe attrs and URL-shaped attrs with mixed schemes ---

(def url-attr-key
  (gen/elements ["href" "src" "xlink:href" "formaction" "action"
                 "cite" "poster" "data" "background"]))

(def safe-attr-key
  (gen/elements ["alt" "title" "aria-label" "class" "data-foo" "id"]))

(def url-value
  (gen/elements ["" "https://example.com/" "/relative/path" "#anchor"
                 "javascript:alert(1)" "vbscript:bad()"
                 "JavaScript:alert(1)" " javascript:alert(1) "
                 "data:image/png;base64,iVBOR"
                 "data:image/svg+xml;base64,PHN2Zw=="
                 "data:text/html,<script>alert(1)</script>"]))

(def safe-string-value
  (gen/fmap str/join (gen/vector gen/char-alphanumeric 0 12)))

(def attr-pair
  (gen/one-of
   [(gen/tuple url-attr-key url-value)
    (gen/tuple safe-attr-key safe-string-value)]))

(def attrs-gen
  (gen/fmap (partial into {}) (gen/vector attr-pair 0 4)))

;; --- inner-html: safe SVG plus assorted XSS payloads --------------------

(def inner-html-gen
  (gen/one-of
   [(gen/return nil)
    (gen/return "")
    (gen/return "<svg></svg>")
    (gen/return "<svg><circle r=\"5\"/></svg>")
    (gen/return "<svg><script>alert(1)</script></svg>")
    (gen/return "<svg onclick=\"bad()\"><circle/></svg>")
    (gen/return "<foreignObject><div>x</div></foreignObject>")
    (gen/return "<iframe src=\"javascript:bad()\"></iframe>")
    (gen/return "<svg><object data=\"x\"></object></svg>")
    (gen/return "<a href=\"javascript:bad()\">x</a>")]))

;; --- nodes: bounded recursion via depth counter -------------------------

(def slot-name-gen (gen/elements ["default" "header" "footer"]))

(def tag-gen
  (gen/elements ["x-button" "x-card" "x-grid" "x-typography" "x-icon"
                 "x-container" "x-divider"]))

(defn node-gen
  "Depth-bounded generator for `::node`. `depth` 0 → leaf (empty
   slots); each recursion decrements. Width capped at two slot
   buckets, two children each, so a depth-3 tree carries ≤ 1+2+4+8
   nodes."
  [depth]
  (gen/let [id    small-string
            tag   tag-gen
            attrs attrs-gen
            inner inner-html-gen
            text  (gen/one-of [(gen/return nil) safe-string-value])
            slots (if (zero? depth)
                    (gen/return {})
                    (gen/map slot-name-gen
                             (gen/vector (node-gen (dec depth)) 0 2)
                             {:max-elements 2}))]
    (cond-> {:id     id
             :tag    tag
             :attrs  attrs
             :props  {}
             :slots  slots
             :layout {:placement :flow}}
      inner (assoc :inner-html inner)
      text  (assoc :text text))))

;; --- documents -----------------------------------------------------------

(def canvas-gen
  (gen/let [width (gen/choose 320 1920)
            left  (gen/choose 0 100)
            right (gen/choose 100 1920)]
    {:width       width
     :content-col {:left left :right right}}))

(def document-gen
  (gen/let [root    (node-gen 3)
            canvas  canvas-gen
            next-id (gen/choose 1 1000)]
    {:root    root
     :canvas  canvas
     :next-id next-id}))
