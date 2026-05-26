(ns bareforge.storage.project-file
  "Explicit Save / Open / New via browser-native file download and
   `<input type=\"file\">`. No File System Access API handles in v1 —
   downloads always prompt for a filename, opens always prompt for a
   file. Works across every modern browser.

   On load, the deserialized payload is validated against
   `::spec/project-file` AND scanned for XSS payloads in
   `:inner-html` / URL attrs via `bareforge.doc.sanitize`. A failure
   refuses the load rather than silently installing a malformed or
   malicious document — CLAUDE.md rule."
  (:require [bareforge.doc.sanitize :as sanitize]
            [bareforge.doc.spec :as spec]
            [bareforge.state :as state]
            [bareforge.storage.indexeddb :as idb]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(def max-file-bytes
  "Cap on the size of an opened project file. EDN parsing + spec
   validation are O(file size), and a 100 MB hand-crafted blob can
   hang the tab without a guard. 5 MB is far above any realistic
   landing-page document."
  (* 5 1024 1024))

(defn- now-iso [] (.toISOString (js/Date.)))

(defn project-basename
  "Current project filename minus the .json extension. Accepts either
   an app-state map (reads `[:project-file :name]`) or a raw filename
   string. Empty / nil → \"untitled\"."
  [x]
  (let [name-str (if (map? x)
                   (get-in x [:project-file :name] "")
                   (or x ""))
        s        (str/replace name-str #"(?i)\.json$" "")]
    (if (= "" s) "untitled" s)))

(defn- current-payload [app-state]
  (-> (idb/serialize app-state)
      (assoc :updated-at (now-iso))))

(defn save!
  "Trigger a browser download of the current project as a .json file.
   Uses an anchor + blob — no File System Access API. Respects an
   optional filename argument; defaults to `<current-basename>.json`."
  ([] (save! (str (project-basename @state/app-state) ".json")))
  ([filename]
   (let [payload (current-payload @state/app-state)
         edn     (pr-str payload)
         blob    (js/Blob. #js [edn] #js {:type "application/json"})
         url     (js/URL.createObjectURL blob)
         ^js a   (js/document.createElement "a")]
     (set! (.-href a) url)
     (set! (.-download a) filename)
     (.appendChild js/document.body a)
     (.click a)
     (.removeChild js/document.body a)
     (js/URL.revokeObjectURL url)
     (swap! state/app-state assoc-in [:project-file :name] filename)
     (state/mark-saved!))))

(defn- apply-loaded-project! [parsed filename]
  (swap! state/app-state
         (fn [s]
           (-> s
               (assoc    :document  (:document parsed))
               (assoc    :theme     (or (:theme parsed) (:theme s)))
               (assoc    :selection [])
               (assoc-in [:history :past]   [])
               (assoc-in [:history :future] [])
               (assoc    :dirty?    false)
               (assoc-in [:project-file :name]
                         (or filename "untitled.json")))))
  ;; The swap above fires the autosave watcher synchronously, arming
  ;; a 750ms timer. Cancel it so the immediately-following
  ;; `clear-autosave!` isn't re-clobbered by a stale `save-now!`.
  (idb/cancel-autosave-timer!))

(defn validate-project
  "Pure: check a deserialized project payload against
   `::spec/project-file` AND scan its document for XSS payloads.
   Returns nil when the payload conforms; otherwise a map
   `{:kind :spec | :unsafe :detail …}` describing why the file was
   refused. Used by `open!` to refuse malformed or malicious files."
  [parsed]
  (when parsed
    (or (when-let [explain (s/explain-data ::spec/project-file parsed)]
          {:kind :spec :detail explain})
        (let [findings (sanitize/unsafe-findings (:document parsed))]
          (when (seq findings)
            {:kind :unsafe :detail findings})))))

(defn classify-payload
  "Pure: turn a deserialized payload into a load-decision map. One of:
     {:status :ok}                       — passes spec + sanitiser
     {:status :unparseable}               — `parsed` is nil (deserialize failed)
     {:status :unsafe :findings findings} — XSS scanner flagged the document
     {:status :invalid :explain explain}  — spec validation failed
   The four cases are exclusive and exhaustive — `open!` `case`-dispatches
   on `:status` and never hits a default."
  [parsed]
  (cond
    (nil? parsed)
    {:status :unparseable}

    :else
    (let [v (validate-project parsed)]
      (case (:kind v)
        :spec   {:status :invalid :explain (:detail v)}
        :unsafe {:status :unsafe  :findings (:detail v)}
        nil     {:status :ok}))))

(defn- handle-loaded-file!
  "Effectful: classify a freshly-read project file's text and either
   apply it to app-state or surface a user-visible error. Resolves the
   open-promise with true on success, false otherwise."
  [raw fname resolve]
  (let [parsed (idb/deserialize raw)
        result (classify-payload parsed)]
    (case (:status result)
      :unparseable
      (do (js/window.alert
           "Could not load: not a valid Bareforge project file.")
          (resolve false))

      :unsafe
      (do (js/console.error
           "Project file refused: unsafe content"
           (clj->js (:findings result)))
          (js/window.alert
           (str "Could not load: project file contains "
                "potentially unsafe content "
                "(script tags, event handlers, or "
                "javascript: URLs)."))
          (resolve false))

      :invalid
      (do (js/console.error
           "Project file failed spec validation"
           (clj->js (:explain result)))
          (js/window.alert
           "Could not load: project file failed validation.")
          (resolve false))

      :ok
      ;; Defence-in-depth — even after the strict scanner clears the
      ;; doc, run it through the soft sanitiser so any near-miss
      ;; payload is neutralised.
      (let [safe-parsed (update parsed :document sanitize/sanitize-doc)]
        (apply-loaded-project! safe-parsed fname)
        (idb/clear-autosave!)
        (resolve true)))))

(defn- read-selected-file!
  "Effectful: enforce the size cap then wire up a FileReader to deliver
   the file's text (or a read-error alert) to `handle-loaded-file!`."
  [^js file resolve]
  (cond
    (> (.-size file) max-file-bytes)
    (do (js/window.alert
         (str "Could not load: file is too large "
              "(over 5 MB). Refusing to parse."))
        (resolve false))

    :else
    (let [reader (js/FileReader.)
          fname  (.-name file)]
      (set! (.-onload reader)
            (fn [_] (handle-loaded-file! (.-result reader) fname resolve)))
      (set! (.-onerror reader)
            (fn [_]
              (js/window.alert "Could not read file.")
              (resolve false)))
      (.readAsText reader file))))

(defn open!
  "Trigger a file input, read the selected file, parse it as a
   Bareforge project, and swap it into app-state. On success, the
   autosave is cleared so a stale entry can't clobber the just-opened
   project on the next reload. Returns a JS Promise that resolves to
   true on success and false on cancel / parse failure."
  []
  (js/Promise.
   (fn [resolve _reject]
     (let [^js input (js/document.createElement "input")]
       (set! (.-type input) "file")
       (set! (.-accept input) ".json,application/json")
       (.addEventListener input "change"
                          (fn [^js e]
                            (let [files (.. e -target -files)]
                              (if (and files (pos? (.-length files)))
                                (read-selected-file! (.item files 0) resolve)
                                (resolve false)))))
       (.click input)))))

(defn new!
  "Reset the document to an empty state (preserving the current
   canvas theme so the user's preset choices are not surprising),
   clear selection, history, and the autosave entry."
  []
  (let [theme (:theme @state/app-state)]
    (reset! state/app-state
            (-> (state/initial-state)
                (assoc :theme theme))))
  (idb/clear-autosave!))

(defn apply-autosave!
  "Install a parsed autosave `parsed` payload into `state/app-state`
   after the same load-boundary checks the file-open path runs.
   Returns true on success; false (with a console error naming the
   findings) when the payload fails spec or sanitiser checks. Unlike
   `apply-loaded-project!`, this leaves `:selection`, `:history`,
   `:dirty?`, and `:project-file` alone — the autosave restore runs
   on a fresh page-load, so there is no other state to clear."
  [parsed]
  (let [classification (classify-payload parsed)]
    (case (:status classification)
      :ok
      (let [safe (update parsed :document sanitize/sanitize-doc)]
        (swap! state/app-state
               (fn [s]
                 (-> s
                     (assoc :document (:document safe))
                     (assoc :theme    (or (:theme safe) (:theme s)))
                     (assoc :dirty?   false))))
        true)

      ;; :unparseable / :invalid / :unsafe all collapse to "refuse and
      ;; warn" — the page-load path has no UI surface to nag the user
      ;; with, but a console error names the offending paths so a dev
      ;; can recover by clearing IDB or opening the original .json.
      (do (js/console.error "Autosave restore refused" (clj->js classification))
          false))))
