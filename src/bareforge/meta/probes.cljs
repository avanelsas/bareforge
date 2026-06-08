(ns bareforge.meta.probes
  "Per-tag shadow-DOM selectors for the handful of components whose
   *painted* surface escapes their host element's layout box.

   The canvas selection overlay (`render/selection`) traces the host
   element's bounding box. For 79 of BareDOM's 83 tags that box is
   exactly the visible surface, so the host is the right thing to
   measure. The four overlay components are the exception:

   - `x-sidebar` (docked) renders a full-width `display:block` host but
     only paints an 18rem `.panel` on the leading edge.
   - `x-drawer` / `x-modal` / `x-popover` paint a `position:fixed` /
     `absolute` panel that leaves the host's box entirely, collapsing
     the host to a thin strip.

   For these, the overlay measures the named shadow element instead so
   the selection border hugs what the user actually sees, across every
   variant. Selectors track BareDOM's shadow templates and are pinned
   to the BareDOM version in `deps.edn` / `meta/versions.cljs`.")

(def ^:private selection-selectors
  {"x-sidebar" ".panel"
   "x-drawer"  "[part=panel]"
   "x-modal"   "[part=dialog]"
   "x-popover" "[part=panel]"})

(defn selection-selector
  "Shadow-DOM selector for the visible surface of `tag`, or nil when the
   host's own box is the right thing to measure (the common case)."
  [tag]
  (get selection-selectors tag))
