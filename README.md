# Bareforge

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="public/assets/bareforge_darkmode.png">
    <source media="(prefers-color-scheme: light)" srcset="public/assets/bareforge_lightmode.png">
    <img alt="Project Logo" src="public/assets/bareforge_lightmode.png" width="200">
  </picture>
</p>

A visual landing-page builder for [BareDOM](https://github.com/avanelsas/baredom).
This project offers a drag and drop interface to build a web component based static page and using bindings, records and events can export to a fully interactive, functional extensible codebase in either ClojureScript or JavaScript.

![CI](https://github.com/avanelsas/bareforge/actions/workflows/ci.yml/badge.svg)

Bareforge is built in ClojureScript on top of BareDOM's 90 stateless web
components. The editor chrome itself is made from BareDOM elements —
palette, inspector, toolbars, layers, modals — dogfooding the library
inside a real end-user application. The canvas uses a hand-written DOM
reconciler (no virtual DOM) to stay philosophically aligned with BareDOM.

## Why Bareforge?

Like most Clojure/ClojureScript developers starting out with UIs, I went through the common phases of using Reagent and Re-frame—which are great utilities in their own right. However, as my UIs became larger and more complex, bundle sizes increased, and I found myself spending too much time rebuilding generic, reusable components from scratch.

I started looking for a different approach and discovered Web Components. I built a few, but didn't have the spare time to develop a comprehensive set that could be used in any project. Then AI arrived. While experimenting with Claude Code, I realised that 1 + 1 could be 3. That is how BareDOM, my first open-source project, was born.

I then turned to another aspect of web development and thought about how I could make web components easier in use. I often found myself taking a Figma design and translating it into UI components and code.

I wondered if I could automate some of that and with that the idea for Bareforge was born. I wanted something that would not just deliver a static page. Instead it should allow exporting a complete and interactive CLJS or JS project that can be used for further development. It is still rough around the edges but I hope it brings you joy and usefulness when designing and developing web/landing pages.

## Features

- **Drag-drop canvas** with before / inside / after drop indicators
- **Inspector** with type-aware editors for every BareDOM component —
  enums as dropdowns, colors as pickers, booleans as switches, URLs,
  numbers, and a per-instance CSS-variable editor
- **Free-form positioning** for decoratives (absolute x/y/w/h) with an
  8-handle resize overlay and keyboard nudging (arrow keys, 10 px with
  Shift, coalesced into a single undo step)
- **Flow resize** handles on non-free elements (E / S / SE only, updating
  `:width` / `:height` as CSS length strings)
- **Component-aware snap** on drop — an `x-navbar` lands at the top of
  the root at full width; an `x-sidebar` does the same; easily extensible
  via `meta/placement.cljs`
- **Theme editor** with 8 built-in presets and full per-token overrides
- **Autosave** to IndexedDB (debounced) plus explicit project files
  (`.json`) via File Download / File Upload
- **Project files** are spec-validated on load — malformed payloads are
  refused rather than silently installed
- **Four export modes**:
  - **CDN export** (static snapshot) — one HTML file, loads BareDOM
    from jsDelivr at runtime (requires internet). Markup only; no
    reactive state.
  - **Bundle export** (static snapshot) — a `.zip` with the HTML +
    a local `vendor/baredom/` folder containing every module the
    document uses; serve from any static HTTP server offline.
    Markup only; same contract as CDN export.
  - **ClojureScript export** (interactive) — a full shadow-cljs
    project that ships a minimal re-frame subset plus the
    declarative data-binding layer. Buttons fire actions, fields
    update state, computed subs recompute. See the
    [Recipes](#recipes) section for how to build one end-to-end.
  - **Vanilla JavaScript export** (interactive) — a `.zip` with a
    tiny reactive store, a hand-written reconciler, and per-group
    view modules. Same feature parity as the ClojureScript export
    (template groups, collection fields, the seven computed
    operations, bindings, triggers, raw-HTML icons) — no
    framework dependency, plain DOM + ES modules.
- **Pluggable export system** — the four targets above are
  built-in plugins under `src/bareforge/export/<name>/`; adding a
  new one (React, Svelte, your in-house framework) is a manifest
  + a single `generate` fn. See [`docs/plugins.md`](./docs/plugins.md).
- **Undo / redo** with 100-step history, including coalesced keyboard
  nudges (hold arrow → one undo step)
- **Preview mode** toggle — drops the editor chrome interactions so you
  can click through the page as a user
- **Escape to deselect** + full keyboard shortcuts (Cmd-Z / Cmd-Shift-Z /
  Delete / arrow-key nudge)

## Fields & bindings

Give a container a **name** and it becomes a component group with its
own reactive state. Declare typed **fields** on the group (e.g.
`:cart-count` → number, default `0`); any attribute of any descendant
can then be **bound** to a field — a `:read` binding tracks it live
(the `:text` of an `x-badge` mirrors `:cart-count`), a `:write`
binding lets an event trigger update it, and event triggers pick an
operation from `:increment`, `:decrement`, `:toggle`, `:set`,
`:clear`. On export, fields become the group's `default-db`, reads
become `rf/reg-sub` + `rf/query`, and event triggers become
`rf/reg-event` + `rf/dispatch` — a minimal re-frame shape, no React,
no Reagent.

## Recipes

The [Fields & bindings](#fields--bindings) section above introduces the
primitives. This one is the practical follow-up: an end-to-end
walkthrough that builds a small but complete interactive app in the
Inspector, followed by a quick-reference for looking up individual
tasks later. By the end of the walkthrough you will have produced
something equivalent to
[`test/fixtures/export/demo-store-with-bindings.json`](test/fixtures/export/demo-store-with-bindings.json)
— a filterable product feed with an add-to-cart flow and a live
cart popover — and you can open that file in a second Bareforge
tab to diff your build against the reference.

> **Note — live commit, no Save button.** Every keystroke and click
> in the Inspector commits to the document immediately. There is no
> "Save" step; Cmd-Z undoes, the history depth is 100 steps, and
> changes autosave to IndexedDB as you work. Project files are
> produced explicitly via File → Save / Open.

### Before you start

- Open the editor: `npx shadow-cljs watch app`, navigate to
  <http://localhost:8765>.
- The Inspector lives in the right-hand rail. It only shows sections
  that are meaningful for the current selection — the panels appear
  and disappear as you give a container a name, add a field, etc.
- Three fixtures in `test/fixtures/export/` make useful reference
  checkpoints: `demo-store-blank.json` is the empty starting state,
  `demo-store.json` is the demo after the layout and fields exist
  but before bindings/triggers, and `demo-store-with-bindings.json`
  is the fully-wired target. Open any of them via File → Open.
- You will need a second browser tab open on the same editor URL if
  you want to diff your build against the reference fixture at the
  end.

### Walkthrough: build the demo store

Eleven steps. Each produces a visible change; if a step looks wrong,
stop and compare against the matching fragment of the reference
fixture before moving on. Tags that appear in the steps
(`x-card`, `x-grid`, `x-popover`, …) all come from BareDOM's
palette on the left.

#### 1. Create the `product` template group

Drag an `x-card` from the palette onto the canvas. Click it to
select. In the Inspector header, type `product` into the **name**
field — the card is now a named group with its own reactive state.

With the group named, a **Fields** section appears lower in the
Inspector. Use the "Add field" form at the bottom three times:

| Name    | Type    | Default |
|---------|---------|---------|
| `id`    | number  | `0`     |
| `title` | string  | `""`    |
| `price` | number  | `0`     |

> **Note.** Bareforge inserts a locked `::id` field automatically the
> first time you name a group; the `id` row above just sets its
> default. You cannot remove the locked field, and its row renders
> read-only.

<!-- screenshot: inspector with group name + fields section -->

#### 2. Create the `product-feed` stateful group

Drag an `x-grid` onto the canvas, select it, and name it
`product-feed`. Add one field:

- name `product-feed-items`, type **vector**, `of-group: product`.

When the type is `vector` a second dropdown appears labelled
"of group" — pick `product` from it. This has two effects: it marks
`product` as a template group, and it unlocks a seed-records table
right under the field row. Click "+ Add record" three times and
fill in:

```edn
{:id 1 :title "Widget" :price 9.99}
{:id 2 :title "Gadget" :price 8.75}
{:id 3 :title "Gizmo"  :price 2.53}
```

Each cell parses according to its field's declared type — number
cells coerce on blur, string cells accept anything.

#### 3. Add the `count-of` computed field

Still on `product-feed`, add another field:

- name `product-feed-item-count`, type **number**.
- Toggle **computed** on.
- Operation: `count-of`.
- Source field: `product-feed-items`.

> **Note — computed fields have no "default" input.** When you tick
> "computed", the default-value row is replaced by the operation
> pickers. If you expected a default box, it's intentional — a
> derived field has no stored value to seed.

#### 4. Add the search filter

Two more fields on `product-feed`:

- `product-search-term`, type **string**, default `""`.
- `visible-products`, type **vector**, `of-group: product`, then tick
  **computed** and pick:
  - Operation: `filter-by`.
  - Source field: `product-feed-items`.
  - Search field: `product-search-term`.
  - Match field: `title`.
  - Match kind stays at its one v1 option, "case-insensitive contains".

The resulting field-def shape, lifted from the reference fixture:

```edn
{:name :visible-products
 :type :vector
 :of-group "product"
 :computed {:operation :filter-by
            :source-field :product-feed-items
            :filter-spec {:search-field :product-search-term
                          :match-field :title
                          :match-kind :contains-ci}}}
```

At this checkpoint your doc is roughly equivalent to
`demo-store.json`: three products seeded, the computed count +
filter wired, nothing user-facing yet.

#### 5. Bind the search field and count display

Drop an `x-search-field` next to the product feed. In the Inspector's
Attributes section, find the `value` row and click the 🔗 icon next
to it. A grouped picker opens showing every field in every named
group — search for `product-search-term` and click it. The binding
shows as `↔ product-feed.product-search-term` with an × to unbind.

Do the same for a count display: drop an `x-typography` or an
`x-badge`, bind its `text` attribute to
`product-feed.product-feed-item-count` (read-only, since the target
is a computed field).

> **Note — binding direction is auto-picked.** Bareforge infers
> `:read`, `:write`, or `:read-write` from the target property's
> kind. Input-ish widgets (value on search field, checked on
> switch, text-area value) get `:read-write`; display props like
> `text` on a badge get `:read`. The binding row shows the chosen
> direction; you can't force it manually in v1.

<!-- screenshot: binding picker open on search-field value -->

#### 6. Point the `product` template at `visible-products`

Select the `product` group's root (the `x-card` you named in step 1).
Because `product-feed`'s `visible-products` is `:of-group "product"`,
a new section called **Rendered from** appears in the Inspector for
this group. In its source-field dropdown, pick
`product-feed / visible-products`.

> **Note — "Rendered from" lives on the template, not its host.**
> This is the most common miss. The dropdown lives on the group
> whose records are being rendered (here: `product`), not on the
> group that owns the collection field (here: `product-feed`).
> Select the right side of the relation before you hunt for the
> section.

At this point, previewing the canvas (or just moving focus off the
Inspector) shows the card rendered three times — once per seeded
product. Design-time template expansion kicks in as soon as the
source field is set.

#### 7. Create the `cart-item` template group

Drag another `x-grid` and name it `cart-item`. Set `columns` to
`1fr auto auto` and `gap` to `sm` via the Inspector's Attributes
section — the three columns will hold title / price / remove.
Add three fields:

| Name    | Type   | Default |
|---------|--------|---------|
| `id`    | number | `0`     |
| `title` | string | `""`    |
| `price` | number | `0`     |

Drop three children into the grid:

- An `x-typography` for the title — bind its `text` to
  `cart-item.title`.
- An `x-typography` for the price — bind its `text` to
  `cart-item.price`.
- An `x-button` (variant ghost, size sm) containing an `×` glyph —
  this is the remove button you'll wire in step 9.

#### 8. Create the `cart` group with its popover and actions

Drag an `x-container` into your page's header area (or into an
`x-navbar`'s `actions` slot if you dropped a navbar earlier). Name
it `cart`. Inside it, drop an `x-popover` — this is the BareDOM
component that shows the floating panel when the cart icon is
clicked. Set its `heading` to `Cart` and its `placement` to
`bottom-end`.

> **Important — set `portal` to `true`** on the x-popover so the
> panel z-orders correctly above page content. Bareforge's exported
> renderer handles portaled children correctly; see CLAUDE.md rule
> 19 for the story.

Inside the popover (default slot), drop the `cart-item` group you
built in step 7. Inside the popover's `trigger` slot, drop an
`x-icon` (the shopping-cart SVG) and an `x-badge` next to it.

Back on the `cart` group itself, add two fields:

- `cart-items`, type **vector**, `of-group: cart-item`, default `[]`.
- `cart-items-count`, type **number**, computed, `count-of`, source
  `cart-items`.

Now the **Actions** section appears beneath the Fields section (it
only materialises once a group has at least one field). Use its
"Add action" form twice:

| Name              | Operation  | Target field  |
|-------------------|------------|---------------|
| `add-to-cart`     | `add`      | `cart-items`  |
| `remove-from-cart`| `remove`   | `cart-items`  |

> **Note — actions gate on "named + has at least one field."** If
> you add an action before either condition is met the form accepts
> the input silently but nothing persists. Add a field first.

#### 9. Wire the triggers

Two event wirings make the cart go.

**Add to cart.** Select the Add-to-cart button you dropped inside the
`product` template (step 6). The Inspector's **Events** section now
lists `press` (because the button dispatches a BareDOM `press`
event). Click its action-picker (🔗). A grouped picker opens,
listing every action across every group; pick
`:app.cart.events/add-to-cart`. A hint line under the row shows
"receives: product record" — the implicit payload is the enclosing
template group's record. No payload configuration is needed for v1
recipes.

**Remove from cart.** Select the `×` button you dropped inside the
`cart-item` template (step 7). Same flow: Events → `press` →
`:app.cart.events/remove-from-cart`. The hint shows "receives:
cart-item record".

<!-- screenshot: action picker with cart events expanded -->

#### 10. Bind the cart badge

Select the `x-badge` in the popover's trigger slot (step 8). Bind its
`text` attribute to `cart.cart-items-count` — a read-binding, since
the target is a computed field. The badge now shows `0` (the cart
starts empty) and will update live as add/remove fire in the
exported project.

#### 11. Export to ClojureScript

File menu → **Export ClojureScript (interactive)**. You get a `.zip`
named after the current project. Unzip, then inside the exported
directory:

```bash
npm install
npx shadow-cljs watch app
```

Open the served URL. The app behaves as you'd expect — three
products visible, typing in the search field narrows the list in
real time, clicking **Add to cart** increments the badge and inserts
a row into the popover panel, clicking **×** on a row removes that
row and decrements the badge.

> **Note — two other export modes are deliberately static.** File →
> "Export HTML (static snapshot)" and "Export bundle (static
> snapshot)" emit markup only; they don't wire `:events` /
> `:bindings` / `:computed` fields. They are useful for
> PDF-like previews or sharing a visual-only copy. Use the
> ClojureScript export when the artefact needs to be interactive.

#### Verify your build

Open `test/fixtures/export/demo-store-with-bindings.json` via File →
Open in a second tab. Compare the two Inspector trees node by node —
same named groups, same field lists, same actions, same bindings,
same triggers. If you see drift, the walkthrough step that produced
that part of the tree is the one to revisit. For the exported
output, the `examples/demo-app/` directory in this repo is a
reference of what the emitted ClojureScript project should look
like.

### Quick reference

Ten one-liner recipes pointing back into the walkthrough. Use this
as a return-visit index after the first read-through.

- **Name a group.** Select the container, type a name in the
  Inspector header. The locked `::id` field is auto-inserted.
  See walkthrough step 1.
- **Add a scalar field.** In the Fields section's "Add field" form,
  pick a type (string/number/boolean/keyword), set a default, click
  Add. See step 1.
- **Add a computed field.** Add a field, tick "computed", pick an
  operation from `count-of / sum-of / empty-of / negation / any-of /
  join-on / filter-by`, pick source field(s). No default input
  appears. See steps 3 and 4.
- **Add a collection field.** Add a field of type `vector`, pick an
  existing named group in the `of-group` dropdown. That group
  becomes a template group; seed records appear in the inline table.
  See step 2.
- **Edit seed records.** Click cells in the seed-records table —
  values parse per field type. `+ Add record` appends a new row with
  auto-incremented `::id`; `×` removes a row. See step 2.
- **Declare an action.** In the Actions section (visible only once
  the group has a field), use "Add action": name + operation (`set /
  toggle / increment / decrement / clear / add / remove`) + target
  field. See step 8.
- **Add a binding.** Click the 🔗 next to any attribute widget,
  search the grouped picker, click the target field. Direction is
  auto-picked from the property kind. See steps 5 and 10.
- **Add a trigger.** Select an interactive element (button, switch,
  search field, …), open Events, click the event's action-picker,
  pick an action-ref. Implicit payload is the enclosing template
  record; explicit payloads are doc-only in v1. See step 9.
- **Set a template's source.** Select the template group's root,
  scroll to "Rendered from", pick the owning collection field.
  See step 6.
- **Export to ClojureScript.** File menu → "Export ClojureScript
  (interactive)". Unzip, `npm install`, `npx shadow-cljs watch app`.
  See step 11.

## Templates

Bareforge ships with 8 starter templates that showcase BareDOM's component
library and modern web design patterns:

| Template | Description |
|----------|-------------|
| **SaaS Hero** | Gaussian blur background, navbar, kinetic typography headline, particle button CTAs, and a stats row |
| **Bento Features** | Section heading with a bento grid of feature cards, including a multi-span highlighted card |
| **Our Story** | Narrative cards separated by organic dividers — a vertical storytelling layout |
| **Pricing Table** | Three-tier pricing cards (Starter / Pro / Enterprise) with badges, dividers, and feature lists |
| **Testimonials** | Grid of customer quote cards with avatars and attributions |
| **How It Works** | Four-step timeline with labels, titles, and descriptive content |
| **Contact** | Two-column layout with a form (wrapped in `x-form`) and a contact info card |
| **Full Landing Page** | Complete multi-section page combining navbar, hero, stats, feature grid, testimonial, and CTA footer with a gaussian blur background |

Pick a template from the Templates panel to start with a pre-built
structure, then customise content, theme, and layout in the editor.

## Status

**Early alpha.** Feature-complete: 90 BareDOM components in the
palette, four export plugins at full feature parity (HTML, bundle,
CLJS, vanilla-JS), nine starter templates, first-run welcome tour,
doc-level XSS sanitiser, CSP + SRI on every export. 486 tests / 0
release-build warnings under Closure Advanced. Expect rough edges
on less-common BareDOM components until their augment entries are
hand-tuned. See [`CHANGELOG.md`](./CHANGELOG.md) for what's in
each release.

## Relationship to BareDOM

Bareforge is a **consumer** of BareDOM, pulled from Clojars as
`com.github.avanelsas/baredom`. This repository does not modify BareDOM
— version bumps happen via `deps.edn`.

## Quick start

```bash
# Install dev dependencies (jszip for bundle export)
npm install

# Start dev server on http://localhost:8765
npx shadow-cljs watch app

# Release build (Closure :advanced)
npx shadow-cljs release app

# Tests
npx shadow-cljs watch test
```

Requirements: JDK 11+, Node 18+.

## Adding a new BareDOM component

When BareDOM ships a new component, or you want to expose one Bareforge
didn't hand-curate, a scaffolder automates the file surgery. Two steps
for the common case:

### Recipe

1. **Bump the BareDOM version** in `deps.edn` if the component is in a
   newer release.

2. **Run the scaffolder**:

   ```bash
   clojure -X:scaffold :tag x-new-thing :category :layout
   ```

   Add `:dry-run true` to preview the edits without writing.

3. **Refresh the browser.** The component shows up in the palette with
   heuristic-typed properties in the inspector.

### What it touches

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

### Optional polish

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

### How it works

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

## Project layout

```
bareforge/
├── CLAUDE.md                  Architecture & development rules
├── docs/architecture.md       Architecture overview for new contributors
├── docs/plugins.md            Export plugin authoring guide
├── deps.edn                   Clojure dependencies + :scaffold alias
├── shadow-cljs.edn            Build config
├── public/
│   ├── index.html             Static shell + editor CSS
│   ├── assets/                Logo + static assets
│   └── js/                    Compiled output (git-ignored)
├── scripts/
│   └── scaffold_component.clj The new-component scaffolder
└── src/bareforge/
    ├── main.cljs              Entry point
    ├── state.cljs             Single app-state atom + history
    ├── doc/                   Pure document model + ops + spec
    ├── meta/                  Component metadata
    │   ├── public_api.cljs    Tag → observed attrs (from BareDOM)
    │   ├── augment.cljs       Hand-curated property kinds + CSS vars
    │   ├── categories.cljs    Palette grouping
    │   ├── slots.cljs         Container slot descriptors
    │   ├── placement.cljs     Snap hints
    │   ├── heuristics.cljc    Shared label / kind inference
    │   └── registry.cljs      Merged get-meta lookup
    ├── render/                Hand-written DOM reconciler + selection overlay
    ├── ui/                    Editor chrome (palette, layers, inspector, …)
    ├── dnd/                   Drag-drop state machine
    ├── storage/               IndexedDB autosave + project files
    └── export/                Pluggable exports — HTML, bundle, CLJS, vanilla-JS
```

See [`docs/architecture.md`](./docs/architecture.md) for the architecture, data model, rendering pipeline,
and per-component notes.

## Philosophy (brief)

Every edit flows through a pure transform (`doc.ops/*`) before being
committed through the single atom (`bareforge.state/app-state`). The
reconciler is a one-way function `DOM = f(state)`. Pure zone
(`doc/`, `meta/`, `export/` serialization, `storage/` serialization)
is side-effect-free and tested without a browser; effectful zone
(`render/`, `dnd/`, `ui/`, `state.cljs`) is the only place with DOM
access or atom writes. `CLAUDE.md` has the full rule list.

## Contributing

Read `CLAUDE.md` before writing code. It encodes the pure / effectful
zone boundary, the one-atom rule, spec usage at boundaries, Closure
Advanced safety, and the runtime-dependency policy (only BareDOM and
JSZip).

## License

MIT. See `LICENSE` for the full text.
