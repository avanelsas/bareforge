(ns bareforge.meta.slots
  "Hand-curated slot descriptions for BareDOM components.

  BareDOM slot names are CSS constants inside each component's shadow DOM;
  they are not machine-readable from public-api. This map captures the
  inspector's view of each component's meaningful slots.

  Slot shape:
  {:name       string   ; the slot= attribute value ('default' for unnamed)
  :label      string   ; human label for the inspector
  :multiple?  boolean} ; whether the slot accepts multiple children

  Omissions are intentional — components without an entry fall through to
  a single catch-all {:name \"default\" :multiple? true} slot.")

(def slots
  {"x-button"
   [{:name "default"    :label "Content"    :multiple? false}
    {:name "icon-start" :label "Icon start" :multiple? false}
    {:name "icon-end"   :label "Icon end"   :multiple? false}]

   "x-card"
   [{:name "default" :label "Content" :multiple? true}]

   "x-container"
   [{:name "default" :label "Content" :multiple? true}]

   "x-grid"
   [{:name "default" :label "Items" :multiple? true}]

   "x-split-pane"
   [{:name "start" :label "Start panel" :multiple? true}
    {:name "end"   :label "End panel"   :multiple? true}]

   "x-navbar"
   [{:name "brand"   :label "Brand"   :multiple? false}
    {:name "start"   :label "Start"   :multiple? true}
    {:name "default" :label "Center"  :multiple? true}
    {:name "end"     :label "End"     :multiple? true}
    {:name "actions" :label "Actions" :multiple? true}
    {:name "toggle"  :label "Toggle"  :multiple? false}]

   "x-sidebar"
   [{:name "default" :label "Content" :multiple? true}]

   "x-typography"
   [{:name "default" :label "Text" :multiple? false}]

   "x-modal"
   [{:name "header"  :label "Header" :multiple? true}
    {:name "default" :label "Body"   :multiple? true}
    {:name "footer"  :label "Footer" :multiple? true}]

   "x-drawer"
   [{:name "header"  :label "Header" :multiple? true}
    {:name "default" :label "Body"   :multiple? true}
    {:name "footer"  :label "Footer" :multiple? true}]

   "x-popover"
   [{:name "trigger" :label "Trigger" :multiple? false}
    {:name "default" :label "Body"    :multiple? true}
    {:name "footer"  :label "Footer"  :multiple? true}]

   "x-icon"
   [{:name "default"    :label "Content"    :multiple? false}]})

(def default-slot
  [{:name "default" :label "Content" :multiple? true}])

(defn slots-for
  "Return the slot descriptors for `tag`, or the default single-slot fallback."
  [tag]
  (get slots tag default-slot))

(defn explicitly-registered?
  "True when `tag` has a hand-written entry in the `slots` map (i.e.
  is not falling through to `default-slot`). `registry/container?`
  uses this to avoid over-classifying leaf web components as
  droppable containers just because the fallback claims so — the
  fallback is meant to give the inspector a generic slot to show,
  not to declare the tag as a real container."
  [tag]
  (contains? slots tag))
