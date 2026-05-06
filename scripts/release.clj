(ns release
  "Release-cut helper. Promotes `CHANGELOG.md`'s `[Unreleased]` block
   to a versioned section, bumps `package.json`, verifies the BareDOM
   `deps.edn` ↔ `meta/versions.cljs` lockstep, and runs the four
   PR-readiness gates. Tagging and pushing stay manual.

   Invoked via the `:release` alias in `deps.edn`:

     clojure -X:release :bump :patch                       ; preview
     clojure -X:release :bump :minor :confirm true         ; write files
     clojure -X:release :version '\"0.5.0\"' :confirm true ; explicit version

   Required (one of):
     :bump     — :patch | :minor | :major
     :version  — explicit X.Y.Z string (overrides :bump)

   Optional:
     :confirm  — true to write changes (default false: preview only).
                 Both runs perform every check; only writes are gated.
     :date     — override release date (YYYY-MM-DD; default today).
     :skip-gates — true to skip the four PR-readiness gates (use only
                   when you have just run them yourself).

   Refuses to proceed if:
     - `[Unreleased]` is empty or only contains 'Nothing yet.'.
     - BareDOM version disagrees between `deps.edn` and
       `src/bareforge/meta/versions.cljs`.
     - Any of the four gates fails (clj-kondo, cljfmt, shadow-cljs
       compile test, shadow-cljs release app).

   Never pushes, never tags, never amends. After a successful run the
   script prints `git add` / `git commit` / `git tag` / `git push`
   commands for the maintainer to copy-paste."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.time LocalDate]))

;; --- file paths ----------------------------------------------------------

(def ^:private package-json-path  "package.json")
(def ^:private changelog-path     "CHANGELOG.md")
(def ^:private deps-edn-path      "deps.edn")
(def ^:private versions-cljs-path "src/bareforge/meta/versions.cljs")

;; --- pure: SemVer arithmetic ---------------------------------------------

(defn- parse-version
  "\"0.3.0\" → [0 3 0]. Throws on malformed input — pre-release suffixes
   (`-rc.1`, `+build`) are intentionally unsupported; this project has
   never used them, and supporting them quietly would make the bump
   semantics ambiguous."
  [s]
  (let [parts (str/split s #"\.")]
    (when-not (= 3 (count parts))
      (throw (ex-info (str "Expected X.Y.Z, got " (pr-str s)) {:got s})))
    (mapv #(Long/parseLong %) parts)))

(defn- format-version [[maj mn pat]]
  (str maj "." mn "." pat))

(defn- bump-version [version bump]
  (let [[maj mn pat] (parse-version version)]
    (case bump
      :patch (format-version [maj mn (inc pat)])
      :minor (format-version [maj (inc mn) 0])
      :major (format-version [(inc maj) 0 0])
      (throw (ex-info (str "Unknown :bump " (pr-str bump))
                      {:got bump :allowed [:patch :minor :major]})))))

;; --- pure: package.json version line rewrite -----------------------------

(def ^:private pkg-version-rx
  ;; Anchored on a line; npm always emits `"version": "X.Y.Z",` at top
  ;; level, but this also tolerates extra whitespace.
  #"(?m)^(\s*\"version\"\s*:\s*\")([^\"]+)(\")")

(defn- read-current-version [pkg-content]
  (or (some-> (re-find pkg-version-rx pkg-content) (nth 2))
      (throw (ex-info "Cannot read current version from package.json"
                      {:file package-json-path}))))

(defn- rewrite-package-json [content new-version]
  (when-not (re-find pkg-version-rx content)
    (throw (ex-info "No version line found in package.json"
                    {:file package-json-path})))
  (str/replace content pkg-version-rx (str "$1" new-version "$3")))

;; --- pure: CHANGELOG.md promotion ----------------------------------------

(defn- unreleased-body
  "The body between `## [Unreleased]` and the next `## [` heading,
   trimmed. Nil if the section is missing or the body is blank."
  [content]
  (when-let [m (re-find #"(?s)## \[Unreleased\]\s*\n+(.*?)\n+## \[" content)]
    (let [body (str/trim (second m))]
      (when-not (str/blank? body) body))))

(defn- empty-unreleased?
  "True when `[Unreleased]` is missing, blank, or only contains the
   placeholder 'Nothing yet.'. The script refuses to release a no-op
   so a forgotten changelog entry can't ship a silent release."
  [content]
  (let [body (unreleased-body content)]
    (or (nil? body)
        (= body "Nothing yet."))))

(defn- promote-changelog
  "Rewrite `[Unreleased]` body into a new versioned section, leaving
   an empty `[Unreleased]` placeholder above. Whitespace around the
   inserted block matches the existing CHANGELOG conventions."
  [content version date]
  (let [m (re-matcher #"(?s)(## \[Unreleased\]\s*\n+)(.*?)\n+(## \[)" content)]
    (when-not (.find m)
      (throw (ex-info "Could not locate [Unreleased] block in CHANGELOG.md"
                      {:file changelog-path})))
    (let [body (str/trim (.group m 2))]
      (str (subs content 0 (.start m))
           (.group m 1)
           "Nothing yet.\n\n"
           "## [" version "] — " date "\n\n"
           body
           "\n\n"
           (.group m 3)
           (subs content (.end m))))))

;; --- pure: BareDOM lockstep extraction -----------------------------------

(defn- baredom-version-from-deps-edn [content]
  (-> (edn/read-string content)
      (get-in [:deps 'com.github.avanelsas/baredom :mvn/version])))

(defn- baredom-version-from-versions-cljs
  "Read the `(def baredom-version ...)` form and return the last
   semver-shaped string inside it. Bypasses the optional docstring
   without parsing the file as Clojure source."
  [content]
  (when-let [block (second
                    (re-find #"(?s)\(def\s+baredom-version\b([^)]*)\)"
                             content))]
    (when-let [match (last (re-seq #"\"(\d+\.\d+\.\d+)\"" block))]
      (second match))))

(defn- check-baredom-lockstep!
  "Throws unless deps.edn and meta/versions.cljs agree. Reuses the same
   contract the runtime test (test/bareforge/meta/versions_test.cljs)
   enforces — the script just front-loads the failure to release time."
  []
  (let [from-deps (baredom-version-from-deps-edn (slurp deps-edn-path))
        from-cljs (baredom-version-from-versions-cljs (slurp versions-cljs-path))]
    (when-not (and from-deps from-cljs (= from-deps from-cljs))
      (throw (ex-info (str "BareDOM version mismatch — "
                           deps-edn-path " says " (pr-str from-deps) ", "
                           versions-cljs-path " says " (pr-str from-cljs)
                           ". Bump both in lockstep before releasing.")
                      {:deps from-deps :cljs from-cljs})))
    from-deps))

;; --- effectful: PR-readiness gates ---------------------------------------

(def ^:private gates
  [{:label "clj-kondo lint"      :cmd ["clj-kondo" "--lint" "src" "test" "scripts"]}
   {:label "cljfmt check"        :cmd ["cljfmt" "check"]}
   {:label "shadow-cljs compile test" :cmd ["npx" "shadow-cljs" "compile" "test"]}
   {:label "shadow-cljs release app"  :cmd ["npx" "shadow-cljs" "release" "app"]}])

(defn- run-gate!
  "Run a gate inheriting stdio so the maintainer sees live progress —
   especially useful for the ~10s shadow-cljs builds. Throws on
   non-zero exit."
  [{:keys [label cmd]}]
  (println (str "\n→ " label " (" (str/join " " cmd) ")"))
  (let [pb   (doto (ProcessBuilder. ^java.util.List cmd)
               (.inheritIO))
        proc (.start pb)
        exit (.waitFor proc)]
    (when-not (zero? exit)
      (throw (ex-info (str label " failed (exit " exit ")")
                      {:gate label :exit exit})))
    (println (str "  ✓ " label " ok"))))

(defn- run-gates! []
  (doseq [g gates] (run-gate! g))
  (println "\nAll four PR-readiness gates green."))

;; --- main ---------------------------------------------------------------

(defn- print-next-steps [version]
  (println "\nNext steps (run yourself — the script does not push or tag):")
  (println (str "  git add " package-json-path " " changelog-path))
  (println (str "  git commit -m \"Release v" version "\""))
  (println (str "  git push origin feature/release-v" version))
  (println "  # open PR; after squash-merge to main, fetch and tag:")
  (println "  git fetch origin && git checkout main && git pull --ff-only")
  (println (str "  git tag v" version " && git push origin v" version)))

(defn- print-preview [current next-version date]
  (println "\nDry run — no files modified. Pass :confirm true to apply.")
  (println "")
  (println "Diff preview:")
  (println "  package.json:")
  (println (str "    -   \"version\": \"" current "\","))
  (println (str "    +   \"version\": \"" next-version "\","))
  (println "  CHANGELOG.md:")
  (println (str "    [Unreleased] body promoted into [" next-version "] — " date)))

(defn -main
  [{:keys [bump version confirm date skip-gates]
    :or   {confirm    false
           skip-gates false}}]
  (when-not (or bump version)
    (throw (ex-info "release requires :bump <:patch|:minor|:major> or :version <\"X.Y.Z\">"
                    {})))
  (let [pkg-content   (slurp package-json-path)
        cl-content    (slurp changelog-path)
        current       (read-current-version pkg-content)
        next-version  (or version (bump-version current bump))
        release-date  (or date (str (LocalDate/now)))
        baredom       (check-baredom-lockstep!)]
    (println "")
    (println (str "Bareforge release: " current " → " next-version
                  "  (date " release-date ")"))
    (println (str "BareDOM lockstep:   " baredom " (deps.edn ↔ versions.cljs agree)"))

    (when (empty-unreleased? cl-content)
      (throw (ex-info "[Unreleased] is empty — write the changelog entry first"
                      {:file changelog-path})))

    (if skip-gates
      (println "\nSkipping PR-readiness gates (:skip-gates true). Re-run them yourself before pushing.")
      (run-gates!))

    (let [pkg-new (rewrite-package-json pkg-content next-version)
          cl-new  (promote-changelog cl-content next-version release-date)]
      (if confirm
        (do
          (spit package-json-path pkg-new)
          (spit changelog-path cl-new)
          (println "")
          (println (str "✓ wrote " package-json-path))
          (println (str "✓ wrote " changelog-path))
          (print-next-steps next-version))
        (print-preview current next-version release-date))
      (println ""))))
