(ns bareforge.export.html
  "Serialize a document + theme into a standalone HTML file that pulls
   BareDOM components from a CDN via ESM imports. v1 only emits the
   CDN flavour; the bundle-mode zip (JSZip) will follow in a separate
   commit.

   **Static snapshot.** This export emits markup + custom-element
   registration only — `:events`, `:bindings`, `:actions`, and
   `:computed` fields on the document are intentionally ignored, and
   template instances bound to seed collections render as a single
   placeholder (not one clone per seed). Users who need the button
   wiring to actually dispatch state updates should use the
   ClojureScript export (`bareforge.export.cljs-project`), which
   ships a re-frame-style runtime.

   The serialization side of this namespace is pure and unit-tested;
   the `download!` side triggers a browser blob download."
  (:require [bareforge.doc.model :as m]
            [bareforge.export.integrity :as integrity]
            [bareforge.meta.versions :as versions]
            [bareforge.render.reconcile :as rec]
            [bareforge.state :as state]
            [clojure.string :as str]))

;; BareDOM is published on npm as `@vanelsas/baredom`. jsDelivr serves
;; per-file paths like `/npm/@vanelsas/baredom@<ver>/dist/x-button.js`,
;; and each dist file's relative `./base.js` import resolves against
;; the same origin so the shared runtime is fetched once and reused.
;; The default version lives in `bareforge.meta.versions` so all export
;; paths share one source of truth — see CLAUDE.md onboarding recipe.
(def ^:private cdn-base "https://cdn.jsdelivr.net/npm/@vanelsas/baredom")

;; --- pure serialization --------------------------------------------------

(defn escape-attr
  "HTML-escape a string for use inside a double-quoted attribute value."
  [s]
  (-> (str s)
      (str/replace "&"  "&amp;")
      (str/replace "<"  "&lt;")
      (str/replace ">"  "&gt;")
      (str/replace "\"" "&quot;")))

(defn escape-text
  "HTML-escape a string for use as text content."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- placement-attr
  "Emit `data-bareforge-placement=\"...\"` for nodes that need
   stacking treatment in the exported HTML. Nil otherwise."
  [layout]
  (case (:placement layout)
    :background " data-bareforge-placement=\"background\""
    :free       " data-bareforge-placement=\"free\""
    nil))

(defn- positioned-parent?
  "True when any direct child of `node` has a :background or :free
   placement — those need the parent to be a positioned containing
   block plus an isolation stacking context."
  [node]
  (some (fn [child]
          (contains? #{:background :free} (get-in child [:layout :placement])))
        (mapcat val (:slots node))))

(defn- attr-str
  "Build the attribute fragment for an element. Attributes are emitted
   in sorted key order so output is deterministic and test-friendly.
   Any `style=` from `:attrs` is suppressed in favour of the layout-
   derived style (via `rec/layout->css`) so the editor and the export
   stay byte-for-byte consistent."
  [node slot-name]
  (let [{:keys [attrs layout]} node
        slot-attr   (when (and slot-name (not= "default" slot-name))
                      (str " slot=\"" (escape-attr slot-name) "\""))
        layout-css  (rec/layout->css layout)
        style-attr  (when layout-css
                      (str " style=\"" (escape-attr layout-css) "\""))
        placement   (placement-attr layout)
        positioned  (when (positioned-parent? node)
                      " data-bareforge-positioned=\"\"")
        rendered    (for [[k v] (sort-by key attrs)
                          :when (and k (some? v) (not= "style" k))]
                      (str " " k "=\"" (escape-attr v) "\""))]
    (str slot-attr style-attr placement positioned (apply str rendered))))

(defn serialize-node
  "Recursively render `node` to an HTML string. `slot-name` is the
   name of the slot this node sits in on its parent (nil for the
   document root). Default-slot children omit the `slot=` attribute.

   Content precedence: `:inner-html` (raw, unescaped) beats `:text`
   (escaped) beats structured slot children. Raw-html nodes like
   `x-icon` use the first branch so their pasted SVG round-trips
   byte-for-byte."
  ([node] (serialize-node node nil))
  ([node slot-name]
   (let [tag      (:tag node)
         opening  (str "<" tag (attr-str node slot-name) ">")
         closing  (str "</" tag ">")
         body     (cond
                    (:inner-html node)
                    (:inner-html node)

                    :else
                    (let [text     (when-let [t (:text node)]
                                     (when (not= "" t)
                                       (escape-text t)))
                          children (apply str
                                          (for [[sname kids] (m/slot-entries node)
                                                child kids]
                                            (serialize-node child sname)))]
                      (str (or text "") children)))]
     (str opening body closing))))

(defn collect-tags
  "Sorted set of every unique tag name used in the document."
  [doc]
  (into (sorted-set) (map :tag) (m/walk-nodes doc)))

(defn- overrides->style
  "Turn a map of `{css-var value}` into an inline `style=` body."
  [overrides]
  (when (seq overrides)
    (apply str
           (for [[k v] (sort-by key overrides)
                 :when (and k v)]
             (str k ":" v ";")))))

(defn- import-block
  "Build the body of the <script type=\"module\"> block.

   BareDOM's ESM modules export `init` and `registerPreset` as named
   exports, and the `customElements.define(...)` call lives inside
   `init` — not at module top level. A static `import '…x-button.js'`
   loads the module but never calls `init`, so the element never
   registers and you get unstyled tags in the DOM. We therefore use
   dynamic imports plus a top-level `await` to load every needed
   module in parallel and call its `init()`."
  [tags base-url]
  (let [tag-literals (apply str
                            (interpose ",\n      "
                                       (for [t tags]
                                         (str "\"" t "\""))))]
    (str
     "    const __base = \"" base-url "\";\n"
     "    const __tags = [\n      " tag-literals "\n    ];\n"
     "    await Promise.all(__tags.map(async (tag) => {\n"
     "      const mod = await import(__base + tag + \".js\");\n"
     "      if (typeof mod.init === \"function\") mod.init();\n"
     "    }));\n")))

(def ^:private csp-cdn
  "Content-Security-Policy for the CDN-loading HTML export. `'unsafe-inline'`
   is required for the bootstrap `<script type=\"module\">` block that
   dynamic-imports BareDOM modules; without it the inline `await
   Promise.all(...)` won't run. `script-src` is then narrowed to
   `'self' https://cdn.jsdelivr.net` so only Bareforge's own bundle
   and BareDOM's published modules execute. `object-src 'none'` and
   `base-uri 'self'` are cheap belt-and-braces against `<object>` /
   `<base href>` injection. Defence-in-depth — the doc sanitiser is
   the primary gate; CSP catches the residue."
  (str "default-src 'self'; "
       "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; "
       "style-src 'self' 'unsafe-inline'; "
       "img-src 'self' data: https:; "
       "font-src 'self' data: https://cdn.jsdelivr.net; "
       "connect-src 'self' https://cdn.jsdelivr.net; "
       "object-src 'none'; "
       "base-uri 'self'"))

(def csp-bundle
  "CSP for the bundle (self-hosted) export. Stricter than the CDN
   variant — no third-party origin, just `'self' 'unsafe-inline'`.
   Public so `bundle.cljs` can pass it through `render-html`'s `:csp`
   key without re-deriving the policy."
  (str "default-src 'self'; "
       "script-src 'self' 'unsafe-inline'; "
       "style-src 'self' 'unsafe-inline'; "
       "img-src 'self' data:; "
       "font-src 'self' data:; "
       "connect-src 'self'; "
       "object-src 'none'; "
       "base-uri 'self'"))

(defn- select-csp
  "Pick the CSP policy. Explicit `csp` wins. Otherwise an `import-base`
   override means scripts come from somewhere other than the CDN, so
   default to the stricter self-only policy; without an override fall
   back to the CDN-aware default."
  [csp import-base]
  (or csp (if import-base csp-bundle csp-cdn)))

(defn- theme-open-tag
  "Build the opening `<x-theme preset=\"…\" style=\"…\">` tag from a
   resolved preset name and optional `{css-var value}` overrides."
  [preset overrides]
  (str "<x-theme preset=\"" (escape-attr preset) "\""
       (when-let [s (overrides->style overrides)]
         (str " style=\"" (escape-attr s) "\""))
       ">"))

(defn- style-block
  "Static shell CSS plus the optional canvas-width constraint that
   matches the editor's content column. `canvas-w` may be nil/0 — in
   that case no width constraint is emitted."
  [canvas-w]
  (str
   "  <style>\n"
   "    :root { color-scheme: light dark; }\n"
   "    html, body { margin: 0; padding: 0; min-height: 100%; }\n"
   "    body { font-family: system-ui, sans-serif; }\n"
   "    x-theme {\n"
   "      display: block;\n"
   "      min-height: 100vh;\n"
   "      background: var(--x-color-bg);\n"
   "      color: var(--x-color-text);\n"
   "    }\n"
   "    [data-bareforge-positioned] {\n"
   "      position: relative;\n"
   "      isolation: isolate;\n"
   "    }\n"
   "    [data-bareforge-positioned] > [data-bareforge-placement=\"background\"] {\n"
   "      z-index: 0;\n"
   "      pointer-events: none;\n"
   "    }\n"
   "    [data-bareforge-positioned]\n"
   "      > :not([data-bareforge-placement=\"background\"]):not([data-bareforge-placement=\"free\"]) {\n"
   "      position: relative;\n"
   "      z-index: 1;\n"
   "    }\n"
   (when (pos-int? canvas-w)
     (str "    body > x-theme > :first-child {\n"
          "      max-width: " canvas-w "px;\n"
          "      margin-left: auto;\n"
          "      margin-right: auto;\n"
          "    }\n"))
   "  </style>\n"))

(defn- head-block
  "Full `<head>…</head>` for the exported shell. The modulepreload
   block is empty when no manifest is wired (current default until
   BareDOM publishes integrity.json) — in that case the dynamic
   imports below behave exactly as before, with only the CSP meta as
   defence."
  [{:keys [title csp tags base-url canvas-w integrity-manifest]}]
  (str
   "<head>\n"
   "  <meta charset=\"utf-8\">\n"
   "  <meta http-equiv=\"Content-Security-Policy\" content=\""
   (escape-attr csp) "\">\n"
   "  <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
   "  <title>" (escape-text title) "</title>\n"
   (style-block canvas-w)
   (integrity/modulepreload-block integrity-manifest tags base-url)
   "  <script type=\"module\">\n"
   (import-block tags base-url)
   "  </script>\n"
   "</head>\n"))

(defn render-html
  "Pure: assemble a full HTML document string for the given
   persistable slice `{:document :theme}`. Options map:
     :title        — <title> contents (defaults to 'Bareforge export')
     :cdn-version  — BareDOM version tag to load from CDN
     :import-base  — override for the dynamic-import base URL. Used
                     by bundle-mode export to rewrite the CDN URL
                     to a local vendor path like \"./vendor/baredom/\"
     :csp          — Content-Security-Policy header value to embed via
                     a `<meta http-equiv=...>`. Defaults to a CDN-aware
                     policy; bundle export passes the stricter
                     self-only variant.
     :integrity-manifest
                   — parsed `dist/integrity.json` from BareDOM (or
                     nil). When supplied, every BareDOM module the
                     doc loads gets a `<link rel=modulepreload
                     integrity=…>` head entry, binding the dynamic
                     `import()` to BareDOM's published bytes via
                     SRI. Nil → no preload block, current behaviour."
  ([snapshot] (render-html snapshot nil))
  ([{:keys [document theme]} {:keys [title cdn-version import-base csp
                                     integrity-manifest]}]
   (let [title       (or title "Bareforge export")
         cdn-version (or cdn-version versions/baredom-version)
         base-url    (or import-base
                         (str cdn-base "@" cdn-version "/dist/"))
         ;; `x-theme` is the shell wrapper in the exported HTML, not a
         ;; document node — ensure its definition is imported so the
         ;; wrapper registers and applies its tokens.
         tags        (conj (collect-tags document) "x-theme")
         preset      (or (:base-preset theme) "default")
         canvas-w    (get-in document [:canvas :width])]
     (str
      "<!doctype html>\n"
      "<html lang=\"en\">\n"
      (head-block {:title              title
                   :csp                (select-csp csp import-base)
                   :tags               tags
                   :base-url           base-url
                   :canvas-w           canvas-w
                   :integrity-manifest integrity-manifest})
      "<body>\n"
      "  " (theme-open-tag preset (:overrides theme)) "\n"
      "    " (serialize-node (:root document)) "\n"
      "  </x-theme>\n"
      "</body>\n"
      "</html>\n"))))

;; --- effectful: download ------------------------------------------------

(defn- current-snapshot []
  (let [s @state/app-state]
    {:document (:document s)
     :theme    (:theme s)}))

(defn- trigger-download! [html filename]
  (let [blob  (js/Blob. #js [html] #js {:type "text/html"})
        url   (js/URL.createObjectURL blob)
        ^js a (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) filename)
    (.appendChild js/document.body a)
    (.click a)
    (.removeChild js/document.body a)
    (js/URL.revokeObjectURL url)))

(defn download!
  "Trigger a browser download of the current project as a standalone
   HTML file. Optional `filename` and `cdn-version` can be passed.

   Fetches BareDOM's published `dist/integrity.json` first; if it
   resolves, the rendered HTML embeds a
   `<link rel=modulepreload integrity=…>` block so the browser
   refuses to execute a tampered CDN response. If the fetch fails
   (network error, 404 — the typical case until BareDOM publishes
   the manifest), the export still ships, just without the SRI
   hardening — the CSP meta still provides the origin-restricted
   defence in either case."
  ([] (download! nil))
  ([{:keys [filename cdn-version title] :or {filename "bareforge-export.html"}}]
   (let [snapshot (current-snapshot)
         version  (or cdn-version versions/baredom-version)]
     (-> (integrity/fetch-manifest!
          (integrity/manifest-url cdn-base version))
         (.then (fn [manifest]
                  (let [html (render-html snapshot
                                          {:title              title
                                           :cdn-version        version
                                           :integrity-manifest manifest})]
                    (trigger-download! html filename))))))))
