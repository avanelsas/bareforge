(ns new-export
  "Scaffold a new export plugin under `src/bareforge/export/<id>/`.
   Invoked via the `:new-export` alias in `deps.edn`:

     clojure -X:new-export :id :react :label \"React\"
     clojure -X:new-export :id :svelte :label \"Svelte\" :order 50

   Required:
     :id     — keyword identifier (becomes the manifest `:id` and
               the directory / namespace segment). Hyphens in the
               keyword become hyphens in paths and underscores in
               JS-bound identifiers.
     :label  — File-menu label string.

   Optional:
     :description  — short blurb (default \"<title> export\").
     :order        — File-menu sort key (default 100, after the
                     four built-ins which use 10/20/30/40).
     :interactive? — boolean (default true).
     :extension    — artefact extension without dot (default \"zip\").
     :dry-run      — true to print the plan without writing.

   Writes:
     src/bareforge/export/<id-snake>/plugin.cljs

   The skeleton has the manifest pre-filled and stub `generate` /
   `download!` fns marked TODO. After running:

   1. Implement `generate` against `bareforge.export.model` —
      `docs/plugins.md` walks through the helpers.
   2. Implement `download!` (typically wraps the generate output in
      a JSZip / blob and triggers a browser download).
   3. Register the plugin in `src/bareforge/export/registry.cljs` —
      add a require + append the manifest to the `plugins` vector.
   4. Add a test under `test/bareforge/export/<id-snake>_test.cljs`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- kebab
  "Drop the leading `:` from a keyword and lower-case the result.
   Hyphens are preserved (path-friendly, ns-friendly)."
  [k]
  (-> (name k)
      str/lower-case
      str/trim
      (str/replace #"[^a-z0-9-]+" "-")
      (str/replace #"^-|-$" "")))

(defn- snake
  "Underscored variant for filesystem paths whose ns mapping
   ClojureScript expects (foo-bar.cljs ns → foo_bar.cljs file)."
  [s]
  (str/replace s "-" "_"))

(defn- title-case [s]
  (->> (str/split s #"-")
       (map str/capitalize)
       (str/join " ")))

(defn- plugin-template
  "Stamp the per-plugin source from the supplied manifest fields."
  [{:keys [id-kebab label description order interactive? extension]}]
  (let [ns-name (str "bareforge.export." id-kebab ".plugin")]
    (str
"(ns " ns-name "
  \"" label " export plugin. TODO — describe what this plugin emits
   and how the user runs the resulting artefact.

   Implement `generate` against `bareforge.export.model` (see
   `docs/plugins.md` for the helper API). Implement `download!`
   to package the generate output and trigger a browser download.\"
  (:require [bareforge.export.model :as em]
            [bareforge.state :as state]))

;; --- pure: file-map generator -------------------------------------------

(defn generate
  \"Pure: produce the full exported project as `{file-path → content}`
   from the document and options map. Plugin authors walk the export
   model here — `(em/detect-groups doc)`, `(em/collect-group-data ...)`,
   `(em/template-group? ...)`, etc.\"
  [doc opts]
  (let [{:keys [groups]} (em/detect-groups doc)]
    ;; TODO: build a real file map. Throw `(ex-info \"…\" {:error :nyi})`
    ;; for any feature this v0.1 doesn't support yet, so the File menu
    ;; surfaces a clear error instead of silently emitting broken
    ;; output.
    {\"index.html\"
     (str \"<!doctype html>\\n<title>" label " export — TODO</title>\\n\"
          \"<p>Implement `generate` in \" *file* \"</p>\\n\")}))

;; --- effectful: download! ------------------------------------------------

(defn- download!
  \"Trigger a browser download of the current document as a " label "
   artefact. Plugin authors typically wrap `generate`'s output in a
   JSZip + blob, but the shape is open — anything that ends in a
   blob URL the user can save works.\"
  [filename]
  (let [doc (:document @state/app-state)]
    (try
      (let [_files (generate doc {:title filename})]
        ;; TODO: package _files into a downloadable artefact and
        ;; trigger the download. See `bareforge.export.cljs-project.download`
        ;; or `bareforge.export.vanilla-js.download` for the JSZip pattern.
        (js/alert (str \"" label " export: TODO implement download! — \"
                       \"see scripts/new_export.clj for the next-step checklist.\")))
      (catch :default e
        (js/console.error \"" label " export failed:\" e)
        (js/alert (str \"" label " export failed: \" (ex-message e)))))))

;; --- manifest ------------------------------------------------------------

(def manifest
  {:id           :" id-kebab "
   :label        \"" label "\"
   :extension    \"" extension "\"
   :interactive? " (boolean interactive?) "
   :description  \"" description "\"
   :order        " order "
   :download!    download!})
")))

(defn- write-or-print! [target content dry-run?]
  (println (str (if dry-run? "[DRY RUN] would write " "Wrote ") target))
  (when-not dry-run?
    (.mkdirs (.getParentFile (io/file target)))
    (spit target content)))

(defn -main
  [{:keys [id label description order interactive? extension dry-run]
    :or   {order        100
           interactive? true
           extension    "zip"
           dry-run      false}}]
  (when-not (keyword? id)
    (throw (ex-info "new-export requires :id <keyword>" {:got id})))
  (when-not (string? label)
    (throw (ex-info "new-export requires :label <string>" {:got label})))
  (let [id-kebab    (kebab id)
        id-snake    (snake id-kebab)
        description (or description (str (title-case id-kebab) " export"))
        target-dir  (str "src/bareforge/export/" id-snake)
        plugin-path (str target-dir "/plugin.cljs")]
    (when (and (not dry-run) (.exists (io/file target-dir)))
      (throw (ex-info (str target-dir " already exists — pick another :id "
                           "or remove the existing directory first")
                      {:dir target-dir})))
    (println (str "\nScaffolding new export plugin: " id))
    (println (str "  ns:      bareforge.export." id-kebab ".plugin"))
    (println (str "  dir:     " target-dir "/"))
    (println (str "  label:   " label))
    (println (str "  order:   " order))
    (println (str "  interactive?: " (boolean interactive?)))
    (write-or-print!
      plugin-path
      (plugin-template {:id-kebab     id-kebab
                        :label        label
                        :description  description
                        :order        order
                        :interactive? interactive?
                        :extension    extension})
      dry-run)
    (println "\nNext steps:")
    (println (str "  1. Implement generate / download! in " plugin-path "."))
    (println "  2. Register the plugin in src/bareforge/export/registry.cljs:")
    (println (str "       (:require ... [bareforge.export." id-kebab ".plugin :as " id-kebab "])"))
    (println (str "       (def plugins (sort-by :order [... " id-kebab "/manifest]))"))
    (println (str "  3. Add a test at test/bareforge/export/" id-snake "_test.cljs."))
    (println (str "  4. Run `npx shadow-cljs compile test` and confirm the new"))
    (println (str "     plugin shows up via registry-test's manifest validation."))
    (println "")))
