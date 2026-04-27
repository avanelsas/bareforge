(ns bareforge.export.registry
  "The in-tree list of export plugins. `toolbar.cljs` reads this to
   build the File menu; each entry's manifest drives label, order,
   extension, and the download! callback.

   Adding a plugin is a single-edit affair: create the plugin ns
   under `src/bareforge/export/<name>/plugin.cljs`, import its
   manifest into this file, and append it to `plugins`. No File-
   menu hand-wiring.

   v1 keeps this as a static vector. Later phases may switch to
   discovery via a conventions-based walk, but a static list is
   dead-simple and easy to audit."
  (:require [bareforge.export.bundle :as bundle-export]
            [bareforge.export.cljs-project.download :as cljs-export]
            [bareforge.export.html :as html-export]
            [bareforge.export.plugin :as plugin]
            [bareforge.export.vanilla-js.plugin :as vanilla-js]))

;; --- Built-in plugins -----------------------------------------------------

(def html-plugin
  {:id           :html
   :label        "Export HTML (static snapshot)"
   :extension    "html"
   :interactive? false
   :description  "Single HTML file loading BareDOM components from a
                  jsDelivr CDN at runtime. Markup only — no JS emitted
                  for :events / :bindings / :computed fields. Best
                  for static preview and PDF-like sharing."
   :order        10
   :download!    (fn [filename]
                   (html-export/download! {:filename filename}))})

(def bundle-plugin
  {:id           :bundle
   :label        "Export bundle (static snapshot)"
   :extension    "zip"
   :interactive? false
   :description  "Zip with the HTML plus a local vendor/baredom/
                  folder carrying every module the document uses;
                  serves from any static HTTP server offline.
                  Same markup-only contract as the HTML export."
   :order        20
   :download!    (fn [filename]
                   (bundle-export/download! {:filename filename}))})

(def cljs-plugin
  {:id           :cljs
   :label        "Export ClojureScript (interactive)"
   :extension    "zip"
   :interactive? true
   :description  "Full shadow-cljs project with a re-frame-subset
                  runtime. Actions fire, bindings track state,
                  computed subs recompute. `npm install && npx
                  shadow-cljs watch app` runs the exported app."
   :order        30
   :download!    (fn [filename]
                   (cljs-export/download! {:filename filename}))})

;; --- Registry + utilities -------------------------------------------------

(def plugins
  "Ordered by `:order`. Adding a plugin: create
   `src/bareforge/export/<name>/plugin.cljs`, import its manifest
   here, and append to this vector."
  (sort-by :order [html-plugin bundle-plugin cljs-plugin
                   vanilla-js/manifest]))

(defn- manifest-valid?
  "True when `m` has every key required by the plugin contract."
  [m]
  (every? #(contains? m %) plugin/valid-manifest-keys))

(defn validated-plugins
  "Return the plugin list, filtering out any whose manifest is
   missing required keys. In v1 every manifest is defined in this
   file so this is belt-and-braces; once third parties add their
   own plugins it becomes the real enforcement point."
  []
  (filter manifest-valid? plugins))

(defn plugin-by-id
  "Look up a plugin by its manifest `:id`. Returns nil if absent."
  [id]
  (first (filter #(= id (:id %)) plugins)))
