(ns scaffold-component
  "JVM Clojure scaffolder that onboards a new BareDOM component into
   Bareforge. Invoked via the `:scaffold` alias in `deps.edn`:

     clojure -X:scaffold :tag x-new-thing :category :layout

   Edits three files in place:

   1. `src/bareforge/meta/public_api.cljs` — adds a `:require` line in
      alphabetical position and an `api-map` entry in alphabetical
      position.
   2. `src/bareforge/meta/augment.cljs` — adds a `(def ^:private …)`
      block immediately before the `(def augment …)` block, plus a
      `\"<tag>\" <tag>` registration at the tail of the augment map.
   3. `src/bareforge/meta/categories.cljs` — adds a
      `\"<tag>\" <category>` entry at the tail of the
      `tag->category` map.

   The observed attributes come from BareDOM's own
   `baredom/components/<tag>/model.cljs` on the classpath (via
   `io/resource`). Attribute names are dereferenced through the
   file's local `(def attr-… \"<name>\")` defs so scaffolded entries
   emit the actual HTML attribute strings, not internal symbol names.

   Reuses `bareforge.meta.heuristics` for `infer-kind` and
   `humanize-tag` — the same helpers the runtime fallback uses,
   so a hand-scaffolded entry and the runtime fallback report the
   same kinds for the same attributes."
  (:require [bareforge.meta.heuristics :as h]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; --- file paths ----------------------------------------------------------

(def ^:private public-api-path "src/bareforge/meta/public_api.cljs")
(def ^:private augment-path    "src/bareforge/meta/augment.cljs")
(def ^:private categories-path "src/bareforge/meta/categories.cljs")

;; --- BareDOM source extraction -------------------------------------------

(defn- component-resource-path
  "`\"x-bento-grid\"` → `\"baredom/components/x_bento_grid/model.cljs\"`."
  [tag]
  (str "baredom/components/" (str/replace tag "-" "_") "/model.cljs"))

(defn- load-model-source
  "Read the BareDOM model source for `tag` from the classpath. Throws
   with an actionable message when the resource isn't there — almost
   always means the user forgot to bump `deps.edn`."
  [tag]
  (if-let [url (io/resource (component-resource-path tag))]
    (slurp url)
    (throw (ex-info
            (str (component-resource-path tag)
                 " not on classpath. Did you bump deps.edn?")
            {:tag tag}))))

(defn- extract-attr-defs
  "Scan a model source for `(def attr-<name> \"<value>\")` forms and
   return a map `{\"attr-<name>\" \"<value>\"}`. BareDOM typically
   references observed-attributes by symbol rather than by literal
   string, so this lookup table lets us dereference them."
  [source]
  (into {}
        (for [[_ sym val] (re-seq
                           #"\(def\s+(attr-[\w-]+)\s+\"([^\"]+)\"\s*\)"
                           source)]
          [sym val])))

(defn- resolve-attr
  "Turn one element of the `observed-attributes` vector into the real
   attribute name. Elements are either plain strings (already
   resolved) or symbol references that need to be looked up in
   `attr-defs`."
  [attr-defs element]
  (cond
    (string? element) element
    (symbol? element) (or (get attr-defs (name element))
                          (throw (ex-info (str "unresolved attr symbol "
                                               element
                                               " — no matching (def " element
                                               " \"...\") in model source")
                                          {:symbol element})))
    :else             (str element)))

(defn- extract-observed-attributes
  "Pull the `observed-attributes` vector out of a model source and
   resolve every element to its actual string name. Uses a regex to
   isolate `(def observed-attributes …)`, strips the `#js` tag, and
   reads the remaining as plain Clojure data."
  [source]
  (let [pattern #"(?s)\(def\s+observed-attributes\s+#js\s*(\[.*?\])\s*\)"
        attr-defs (extract-attr-defs source)]
    (if-let [m (re-find pattern source)]
      (let [raw (read-string (second m))]
        (mapv #(resolve-attr attr-defs %) raw))
      (throw (ex-info "no (def observed-attributes …) form found"
                      {:source-head (subs source 0 (min 200 (count source)))})))))

;; --- public_api.cljs (text-based) ----------------------------------------

(defn- public-api-has-tag?
  "True when the api-map source already contains a `<tag>/tag-name`
   symbol — the stable marker every registered tag carries."
  [source tag]
  (boolean (re-find (re-pattern (str "(?m)^\\s+" tag "/tag-name\\s"))
                    source)))

(def ^:private require-line-re
  #"(?m)^(\s+)\[baredom\.components\.(x[-\w]+)\.model(\s+):as\s+([-\w]+)\]")

(def ^:private api-map-line-re
  #"(?m)^(\s+)(x[-\w]+)/tag-name(\s+)\(api")

(defn- insert-at-sorted-position
  "Pure: given the file text, a regex whose 2nd capture is a tag
   name per matching line, a new tag, and a new line of text,
   return the updated file text with the new line inserted just
   before the first existing line whose tag sorts after `new-tag`.
   Appends at the end of the matching block if nothing sorts after."
  [source line-re new-tag new-line]
  (let [matches (re-seq line-re source)
        candidates (for [[whole _ tag] matches
                         :when (pos? (compare tag new-tag))]
                     whole)]
    (if-let [before (first candidates)]
      (str/replace-first source before (str new-line "\n" before))
      ;; No match sorts after — append after the last matching line
      (let [last-match (-> matches last first)]
        (str/replace-first source last-match (str last-match "\n" new-line))))))

(defn- public-api-require-line
  "Build a single `:require` entry matching the column alignment the
   existing file uses — 12-space indent, 45-column tag column width.
   The exact padding doesn't matter semantically; picking a value
   close to existing lines keeps the file visually tidy."
  [tag]
  (let [prefix (str "            [baredom.components." tag ".model")
        padded (format "%-58s" prefix)]
    (str padded ":as " tag "]")))

(defn- public-api-map-line
  "Build a single api-map entry matching the column alignment
   the existing file uses."
  [tag]
  (let [key-col     (format "%-32s" (str "   " tag "/tag-name"))
        val-prefix  (str "(api " tag "/tag-name "
                         tag "/property-api "
                         tag "/observed-attributes)")]
    (str key-col val-prefix)))

(defn- edit-public-api!
  "Idempotent text-based edit of public_api.cljs."
  [tag dry-run?]
  (let [source (slurp public-api-path)]
    (if (public-api-has-tag? source tag)
      :skipped
      (let [with-require (insert-at-sorted-position
                          source require-line-re tag
                          (public-api-require-line tag))
            ;; Close the `:require` bracket: the inserted line sits
            ;; inside a `(:require …)` form, so if we happened to
            ;; insert at the end it might now sit outside the
            ;; closing bracket. Handle below in the api-map
            ;; insertion by using a fresh source read.
            with-api-map (insert-at-sorted-position
                          with-require api-map-line-re tag
                          (public-api-map-line tag))]
        (if dry-run?
          :would-add
          (do (spit public-api-path with-api-map)
              :added))))))

;; --- augment.cljs (text-based) -------------------------------------------

(defn- augment-has-tag?
  "Does augment.cljs already have either a `(def ^:private <tag> …)`
   block or a `\"<tag>\" <tag>` registration in the augment map?"
  [source tag]
  (or (boolean (re-find (re-pattern (str "\\(def\\s+\\^:private\\s+" tag "\\b"))
                        source))
      (boolean (re-find (re-pattern (str "\"" tag "\"\\s+" tag "\\b"))
                        source))))

(defn- augment-entry-text
  "Format the `(def ^:private …)` block for a new component."
  [tag category label attrs]
  (let [prop-lines (str/join "\n    "
                             (for [attr attrs]
                               (format "{:name %s :kind %s}"
                                       (pr-str attr)
                                       (h/infer-kind attr))))]
    (format
     "(def ^:private %s\n  {:category %s\n   :label    %s\n   :properties\n   [%s]})"
     tag category (pr-str label) prop-lines)))

(defn- augment-registration-text
  "Single-line `\"<tag>\" <tag>` entry for the augment map."
  [tag]
  (format "   %-22s %s" (str \" tag \") tag))

(defn- edit-augment!
  "Text-based edit of augment.cljs. Inserts the new def above
   `(def augment …)` with a blank line between them, and adds
   the registration just before the final closing `})` of the
   augment map."
  [tag category label attrs dry-run?]
  (let [source (slurp augment-path)]
    (if (augment-has-tag? source tag)
      (throw (ex-info
              (str tag " already registered in augment.cljs. "
                   "Scaffolder aborting to avoid clobbering hand edits.")
              {:tag tag}))
      (let [entry (augment-entry-text tag category label attrs)
            reg   (augment-registration-text tag)
            ;; Insert the new def block right before `(def augment`
            with-entry (str/replace-first
                        source
                        "(def augment"
                        (str entry "\n\n(def augment"))
            ;; Find the final `})` in the file — that's the closing
            ;; of the augment map — and insert the registration on a
            ;; new line immediately before it (no trailing newline so
            ;; the `})` stays tight on the same line as the new entry).
            last-close (str/last-index-of with-entry "})")
            with-reg   (str (subs with-entry 0 last-close)
                            "\n" reg
                            (subs with-entry last-close))]
        (if dry-run?
          :would-add
          (do (spit augment-path with-reg)
              :added))))))

;; --- categories.cljs (text-based) ----------------------------------------

(defn- categories-has-tag? [source tag]
  (boolean (re-find (re-pattern (str "\"" tag "\"\\s+:"))
                    source)))

(defn- category-entry-text [tag category]
  (format "   %-22s %s" (str \" tag \") category))

(defn- edit-categories!
  [tag category dry-run?]
  (let [source (slurp categories-path)]
    (if (categories-has-tag? source tag)
      :skipped
      (let [entry (category-entry-text tag category)
            ;; Find the closing `})` of the tag->category map and
            ;; insert the entry just before it (no trailing newline).
            last-close (str/last-index-of source "})")
            with-entry (str (subs source 0 last-close)
                            "\n" entry
                            (subs source last-close))]
        (if dry-run?
          :would-add
          (do (spit categories-path with-entry)
              :added))))))

;; --- entry point ---------------------------------------------------------

(defn scaffold!
  "Exec-fn for the `:scaffold` alias. Accepts:
     :tag      — required, the BareDOM tag name (e.g. x-new-thing)
     :category — required, a keyword category (:layout :form …)
     :label    — optional, overrides the humanized default
     :dry-run  — optional, prints what would change without writing"
  [{:keys [tag category label dry-run]}]
  (let [tag      (some-> tag name)
        category (some-> category keyword)
        dry-run? (boolean dry-run)]
    (when-not tag
      (throw (ex-info "missing :tag (e.g. :tag x-bento-grid)" {})))
    (when-not category
      (throw (ex-info "missing :category (e.g. :category :layout)" {})))

    (println (str "Scaffolding " tag " (" category ")"
                  (when dry-run? " [DRY RUN]")))

    (let [source (load-model-source tag)
          attrs  (extract-observed-attributes source)
          label  (or label (h/humanize-tag tag))]
      (println (str "  model.cljs: " (count attrs) " observed attribute"
                    (when-not (= 1 (count attrs)) "s")))

      (let [result (edit-public-api! tag dry-run?)]
        (println (str "  public_api.cljs: " (name result))))

      (let [result (edit-augment! tag category label attrs dry-run?)]
        (println (str "  augment.cljs: " (name result))))

      (let [result (edit-categories! tag category dry-run?)]
        (println (str "  categories.cljs: " (name result))))

      (println)
      (println (str "Done. " label " is now a Bareforge component."))
      (println "Remember to edit slots.cljs if it is a container,")
      (println "or placement.cljs if it needs a snap hint.")
      nil)))
