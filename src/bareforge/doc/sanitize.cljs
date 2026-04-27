(ns bareforge.doc.sanitize
  "Pure sanitisation helpers for the two doc fields that ship raw
   user-supplied strings into the DOM: `:inner-html` (raw SVG/HTML on
   `:raw-html-slot?` components like x-icon) and `:attrs` URL values
   (`href`, `src`, …).

   Two layers of protection:

   - **Block-list scanners** (`unsafe-findings`): given a doc, return a
     vector of `[path reason]` entries naming each suspect site. Used at
     the load boundary by `storage/project-file/validate-project` to
     refuse a malicious payload outright with an explanatory message.

   - **Best-effort sanitisers** (`sanitize-svg-fragment`,
     `sanitize-doc`): strip the obvious payloads — `<script>` /
     `<foreignObject>` blocks, `on*=` attributes, and `javascript:` /
     `vbscript:` URL schemes. Used on commit so a paste from a hostile
     icon site doesn't silently embed a payload.

   This is a pure-string module by design: no DOMParser, no live
   nodes. Everything is testable in node, runs on every platform.
   The trade-off is the regexes don't follow encoded payloads
   perfectly — they're conservative defence-in-depth, not the only
   gate. The load-boundary scanner is the strict gate; the
   sanitiser is the soft one."
  (:require [bareforge.doc.model :as m]
            [clojure.string :as str]))

;; --- URL safety -----------------------------------------------------------

(def url-attrs
  "Attribute names that carry URLs and therefore need scheme
   filtering. Superset of what BareDOM augment marks `:kind :url`,
   plus historically-dangerous keys (`xlink:href`, `formaction`,
   `action`, `cite`, `poster`, `data`, `background`) so a future
   tag with one of these can't slip past."
  #{"href" "src" "xlink:href" "formaction" "action"
    "cite" "poster" "data" "background"})

(defn url-attr? [k] (contains? url-attrs k))

(defn- normalise-url
  "Lowercase, strip whitespace and HTML-decoded control chars from a
   URL string before scheme matching. Catches `JavaScript:`,
   `\\tjavascript:`, `java\\nscript:` etc."
  [s]
  (-> (str s)
      str/lower-case
      (str/replace #"[\s\u0000-\u001f]" "")))

(def ^:private dangerous-scheme-re
  #"^(javascript:|vbscript:|livescript:|mocha:)")

(def ^:private allowed-data-re
  #"^data:image/(png|jpe?g|gif|webp|svg\+xml);")

(defn safe-url?
  "True when `url` is a non-empty string whose scheme isn't a known
   script-execution scheme. Empty / nil → true (an empty href is a
   no-op, not an attack). Relative paths and ordinary http/https
   pass. `data:` is rejected except for known image MIME types."
  [url]
  (let [s (normalise-url url)]
    (cond
      (or (nil? url) (= "" s)) true
      (re-find dangerous-scheme-re s) false
      (str/starts-with? s "data:") (boolean (re-find allowed-data-re s))
      :else true)))

;; --- SVG / HTML fragment safety ------------------------------------------

(def ^:private dangerous-element-re
  ;; Opening tags only — close tags get swept by sanitize-svg-fragment.
  #"(?i)<(script|foreignobject|iframe|object|embed)\b")

(def ^:private dangerous-event-attr-re
  ;; Any `on*` attribute, double / single quoted or unquoted.
  #"(?i)\son[a-z]+\s*=\s*(\"[^\"]*\"|'[^']*'|[^\s>]+)")

(def ^:private dangerous-url-attr-re
  ;; Attribute that looks URL-shaped and starts with a script scheme.
  ;; (href|src|xlink:href|formaction|action|poster) = "javascript:..."
  #"(?i)\s(href|src|xlink:href|formaction|action|poster)\s*=\s*(\"\s*(?:javascript|vbscript|livescript|mocha):[^\"]*\"|'\s*(?:javascript|vbscript|livescript|mocha):[^']*')")

(defn safe-svg-fragment?
  "True when an SVG/HTML fragment contains none of the obvious XSS
   patterns. Conservative: a result of `false` does not necessarily
   mean exploitable, but the load boundary refuses to accept
   anything that fails. Authors fix the icon source instead of
   shipping unfiltered."
  [s]
  (let [s (str s)]
    (not (or (re-find dangerous-element-re s)
             (re-find dangerous-event-attr-re s)
             (re-find dangerous-url-attr-re s)))))

(defn sanitize-svg-fragment
  "Strip script-bearing tags, on* event handlers, and dangerous URL
   schemes from an SVG/HTML fragment. Best-effort — pairs with the
   load-boundary scanner. Nil and blank input pass through unchanged."
  [s]
  (if (or (nil? s) (= "" s))
    s
    (-> s
        ;; Drop entire <script>…</script> / <foreignObject>…</foreignObject>
        ;; / <iframe>…</iframe> blocks (greedy on the body).
        (str/replace #"(?is)<script\b[^>]*>[\s\S]*?</script\s*>" "")
        (str/replace #"(?is)<script\b[^>]*/>" "")
        (str/replace #"(?is)<foreignobject\b[^>]*>[\s\S]*?</foreignobject\s*>" "")
        (str/replace #"(?is)<foreignobject\b[^>]*/>" "")
        (str/replace #"(?is)<iframe\b[^>]*>[\s\S]*?</iframe\s*>" "")
        (str/replace #"(?is)<iframe\b[^>]*/>" "")
        (str/replace #"(?is)<(object|embed)\b[^>]*>" "")
        ;; on*= handlers, regardless of quoting.
        (str/replace dangerous-event-attr-re "")
        ;; href / src / xlink:href / formaction / action / poster
        ;; with javascript-like schemes.
        (str/replace dangerous-url-attr-re ""))))

;; --- doc walker -----------------------------------------------------------

(defn- walk-nodes-with-path
  "Eager seq of [path node] pairs covering every node in the doc,
   path is the data-structure path (`get-in doc path`) ending at a
   `::node`. Mirrors `model/walk-nodes` but keeps the structural
   path so callers can pinpoint findings."
  [doc]
  (let [out (volatile! [])]
    (letfn [(rec [path node]
              (vswap! out conj [path node])
              (doseq [[sname kids] (m/slot-entries node)]
                (dotimes [i (count kids)]
                  (rec (conj path :slots sname i) (nth kids i)))))]
      (rec [:root] (:root doc))
      @out)))

(defn unsafe-findings
  "Walk `doc` and return a vector of `{:path :reason :preview}` maps,
   one per unsafe `:inner-html` or URL-typed attr value. Empty
   vector means the doc is clean. The load-boundary check uses
   this directly: any non-empty result refuses the load."
  [doc]
  (vec
   (mapcat
    (fn [[path node]]
      (concat
       (when-let [ih (:inner-html node)]
         (when-not (safe-svg-fragment? ih)
           [{:path   (conj path :inner-html)
             :reason "inner-html contains script/event/javascript-url payload"
             :preview (subs ih 0 (min 80 (count ih)))}]))
       (for [[k v] (:attrs node)
             :when (and (url-attr? k) (string? v) (not (safe-url? v)))]
         {:path   (conj path :attrs k)
          :reason (str "attr " (pr-str k) " carries unsafe URL scheme")
          :preview v})))
    (walk-nodes-with-path doc))))

(defn- sanitize-node-attrs
  "Drop URL-typed attrs whose value isn't safe. Non-URL attrs pass
   through untouched — content like alt text, aria labels etc. is
   not a script-execution surface."
  [attrs]
  (reduce-kv
   (fn [acc k v]
     (if (and (url-attr? k) (string? v) (not (safe-url? v)))
       acc
       (assoc acc k v)))
   {} (or attrs {})))

(defn- sanitize-node
  [node]
  (cond-> node
    (:inner-html node) (update :inner-html sanitize-svg-fragment)
    (:attrs node)      (update :attrs sanitize-node-attrs)
    true (update :slots
                 (fn [slots]
                   (reduce-kv
                    (fn [acc sname kids]
                      (assoc acc sname (mapv sanitize-node kids)))
                    {} (or slots {}))))))

(defn sanitize-doc
  "Return a copy of `doc` with `:inner-html` filtered through
   `sanitize-svg-fragment` and dangerous URL attrs dropped, on every
   node. Defence-in-depth — pairs with the strict load-boundary
   scanner. Cheap and idempotent."
  [doc]
  (cond-> doc
    (:root doc) (update :root sanitize-node)))
