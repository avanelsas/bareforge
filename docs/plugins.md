# Writing a Bareforge export plugin

Bareforge ships with four export plugins out of the box (`html`,
`bundle`, `cljs`, `vanilla-js`). Every plugin is a small
ClojureScript namespace that consumes a target-agnostic **export
model** and emits the files users download. Adding a fifth
exporter — React, Svelte, Vue, a markdown-ish hand-off format,
whatever — is a single-folder, single-registry-edit affair.

This doc walks through the contract, the model, and a minimal
end-to-end example.

- [The architecture in one paragraph](#the-architecture-in-one-paragraph)
- [Plugin anatomy](#plugin-anatomy)
- [The export model](#the-export-model)
- [Hello-world plugin](#hello-world-plugin)
- [Testing recipe](#testing-recipe)
- [Stability + deprecation pledge](#stability--deprecation-pledge)
- [FAQ](#faq)

## The architecture in one paragraph

Bareforge's document is a tree of nodes the user edits in the
Inspector. Three concerns sit on top of it: **semantic
interpretation** (which groups are templates, what a
`:filter-by` targets, how a collection field relates to its
record shape), **target codegen** (emit CLJS, emit JS, emit
whatever-comes-next), and **UI wiring** (File menu, download
flow). Phase A of the plugin refactor extracted the first
concern into `bareforge.export.model` as a stable boundary.
Phase B put a static plugin registry in
`bareforge.export.registry`. Third-party exporters sit entirely
inside the second concern, reading the model and returning
files-map; the UI wiring is automatic.

## Plugin anatomy

A plugin is a directory under `src/bareforge/export/<name>/`
with at least one file — `plugin.cljs` — that exports a
manifest and (for downloadable plugins) a `download!` fn. The
minimum shape:

```clojure
(ns bareforge.export.<name>.plugin
  (:require [bareforge.export.model :as em]))

(defn generate
  "Pure: take the document and options, return a map of
   {relative-file-path -> file-content-string}."
  [doc opts]
  …)

(def manifest
  {:id           :<name>                 ; unique keyword identifier
   :label        "Export <Name>"          ; File-menu label users see
   :extension    "zip"                    ; artefact extension, no dot
   :interactive? true                     ; :events / :bindings wired?
   :description  "short description"
   :order        50                       ; File-menu sort key
   :download!    (fn [filename]
                   ;; effectful: package the `generate` output into
                   ;; a download via JSZip or similar.
                   …)})
```

Register it in `src/bareforge/export/registry.cljs` by
requiring the plugin ns and appending its manifest to the
`plugins` vector. That's the only cross-cutting edit — the File
menu, label formatting, and download flow are driven from the
manifest.

**Manifest keys** are enforced by
`bareforge.export.plugin/valid-manifest-keys`. A plugin whose
manifest is missing any required key is silently dropped by
`registry/validated-plugins` — the File menu won't show it, and
no error is raised at compile time. When you add a plugin,
run the test suite; `registry_test.cljs` asserts every
registered plugin has a complete manifest.

**NYI is explicit.** If a plugin only supports a subset of the
features a document might use, throw
`(ex-info "<message naming the feature>" {:error :nyi})` from
`generate`. The download layer surfaces the message to the user as a
dialog, so unsupported features fail loudly rather than emitting
broken output. See
`bareforge.export.vanilla-js.codegen/assert-supported!` for the
canonical pattern, and the testing recipe below for how to cover the
gate in a unit test.

## The export model

`bareforge.export.model/detect-groups` is the entry point most
plugins start with. It walks the document and returns:

```clojure
{:groups     [{:id           "8"            ; doc-node id
                :tag          "x-card"       ; BareDOM tag
                :ns-name      "product"      ; derived from :name
                :parent       "root"
                :instance-ids ["8"]} …]
 :root-order [{:id "12" :group? true :ns-name "product-feed"}
              {:id "n_23" :group? false} …]}
```

Other helpers layer on top. The full public API of
`bareforge.export.model`:

| Helper | Purpose |
|---|---|
| `computed?` / `collection-field?` | Field-def predicates |
| `template-group?` / `stateful-group?` | Classify a detected group |
| `detect-groups` | Walk the tree, return the above structure |
| `collect-node-data` / `collect-group-data` | Fields / actions / bindings / events per node or per group |
| `field-owner-index` | `{field-kw → owning-group-ns-name}` |
| `explicit-field-owners` | Per-subtree user-picked ownership |
| `name->ns-name-map` | `{user-name → ns-name}` |
| `collect-read-bindings` | Every `:read` / `:read-write` field a subtree touches |
| `collect-trigger-payload-fields` | Fields referenced by triggers' `:payload` vectors |
| `collect-trigger-action-refs` | Every action-ref dispatched in a subtree |
| `find-sub-groups` | Direct sub-groups of a node |
| `stateful-host-for-template` | Which stateful group owns a template |
| `filter-by-of-group` | Which template a `:filter-by` computed targets |

Use these rather than re-walking `:root` yourself. They encode
subtleties — e.g. sub-group boundaries, template vs. stateful
distinction, explicit `:owner` vs. auto-resolved owner — that
every exporter has to respect for the emitted output to behave
identically across targets.

See `bareforge.export.cljs-project` and
`bareforge.export.vanilla-js.codegen` for worked examples of how
each helper composes into a target-specific walk.

## Hello-world plugin

The smallest plugin that produces something useful: a
markdown dump of every named group's fields.

```clojure
;; src/bareforge/export/markdown/plugin.cljs
(ns bareforge.export.markdown.plugin
  (:require [bareforge.export.model :as em]
            [bareforge.state :as state]
            [clojure.string :as str]))

(defn- field-line [fd]
  (str "- `" (name (:name fd)) "` "
       "(" (name (:type fd)) ")"
       (when (:default fd) (str " — default `" (pr-str (:default fd)) "`"))))

(defn- group-section [doc group]
  (let [data (em/collect-group-data doc (:instance-ids group) [group])]
    (str "## " (:ns-name group) "\n\n"
         (str/join "\n" (map field-line (:fields data)))
         "\n")))

(defn generate
  "Return a single markdown file summarising every named group."
  [doc _opts]
  (let [{:keys [groups]} (em/detect-groups doc)]
    {"groups.md"
     (str "# Bareforge document — group summary\n\n"
          (str/join "\n" (map #(group-section doc %) groups)))}))

(defn- download! [filename]
  (let [doc  (:document @state/app-state)
        md   (get (generate doc {}) "groups.md")
        blob (js/Blob. #js [md] #js {:type "text/markdown"})
        url  (js/URL.createObjectURL blob)
        ^js a (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) filename)
    (.appendChild js/document.body a)
    (.click a)
    (.removeChild js/document.body a)
    (js/URL.revokeObjectURL url)))

(def manifest
  {:id           :markdown
   :label        "Export groups summary (markdown)"
   :extension    "md"
   :interactive? false
   :description  "A flat markdown file listing every named group's fields."
   :order        60
   :download!    download!})
```

Register it:

```clojure
;; src/bareforge/export/registry.cljs
(:require …
          [bareforge.export.markdown.plugin :as markdown])

(def plugins
  (sort-by :order [html-plugin bundle-plugin cljs-plugin
                   vanilla-js/manifest
                   markdown/manifest]))
```

Restart `shadow-cljs watch app` and the File menu has a new
"Export groups summary (markdown)" entry. No toolbar changes,
no UI plumbing.

## Testing recipe

Every plugin should have a test namespace under
`test/bareforge/export/<name>_test.cljs`. The minimum:

1. **Manifest completeness** — verify the manifest has every
   key in `bareforge.export.plugin/valid-manifest-keys`. The
   existing `registry_test.cljs` covers this for built-in
   plugins; if your plugin is external, add it to your own
   suite.
2. **Generate against a synthetic doc** — build a minimal doc
   with `em/detect-groups`-discoverable groups and assert the
   shape of the emitted output (file presence, key strings
   inside each file).
3. **NYI gate** — if you only support a subset, explicitly
   throw `(ex-info "…" {:error :nyi})` and test that your
   plugin throws on a doc that uses unsupported features. See
   `test/bareforge/export/vanilla_js_test.cljs` for the
   pattern.
4. **Golden fixtures** (optional but valuable) — run
   `generate` against `test/fixtures/export/demo-store.json` or
   `demo-store-with-bindings.json` and diff against a checked-in
   golden. Easiest way to catch unintended output churn during
   refactors.

DOM / browser behaviour can't be validated in the node-test
build. Ship plugins with a manual verification recipe in the
plugin's README or commit message, covering the demo-store
fixtures if possible.

## Stability + deprecation pledge

The export model (`bareforge.export.model` public fns and their
return shapes) and the plugin contract
(`bareforge.export.plugin/valid-manifest-keys`, the `generate`
signature, the manifest shape) are the two surfaces plugin
authors depend on.

**Pre-1.0 (today):** both surfaces are considered unstable.
Breaking changes may land in any minor version. Plugin authors
should pin Bareforge to a specific commit or release tag and
update deliberately.

**Post-1.0 (future):** additive-only for the model shape and
manifest keys. New helpers may be added; existing ones keep
their signatures. Deprecations carry a one-minor-version
warning before removal. The plugin contract itself is
considered stable — `:id`, `:label`, `:extension`,
`:interactive?`, `:description`, `:order`, `:download!`
are the keys plugins will always see.

## FAQ

**Can my plugin live in its own repo / Clojars lib?** Not in v1.
Plugins are in-tree under `src/bareforge/export/<name>/` and
registered in `registry.cljs`. If there's external contributor
uptake, out-of-tree Clojars distribution becomes the logical
next step; the plugin contract is designed to port there with
minimal changes.

**What should `:interactive?` do?** Today it only affects the
File-menu label (appends "(static snapshot)" or "(interactive)"
suffix). Future versions may use it to gate UI hints or
auto-select a plugin based on user intent.

**Can my plugin contribute Inspector panels or options dialogs?**
Not in v1. Plugins are codegen-only. If you need per-export
options, expose them as `(generate doc opts)` parameters and
document them in the plugin's README; user-facing option UIs are
a potential v2 extension.

**Can I read the export model from outside a plugin (e.g. from a
test or a script)?** Yes — `bareforge.export.model` is a public
namespace and its helpers are pure. Call `em/detect-groups` on
any document to explore the shape interactively.

**Where does my plugin's runtime shim live?** If your target
needs runtime helpers (like the CLJS plugin's `framework.cljs` or
the vanilla-JS plugin's `runtime.js`), keep them inside your
plugin's subdir and slurp them into the emitted output via
`shadow.resource/inline`. This mirrors how core plugins do it
and keeps one source of truth.
