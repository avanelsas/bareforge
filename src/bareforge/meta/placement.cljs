(ns bareforge.meta.placement
  "Placement hints drive the canvas drag-drop snap logic.

   Hint values:
   - :flow              — normal flow inside the content column (default)
   - :top-full-width    — snap to top of page, full width
   - :bottom-full-width — snap to bottom of page, full width
   - :free              — freeform absolute positioning
   - :background        — decorative layer behind siblings in a container

   Some components support multiple hints (e.g. organic shapes can be a
   background layer OR a flow child). Those carry :also in their entry.")

(def placement
  {"x-navbar"          {:hint :top-full-width}
   "x-sidebar"         {:hint :top-full-width}

   "x-gaussian-blur"   {:hint :background}
   "x-liquid-fill"     {:hint :background}
   "x-neural-glow"     {:hint :background}
   "x-metaball-cursor" {:hint :background}
   "x-soft-body"       {:hint :background}
   "x-splash"          {:hint :background}

   "x-organic-shape"   {:hint :background :also #{:flow}}})

(def default-hint {:hint :flow})

(defn hint-for
  "Return the placement hint map for `tag`, or the flow default."
  [tag]
  (get placement tag default-hint))

(defn apply-snap
  "Pure: translate a placement hint into a drop-time override.
   Takes the `hint` keyword (from `hint-for`), the current
   document, and the `base-target` map `{:parent-id :slot :index}`
   computed by the drag layer. Returns either nil (no snap applies)
   or `{:target <target'> :layout <layout-overrides>}` where
   `:target` replaces the insertion target and `:layout` is merged
   onto the seed's layout.

   Snap is drop-time only — the returned overrides land in the
   node's initial layout and never constrain later edits. The
   `:background` hint returns nil here because it is handled by
   its own branch in `commit-drop!` (it is a placement kind, not
   a target redirect)."
  [hint doc base-target]
  (case hint
    :top-full-width
    {:target (assoc base-target
                    :parent-id "root"
                    :slot      "default"
                    :index     0)
     :layout {:width "100%"}}

    :bottom-full-width
    (let [n (count (get-in doc [:root :slots "default"] []))]
      {:target (assoc base-target
                      :parent-id "root"
                      :slot      "default"
                      :index     n)
       :layout {:width "100%"}})

    nil))
