(ns bareforge.meta.events
  "Per-component DOM event list — the source of truth for which events
   the inspector's Events section offers on each interactive tag.

   Keys are tag strings; values are vectors of event-name strings. Any
   tag not in the map is treated as non-interactive and the Events
   section is hidden for it.

   Adding a component to this map immediately surfaces its events in
   the inspector. Event names should match the actual DOM event the
   component dispatches (e.g. `press` for `x-button`, `x-popover-toggle`
   for `x-popover`).")

(def events-by-tag
  {"x-button"       ["press"]
   "x-file-upload"  ["x-file-upload-select" "x-file-upload-remove"]
   "x-switch"       ["change"]
   "x-search-field" ["input"]
   "x-text-area"    ["input"]
   "x-select"       ["change"]
   "x-menu-item"    ["click"]
   "x-checkbox"     ["change"]
   "x-popover"      ["x-popover-toggle" "mouseenter" "mouseleave"]
   "x-combobox"     ["x-combobox-change" "x-combobox-input" "x-combobox-toggle"]
   "x-tooltip"      ["x-tooltip-show" "x-tooltip-hide"]
   "x-welcome-tour" ["x-welcome-tour-start" "x-welcome-tour-step-change" "x-welcome-tour-complete" "x-welcome-tour-skip"]})

(defn events-for
  "Return the vector of supported event names for `tag`, or nil."
  [tag]
  (get events-by-tag tag))
