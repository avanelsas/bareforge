(ns bareforge.meta.patterns
  "Per-tag named pre-styled configurations exposed by the palette as
   one-click insert variants. Each pattern's `:overrides` map matches
   the shape `palette/seed-for-tag` returns — passed straight into
   `doc.ops/insert-new` as the overrides arg, so a pattern lands
   pre-populated with its own attrs / props / text / inner-html.

   Patterns are pure data: no rendering changes, no special DOM
   plumbing. The palette tile checks `(patterns-for tag)` and
   surfaces the variants when non-empty.

   Coverage starts narrow (the most-reached-for components). The
   companion test in `meta/patterns_test.cljs` warns — not fails —
   when a registered tag has no patterns, so gaps stay visible
   without blocking new tag bumps.")

(def ^:private patterns-by-tag
  "Map from tag → ordered vector of `{:id :label :overrides}`. Order
   is display order. `:id` is a stable keyword for tests / config;
   `:label` is the human-readable name shown on the palette flyout."
  {"x-button"
   [{:id :primary
     :label "Primary"
     :overrides {:text "Primary"
                 :attrs {"label" "Primary" "variant" "primary"}}}
    {:id :secondary
     :label "Secondary"
     :overrides {:text "Secondary"
                 :attrs {"label" "Secondary" "variant" "secondary"}}}
    {:id :ghost
     :label "Ghost"
     :overrides {:text "Ghost"
                 :attrs {"label" "Ghost" "variant" "ghost"}}}
    {:id :danger
     :label "Danger"
     :overrides {:text "Delete"
                 :attrs {"label" "Delete" "variant" "danger"}}}
    {:id :loading
     :label "Loading"
     :overrides {:text "Loading…"
                 :attrs {"label" "Loading…" "variant" "primary"}
                 :props {:loading true}}}]

   "x-typography"
   [{:id :h1   :label "Heading 1"
     :overrides {:text "Heading 1" :attrs {"variant" "h1"}}}
    {:id :h2   :label "Heading 2"
     :overrides {:text "Heading 2" :attrs {"variant" "h2"}}}
    {:id :h3   :label "Heading 3"
     :overrides {:text "Heading 3" :attrs {"variant" "h3"}}}
    {:id :body :label "Body"
     :overrides {:text "Body text"   :attrs {"variant" "body1"}}}
    {:id :caption :label "Caption"
     :overrides {:text "Caption"     :attrs {"variant" "caption"}}}
    {:id :code :label "Code"
     :overrides {:text "code()"      :attrs {"variant" "code"}}}]

   "x-alert"
   [{:id :info    :label "Info"
     :overrides {:attrs {"text" "Information message" "type" "info"}}}
    {:id :success :label "Success"
     :overrides {:attrs {"text" "Operation succeeded" "type" "success"}}}
    {:id :warning :label "Warning"
     :overrides {:attrs {"text" "Heads up — please review" "type" "warning"}}}
    {:id :error   :label "Error"
     :overrides {:attrs {"text" "Something went wrong" "type" "error"}}}
    {:id :dismissible :label "Dismissible"
     :overrides {:attrs {"text" "Click × to dismiss" "type" "info"
                         "dismissible" ""}}}]

   "x-badge"
   [{:id :neutral :label "Neutral"
     :overrides {:attrs {"text" "Neutral"  "variant" "neutral"}}}
    {:id :info    :label "Info"
     :overrides {:attrs {"text" "Info"     "variant" "info"}}}
    {:id :success :label "Success"
     :overrides {:attrs {"text" "Success"  "variant" "success"}}}
    {:id :warning :label "Warning"
     :overrides {:attrs {"text" "Warning"  "variant" "warning"}}}
    {:id :error   :label "Error"
     :overrides {:attrs {"text" "Error"    "variant" "error"}}}
    {:id :dot     :label "Dot indicator"
     :overrides {:attrs {"variant" "info"  "dot" ""}}}]

   "x-chip"
   [{:id :default    :label "Default"
     :overrides {:attrs {"label" "Tag"}}}
    {:id :removable  :label "Removable"
     :overrides {:attrs {"label" "Removable" "removable" ""}}}
    {:id :disabled   :label "Disabled"
     :overrides {:attrs {"label" "Disabled" "disabled" ""}}}]

   "x-card"
   [{:id :elevated   :label "Elevated"
     :overrides {:attrs {"variant" "elevated" "padding" "md" "radius" "lg"}}}
    {:id :outlined   :label "Outlined"
     :overrides {:attrs {"variant" "outlined" "padding" "md" "radius" "lg"}}}
    {:id :filled     :label "Filled"
     :overrides {:attrs {"variant" "filled"   "padding" "md" "radius" "lg"}}}
    {:id :interactive :label "Interactive"
     :overrides {:attrs {"variant" "elevated" "padding" "md" "radius" "lg"
                         "interactive" ""}}}]

   "x-divider"
   [{:id :horizontal :label "Horizontal"
     :overrides {}}
    {:id :vertical   :label "Vertical"
     :overrides {:attrs {"orientation" "vertical"}}}]

   "x-switch"
   [{:id :off :label "Off"
     :overrides {}}
    {:id :on  :label "On (checked)"
     :overrides {:attrs {"checked" ""}}}]

   "x-checkbox"
   [{:id :off :label "Off"
     :overrides {}}
    {:id :on  :label "On (checked)"
     :overrides {:attrs {"checked" ""}}}]

   "x-grid"
   [{:id :two-col   :label "2 columns"
     :overrides {:attrs {"columns" "repeat(2, 1fr)" "gap" "16px"}}}
    {:id :three-col :label "3 columns"
     :overrides {:attrs {"columns" "repeat(3, 1fr)" "gap" "16px"}}}
    {:id :four-col  :label "4 columns"
     :overrides {:attrs {"columns" "repeat(4, 1fr)" "gap" "16px"}}}
    {:id :sidebar   :label "Sidebar + body"
     :overrides {:attrs {"columns" "240px 1fr" "gap" "24px"}}}]})

(defn patterns-for
  "Pure: the named patterns vector for `tag`, or nil when no patterns
   are registered. Callers branch on truthy / falsy to decide whether
   to render the flyout affordance on a palette tile."
  [tag]
  (get patterns-by-tag tag))

(defn covered-tags
  "Pure: the set of tags that currently have at least one pattern.
   Used by the meta-coverage test to print a warning for tags without
   patterns."
  []
  (set (keys patterns-by-tag)))
