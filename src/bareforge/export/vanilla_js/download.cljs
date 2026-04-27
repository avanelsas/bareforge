(ns bareforge.export.vanilla-js.download
  "Browser-side download of the vanilla-JS export. Packages the
   `generate`-produced file map into a .zip via JSZip and triggers
   a blob download. Shares the pattern with
   `bareforge.export.cljs-download`; differences are only the
   generate fn (v0.1 throws NYI for unsupported features) and the
   default filename."
  (:require [bareforge.export.integrity :as integrity]
            [bareforge.export.plugin :as plugin]
            [bareforge.meta.versions :as versions]
            [bareforge.state :as state]
            ["jszip" :as JSZip]))

(def ^:private cdn-base "https://cdn.jsdelivr.net/npm/@vanelsas/baredom")

(defn- trigger-download! [^js blob filename]
  (let [url   (js/URL.createObjectURL blob)
        ^js a (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) filename)
    (.appendChild js/document.body a)
    (.click a)
    (.removeChild js/document.body a)
    (js/URL.revokeObjectURL url)))

(defn- build-and-download! [generate-fn doc title filename manifest]
  (try
    (let [files (generate-fn doc {:title              title
                                  :integrity-manifest manifest})
          zip   (JSZip.)]
      (doseq [[path content] files]
        (plugin/assert-safe-zip-path! path)
        (.file zip path content))
      (-> (.generateAsync zip #js {:type "blob"})
          (.then (fn [^js blob] (trigger-download! blob filename)))
          (.catch (fn [^js err]
                    (js/console.error "Vanilla-JS export failed:" err)
                    (js/alert (str "Vanilla-JS export failed: " err))))))
    (catch :default e
      (let [data (ex-data e)
            msg  (if (= :nyi (:error data))
                   (ex-message e)
                   (str "Vanilla-JS export failed: " (ex-message e)))]
        (js/console.error "Vanilla-JS export failed:" e)
        (js/alert msg)))))

(defn download!
  "Run `generate-fn doc opts` and trigger a .zip download. `opts`
   accepts :filename (default \"bareforge-vanilla-js-export.zip\")
   and :title (default \"Bareforge Vanilla JS Export\").

   Fetches BareDOM's integrity.json before generate so the exported
   index.html embeds `<link rel=modulepreload integrity=…>` SRI
   bindings. On fetch failure (typical until BareDOM publishes the
   manifest) the export still ships, just without the SRI block —
   the CSP meta still provides origin-restricted defence either way."
  [generate-fn {:keys [filename title]
                :or   {filename "bareforge-vanilla-js-export.zip"
                       title    "Bareforge Vanilla JS Export"}}]
  (let [doc      (:document @state/app-state)
        manifest-url (integrity/manifest-url cdn-base versions/baredom-version)]
    (-> (integrity/fetch-manifest! manifest-url)
        (.then (fn [manifest]
                 (build-and-download! generate-fn doc title filename manifest))))))
