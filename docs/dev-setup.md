# Developer setup

First-time contributor walkthrough. After a clean clone, you should reach a green local build in under five minutes (longer on the very first run while shadow-cljs caches warm up).

## Toolchain

Bareforge expects:

- **Java 21** (Temurin LTS)
- **Node 20** (LTS) plus `npm`
- **Clojure CLI** 1.12+ — `clj` / `clojure` on PATH
- **clj-kondo** and **cljfmt** on PATH

A `.tool-versions` file at the repo root pins exact versions. If you use [`mise`](https://mise.jdx.dev) or [`asdf`](https://asdf-vm.com), `mise install` (or `asdf install`) picks them up automatically. Otherwise install the toolchain by hand and verify:

```bash
java -version          # 21.x
node --version         # v20.x
clojure --version      # 1.12+
clj-kondo --version
cljfmt --version
```

clj-kondo and cljfmt installation: see [clj-kondo install docs](https://github.com/clj-kondo/clj-kondo/blob/master/doc/install.md) and [cljfmt releases](https://github.com/weavejester/cljfmt/releases). Both are also available via Homebrew (`brew install borkdude/brew/clj-kondo` / `brew install weavejester/cljfmt/cljfmt`).

The same versions are pinned in [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) — local pass implies CI pass barring infra issues.

## First build

```bash
git clone git@github.com:avanelsas/bareforge.git
cd bareforge
npm install
./scripts/check.sh
```

`scripts/check.sh` runs the four PR-readiness gates in CI order and exits non-zero on the first failure. Expect ~25–30 seconds on a warm cache. A successful run ends with:

```
All four PR-readiness gates green in Ns.
```

## Daily dev loop

```bash
npx shadow-cljs watch app
```

- Canvas at <http://localhost:8765>
- nREPL on `localhost:7888` — connect from your editor as CLJS, not JVM Clojure. Step-by-step in [`docs/repl.md`](./repl.md).
- Source changes hot-reload; the REPL connection survives reloads.

## Before opening a PR

1. Run `./scripts/check.sh`. All four gates must be green.
2. Push the branch (`git push -u origin feature/<name>`).
3. Summarize the change and gate results in chat.
4. **Wait for explicit maintainer approval** before opening the PR. CLAUDE.md treats unsolicited PR creation as the most-violated rule. Plan approval / auto-mode / "tests pass" do not count.

## Common pitfalls

- **Closure Advanced renames** — `npx shadow-cljs release app` is the only gate that catches them. Run the full `check.sh`, not just `compile test`. The CLJS dev build and the release build can disagree on whether your code works.
- **BareDOM version lockstep** — bumping BareDOM means editing both `deps.edn` and `src/bareforge/meta/versions.cljs`. The mismatch test in `test/bareforge/meta/versions_test.cljs` fails loudly if either side moves alone.
- **`No JS runtime` from the REPL** — open <http://localhost:8765> in a browser; CLJS evaluates *in* the browser. Full troubleshooting in [`docs/repl.md`](./repl.md#troubleshooting).
- **`watch for build not running {:build-id :frontend}`** — Bareforge's build is `:app`, not `:frontend`. See the same troubleshooting section.

## Where things live

- [`docs/architecture.md`](./architecture.md) — pure / effectful zone boundary, document model, rendering pipeline, project layout.
- [`docs/repl.md`](./repl.md) — editor-connected REPL setup and troubleshooting.
- [`docs/recipes.md`](./recipes.md) — end-to-end walkthrough building the demo store.
- [`docs/adding-components.md`](./adding-components.md) — onboarding a new BareDOM component into the palette.
- [`docs/plugins.md`](./plugins.md) — writing an export plugin.
- [`docs/export-cljs-spec.md`](./export-cljs-spec.md) — the 19 numbered codegen rules for the CLJS export.
- [`CLAUDE.md`](../CLAUDE.md) — internal maintainer rules: one atom, the four PR gates, Closure Advanced safety, Hickey-style design lens.
