# Adding a new BareDOM component

When BareDOM ships a new component, or you want to expose one Bareforge
didn't hand-curate, a scaffolder automates the file surgery. Two steps
for the common case.

## Recipe

1. **Bump the BareDOM version** in `deps.edn` if the component is in a
   newer release.

2. **Run the scaffolder**:

   ```bash
   clojure -X:scaffold :tag x-new-thing :category :layout
   ```

   Add `:dry-run true` to preview the edits without writing.

3. **Refresh the browser.** The component shows up in the palette with
   heuristic-typed properties in the inspector.

## What it touches

The script edits three files automatically:

- `src/bareforge/meta/public_api.cljs` — adds a `:require` line and an
  `api-map` entry in alphabetical position.
- `src/bareforge/meta/augment.cljs` — adds a `(def ^:private <tag> …)`
  block with one property per observed attribute, each typed by
  `bareforge.meta.heuristics/infer-kind` (known booleans become
  `:boolean`, URLs `:url`, ms durations `:number`, everything else
  `:string-short`).
- `src/bareforge/meta/categories.cljs` — registers the chosen category.

All three edits are idempotent — re-running is safe, already-present
entries are skipped with a note.

## Optional polish

Only needed when the defaults aren't enough:

- **Slots.** If the component is a container whose children should be
  droppable, add an entry to `src/bareforge/meta/slots.cljs`.
  Bareforge's `container?` predicate only returns true for explicitly
  registered tags, so an unregistered container behaves as a leaf.

- **Placement snap.** If the component should land at the top or bottom
  of the root on drop (navbars, sidebars, …), add a hint to
  `src/bareforge/meta/placement.cljs`.

- **Enum choices.** The scaffolder can't guess enum domains. For
  `variant`, `size`, `type`, etc., hand-upgrade the generated property
  to `{:kind :enum :choices [...] :default "..."}`.

- **CSS variables.** Add a `:css-vars` vector to expose themeable
  variables in the inspector's Component Variables section.

Even without any of these, the runtime fallback keeps the component
usable — the inspector shows a humanized label and typed fields for
every observed attribute.

## How it works

The scaffolder reads BareDOM source directly from the jar on the
classpath (`baredom/components/<tag>/model.cljs`), extracts the
`observed-attributes` vector, and resolves any symbol references
through the file's `(def attr-foo "foo")` defs to get real string
names. Text-based insertion with idempotency checks means re-running
is safe.

Source: `scripts/scaffold_component.clj`. The same
`heuristics.cljc` helpers power both the scaffolder and the runtime
fallback, so a scaffolded entry and an unaugmented tag report the same
kinds for the same attribute names.
