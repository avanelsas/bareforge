(ns bareforge.export.bundle
  "Offline-capable 'bundle' HTML export. Produces a .zip that contains
   the rendered HTML plus a copy of every BareDOM module the document
   needs, so the result can be served from any static file server
   without an internet connection at runtime.

   **Static snapshot, same contract as `bareforge.export.html`** —
   markup only; no JS is emitted for `:events` / `:bindings` /
   `:computed`, and seed-backed template instances render as a
   single placeholder. The zip just pairs that static HTML with its
   vendored deps. Reach for the ClojureScript export when the
   exported artefact needs to be interactive.

   The fetch step IS online — we pull module source from the same
   jsDelivr CDN the default export points at, then rewrite the HTML's
   dynamic-import base to the local vendor path. Scope note: v1
   assumes BareDOM's `dist/` directory is flat (each tag file plus
   `base.js` at the same level) and walks only relative `./*.js` /
   `../*.js` imports. If that assumption breaks we'll grow the
   resolver.

   Pure helpers sit at the top of this namespace and are unit-testable
   without a browser. The effectful half handles `fetch`, `JSZip`,
   and the blob download."
  (:require [bareforge.export.html :as html]
            [bareforge.export.plugin :as plugin]
            [bareforge.meta.versions :as versions]
            [bareforge.state :as state]
            [clojure.string :as str]
            ["jszip" :as JSZip]))

(def ^:private cdn-root             "https://cdn.jsdelivr.net/npm/@vanelsas/baredom")
(def ^:private local-prefix         "vendor/baredom/")
(def ^:private import-base-for-html (str "./" local-prefix))

;; --- pure helpers --------------------------------------------------------

(defn module-filename
  "Turn a BareDOM tag name into its dist filename, e.g.
   `x-button` → `x-button.js`."
  [tag]
  (str (name tag) ".js"))

(defn initial-module-set
  "The initial set of module filenames to fetch for a given document:
   one per tag used + `x-theme.js` (the outer wrapper the exporter
   always emits) + `base.js` (the shared runtime each dist file
   imports)."
  [doc]
  (into #{"base.js" "x-theme.js"}
        (map module-filename (html/collect-tags doc))))

(defn extract-relative-imports
  "Pure: scan a JS module source for relative import targets. Matches
   the path argument of both static (`import ... from '…'`) and
   dynamic (`import('…')`) forms. Returns a set of strings, each
   beginning with `./` or `../` and ending in `.js`."
  [source]
  (let [re #"['\"](\.{1,2}/[^'\"]+?\.js)['\"]"]
    (into #{} (map second) (re-seq re source))))

(defn parent-dir
  "Pure: given a filename like `subdir/foo.js`, return `subdir/`. For
   a root-level file, return the empty string."
  [filename]
  (if-let [slash (str/last-index-of filename "/")]
    (subs filename 0 (inc slash))
    ""))

(defn normalize-import
  "Pure: resolve a relative import string against the parent directory
   of the file that contains it, producing a path rooted at the
   flat `vendor/baredom/` tree. `./foo.js` at the root becomes
   `foo.js`; `../foo.js` from `sub/` becomes `foo.js`; `./foo.js`
   from `sub/` becomes `sub/foo.js`."
  [parent-dir rel]
  (cond
    (str/starts-with? rel "./")
    (str parent-dir (subs rel 2))

    (str/starts-with? rel "../")
    (let [segs (vec (remove #{""} (str/split parent-dir #"/")))
          tail (subs rel 3)]
      (if (seq segs)
        (let [up (vec (butlast segs))]
          (str (when (seq up) (str (str/join "/" up) "/")) tail))
        tail))

    :else rel))

;; --- effectful: fetch + zip ---------------------------------------------

(defn- fetch-text!
  "Fetch `url` as text. Returns a Promise that resolves to the body
   string or rejects if the HTTP status is not OK."
  [url]
  (-> (js/fetch url)
      (.then (fn [^js r]
               (if (.-ok r)
                 (.text r)
                 (throw (js/Error. (str "Fetch failed: " (.-status r)
                                        " " url))))))))

(defn- url-for [cdn-version filename]
  (str cdn-root "@" cdn-version "/dist/" filename))

(defn fetch-all-modules!
  "Recursively fetch module sources starting from `initial`, following
   relative imports in each fetched file. Returns a Promise that
   resolves to a plain JS object `{filename -> source-string}`.
   Duplicate work is avoided via a `seen` set.

   The bookkeeping uses native JS `Set` and `Array` mutables — scoped
   strictly to this function and never observed outside. Conforms to
   CLAUDE.md's effectful-zone rules (no `volatile!`, no Clojure atoms
   beyond `state/app-state`)."
  [cdn-version initial]
  (js/Promise.
   (fn [resolve reject]
     (let [result     #js {}
           ^js seen   (js/Set.)
           ^js pending #js []
           enqueue!   (fn [f]
                        (when-not (.has seen f)
                          (.add seen f)
                          (.push pending f)))]
       (doseq [f initial] (enqueue! f))
       (letfn [(step []
                 (if (zero? (.-length pending))
                   (resolve result)
                   ;; .splice drains the queue into a local batch so
                   ;; fresh imports discovered mid-batch land in the
                   ;; next round, not the current one.
                   (let [batch (.splice pending 0 (.-length pending))]
                     (-> (js/Promise.all
                          (.map batch
                                (fn [f]
                                  (-> (fetch-text! (url-for cdn-version f))
                                      (.then
                                       (fn [src]
                                         (unchecked-set result f src)
                                         (let [dir (parent-dir f)]
                                           (doseq [imp (extract-relative-imports src)]
                                             (enqueue! (normalize-import dir imp))))))))))
                         (.then step)
                         (.catch reject)))))]
         (step))))))

(defn- build-zip!
  "Wrap an HTML string and a `{filename -> source}` JS object into a
   single JSZip blob. Modules are placed under `vendor/baredom/` so
   the rewritten import base in the HTML resolves correctly."
  [html-text ^js modules]
  (let [zip (JSZip.)]
    (.file zip "index.html" html-text)
    (doseq [filename (js-keys modules)]
      (let [path (str local-prefix filename)]
        ;; Module filenames come from CDN URLs; a tampered response
        ;; could ship a `..`-flavoured name. Refuse before emit.
        (plugin/assert-safe-zip-path! path)
        (.file zip path (unchecked-get modules filename))))
    (.generateAsync zip #js {:type "blob"})))

(defn- trigger-download! [^js blob filename]
  (let [url   (js/URL.createObjectURL blob)
        ^js a (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) filename)
    (.appendChild js/document.body a)
    (.click a)
    (.removeChild js/document.body a)
    (js/URL.revokeObjectURL url)))

(defn- current-snapshot []
  (let [s @state/app-state]
    {:document (:document s)
     :theme    (:theme s)}))

(defn download!
  "Trigger a browser download of the current project as an offline-
   capable .zip. Fetches every BareDOM module the document uses
   from jsDelivr at export time, bundles them alongside a rewritten
   HTML whose import base points at the local `vendor/baredom/`
   folder, and delivers the result as a blob download. The produced
   zip is ready to be served from any static HTTP server.

   Optional keys: `:filename` `:cdn-version` `:title`."
  ([] (download! nil))
  ([{:keys [filename cdn-version title]
     :or   {filename "bareforge-export.zip"}}]
   (let [snapshot (current-snapshot)
         cdn-v    (or cdn-version versions/baredom-version)
         html-src (html/render-html snapshot
                                    {:title       title
                                     :cdn-version cdn-v
                                     :import-base import-base-for-html
                                     ;; Bundle is self-hosted — no
                                     ;; jsDelivr egress at runtime, so
                                     ;; tighten script-src to 'self'.
                                     :csp         html/csp-bundle})
         initial  (initial-module-set (:document snapshot))]
     (-> (fetch-all-modules! cdn-v initial)
         (.then (fn [modules] (build-zip! html-src modules)))
         (.then (fn [blob]    (trigger-download! blob filename)))
         (.catch (fn [err]
                   (js/console.error "Bundle export failed:" err)
                   (js/alert (str "Bundle export failed: " err))))))))
