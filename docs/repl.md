# REPL-driven development

Bareforge ships an editor-connected nREPL out of the box. Driving the
running app from a REPL is the recommended dev loop: there is exactly one
atom (`bareforge.state/app-state`) and exactly one chokepoint for mutation
(`bareforge.state/commit!`), so the surface for inspecting and probing live
state is small and predictable.

## TL;DR — the four invariants

A working session always satisfies these four. If something breaks, the
fault is one of them, in this order:

1. **Watch is running.** A terminal somewhere shows `npx shadow-cljs watch
   app` with `[:app] Build completed.`, not idle and not Ctrl-C'd.
2. **A browser tab is open on <http://localhost:8765>** and finished
   loading. CLJS evaluates *in the browser* — without a tab, there is no
   runtime.
3. **The build you attach to is `:app`.** Not `:frontend` (a popular
   convention from other shadow-cljs projects, and a default in some
   editor templates), not `:test`. Bareforge defines `:app`, `:test`, and
   `:export-cljs`; only `:app` has a browser runtime.
4. **The REPL you evaluate in is CLJS, not JVM Clojure.** A buffer name
   ending in `(clj)` or a prompt of `shadow.user>` means JVM. CLJS prompt
   is `cljs.user>` (or the namespace you switched to).

The single smoke test that verifies (2)–(4) at once:

```clojure
(exists? js/window)   ; true → CLJS, browser-attached. Anything else → broken.
```

## Step 1 — start the server

```bash
npx shadow-cljs watch app
```

Wait for these two log lines:

```
shadow-cljs - nREPL server started on port 7888
[:app] Build completed.
```

If the port isn't `7888`, the `:nrepl {:port 7888}` block in
`shadow-cljs.edn` isn't being read — stop and check. If `[:app] Build
completed.` never appears, fix the compile error first; the REPL has
nothing to attach to.

## Step 2 — open the app in a browser

<http://localhost:8765>. Wait for the canvas to render. Leave the tab
open for the entire REPL session.

## Step 3 — connect your editor *as CLJS*

This is the single most common stumble: connecting as JVM Clojure when
you meant CLJS. Use the CLJS-specific command for your editor.

### Calva (VS Code)

`Cmd-Shift-P` → **`Calva: Connect to a Running REPL Server in the
project`** → **shadow-cljs** → pick build **`:app`**.

The status bar should show `cljs:app`. If it shows `clj:app`, disconnect
and reconnect — Calva opened a JVM REPL instead.

### CIDER (Emacs)

Use `cider-connect-cljs`, **not** `cider-connect` or `cider-connect-clj`:

```
M-x cider-connect-cljs
  Host:       localhost
  Port:       7888
  REPL type:  shadow
  Build:      app           ← no leading colon in CIDER's prompt
```

The buffer name should end in `(cljs)`. If it ends in `(clj)`, you're in
a JVM REPL — quit (`C-c C-q`) and re-run `cider-connect-cljs`.

### Cursive (IntelliJ)

Cursive doesn't have a one-shot CLJS connect. Two-step:

1. Create a **"Clojure REPL — Remote"** run config: `localhost:7888`.
   Run it. You're now in JVM Clojure (`shadow.user>`).
2. Upgrade in place:

   ```clojure
   (shadow.cljs.devtools.api/repl :app)
   ```

   Prompt should switch to `cljs.user>`. To go back to JVM:
   `:cljs/quit`.

If you see `Unable to resolve symbol: shadow.cljs.devtools.api`, the
`:nrepl` alias didn't load — see Troubleshooting below.

## Step 4 — verify you're attached

Run the smoke test before anything else:

```clojure
(exists? js/window)
;; => true
```

If it returns `true`, you're CLJS-attached and ready. If it errors with
`Unable to resolve symbol: js`, you're in JVM — go back to Step 3.

Then:

```clojure
(shadow.cljs.devtools.api/get-runtimes-for-build :app)
;; => one entry per browser tab on :8765
```

Empty list → no browser runtime is registered. Reload <http://localhost:8765>.

## First session

```clojure
(in-ns 'bareforge.state)

;; Inspect the current document.
@app-state
(get-in @app-state [:document :nodes])
(:selection @app-state)

;; Probe an op without committing — pure, returns a new document value.
(require '[bareforge.doc.ops :as ops])
(def doc (:document @app-state))
;; Replace `some-op` with any real op from bareforge.doc.ops.

;; Commit through the same path the UI uses. History, autosave, and the
;; reconciler all fire as if a user clicked.
(commit! (ops/some-op doc ,,,))
```

The canvas should reflect the change immediately. If it doesn't, check
the browser console — your op may have produced an invalid document.

## Conventions

- **Don't `swap!` `app-state` directly** to mutate `:document`. Go through
  `bareforge.doc.ops/*` (pure) then `bareforge.state/commit!` (effectful)
  — the same rule the UI follows. Direct `swap!` skips history and
  watchers and leaves the canvas out of sync.
- **Non-document UI nudges** can use `bareforge.state/assoc-ui!`. These
  skip history by design.
- **Reload on save still works.** The REPL is for probing and one-off
  experiments; source files are the source of truth.

## Troubleshooting

### `watch for build not running {:build-id :frontend}`

You attached to a build that doesn't exist. Bareforge uses `:app`. Run
`(shadow.cljs.devtools.api/repl :app)`. In Calva / CIDER, reconnect and
pick `:app` in the build prompt.

### `Unable to resolve symbol: app-state` (or any CLJS symbol)

You're in a JVM Clojure REPL. CLJS namespaces aren't loaded there. Run
`(exists? js/window)` to confirm — it errors in JVM, returns `true` in
CLJS. Either upgrade in place with `(shadow.cljs.devtools.api/repl :app)`
or reconnect with the editor's CLJS-specific command (Step 3).

### `No available JS runtime`

You're in CLJS, but no browser is attached. Open <http://localhost:8765>
in a tab and let it finish loading, then re-evaluate. Confirm with
`(shadow.cljs.devtools.api/get-runtimes-for-build :app)` — empty list
means no runtime registered.

### `Unable to resolve symbol: shadow.cljs.devtools.api`

The `:nrepl` alias didn't make it onto the classpath. Verify:

- `shadow-cljs.edn` contains `:deps {:aliases [:nrepl]}` (not
  `:deps true`).
- `deps.edn` has the `:nrepl` alias pulling `nrepl/nrepl` and
  `cider/cider-nrepl`.
- On first run, the very first lines of `npx shadow-cljs watch app`
  output should show `Downloading: cider/cider-nrepl/...`. If absent on
  a clean cache (`rm -rf .cpcache .shadow-cljs`), the alias isn't being
  applied.

### REPL hangs forever on a simple form

The browser tab crashed or was closed. Reload <http://localhost:8765>,
then the form should return.
