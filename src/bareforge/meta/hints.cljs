(ns bareforge.meta.hints
  "Per-tag hint strings shown inside empty containers in edit mode.
   Pure data: the canvas reconciler reads `hint-for` and stamps the
   chosen text onto each container's `data-bareforge-hint` attribute
   on creation, and the existing empty-state CSS rule renders it via
   `::before`. Tags without a custom hint fall back to the generic
   `<tag>  (empty)` style.

   Hints are intentionally short (≤ 28 chars) so they fit inside the
   small dashed empty-state footprint without wrapping. Coverage is
   bounded by `meta.hints-test`'s assertion that every hinted tag
   exists in the registry — bumps that rename a tag will fail loudly
   instead of silently surfacing the wrong hint.")

(def ^:private hints
  {"x-card"        "Drop heading or content"
   "x-grid"        "Drop tiles into the grid"
   "x-container"   "Drop components here"
   "x-navbar"      "Drop nav links / actions"
   "x-modal"       "Drop modal content here"
   "x-popover"     "Drop popover content"
   "x-drawer"      "Drop drawer content"
   "x-tabs"        "Drop an x-tab here"
   "x-tab"         "Drop tab content"
   "x-bento-grid"  "Drop x-bento-item here"
   "x-bento-item"  "Drop bento content"
   "x-form-field"  "Drop a form input"
   "x-table"       "Drop x-table-row here"
   "x-table-row"   "Drop x-table-cell here"
   "x-sidebar"     "Drop sidebar content"
   "x-menu"        "Drop x-menu-item here"
   "x-stepper"     "Drop step content"
   "x-carousel"    "Drop slides here"
   "x-collapse"    "Drop collapsible content"})

(defn hint-for
  "Pure: return the hint string for `tag`, or nil when no custom
   hint is registered. Callers use the nil sentinel to fall back to
   the generic `(empty)` affordance."
  [tag]
  (get hints tag))

(defn covered-tags
  "Pure: the set of tags that currently have a custom empty-state
   hint. Used by the meta-coverage test."
  []
  (set (keys hints)))
