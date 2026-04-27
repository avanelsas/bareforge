(ns bareforge.export.plugin
  "Contract for Bareforge export plugins.

   An export plugin is an in-tree namespace (v1 scope — later
   phases may open to out-of-tree Clojars libs) that converts a
   Bareforge document into files the user can download. Each
   plugin contributes:

   - A **manifest** map describing how it appears in the File menu.
   - A **download! fn** that triggers a browser download of the
     generated artefact.
   - (Later phases) A pure `(generate model opts) → {file-path →
     content}` entry point that the download! fn wraps.

   Third-party contributors add a plugin by creating a subdir
   under `src/bareforge/export/<name>/`, implementing
   `plugin.cljs` to the shape below, and registering it in
   `bareforge.export.registry`. Nothing else is plumbed by hand —
   the File menu, the download flow, and the label styling are
   driven from the manifest.

   This ns is mostly documentation; the enforced shape lives in
   `registry/valid-plugin?` (Phase B) and will graduate to a
   `clojure.spec.alpha` boundary once the contract is stable
   past v1.

   ## Manifest shape

   ```clojure
   {:id           :my-target          ; keyword, unique across plugins
    :label        \"Export My Target\" ; File-menu label (users see this)
    :extension    \"zip\"              ; artefact extension, no leading dot
    :interactive? true                 ; bindings / events actually work?
                                       ;   affects the File-menu label
                                       ;   suffix and docs
    :description  \"…\"                ; short blurb for docs / tooltips
    :order        40                   ; File-menu sort key; lower = higher
    :download!    (fn [filename] …)}   ; effectful; takes the base filename
                                       ;   with extension, triggers download
   ```

   ## Semantics of `:interactive?`

   `false` means the plugin emits markup only — `:events` and
   `:bindings` from the document are NOT wired in the output. HTML
   and bundle exports are the canonical examples. The File menu
   annotates the label with \"(static snapshot)\" for clarity.

   `true` means the output supports reactive state updates — a
   minimal runtime is shipped alongside the markup. The CLJS
   project export is the canonical example; the vanilla JS
   plugin lands next.

   No code reads this flag behaviourally yet; it's documentation +
   label styling. A later phase may use it to gate UI hints or
   auto-generate a 'pick an export' dialog for new users."
  (:require [clojure.string :as str]))

(def valid-manifest-keys
  "Keys the registry requires on every plugin manifest. Presence
   is enforced at registry-assembly time (see registry/assemble)."
  #{:id :label :extension :interactive? :description :order :download!})

;; --- shared zip-path safety ---------------------------------------------

(defn safe-zip-path?
  "True when `path` is a relative, non-empty string that doesn't
   escape the archive root via `..` segments, an absolute prefix,
   a Windows drive prefix, or NUL bytes. Plugins compose paths from
   group ns-names (already normalised by `model/name->ns-segment`)
   and a hard-coded `app-ns` default — so zip-slip from a malicious
   doc is already implausible. This predicate is the boundary check
   that pins the invariant: any plugin that goes off-script gets
   refused at the JSZip emit site instead of writing whatever the
   archive's extractor will follow.

   Refused: `\"\"`, `\"/etc/x\"`, `\"../escape.txt\"`,
   `\"a/../b\"`, `\"C:\\\\x\"`, `\"a\\u0000b\"`."
  [path]
  (and (string? path)
       (not= "" path)
       (not (str/starts-with? path "/"))
       (not (re-find #"\\" path))
       (not (re-find #"^[A-Za-z]:" path))
       (not (re-find #"\u0000" path))
       (not (some #(= ".." %) (str/split path #"/")))))

(defn assert-safe-zip-path!
  "Throw if `path` would escape the archive root. Plugins call this
   immediately before `(.file zip path content)` so a regression in
   path construction surfaces as a clean error instead of a
   silently-overwritten file on the user's filesystem."
  [path]
  (when-not (safe-zip-path? path)
    (throw (ex-info (str "Refusing to emit zip entry at unsafe path "
                         (pr-str path)
                         " — plugins must compose paths that stay "
                         "inside the archive root.")
                    {:error :unsafe-zip-path :path path}))))
