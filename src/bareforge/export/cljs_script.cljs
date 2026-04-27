(ns bareforge.export.cljs-script
  "Node script entry point for exporting a Bareforge project file
   to a ClojureScript project on disk.

   Usage:
     npx shadow-cljs compile export-cljs
     node out/export-cljs.js <input.json> [output-dir]

   Defaults:
     output-dir = out/export"
  (:require [cljs.reader :as edn]
            [bareforge.export.cljs-project :as cp]))

(defn main []
  (let [args    (js->clj (.slice js/process.argv 2))
        input   (first args)
        output  (or (second args) "out/export")]
    (when-not input
      (println "Usage: node out/export-cljs.js <input.json> [output-dir]")
      (js/process.exit 1))
    (let [fs      (js/require "fs")
          raw     (.readFileSync fs input "utf8")
          parsed  (edn/read-string raw)]
      (when-not (= "bareforge-project" (:format parsed))
        (println "Error: not a valid Bareforge project file")
        (js/process.exit 1))
      (let [doc   (:document parsed)
            files (cp/generate doc {:app-ns "app"
                                    :title  "Bareforge Export"
                                    :port   9000})]
        (cp/write-project! output files)
        (println (str "Exported " (count files) " files to " output "/"))
        (doseq [path (sort (keys files))]
          (println (str "  " path)))))))
