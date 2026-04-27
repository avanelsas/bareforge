(ns bareforge.export.cljs-project.download
  "Browser-side ClojureScript project export. Generates the project
   files via `cljs-project/generate` and packages them into a .zip
   download using JSZip."
  (:require [bareforge.export.cljs-project :as cp]
            [bareforge.export.plugin :as plugin]
            [bareforge.state :as state]
            ["jszip" :as JSZip]))

(defn- trigger-download! [^js blob filename]
  (let [url   (js/URL.createObjectURL blob)
        ^js a (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) filename)
    (.appendChild js/document.body a)
    (.click a)
    (.removeChild js/document.body a)
    (js/URL.revokeObjectURL url)))

(defn download!
  "Generate a ClojureScript project from the current document and
   trigger a .zip download. Options:
     :filename — download filename (default \"bareforge-cljs-export.zip\")
     :app-ns   — root namespace (default \"app\")
     :title    — project title (default \"Bareforge Export\")"
  ([] (download! nil))
  ([{:keys [filename app-ns title]
     :or   {filename "bareforge-cljs-export.zip"
            app-ns   "app"
            title    "Bareforge Export"}}]
   (let [doc   (:document @state/app-state)
         files (cp/generate doc {:app-ns app-ns :title title})
         zip   (JSZip.)]
     (doseq [[path content] files]
       (plugin/assert-safe-zip-path! path)
       (.file zip path content))
     (-> (.generateAsync zip #js {:type "blob"})
         (.then (fn [^js blob] (trigger-download! blob filename)))
         (.catch (fn [^js err]
                   (js/console.error "ClojureScript export failed:" err)
                   (js/alert (str "ClojureScript export failed: " err))))))))
