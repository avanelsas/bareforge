(ns bareforge.export.vanilla-js.plugin
  "Vanilla-JavaScript export plugin. Consumes the
   `bareforge.export.model` lowered form of a Bareforge document
   and emits a small static project — runtime.js + renderer.js +
   per-group view modules + an index.html that wires them up.

   Full feature parity with the other exports: stateful groups
   with stored fields, simple actions (`:set` / `:toggle` /
   `:increment` / `:decrement` / `:clear`), template groups and
   collection fields with `:add` / `:remove`, vector-record
   re-keying via `qualifyMap`, attribute bindings (read / write /
   read-write), implicit trigger payloads from within template
   views, `:text-field` substitution, the seven v1 computed
   operations (`:count-of`, `:sum-of`, `:empty-of`, `:negation`,
   `:any-of`, `:filter-by`, `:join-on`), and `:inner-html`
   raw-HTML nodes (e.g. x-icon SVG) — parsed at codegen time via
   `bareforge.export.html-to-hiccup` and emitted inline as nested
   hiccup, layered on top of the doc-level
   `bareforge.doc.sanitize` defence."
  (:require [bareforge.export.html :as html]
            [bareforge.export.integrity :as integrity]
            [bareforge.export.model :as em]
            [bareforge.export.vanilla-js.codegen :as codegen]
            [bareforge.export.vanilla-js.download :as download]
            [bareforge.meta.versions :as versions]
            [clojure.string :as str]
            [shadow.resource :as rc]))

(def ^:private runtime-js-template
  (rc/inline "bareforge/export/vanilla_js/runtime.js"))

(def ^:private renderer-js-template
  (rc/inline "bareforge/export/vanilla_js/renderer.js"))

(defn- baredom-loader-block
  "BareDOM's custom elements need their ESM modules loaded and
   `init()` called before the browser can upgrade `<x-switch>` /
   `<x-button>` / etc. into working components. Dynamic-import
   every tag the doc uses from the jsDelivr CDN at the same
   version the other exports pin via `meta.versions`. Same
   mechanism as `bareforge.export.html/import-block`.

   When `manifest` is supplied (BareDOM's published
   `dist/integrity.json`), a `<link rel=modulepreload integrity=…>`
   block is emitted ahead of the script so the dynamic imports are
   SRI-bound. Nil → no preload block."
  [doc manifest]
  (let [tags (conj (html/collect-tags doc) "x-theme")
        tag-literals (str/join ",\n      "
                       (for [t tags] (str "\"" t "\"")))
        base-url (str "https://cdn.jsdelivr.net/npm/@vanelsas/baredom@"
                      versions/baredom-version "/dist/")]
    (str (integrity/modulepreload-block manifest tags base-url)
         "  <script type=\"module\">\n"
         "    const __base = \"" base-url "\";\n"
         "    const __tags = [\n      " tag-literals "\n    ];\n"
         "    await Promise.all(__tags.map(async (tag) => {\n"
         "      const mod = await import(__base + tag + \".js\");\n"
         "      if (typeof mod.init === \"function\") mod.init();\n"
         "    }));\n"
         "  </script>\n")))

(defn- index-html
  "index.html for the exported project. Inlines a BareDOM loader
   block so every custom element the doc uses is registered
   before `app.js` runs. CSS mirrors `bareforge.export.html`'s
   shell so `x-theme` actually paints with the active preset's
   `--x-color-bg` / `--x-color-text` tokens — without those
   bindings, the theme inherits whatever the OS color-scheme
   resolves to (often black in dark mode)."
  [doc title manifest]
  (str "<!doctype html>\n"
       "<html lang=\"en\">\n"
       "<head>\n"
       "  <meta charset=\"utf-8\">\n"
       "  <meta http-equiv=\"Content-Security-Policy\" content=\""
         "default-src 'self'; "
         "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; "
         "style-src 'self' 'unsafe-inline'; "
         "img-src 'self' data: https:; "
         "font-src 'self' data: https://cdn.jsdelivr.net; "
         "connect-src 'self' https://cdn.jsdelivr.net; "
         "object-src 'none'; "
         "base-uri 'self'\">\n"
       "  <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
       "  <title>" title "</title>\n"
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
       "  </style>\n"
       (baredom-loader-block doc manifest)
       "</head>\n"
       "<body>\n"
       "  <x-theme preset=\"default\">\n"
       "    <div id=\"app\"></div>\n"
       "  </x-theme>\n"
       "  <script type=\"module\" src=\"./app.js\"></script>\n"
       "</body>\n"
       "</html>\n"))

(defn- package-json [title]
  (str "{\n"
       "  \"name\": \""
       (-> title clojure.string/lower-case
           (clojure.string/replace #"[^a-z0-9]+" "-")
           (clojure.string/replace #"^-|-$" ""))
       "\",\n"
       "  \"version\": \"0.1.0\",\n"
       "  \"private\": true,\n"
       "  \"description\": \"Bareforge vanilla-JS export\",\n"
       "  \"type\": \"module\"\n"
       "}\n"))

(defn generate
  "Pure: produce the full exported vanilla-JS project as
   `{file-path → content}` from the document and options map.

   v0.1 throws `ex-info {:error :nyi}` when the document contains
   features the codegen hasn't implemented yet (template groups,
   computed fields, etc.) — see the ns docstring for the full list.
   This lets the File menu surface a clear error rather than
   silently emit broken JS."
  [doc {:keys [title integrity-manifest]
        :or   {title "Bareforge Vanilla JS Export"}}]
  (let [{:keys [groups]} (em/detect-groups doc)
        _                (codegen/assert-supported! doc groups)]
    (merge
      {"index.html"   (index-html doc title integrity-manifest)
       "package.json" (package-json title)
       "runtime.js"   runtime-js-template
       "renderer.js"  renderer-js-template
       "app.js"       (codegen/emit-app-js doc groups)}
      (into {}
        (for [g groups
              [suffix content] [["db.js"     (codegen/emit-group-db doc g groups)]
                                ["subs.js"   (codegen/emit-group-subs doc g groups)]
                                ["events.js" (codegen/emit-group-events doc g groups)]
                                ["views.js"  (codegen/emit-group-views doc g groups)]]
              :when content]
          [(str (:ns-name g) "/" suffix) content])))))

(def manifest
  {:id           :vanilla-js
   :label        "Export vanilla JavaScript (interactive)"
   :extension    "zip"
   :interactive? true
   :description  "Plain DOM API + tiny reactive store, no framework.
                  Full feature parity with the other exports —
                  stateful and template groups, collection fields,
                  bindings, triggers, the seven computed operations,
                  and inline x-icon SVG."
   :order        40
   :download!    (fn [filename]
                   (download/download! generate {:filename filename}))})
