# Contributing to Bareforge

Thanks for your interest in contributing! Bareforge is a visual landing-page builder authored in ClojureScript on top of [BareDOM](https://github.com/avanelsas/baredom). Contributions of all sizes are welcome — bug reports, new export plugins, UI improvements, docs fixes, and ideas.

## Workflow

Two steps:

1. **Open an issue first.** Describe the bug, idea, or feature you'd like to work on. This lets me discuss the approach with you up front, avoid duplicated effort, and make sure the change fits the project's design principles.
2. **Follow up with a Pull Request.** Reference the issue number in the PR description, and try to keep each PR focused on one thing. Smaller PRs get reviewed faster.

## Please don't force-push on open PRs

Once your PR is open, please **avoid `git push --force` (or `--force-with-lease`) on the PR branch**. Add new commits on top instead.

Why it matters:

- Reviewers can see exactly what changed between review rounds. GitHub's "changes since last review" view only works when the commit history is preserved.
- Incremental commits make it much easier for others to jump in, suggest changes, or pick up where you left off.

Don't worry about a "messy" history — squashing happens at merge time, so your PR will land as a clean commit on `main` regardless of how many intermediate commits you pushed.

## Development setup

- **Run the editor**: `npx shadow-cljs watch app` (http://localhost:8765).
- **Run the tests**: `npx shadow-cljs compile test` (autoruns in Node).
- **Release build**: `npx shadow-cljs release app` (must compile clean, zero warnings — see test gates below).
- **Lint**: `clj-kondo --lint src test scripts`.
- **Format**: `cljfmt check` (or `cljfmt fix` to apply).

CI runs all five on every push and PR — see [`.github/workflows/ci.yml`](./.github/workflows/ci.yml).

Two scaffolders speed up common tasks:

- **New export plugin**: `clojure -X:new-export :id :my-target :label "My Target"` stamps a `src/bareforge/export/<id>/plugin.cljs` skeleton.
- **New BareDOM component**: `clojure -X:scaffold :tag x-thing :category :layout` adds a tag to the palette + auto-fills augment metadata.

## Architecture pointers

- [`docs/architecture.md`](./docs/architecture.md) — orientation for new contributors: the pure/effectful split, the document model (groups, fields, records, bindings, events), the export plugin model, the rendering pipeline.
- [`CLAUDE.md`](./CLAUDE.md) — the day-to-day rule book: Closure Advanced safety, the one-state-atom rule, the runtime-dependency policy (BareDOM + JSZip only), the spec usage guidelines.
- [`docs/plugins.md`](./docs/plugins.md) — export plugin authoring: manifest contract, the export model API, testing recipe, hello-world walkthrough. The four built-in plugins under `src/bareforge/export/<name>/` are good worked examples.
- Adding a BareDOM component to the palette — README has the two-step recipe (version bump + scaffolder).
- Tests run in Node by default. DOM-touching changes can't be exercised there; include a manual verification recipe in your PR description.

## Test gates

Before opening a PR, please confirm:

- `npx shadow-cljs release app` exits with **zero warnings** — Closure Advanced renaming bites late, and the release-mode signal is the only one that catches it.
- `npx shadow-cljs compile test` ends with **`0 failures, 0 errors`**.
- `clj-kondo` and `cljfmt check` are green.

Bug fixes ship with a regression test in the same commit — the test pins the bug, the implementation fixes it; reviewers see both halves.

## Conduct

Be kind, be constructive, and assume good intent. I want Bareforge to be a friendly place to learn and build.
