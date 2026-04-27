(ns bareforge.export.integrity
  "Subresource Integrity (SRI) consumer for BareDOM's published
   `dist/integrity.json` manifest. The manifest pairs every shipped
   `<tag>.js` filename with a `sha384-<base64>` hash; embedding those
   hashes via `<link rel=\"modulepreload\" integrity=…>` in the
   exported HTML's <head> binds the dynamic `import()` calls to the
   exact bytes BareDOM published. If jsDelivr (or any CDN we route
   through) ever serves a tampered module, the browser refuses to
   execute it.

   ## Design

   Pure where possible:

   - `parse-manifest`       string → validated map (or nil)
   - `manifest-url`         compose the CDN URL from base + version
   - `integrity-for`        manifest + filename → SRI value or nil
   - `modulepreload-block`  manifest + tags + base-url → HTML string

   Effectful entry point:

   - `fetch-manifest!`      Promise<manifest> | Promise<nil> on
                            network / parse failure

   ## Graceful fallback

   `fetch-manifest!` resolves to nil rather than rejecting when the
   manifest is missing or malformed — the export still ships, just
   without the SRI hardening. This means Bareforge can land the
   consumer code today: until BareDOM's next release publishes the
   manifest, every fetch 404s and exports behave exactly as they
   do now. The day BareDOM ships, every export gains SRI with
   no further code change.

   ## Manifest shape

   Pinned by mutual agreement with BareDOM's release pipeline.
   Validation accepts any object that has a string `version`, a
   string `algorithm` (sha384 today), and a string→string `files`
   map; unknown keys are tolerated so manifest evolution doesn't
   force a Bareforge bump.")

;; --- pure: URL composition ----------------------------------------------

(defn manifest-url
  "Compose the URL of BareDOM's integrity manifest at version `v` on
   the CDN rooted at `cdn-base` (e.g. `https://cdn.jsdelivr.net/npm/
   @vanelsas/baredom`)."
  [cdn-base v]
  (str cdn-base "@" v "/dist/integrity.json"))

;; --- pure: validation ---------------------------------------------------

(defn- valid-sri-value?
  "An SRI value is `<algorithm>-<base64>`; we accept any algorithm
   the manifest declares but verify the shape so a malformed entry
   doesn't make it into a `<link integrity=…>` attribute."
  [s]
  (boolean (and (string? s) (re-matches #"[a-z0-9]+-[A-Za-z0-9+/=]+" s))))

(defn parse-manifest
  "Parse the JSON text of an integrity manifest into a CLJS map.
   Returns nil for anything that fails to parse, doesn't carry the
   minimum required keys, or has an unusable `:files` map. Pure —
   no fetch, suitable for unit tests.

   Top-level keys are keywordised (`:version`, `:algorithm`,
   `:files`); `:files` keeps string keys (filenames aren't natural
   keywords)."
  [s]
  (try
    (let [obj (some-> s js/JSON.parse js->clj)]
      (when (map? obj)
        (let [version   (get obj "version")
              algorithm (get obj "algorithm")
              files     (get obj "files")]
          (when (and (string? version)
                     (string? algorithm)
                     (map? files)
                     (every? string? (keys files))
                     (every? valid-sri-value? (vals files)))
            {:version      version
             :algorithm    algorithm
             :generated-at (get obj "generated-at")
             :files        files}))))
    (catch :default _ nil)))

;; --- pure: lookup -------------------------------------------------------

(defn integrity-for
  "Return the SRI value for `filename` from `manifest`, or nil if
   the manifest is missing the entry. Tolerates a nil manifest so
   callers can write straight-line `(when-let [v (integrity-for …)]
   …)`."
  [manifest filename]
  (when manifest
    (get-in manifest [:files filename])))

;; --- pure: HTML emission ------------------------------------------------

(defn- preload-link
  "Render one `<link rel=modulepreload>` entry with an integrity
   binding. `crossorigin` is required for SRI on cross-origin
   resources (jsDelivr serves `Access-Control-Allow-Origin: *`, so
   it works)."
  [base-url filename sri]
  (str "  <link rel=\"modulepreload\""
       " href=\"" base-url filename "\""
       " integrity=\"" sri "\""
       " crossorigin=\"anonymous\">\n"))

(defn modulepreload-block
  "Render the `<head>`-block of `<link rel=modulepreload integrity=…>`
   entries for every tag in `tags` whose filename appears in the
   manifest. Tags missing from the manifest are skipped silently —
   defence-in-depth against a partial manifest. Returns the empty
   string if `manifest` is nil or `tags` is empty, so the caller can
   inline this unconditionally."
  [manifest tags base-url]
  (if (or (nil? manifest) (empty? tags))
    ""
    (apply str
      (for [tag (sort tags)
            :let [filename (str tag ".js")
                  sri      (integrity-for manifest filename)]
            :when sri]
        (preload-link base-url filename sri)))))

;; --- effectful: fetch ---------------------------------------------------

(defn fetch-manifest!
  "Fetch and parse BareDOM's integrity manifest at the given URL.
   Returns a JS Promise that resolves to a parsed manifest map or
   nil — never rejects. Network errors, non-2xx responses, and
   parse failures all collapse to nil so the export pipeline can
   choose the no-SRI fallback without try/catch."
  [url]
  (-> (js/fetch url)
      (.then (fn [^js resp]
               (if (.-ok resp)
                 (.text resp)
                 (throw (ex-info (str "manifest fetch failed: HTTP "
                                      (.-status resp))
                                 {:status (.-status resp)})))))
      (.then parse-manifest)
      (.catch (fn [err]
                (js/console.warn
                  "Integrity manifest unavailable; export will ship without SRI."
                  err)
                nil))))
