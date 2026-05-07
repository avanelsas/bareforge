(ns bareforge.meta.augment
  "Hand-curated rich metadata for BareDOM components.

   Each entry describes how the inspector should present a component:
   - :category  — palette grouping
   - :label     — human-readable name for the palette
   - :properties — ordered list of editable attributes

   Property shape:
     {:name    string       ; HTML attribute name
      :kind    keyword      ; :boolean | :enum | :string-short | :string-long
                            ; | :number | :color | :url | :date
      :choices [string]     ; required for :enum
      :default any}         ; optional

   v1 scope: rich entries for a small seed set. Tags not in this map fall
   through to the raw-attribute fallback in meta/registry. Coverage grows
   via subsequent PRs; the meta/registry-test enumerates unaugmented tags
   so gaps stay visible.")

(def ^:private x-button
  {:category :form
   :label    "Button"
   :properties
   [{:name "variant"  :kind :enum
     :choices ["primary" "secondary" "tertiary" "ghost" "danger"]
     :default "primary"}
    {:name "size"     :kind :enum :choices ["sm" "md" "lg"] :default "md"}
    {:name "type"     :kind :enum :choices ["button" "submit" "reset"]
     :default "button"}
    {:name "label"    :kind :string-short}
    {:name "disabled" :kind :boolean :default false}
    {:name "loading"  :kind :boolean :default false}
    {:name "pressed"  :kind :boolean :default false}]
   :css-vars
   [{:label "Background" :kind :color
     :vars-by-attr {"variant" {"primary"   "--x-button-bg"
                               "secondary" "--x-button-secondary-bg"
                               "tertiary"  "--x-button-tertiary-bg"
                               "ghost"     "--x-button-ghost-bg"
                               "danger"    "--x-button-danger-bg"}}
     :default-attr-value "primary"}
    {:label "Text colour" :kind :color
     :vars-by-attr {"variant" {"primary"   "--x-button-fg"
                               "secondary" "--x-button-secondary-fg"
                               "tertiary"  "--x-button-tertiary-fg"
                               "ghost"     "--x-button-ghost-fg"
                               "danger"    "--x-button-danger-fg"}}
     :default-attr-value "primary"}
    {:label "Font size" :kind :length
     :vars-by-attr {"size" {"sm" "--x-button-font-size-sm"
                            "md" "--x-button-font-size-md"
                            "lg" "--x-button-font-size-lg"}}
     :default-attr-value "md"}
    {:var "--x-button-font-weight" :label "Font weight" :kind :string}
    {:var "--x-button-gap"         :label "Gap"         :kind :length}]})

(def ^:private x-card
  {:category :layout
   :label    "Card"
   :properties
   [{:name "variant" :kind :enum
     :choices ["elevated" "outlined" "filled" "ghost"]
     :default "elevated"}
    {:name "padding" :kind :enum :choices ["none" "sm" "md" "lg"] :default "md"}
    {:name "radius"  :kind :enum :choices ["none" "sm" "md" "lg" "xl"]
     :default "lg"}
    {:name "interactive" :kind :boolean :default false}
    {:name "disabled"    :kind :boolean :default false}
    {:name "label"       :kind :string-short}]
   :css-vars
   [{:var "--x-card-background"   :label "Background"   :kind :color}
    {:var "--x-card-color"        :label "Text colour"  :kind :color}
    {:var "--x-card-border-color" :label "Border"       :kind :color}
    {:label "Padding" :kind :length
     :vars-by-attr {"padding" {"none" "--x-card-padding-none"
                               "sm"   "--x-card-padding-sm"
                               "md"   "--x-card-padding-md"
                               "lg"   "--x-card-padding-lg"}}
     :default-attr-value "md"}
    {:label "Radius" :kind :length
     :vars-by-attr {"radius" {"none" "--x-card-radius-none"
                              "sm"   "--x-card-radius-sm"
                              "md"   "--x-card-radius-md"
                              "lg"   "--x-card-radius-lg"
                              "xl"   "--x-card-radius-xl"}}
     :default-attr-value "lg"}]})

(def ^:private x-container
  {:category :layout
   :label    "Container"
   :properties
   [{:name "as" :kind :enum
     :choices ["div" "section" "article" "main" "aside" "header" "footer" "nav"]
     :default "div"}
    {:name "size" :kind :enum :choices ["xs" "sm" "md" "lg" "xl" "full"]
     :default "lg"}
    {:name "padding" :kind :enum :choices ["none" "sm" "md" "lg"] :default "md"}
    {:name "center" :kind :boolean :default false}
    {:name "fluid"  :kind :boolean :default false}
    {:name "label"  :kind :string-short}]
   :css-vars
   [{:var "--x-container-bg"    :label "Background"  :kind :color}
    {:var "--x-container-color" :label "Text colour" :kind :color}
    {:label "Max width" :kind :length
     :vars-by-attr {"size" {"xs" "--x-container-max-width-xs"
                            "sm" "--x-container-max-width-sm"
                            "md" "--x-container-max-width-md"
                            "lg" "--x-container-max-width-lg"
                            "xl" "--x-container-max-width-xl"}}
     :default-attr-value "lg"}
    {:label "Padding" :kind :length
     :vars-by-attr {"padding" {"none" "--x-container-padding-none"
                               "sm"   "--x-container-padding-sm"
                               "md"   "--x-container-padding-md"
                               "lg"   "--x-container-padding-lg"}}
     :default-attr-value "md"}]})

(def ^:private x-grid
  {:category :layout
   :label    "Grid"
   :properties
   [{:name "columns"          :kind :number
     :display-name "Columns"
     :transform :grid-columns}
    {:name "min-column-size"  :kind :string-short :default "16rem"}
    {:name "gap"              :kind :enum
     :choices ["none" "xs" "sm" "md" "lg" "xl"] :default "md"}
    {:name "row-gap"          :kind :enum
     :choices ["none" "xs" "sm" "md" "lg" "xl"] :default "md"}
    {:name "column-gap"       :kind :enum
     :choices ["none" "xs" "sm" "md" "lg" "xl"] :default "md"}
    {:name "align-items"      :kind :string-short}
    {:name "justify-items"    :kind :string-short}
    {:name "auto-flow"        :kind :string-short}
    {:name "inline"           :kind :boolean :default false}]
   :css-vars
   [{:var "--x-grid-row-gap"    :label "Row gap"    :kind :length}
    {:var "--x-grid-column-gap" :label "Column gap" :kind :length}]})

(def ^:private x-navbar
  {:category :navigation
   :label    "Navbar"
   :properties
   [{:name "orientation" :kind :enum :choices ["horizontal" "vertical"]
     :default "horizontal"}
    {:name "variant"     :kind :enum
     :choices ["default" "subtle" "inverted" "transparent"]
     :default "default"}
    {:name "breakpoint"  :kind :enum :choices ["sm" "md" "lg" "xl"]
     :default "md"}
    {:name "alignment"   :kind :enum
     :choices ["start" "center" "space-between"]
     :default "space-between"}
    {:name "label"       :kind :string-short}
    {:name "sticky"      :kind :boolean :default false}
    {:name "elevated"    :kind :boolean :default false}]
   :css-vars
   [{:var "--x-navbar-bg"             :label "Background"  :kind :color}
    {:var "--x-navbar-color"          :label "Text colour" :kind :color}
    {:var "--x-navbar-height"         :label "Height"      :kind :length}
    {:var "--x-navbar-padding-inline" :label "Padding"     :kind :length}
    {:var "--x-navbar-radius"         :label "Radius"      :kind :length}]})

(def ^:private x-typography
  {:category :text
   :label    "Typography"
   :properties
   [{:name "variant" :kind :enum
     :choices ["h1" "h2" "h3" "h4" "h5" "h6"
               "subtitle1" "subtitle2" "body1" "body2"
               "caption" "overline" "blockquote" "code" "kbd" "small"]
     :default "body1"}
    {:name "align"      :kind :enum
     :choices ["left" "center" "right" "justify"]
     :default "left"}
    {:name "truncate"   :kind :boolean :default false}
    {:name "line-clamp" :kind :string-short}]
   :css-vars
   [{:var "--x-typography-color"       :label "Colour"      :kind :color}
    {:var "--x-typography-font-family" :label "Font family" :kind :string}]})

(def ^:private x-switch
  {:category :form
   :label    "Switch"
   :properties
   [{:name "checked"  :kind :boolean :default false}
    {:name "disabled" :kind :boolean :default false}
    {:name "readonly" :kind :boolean :default false}
    {:name "required" :kind :boolean :default false}
    {:name "name"     :kind :string-short}
    {:name "value"    :kind :string-short}
    {:name "aria-label" :kind :string-short}]
   :css-vars
   [{:var "--x-switch-thumb-bg"         :label "Thumb"        :kind :color}
    {:var "--x-switch-track-bg"         :label "Track (off)"  :kind :color}
    {:var "--x-switch-track-bg-checked" :label "Track (on)"   :kind :color}
    {:var "--x-switch-thumb-size"       :label "Thumb size"   :kind :length}
    {:var "--x-switch-track-width"      :label "Track width"  :kind :length}
    {:var "--x-switch-track-height"     :label "Track height" :kind :length}
    {:var "--x-switch-track-radius"     :label "Track radius" :kind :length}]})

(def ^:private x-alert
  {:category :feedback
   :label    "Alert"
   :properties
   [{:name "type" :kind :enum :choices ["info" "success" "warning" "error"]
     :default "info"}
    {:name "text"        :kind :string-long}
    {:name "icon"        :kind :string-short}
    {:name "dismissible" :kind :boolean :default false}
    {:name "disabled"    :kind :boolean :default false}
    {:name "timeout-ms"  :kind :number}]
   :css-vars
   [{:var "--x-alert-bg"           :label "Background"   :kind :color}
    {:var "--x-alert-color"        :label "Text colour"  :kind :color}
    {:var "--x-alert-border-color" :label "Border"       :kind :color}
    {:var "--x-alert-padding-x"    :label "Padding (x)"  :kind :length}]})

(def ^:private x-checkbox
  {:category :form
   :label    "Checkbox"
   :properties
   [{:name "checked"       :kind :boolean :default false}
    {:name "indeterminate" :kind :boolean :default false}
    {:name "disabled"      :kind :boolean :default false}
    {:name "readonly"      :kind :boolean :default false}
    {:name "required"      :kind :boolean :default false}
    {:name "name"          :kind :string-short}
    {:name "value"         :kind :string-short}
    {:name "aria-label"    :kind :string-short}]
   :css-vars
   [{:var "--x-checkbox-bg"            :label "Background"        :kind :color}
    {:var "--x-checkbox-bg-checked"    :label "Background (on)"   :kind :color}
    {:var "--x-checkbox-check-color"   :label "Check colour"      :kind :color}
    {:var "--x-checkbox-border-color"  :label "Border"            :kind :color}
    {:var "--x-checkbox-size"          :label "Size"              :kind :length}
    {:var "--x-checkbox-border-radius" :label "Radius"            :kind :length}]})

(def ^:private x-radio
  {:category :form
   :label    "Radio"
   :properties
   [{:name "checked"    :kind :boolean :default false}
    {:name "disabled"   :kind :boolean :default false}
    {:name "readonly"   :kind :boolean :default false}
    {:name "required"   :kind :boolean :default false}
    {:name "name"       :kind :string-short}
    {:name "value"      :kind :string-short}
    {:name "aria-label" :kind :string-short}]
   :css-vars
   [{:var "--x-radio-bg"            :label "Background" :kind :color}
    {:var "--x-radio-checked-color" :label "Dot colour" :kind :color}
    {:var "--x-radio-border-color"  :label "Border"     :kind :color}
    {:var "--x-radio-size"          :label "Size"       :kind :length}
    {:var "--x-radio-dot-size"      :label "Dot size"   :kind :length}]})

(def ^:private x-slider
  {:category :form
   :label    "Slider"
   :properties
   [{:name "value"      :kind :number}
    {:name "min"        :kind :number}
    {:name "max"        :kind :number}
    {:name "step"       :kind :number}
    {:name "size"       :kind :enum :choices ["sm" "md" "lg"] :default "md"}
    {:name "show-value" :kind :boolean :default false}
    {:name "disabled"   :kind :boolean :default false}
    {:name "readonly"   :kind :boolean :default false}
    {:name "name"       :kind :string-short}
    {:name "label"      :kind :string-short}]
   :css-vars
   [{:var "--x-slider-fill-color"  :label "Fill colour"  :kind :color}
    {:var "--x-slider-track-color" :label "Track colour" :kind :color}
    {:var "--x-slider-thumb-color" :label "Thumb colour" :kind :color}
    {:var "--x-slider-value-color" :label "Value text"   :kind :color}
    {:var "--x-slider-label-color" :label "Label text"   :kind :color}
    {:var "--x-slider-radius"      :label "Track radius" :kind :length}]})

(def ^:private x-select
  {:category :form
   :label    "Select"
   :properties
   [{:name "value"       :kind :string-short}
    {:name "size"        :kind :enum :choices ["sm" "md" "lg"] :default "md"}
    {:name "placeholder" :kind :string-short}
    {:name "disabled"    :kind :boolean :default false}
    {:name "required"    :kind :boolean :default false}
    {:name "name"        :kind :string-short}]
   :css-vars
   [{:var "--x-select-bg"             :label "Background"     :kind :color}
    {:var "--x-select-fg"             :label "Text colour"    :kind :color}
    {:var "--x-select-placeholder-fg" :label "Placeholder"    :kind :color}
    {:var "--x-select-chevron"        :label "Chevron"        :kind :color}
    {:var "--x-select-border"         :label "Border"         :kind :color}
    {:var "--x-select-radius"         :label "Radius"         :kind :length}
    {:label "Height" :kind :length
     :vars-by-attr {"size" {"sm" "--x-select-height-sm"
                            "md" "--x-select-height-md"
                            "lg" "--x-select-height-lg"}}
     :default-attr-value "md"}
    {:label "Font size" :kind :length
     :vars-by-attr {"size" {"sm" "--x-select-font-size-sm"
                            "md" "--x-select-font-size-md"
                            "lg" "--x-select-font-size-lg"}}
     :default-attr-value "md"}]})

(def ^:private x-modal
  {:category :overlay
   :label    "Modal"
   :properties
   [{:name "open"  :kind :boolean :default false}
    {:name "size"  :kind :enum :choices ["sm" "md" "lg" "xl" "full"] :default "md"}
    {:name "label" :kind :string-short}]
   :css-vars
   [{:var "--x-modal-bg"             :label "Background"     :kind :color}
    {:var "--x-modal-fg"             :label "Text colour"    :kind :color}
    {:var "--x-modal-backdrop"       :label "Backdrop"       :kind :color}
    {:var "--x-modal-border"         :label "Border"         :kind :color}
    {:var "--x-modal-radius"         :label "Radius"         :kind :length}
    {:var "--x-modal-max-height"     :label "Max height"     :kind :length}
    {:var "--x-modal-header-padding" :label "Header padding" :kind :length}
    {:var "--x-modal-body-padding"   :label "Body padding"   :kind :length}
    {:var "--x-modal-footer-padding" :label "Footer padding" :kind :length}
    {:label "Width" :kind :length
     :vars-by-attr {"size" {"sm" "--x-modal-width-sm"
                            "md" "--x-modal-width-md"
                            "lg" "--x-modal-width-lg"
                            "xl" "--x-modal-width-xl"}}
     :default-attr-value "md"}]})

(def ^:private x-badge
  {:category :feedback
   :label    "Badge"
   :properties
   [{:name "variant"    :kind :enum
     :choices ["neutral" "info" "success" "warning" "error"]
     :default "neutral"}
    {:name "size"       :kind :enum :choices ["sm" "md"] :default "md"}
    {:name "pill"       :kind :boolean :default false}
    {:name "dot"        :kind :boolean :default false}
    {:name "count"      :kind :string-short}
    {:name "max"        :kind :string-short}
    {:name "text"       :kind :string-short}
    {:name "aria-label" :kind :string-short}]
   ;; Variant rules reassign --x-badge-bg / -color / -border via
   ;; :host([data-variant='X']) CSS-variable indirection, so inline
   ;; overrides on the active tokens outrank them for any variant.
   :css-vars
   [{:var "--x-badge-bg"        :label "Background" :kind :color}
    {:var "--x-badge-color"     :label "Text"       :kind :color}
    {:var "--x-badge-border"    :label "Border"     :kind :color}
    {:var "--x-badge-font-size" :label "Font size"  :kind :length}
    {:var "--x-badge-height"    :label "Height"     :kind :length}
    {:var "--x-badge-padding"   :label "Padding"    :kind :length}
    {:var "--x-badge-radius"    :label "Radius"     :kind :length}]})

(def ^:private x-chip
  {:category :feedback
   :label    "Chip"
   :properties
   [{:name "label"     :kind :string-short}
    {:name "value"     :kind :string-short}
    {:name "removable" :kind :boolean :default false}
    {:name "disabled"  :kind :boolean :default false}]
   :css-vars
   [{:var "--x-chip-bg"          :label "Background"   :kind :color}
    {:var "--x-chip-color"       :label "Text"         :kind :color}
    {:var "--x-chip-border"      :label "Border"       :kind :color}
    {:var "--x-chip-font-size"   :label "Font size"    :kind :length}
    {:var "--x-chip-padding-x"   :label "Padding (x)"  :kind :length}
    {:var "--x-chip-padding-y"   :label "Padding (y)"  :kind :length}
    {:var "--x-chip-radius"      :label "Radius"       :kind :length}
    {:var "--x-chip-remove-size" :label "Remove icon"  :kind :length}]})

(def ^:private x-avatar
  {:category :data
   :label    "Avatar"
   :properties
   [{:name "src"       :kind :url}
    {:name "alt"       :kind :string-short}
    {:name "name"      :kind :string-short}
    {:name "initials"  :kind :string-short}
    {:name "size"      :kind :enum :choices ["xs" "sm" "md" "lg" "xl"] :default "md"}
    {:name "shape"     :kind :enum :choices ["circle" "square" "rounded"] :default "circle"}
    {:name "variant"   :kind :enum :choices ["neutral" "brand" "subtle"] :default "neutral"}
    {:name "status"    :kind :enum :choices ["online" "offline" "busy" "away"]}
    {:name "disabled"  :kind :boolean :default false}]
   :css-vars
   [{:var "--x-avatar-bg"        :label "Background"  :kind :color}
    {:var "--x-avatar-color"     :label "Text"        :kind :color}
    {:var "--x-avatar-border"    :label "Border"      :kind :color}
    {:var "--x-avatar-ring"      :label "Ring"        :kind :color}
    {:var "--x-avatar-radius"    :label "Radius"      :kind :length}
    {:var "--x-avatar-font-size" :label "Initials size" :kind :length}
    {:label "Size" :kind :length
     :vars-by-attr {"size" {"xs" "--x-avatar-size-xs"
                            "sm" "--x-avatar-size-sm"
                            "md" "--x-avatar-size-md"
                            "lg" "--x-avatar-size-lg"
                            "xl" "--x-avatar-size-xl"}}
     :default-attr-value "md"}]})

(def ^:private x-drawer
  {:category :overlay
   :label    "Drawer"
   :properties
   [{:name "open"      :kind :boolean :default false}
    {:name "placement" :kind :enum :choices ["left" "right" "top" "bottom"]
     :default "right"}
    {:name "label"     :kind :string-short}]
   :css-vars
   [{:var "--x-drawer-bg"             :label "Background"     :kind :color}
    {:var "--x-drawer-fg"             :label "Text"           :kind :color}
    {:var "--x-drawer-backdrop"       :label "Backdrop"       :kind :color}
    {:var "--x-drawer-border"         :label "Border"         :kind :color}
    {:var "--x-drawer-size"           :label "Size"           :kind :length}
    {:var "--x-drawer-header-padding" :label "Header padding" :kind :length}
    {:var "--x-drawer-body-padding"   :label "Body padding"   :kind :length}
    {:var "--x-drawer-footer-padding" :label "Footer padding" :kind :length}]})

(def ^:private x-tabs
  {:category :navigation
   :label    "Tabs"
   :properties
   [{:name "value"       :kind :string-short}
    {:name "orientation" :kind :enum :choices ["horizontal" "vertical"]
     :default "horizontal"}
    {:name "activation"  :kind :enum :choices ["auto" "manual"]
     :default "auto"}
    {:name "loop"        :kind :boolean :default false}
    {:name "label"       :kind :string-short}]
   :css-vars
   [{:var "--x-tabs-gap" :label "Gap" :kind :length}]})

(def ^:private x-tab
  {:category :navigation
   :label    "Tab"
   :properties
   [{:name "value"       :kind :string-short}
    {:name "selected"    :kind :boolean :default false}
    {:name "disabled"    :kind :boolean :default false}
    {:name "orientation" :kind :enum :choices ["horizontal" "vertical"]
     :default "horizontal"}
    {:name "size"        :kind :enum :choices ["sm" "md" "lg"] :default "md"}
    {:name "variant"     :kind :enum :choices ["default" "underline" "pill"]
     :default "default"}
    {:name "label"       :kind :string-short}
    {:name "controls"    :kind :string-short}]
   :css-vars
   [{:var "--x-tab-background"            :label "Background"       :kind :color}
    {:var "--x-tab-color"                 :label "Text"             :kind :color}
    {:var "--x-tab-border-color"          :label "Border"           :kind :color}
    {:var "--x-tab-hover-background"      :label "Hover background" :kind :color}
    {:var "--x-tab-selected-background"   :label "Selected bg"      :kind :color}
    {:var "--x-tab-selected-color"        :label "Selected text"    :kind :color}
    {:var "--x-tab-selected-border-color" :label "Selected border"  :kind :color}
    {:var "--x-tab-radius"                :label "Radius"           :kind :length}
    {:label "Padding" :kind :length
     :vars-by-attr {"size" {"sm" "--x-tab-padding-sm"
                            "md" "--x-tab-padding-md"
                            "lg" "--x-tab-padding-lg"}}
     :default-attr-value "md"}]})

(def ^:private x-breadcrumbs
  {:category :navigation
   :label    "Breadcrumbs"
   :properties
   [{:name "separator"   :kind :string-short}
    {:name "size"        :kind :enum :choices ["sm" "md" "lg"] :default "md"}
    {:name "variant"     :kind :enum :choices ["default" "subtle" "text"]
     :default "default"}
    {:name "wrap"        :kind :boolean :default false}
    {:name "max-items"   :kind :number}
    {:name "items-before" :kind :number}
    {:name "items-after"  :kind :number}
    {:name "disabled"    :kind :boolean :default false}
    {:name "aria-label"  :kind :string-short}]
   :css-vars
   [{:var "--x-breadcrumbs-color"           :label "Text"           :kind :color}
    {:var "--x-breadcrumbs-color-hover"     :label "Hover text"     :kind :color}
    {:var "--x-breadcrumbs-color-current"   :label "Current text"   :kind :color}
    {:var "--x-breadcrumbs-separator-color" :label "Separator"      :kind :color}
    {:var "--x-breadcrumbs-font-size"       :label "Font size"      :kind :length}
    {:var "--x-breadcrumbs-gap"             :label "Gap"            :kind :length}]})

(def ^:private x-dropdown
  {:category :navigation
   :label    "Dropdown"
   :properties
   [{:name "open"      :kind :boolean :default false}
    {:name "disabled"  :kind :boolean :default false}
    {:name "placement" :kind :enum
     :choices ["bottom-start" "bottom-end" "top-start" "top-end"]
     :default "bottom-start"}
    {:name "label"     :kind :string-short}]
   :css-vars
   [{:var "--x-dropdown-trigger-bg"        :label "Trigger bg"    :kind :color}
    {:var "--x-dropdown-trigger-bg-hover"  :label "Trigger hover" :kind :color}
    {:var "--x-dropdown-chevron-color"     :label "Chevron"       :kind :color}
    {:var "--x-dropdown-panel-bg"          :label "Panel bg"      :kind :color}
    {:var "--x-dropdown-panel-border"      :label "Panel border"  :kind :color}
    {:var "--x-dropdown-panel-radius"      :label "Panel radius"  :kind :length}
    {:var "--x-dropdown-panel-max-height"  :label "Panel max-h"   :kind :length}
    {:var "--x-dropdown-panel-min-width"   :label "Panel min-w"   :kind :length}
    {:var "--x-dropdown-panel-padding"     :label "Panel padding" :kind :length}]})

(def ^:private x-divider
  {:category :layout
   :label    "Divider"
   :properties
   [{:name "orientation" :kind :enum :choices ["horizontal" "vertical"]
     :default "horizontal"}
    {:name "variant"     :kind :enum :choices ["solid" "dashed" "dotted"]
     :default "solid"}
    {:name "thickness"   :kind :string-short}
    {:name "color"       :kind :string-short}
    {:name "inset"       :kind :string-short}
    {:name "length"      :kind :string-short}
    {:name "label"       :kind :string-short}
    {:name "align"       :kind :enum :choices ["center" "start" "end"]
     :default "center"}
    {:name "role"        :kind :string-short}
    {:name "aria-label"  :kind :string-short}]
   :css-vars
   [{:var "--x-divider-color"     :label "Colour"    :kind :color}
    {:var "--x-divider-thickness" :label "Thickness" :kind :length}
    {:var "--x-divider-inset"     :label "Inset"     :kind :length}
    {:var "--x-divider-length"    :label "Length"    :kind :length}]})

(def ^:private x-spacer
  {:category :layout
   :label    "Spacer"
   :properties
   [{:name "size" :kind :string-short}
    {:name "axis" :kind :enum :choices ["vertical" "horizontal"]
     :default "vertical"}
    {:name "grow" :kind :boolean :default false}]
   :css-vars
   [{:var "--x-spacer-size" :label "Size" :kind :length}]})

(def ^:private x-progress
  {:category :feedback
   :label    "Progress"
   :properties
   [{:name "value"         :kind :number}
    {:name "max"           :kind :number}
    {:name "variant"       :kind :enum :choices ["default" "success" "warning" "danger"]
     :default "default"}
    {:name "size"          :kind :enum :choices ["sm" "md" "lg"] :default "md"}
    {:name "label"         :kind :string-short}
    {:name "show-value"    :kind :boolean :default false}
    {:name "indeterminate" :kind :boolean :default false}]
   :css-vars
   [{:var "--x-progress-fill-color"    :label "Fill"          :kind :color}
    {:var "--x-progress-track-color"   :label "Track"         :kind :color}
    {:var "--x-progress-value-color"   :label "Value text"    :kind :color}
    {:var "--x-progress-label-color"   :label "Label text"    :kind :color}
    {:var "--x-progress-height"        :label "Height"        :kind :length}
    {:var "--x-progress-border-radius" :label "Radius"        :kind :length}]})

(def ^:private x-progress-circle
  {:category :feedback
   :label    "Progress circle"
   :properties
   [{:name "value"         :kind :number}
    {:name "max"           :kind :number}
    {:name "variant"       :kind :enum :choices ["default" "success" "warning" "danger"]
     :default "default"}
    {:name "size"          :kind :enum :choices ["sm" "md" "lg"] :default "md"}
    {:name "label"         :kind :string-short}
    {:name "show-value"    :kind :boolean :default false}
    {:name "indeterminate" :kind :boolean :default false}]
   :css-vars
   [{:var "--x-progress-circle-fill-color"   :label "Fill"         :kind :color}
    {:var "--x-progress-circle-track-color"  :label "Track"        :kind :color}
    {:var "--x-progress-circle-value-color"  :label "Value text"   :kind :color}
    {:var "--x-progress-circle-size"         :label "Size"         :kind :length}
    {:var "--x-progress-circle-stroke-width" :label "Stroke width" :kind :length}]})

(def ^:private x-spinner
  {:category :feedback
   :label    "Spinner"
   :properties
   [{:name "size"    :kind :enum :choices ["xs" "sm" "md" "lg" "xl"] :default "md"}
    {:name "variant" :kind :enum :choices ["default" "primary" "success" "warning" "danger"]
     :default "default"}
    {:name "label"   :kind :string-short}]
   :css-vars
   [{:var "--x-spinner-color"       :label "Colour"    :kind :color}
    {:var "--x-spinner-track-color" :label "Track"     :kind :color}
    {:var "--x-spinner-size"        :label "Size"      :kind :length}
    {:var "--x-spinner-thickness"   :label "Thickness" :kind :length}
    {:var "--x-spinner-duration"    :label "Duration"  :kind :string}]})

(def ^:private x-skeleton
  {:category :feedback
   :label    "Skeleton"
   :properties
   [{:name "variant"   :kind :enum :choices ["rect" "text" "circle"] :default "rect"}
    {:name "animation" :kind :enum :choices ["pulse" "wave" "none"]    :default "pulse"}
    {:name "width"     :kind :string-short}
    {:name "height"    :kind :string-short}]
   :css-vars
   [{:var "--x-skeleton-color"         :label "Base colour" :kind :color}
    {:var "--x-skeleton-highlight"     :label "Highlight"   :kind :color}
    {:var "--x-skeleton-border-radius" :label "Radius"      :kind :length}
    {:var "--x-skeleton-duration"      :label "Duration"    :kind :string}]})

(def ^:private x-toast
  {:category :feedback
   :label    "Toast"
   :properties
   [{:name "type"          :kind :enum :choices ["info" "success" "warning" "error"]
     :default "info"}
    {:name "heading"       :kind :string-short}
    {:name "message"       :kind :string-long}
    {:name "icon"          :kind :string-short}
    {:name "dismissible"   :kind :boolean :default false}
    {:name "disabled"      :kind :boolean :default false}
    {:name "timeout-ms"    :kind :number}
    {:name "show-progress" :kind :boolean :default false}]
   ;; x-toast uses the same :host([data-type='X']) indirection as
   ;; x-alert / x-badge, so the active tokens outrank per-type rules
   ;; when set via inline style.
   :css-vars
   [{:var "--x-toast-bg"                :label "Background"    :kind :color}
    {:var "--x-toast-color"             :label "Text"          :kind :color}
    {:var "--x-toast-border-color"      :label "Border"        :kind :color}
    {:var "--x-toast-icon-color"        :label "Icon"          :kind :color}
    {:var "--x-toast-font-size"         :label "Font size"     :kind :length}
    {:var "--x-toast-heading-font-size" :label "Heading size"  :kind :length}
    {:var "--x-toast-heading-weight"    :label "Heading weight" :kind :string}
    {:var "--x-toast-gap"               :label "Gap"           :kind :length}]})

(def ^:private x-toaster
  {:category :feedback
   :label    "Toaster"
   :properties
   [{:name "position"   :kind :enum
     :choices ["top-start" "top-center" "top-end"
               "bottom-start" "bottom-center" "bottom-end"]
     :default "top-end"}
    {:name "max-toasts" :kind :number}
    {:name "label"      :kind :string-short}]
   :css-vars
   [{:var "--x-toaster-gap"       :label "Gap between toasts" :kind :length}
    {:var "--x-toaster-inset"     :label "Edge inset"         :kind :length}
    {:var "--x-toaster-max-width" :label "Max width"          :kind :length}
    {:var "--x-toaster-z-index"   :label "Z-index"            :kind :string}]})

(def ^:private x-table
  {:category :data
   :label    "Table"
   :properties
   [{:name "columns"     :kind :number}
    {:name "row-count"   :kind :number}
    {:name "caption"     :kind :string-short}
    {:name "selectable"  :kind :enum :choices ["none" "single" "multi"]
     :default "none"}
    {:name "striped"     :kind :boolean :default false}
    {:name "bordered"    :kind :boolean :default false}
    {:name "full-width"  :kind :boolean :default false}
    {:name "compact"     :kind :boolean :default false}]
   :css-vars
   [{:var "--x-table-border-color"  :label "Border"         :kind :color}
    {:var "--x-table-stripe-bg"     :label "Stripe bg"      :kind :color}
    {:var "--x-table-row-bg"        :label "Row bg"         :kind :color}
    {:var "--x-table-caption-color" :label "Caption"        :kind :color}
    {:var "--x-table-border-radius" :label "Radius"         :kind :length}
    {:var "--x-table-cell-padding"  :label "Cell padding"   :kind :length}
    {:var "--x-table-compact-padding" :label "Compact padding" :kind :length}]})

(def ^:private x-table-row
  {:category :data
   :label    "Table row"
   :properties
   [{:name "selected"    :kind :boolean :default false}
    {:name "disabled"    :kind :boolean :default false}
    {:name "interactive" :kind :boolean :default false}
    {:name "row-index"   :kind :number}]
   :css-vars
   [{:var "--x-table-row-bg"               :label "Background"      :kind :color}
    {:var "--x-table-row-hover-bg"         :label "Hover"           :kind :color}
    {:var "--x-table-row-selected-bg"      :label "Selected"        :kind :color}
    {:var "--x-table-row-selected-hover-bg" :label "Selected hover" :kind :color}]})

(def ^:private x-table-cell
  {:category :data
   :label    "Table cell"
   :properties
   [{:name "type"           :kind :enum :choices ["data" "header"] :default "data"}
    {:name "scope"          :kind :enum :choices ["col" "row" "colgroup" "rowgroup"]}
    {:name "align"          :kind :enum :choices ["start" "center" "end"]
     :default "start"}
    {:name "valign"         :kind :enum :choices ["top" "middle" "bottom"]
     :default "middle"}
    {:name "col-span"       :kind :number}
    {:name "row-span"       :kind :number}
    {:name "truncate"       :kind :boolean :default false}
    {:name "sticky"         :kind :boolean :default false}
    {:name "sortable"       :kind :boolean :default false}
    {:name "sort-direction" :kind :enum :choices ["asc" "desc"]}
    {:name "disabled"       :kind :boolean :default false}]
   :css-vars
   [{:var "--x-table-cell-bg"           :label "Background"   :kind :color}
    {:var "--x-table-cell-color"        :label "Text"         :kind :color}
    {:var "--x-table-cell-border-color" :label "Border"       :kind :color}
    {:var "--x-table-cell-header-bg"    :label "Header bg"    :kind :color}
    {:var "--x-table-cell-header-color" :label "Header text"  :kind :color}
    {:var "--x-table-cell-padding"      :label "Padding"      :kind :length}
    {:var "--x-table-cell-font-size"    :label "Font size"    :kind :length}
    {:var "--x-table-cell-min-width"    :label "Min width"    :kind :length}
    {:var "--x-table-cell-max-width"    :label "Max width"    :kind :length}]})

(def ^:private x-stat
  {:category :data
   :label    "Stat"
   :properties
   [{:name "label"    :kind :string-short}
    {:name "value"    :kind :string-short}
    {:name "hint"     :kind :string-short}
    {:name "variant"  :kind :enum
     :choices ["default" "subtle" "positive" "warning" "danger"]
     :default "default"}
    {:name "size"     :kind :enum :choices ["sm" "md" "lg"] :default "md"}
    {:name "emphasis" :kind :enum :choices ["normal" "high"] :default "normal"}
    {:name "trend"    :kind :enum :choices ["up" "down" "neutral"]}
    {:name "align"    :kind :enum :choices ["start" "center" "end"]
     :default "start"}
    {:name "loading"  :kind :boolean :default false}]
   :css-vars
   [{:var "--x-stat-background"  :label "Background"    :kind :color}
    {:var "--x-stat-color"       :label "Text"          :kind :color}
    {:var "--x-stat-value-color" :label "Value text"    :kind :color}
    {:var "--x-stat-label-color" :label "Label text"    :kind :color}
    {:var "--x-stat-hint-color"  :label "Hint text"     :kind :color}
    {:var "--x-stat-border-color" :label "Border"       :kind :color}
    {:var "--x-stat-padding"     :label "Padding"       :kind :length}
    {:var "--x-stat-radius"      :label "Radius"        :kind :length}
    {:var "--x-stat-gap"         :label "Gap"           :kind :length}
    {:var "--x-stat-value-size"  :label "Value size"    :kind :length}
    {:var "--x-stat-label-size"  :label "Label size"    :kind :length}]})

(def ^:private x-avatar-group
  {:category :data
   :label    "Avatar group"
   :properties
   [{:name "size"      :kind :enum :choices ["xs" "sm" "md" "lg" "xl"] :default "md"}
    {:name "overlap"   :kind :enum :choices ["none" "sm" "md" "lg"] :default "md"}
    {:name "max"       :kind :number}
    {:name "direction" :kind :enum :choices ["ltr" "rtl"] :default "ltr"}
    {:name "disabled"  :kind :boolean :default false}
    {:name "label"     :kind :string-short}]
   :css-vars
   [{:var "--x-avatar-group-overflow-bg"     :label "Overflow bg"    :kind :color}
    {:var "--x-avatar-group-overflow-color"  :label "Overflow text"  :kind :color}
    {:var "--x-avatar-group-overflow-ring"   :label "Overflow ring"  :kind :color}
    {:var "--x-avatar-group-overflow-border" :label "Overflow border" :kind :color}
    {:var "--x-avatar-group-size"            :label "Size"           :kind :length}
    {:var "--x-avatar-group-font-size"       :label "Overflow size"  :kind :length}]})

(def ^:private x-popover
  {:category :overlay
   :label    "Popover"
   :properties
   [{:name "open"        :kind :boolean :default false}
    {:name "placement"   :kind :enum
     :choices ["bottom-start" "bottom-end" "top-start" "top-end"]
     :default "bottom-start"}
    {:name "heading"     :kind :string-short}
    {:name "close-label" :kind :string-short}
    {:name "no-close"    :kind :boolean :default false}
    {:name "disabled"    :kind :boolean :default false}
    ;; Added in BareDOM 2.3: renders the panel into document.body so it
    ;; escapes its ancestor stacking context. Recommended when the
    ;; popover sits next to decorative / overflow-clipping content.
    {:name "portal"      :kind :boolean :default false}]
   :css-vars
   [{:var "--x-popover-panel-bg"           :label "Panel bg"       :kind :color}
    {:var "--x-popover-panel-border"       :label "Panel border"   :kind :color}
    {:var "--x-popover-heading-color"      :label "Heading text"   :kind :color}
    {:var "--x-popover-body-color"         :label "Body text"      :kind :color}
    {:var "--x-popover-close-color"        :label "Close icon"     :kind :color}
    {:var "--x-popover-arrow-bg"           :label "Arrow bg"       :kind :color}
    {:var "--x-popover-panel-radius"       :label "Panel radius"   :kind :length}
    {:var "--x-popover-panel-max-width"    :label "Panel max-w"    :kind :length}
    {:var "--x-popover-panel-max-height"   :label "Panel max-h"    :kind :length}
    {:var "--x-popover-header-padding"     :label "Header padding" :kind :length}
    {:var "--x-popover-body-padding"       :label "Body padding"   :kind :length}
    {:var "--x-popover-footer-padding"     :label "Footer padding" :kind :length}]})

(def ^:private x-search-field
  {:category :form
   :label    "Search field"
   :properties
   [{:name "value"        :kind :string-short}
    {:name "placeholder"  :kind :string-short}
    {:name "label"        :kind :string-short}
    {:name "name"         :kind :string-short}
    {:name "autocomplete" :kind :enum :choices ["on" "off"] :default "off"}
    {:name "disabled"     :kind :boolean :default false}
    {:name "required"     :kind :boolean :default false}]
   :css-vars
   [{:var "--x-search-field-bg"                :label "Background"    :kind :color}
    {:var "--x-search-field-color"             :label "Text"          :kind :color}
    {:var "--x-search-field-border"            :label "Border"        :kind :color}
    {:var "--x-search-field-icon-color"        :label "Icon"          :kind :color}
    {:var "--x-search-field-clear-color"       :label "Clear icon"    :kind :color}
    {:var "--x-search-field-focus-ring-color"  :label "Focus ring"    :kind :color}
    {:var "--x-search-field-border-radius"     :label "Radius"        :kind :length}]})

(def ^:private x-text-area
  {:category :form
   :label    "Text area"
   :properties
   [{:name "value"       :kind :string-long}
    {:name "placeholder" :kind :string-short}
    {:name "label"       :kind :string-short}
    {:name "hint"        :kind :string-short}
    {:name "error"       :kind :string-short}
    {:name "name"        :kind :string-short}
    {:name "rows"        :kind :number}
    {:name "minlength"   :kind :number}
    {:name "maxlength"   :kind :number}
    {:name "resize"      :kind :enum
     :choices ["none" "vertical" "horizontal" "both"]
     :default "vertical"}
    {:name "disabled"    :kind :boolean :default false}
    {:name "readonly"    :kind :boolean :default false}
    {:name "required"    :kind :boolean :default false}]
   :css-vars
   [{:var "--x-text-area-bg"               :label "Background"    :kind :color}
    {:var "--x-text-area-color"            :label "Text"          :kind :color}
    {:var "--x-text-area-border"           :label "Border"        :kind :color}
    {:var "--x-text-area-label-color"      :label "Label text"    :kind :color}
    {:var "--x-text-area-hint-color"       :label "Hint text"     :kind :color}
    {:var "--x-text-area-error-color"      :label "Error text"    :kind :color}
    {:var "--x-text-area-focus-ring-color" :label "Focus ring"    :kind :color}
    {:var "--x-text-area-border-radius"    :label "Radius"        :kind :length}
    {:var "--x-text-area-padding"          :label "Padding"       :kind :length}
    {:var "--x-text-area-min-height"       :label "Min height"    :kind :length}
    {:var "--x-text-area-font-size"        :label "Font size"     :kind :length}]})

(def ^:private x-color-picker
  {:category :form
   :label    "Color picker"
   :properties
   [{:name "value"    :kind :string-short}
    {:name "alpha"    :kind :boolean :default false}
    {:name "swatches" :kind :string-long}
    {:name "mode"     :kind :enum :choices ["inline" "popover"] :default "popover"}
    {:name "open"     :kind :boolean :default false}
    {:name "disabled" :kind :boolean :default false}
    {:name "readonly" :kind :boolean :default false}
    {:name "name"     :kind :string-short}
    {:name "label"    :kind :string-short}]
   :css-vars
   [{:var "--x-color-picker-bg"           :label "Background"   :kind :color}
    {:var "--x-color-picker-text"         :label "Text"         :kind :color}
    {:var "--x-color-picker-border"       :label "Border"       :kind :color}
    {:var "--x-color-picker-width"        :label "Width"        :kind :length}
    {:var "--x-color-picker-area-height"  :label "Area height"  :kind :length}
    {:var "--x-color-picker-strip-height" :label "Strip height" :kind :length}
    {:var "--x-color-picker-swatch-size"  :label "Swatch size"  :kind :length}
    {:var "--x-color-picker-radius"       :label "Radius"       :kind :length}
    {:var "--x-color-picker-gap"          :label "Gap"          :kind :length}]})

(def ^:private x-date-picker
  {:category :form
   :label    "Date picker"
   :properties
   [{:name "value"       :kind :string-short}
    {:name "mode"        :kind :enum :choices ["single" "range"] :default "single"}
    {:name "start"       :kind :string-short}
    {:name "end"         :kind :string-short}
    {:name "min"         :kind :string-short}
    {:name "max"         :kind :string-short}
    {:name "format"      :kind :string-short}
    {:name "locale"      :kind :string-short}
    {:name "separator"   :kind :string-short}
    {:name "placeholder" :kind :string-short}
    {:name "close-on-select" :kind :boolean :default false}
    {:name "disabled"    :kind :boolean :default false}
    {:name "readonly"    :kind :boolean :default false}
    {:name "required"    :kind :boolean :default false}
    {:name "name"        :kind :string-short}]
   :css-vars
   [{:var "--x-date-picker-input-bg"      :label "Input bg"     :kind :color}
    {:var "--x-date-picker-text"          :label "Text"         :kind :color}
    {:var "--x-date-picker-border"        :label "Border"       :kind :color}
    {:var "--x-date-picker-popover-bg"    :label "Popover bg"   :kind :color}
    {:var "--x-date-picker-selected-bg"   :label "Selected"     :kind :color}
    {:var "--x-date-picker-range-bg"      :label "Range fill"   :kind :color}
    {:var "--x-date-picker-day-hover"     :label "Day hover"    :kind :color}
    {:var "--x-date-picker-radius"        :label "Radius"       :kind :length}
    {:var "--x-date-picker-popover-width" :label "Popover width" :kind :length}]})

(def ^:private x-currency-field
  {:category :form
   :label    "Currency field"
   :properties
   [{:name "value"       :kind :string-short}
    {:name "currency"    :kind :string-short}
    {:name "locale"      :kind :string-short}
    {:name "min"         :kind :number}
    {:name "max"         :kind :number}
    {:name "placeholder" :kind :string-short}
    {:name "label"       :kind :string-short}
    {:name "hint"        :kind :string-short}
    {:name "error"       :kind :string-short}
    {:name "name"        :kind :string-short}
    {:name "disabled"    :kind :boolean :default false}
    {:name "readonly"    :kind :boolean :default false}
    {:name "required"    :kind :boolean :default false}]
   :css-vars
   [{:var "--x-currency-field-bg"               :label "Background"    :kind :color}
    {:var "--x-currency-field-color"            :label "Text"          :kind :color}
    {:var "--x-currency-field-symbol-color"     :label "Symbol"        :kind :color}
    {:var "--x-currency-field-border"           :label "Border"        :kind :color}
    {:var "--x-currency-field-label-color"      :label "Label text"    :kind :color}
    {:var "--x-currency-field-hint-color"       :label "Hint text"     :kind :color}
    {:var "--x-currency-field-error-color"      :label "Error text"    :kind :color}
    {:var "--x-currency-field-focus-ring-color" :label "Focus ring"    :kind :color}
    {:var "--x-currency-field-border-radius"    :label "Radius"        :kind :length}]})

(def ^:private x-form-field
  {:category :form
   :label    "Form field"
   :properties
   [{:name "label"        :kind :string-short}
    {:name "type"         :kind :enum
     :choices ["text" "email" "password" "url" "number" "tel"]
     :default "text"}
    {:name "value"        :kind :string-short}
    {:name "placeholder"  :kind :string-short}
    {:name "hint"         :kind :string-short}
    {:name "error"        :kind :string-short}
    {:name "name"         :kind :string-short}
    {:name "autocomplete" :kind :string-short}
    {:name "disabled"     :kind :boolean :default false}
    {:name "readonly"     :kind :boolean :default false}
    {:name "required"     :kind :boolean :default false}]
   :css-vars
   [{:var "--x-form-field-input-bg"            :label "Input bg"    :kind :color}
    {:var "--x-form-field-input-color"         :label "Input text"  :kind :color}
    {:var "--x-form-field-input-border"        :label "Input border" :kind :color}
    {:var "--x-form-field-label-color"         :label "Label text"  :kind :color}
    {:var "--x-form-field-hint-color"          :label "Hint text"   :kind :color}
    {:var "--x-form-field-error-color"         :label "Error text"  :kind :color}
    {:var "--x-form-field-focus-ring-color"    :label "Focus ring"  :kind :color}
    {:var "--x-form-field-input-padding"       :label "Padding"     :kind :length}
    {:var "--x-form-field-input-border-radius" :label "Radius"      :kind :length}
    {:var "--x-form-field-label-font-size"     :label "Label size"  :kind :length}]})

(def ^:private x-sidebar
  {:category :navigation
   :label    "Sidebar"
   :properties
   [{:name "open"       :kind :boolean :default false}
    {:name "collapsed"  :kind :boolean :default false}
    {:name "placement"  :kind :enum :choices ["left" "right"] :default "left"}
    {:name "variant"    :kind :enum :choices ["docked" "overlay" "modal"]
     :default "docked"}
    {:name "breakpoint" :kind :number}
    {:name "label"      :kind :string-short}]
   :css-vars
   [{:var "--x-sidebar-bg"             :label "Background"      :kind :color}
    {:var "--x-sidebar-fg"             :label "Text"            :kind :color}
    {:var "--x-sidebar-backdrop"       :label "Backdrop"        :kind :color}
    {:var "--x-sidebar-width"          :label "Width"           :kind :length}
    {:var "--x-sidebar-collapsed-width" :label "Collapsed width" :kind :length}]})

(def ^:private x-menu
  {:category :navigation
   :label    "Menu"
   :properties
   [{:name "open"      :kind :boolean :default false}
    {:name "placement" :kind :enum
     :choices ["bottom-start" "bottom-end" "top-start" "top-end"]
     :default "bottom-start"}
    {:name "label"     :kind :string-short}]
   :css-vars
   [{:var "--x-menu-bg"            :label "Background" :kind :color}
    {:var "--x-menu-border"        :label "Border"     :kind :color}
    {:var "--x-menu-border-radius" :label "Radius"     :kind :length}
    {:var "--x-menu-padding"       :label "Padding"    :kind :length}
    {:var "--x-menu-min-width"     :label "Min width"  :kind :length}]})

(def ^:private x-menu-item
  {:category :navigation
   :label    "Menu item"
   :properties
   [{:name "value"    :kind :string-short}
    {:name "disabled" :kind :boolean :default false}
    {:name "variant"  :kind :enum :choices ["" "danger"] :default ""}
    {:name "type"     :kind :enum :choices ["" "divider"] :default ""}]
   :css-vars
   [{:var "--x-menu-item-color"         :label "Text"            :kind :color}
    {:var "--x-menu-item-hover-bg"      :label "Hover bg"        :kind :color}
    {:var "--x-menu-item-focus-bg"      :label "Focus bg"        :kind :color}
    {:var "--x-menu-item-focus-color"   :label "Focus text"      :kind :color}
    {:var "--x-menu-item-danger-color"  :label "Danger text"     :kind :color}
    {:var "--x-menu-item-danger-hover-bg" :label "Danger hover"  :kind :color}
    {:var "--x-menu-item-divider-color" :label "Divider"         :kind :color}
    {:var "--x-menu-item-padding"       :label "Padding"         :kind :length}
    {:var "--x-menu-item-border-radius" :label "Radius"          :kind :length}
    {:var "--x-menu-item-font-size"     :label "Font size"       :kind :length}
    {:var "--x-menu-item-icon-gap"      :label "Icon gap"        :kind :length}]})

(def ^:private x-stepper
  {:category :navigation
   :label    "Stepper"
   :properties
   [{:name "steps"       :kind :string-long}
    {:name "current"     :kind :number}
    {:name "orientation" :kind :enum :choices ["horizontal" "vertical"]
     :default "horizontal"}
    {:name "size"        :kind :enum :choices ["sm" "md" "lg"] :default "md"}
    {:name "disabled"    :kind :boolean :default false}]
   :css-vars
   [{:var "--x-stepper-current-bg"        :label "Current bg"       :kind :color}
    {:var "--x-stepper-current-color"     :label "Current text"     :kind :color}
    {:var "--x-stepper-complete-bg"       :label "Complete bg"      :kind :color}
    {:var "--x-stepper-complete-color"    :label "Complete text"    :kind :color}
    {:var "--x-stepper-complete-connector" :label "Done connector"  :kind :color}
    {:var "--x-stepper-idle-connector"    :label "Idle connector"   :kind :color}
    {:var "--x-stepper-desc-color"        :label "Description text" :kind :color}
    {:var "--x-stepper-indicator-size"    :label "Indicator size"   :kind :length}
    {:var "--x-stepper-connector-thickness" :label "Connector thickness" :kind :length}
    {:var "--x-stepper-font-size"         :label "Font size"        :kind :length}]})

(def ^:private x-pagination
  {:category :navigation
   :label    "Pagination"
   :properties
   [{:name "page"           :kind :number}
    {:name "total-pages"    :kind :number}
    {:name "sibling-count"  :kind :number}
    {:name "boundary-count" :kind :number}
    {:name "size"           :kind :enum :choices ["sm" "md" "lg"] :default "md"}
    {:name "disabled"       :kind :boolean :default false}
    {:name "label"          :kind :string-short}]
   :css-vars
   [{:var "--x-pagination-button-bg"         :label "Button bg"      :kind :color}
    {:var "--x-pagination-button-color"      :label "Button text"    :kind :color}
    {:var "--x-pagination-button-border"     :label "Button border"  :kind :color}
    {:var "--x-pagination-button-hover-bg"   :label "Hover bg"       :kind :color}
    {:var "--x-pagination-button-hover-color" :label "Hover text"    :kind :color}
    {:var "--x-pagination-current-bg"        :label "Current bg"     :kind :color}
    {:var "--x-pagination-current-color"     :label "Current text"   :kind :color}
    {:var "--x-pagination-current-border"    :label "Current border" :kind :color}
    {:var "--x-pagination-ellipsis-color"    :label "Ellipsis"       :kind :color}
    {:var "--x-pagination-button-size"       :label "Button size"    :kind :length}
    {:var "--x-pagination-button-radius"     :label "Radius"         :kind :length}
    {:var "--x-pagination-font-size"         :label "Font size"      :kind :length}
    {:var "--x-pagination-gap"               :label "Gap"            :kind :length}]})

(def ^:private x-command-palette
  {:category :navigation
   :label    "Command palette"
   :properties
   [{:name "open"             :kind :boolean :default false}
    {:name "modal"            :kind :boolean :default false}
    {:name "dismissible"      :kind :boolean :default false}
    {:name "disabled"         :kind :boolean :default false}
    {:name "no-scrim"         :kind :boolean :default false}
    {:name "close-on-scrim"   :kind :boolean :default false}
    {:name "close-on-escape"  :kind :boolean :default false}
    {:name "label"            :kind :string-short}
    {:name "placeholder"      :kind :string-short}
    {:name "empty-text"       :kind :string-short}]
   :css-vars
   [{:var "--x-command-palette-bg"                :label "Background"  :kind :color}
    {:var "--x-command-palette-text"              :label "Text"        :kind :color}
    {:var "--x-command-palette-placeholder"       :label "Placeholder" :kind :color}
    {:var "--x-command-palette-backdrop"          :label "Backdrop"    :kind :color}
    {:var "--x-command-palette-divider"           :label "Divider"     :kind :color}
    {:var "--x-command-palette-group-text"        :label "Group text"  :kind :color}
    {:var "--x-command-palette-item-hover"        :label "Item hover"  :kind :color}
    {:var "--x-command-palette-item-active"       :label "Item active" :kind :color}
    {:var "--x-command-palette-item-active-text"  :label "Active text" :kind :color}
    {:var "--x-command-palette-item-text"         :label "Item text"   :kind :color}
    {:var "--x-command-palette-icon-color"        :label "Icon"        :kind :color}
    {:var "--x-command-palette-empty-text"        :label "Empty text"  :kind :color}
    {:var "--x-command-palette-radius"            :label "Radius"      :kind :length}]})

(def ^:private x-form
  {:category :form
   :label    "Form"
   :properties
   [{:name "loading"      :kind :boolean :default false}
    {:name "novalidate"   :kind :boolean :default false}
    {:name "autocomplete" :kind :enum :choices ["on" "off"] :default "on"}]
   :css-vars
   [{:var "--x-form-gap"   :label "Gap between fields" :kind :length}
    {:var "--x-form-width" :label "Width"              :kind :length}]})

(def ^:private x-fieldset
  {:category :form
   :label    "Fieldset"
   :properties
   [{:name "legend"     :kind :string-short}
    {:name "disabled"   :kind :boolean :default false}
    {:name "aria-label" :kind :string-short}]
   :css-vars
   [{:var "--x-fieldset-bg"                :label "Background"    :kind :color}
    {:var "--x-fieldset-border-color"      :label "Border"        :kind :color}
    {:var "--x-fieldset-legend-color"      :label "Legend text"   :kind :color}
    {:var "--x-fieldset-input-bg"          :label "Input bg"      :kind :color}
    {:var "--x-fieldset-input-border"      :label "Input border"  :kind :color}
    {:var "--x-fieldset-input-color"       :label "Input text"    :kind :color}
    {:var "--x-fieldset-border-radius"     :label "Radius"        :kind :length}
    {:var "--x-fieldset-border-width"      :label "Border width"  :kind :length}
    {:var "--x-fieldset-gap"               :label "Gap"           :kind :length}
    {:var "--x-fieldset-legend-font-size"  :label "Legend size"   :kind :length}
    {:var "--x-fieldset-legend-padding"    :label "Legend padding" :kind :length}]})

(def ^:private x-collapse
  {:category :overlay
   :label    "Collapse"
   :properties
   [{:name "open"        :kind :boolean :default false}
    {:name "disabled"    :kind :boolean :default false}
    {:name "header"      :kind :string-short}
    {:name "duration-ms" :kind :number}]
   :css-vars
   [{:var "--x-collapse-bg"               :label "Background"    :kind :color}
    {:var "--x-collapse-border"           :label "Border"        :kind :color}
    {:var "--x-collapse-trigger-bg"       :label "Trigger bg"    :kind :color}
    {:var "--x-collapse-trigger-bg-hover" :label "Trigger hover" :kind :color}
    {:var "--x-collapse-trigger-color"    :label "Trigger text"  :kind :color}
    {:var "--x-collapse-chevron-color"    :label "Chevron"       :kind :color}
    {:var "--x-collapse-border-radius"    :label "Radius"        :kind :length}
    {:var "--x-collapse-trigger-padding"  :label "Trigger padding" :kind :length}
    {:var "--x-collapse-content-padding"  :label "Content padding" :kind :length}
    {:var "--x-collapse-font-size"        :label "Font size"     :kind :length}]})

(def ^:private x-carousel
  {:category :overlay
   :label    "Carousel"
   :properties
   [{:name "autoplay"   :kind :boolean :default false}
    {:name "interval"   :kind :number}
    {:name "loop"       :kind :boolean :default false}
    {:name "arrows"     :kind :boolean :default true}
    {:name "dots"       :kind :boolean :default true}
    {:name "disabled"   :kind :boolean :default false}
    {:name "current"    :kind :number}
    {:name "transition" :kind :enum :choices ["slide" "fade"] :default "slide"}
    {:name "direction"  :kind :enum :choices ["horizontal" "vertical"]
     :default "horizontal"}
    {:name "peek"       :kind :string-short}
    {:name "aria-label" :kind :string-short}]
   :css-vars
   [{:var "--x-carousel-arrow-bg"        :label "Arrow bg"       :kind :color}
    {:var "--x-carousel-arrow-color"     :label "Arrow text"     :kind :color}
    {:var "--x-carousel-arrow-hover-bg"  :label "Arrow hover"    :kind :color}
    {:var "--x-carousel-dot-color"       :label "Dot"            :kind :color}
    {:var "--x-carousel-dot-active-color" :label "Active dot"    :kind :color}
    {:var "--x-carousel-height"          :label "Height"         :kind :length}
    {:var "--x-carousel-radius"          :label "Radius"         :kind :length}
    {:var "--x-carousel-gap"             :label "Gap"            :kind :length}
    {:var "--x-carousel-arrow-size"      :label "Arrow size"     :kind :length}
    {:var "--x-carousel-dot-size"        :label "Dot size"       :kind :length}]})

(def ^:private x-timeline
  {:category :data
   :label    "Timeline"
   :properties
   [{:name "label"    :kind :string-short}
    {:name "position" :kind :enum :choices ["start" "end" "alternating"]
     :default "start"}
    {:name "striped"  :kind :boolean :default false}]
   :css-vars
   [{:var "--x-timeline-label-color"       :label "Label text"   :kind :color}
    {:var "--x-timeline-gap"               :label "Gap"          :kind :length}
    {:var "--x-timeline-label-font-size"   :label "Label size"   :kind :length}
    {:var "--x-timeline-label-padding"     :label "Label padding" :kind :length}]})

(def ^:private x-timeline-item
  {:category :data
   :label    "Timeline item"
   :properties
   [{:name "label"     :kind :string-short}
    {:name "title"     :kind :string-short}
    {:name "status"    :kind :string-short}
    {:name "icon"      :kind :string-short}
    {:name "connector" :kind :string-short}
    {:name "position"  :kind :string-short}
    {:name "disabled"  :kind :boolean :default false}]
   :css-vars
   [{:var "--x-timeline-item-marker-bg"         :label "Marker bg"     :kind :color}
    {:var "--x-timeline-item-marker-color"      :label "Marker text"   :kind :color}
    {:var "--x-timeline-item-connector-color"   :label "Connector"     :kind :color}
    {:var "--x-timeline-item-label-color"       :label "Label text"    :kind :color}
    {:var "--x-timeline-item-stripe-bg"         :label "Stripe bg"     :kind :color}
    {:var "--x-timeline-item-marker-size"       :label "Marker size"   :kind :length}
    {:var "--x-timeline-item-connector-width"   :label "Connector width" :kind :length}
    {:var "--x-timeline-item-label-font-size"   :label "Label size"    :kind :length}
    {:var "--x-timeline-item-title-font-size"   :label "Title size"    :kind :length}
    {:var "--x-timeline-item-label-width"       :label "Label width"   :kind :length}
    {:var "--x-timeline-item-gap"               :label "Gap"           :kind :length}]})

(def ^:private x-context-menu
  {:category :overlay
   :label    "Context menu"
   :properties
   [{:name "open"      :kind :boolean :default false}
    {:name "disabled"  :kind :boolean :default false}
    {:name "placement" :kind :enum
     :choices ["bottom-start" "bottom-end" "top-start" "top-end"
               "right-start" "left-start"]
     :default "bottom-start"}
    {:name "offset"    :kind :number}
    {:name "z-index"   :kind :number}]
   :css-vars
   [{:var "--x-context-menu-bg"          :label "Background"  :kind :color}
    {:var "--x-context-menu-border"      :label "Border"      :kind :color}
    {:var "--x-context-menu-item-fg"     :label "Item text"   :kind :color}
    {:var "--x-context-menu-item-hover"  :label "Item hover"  :kind :color}
    {:var "--x-context-menu-item-active" :label "Item active" :kind :color}
    {:var "--x-context-menu-separator"   :label "Separator"   :kind :color}
    {:var "--x-context-menu-shadow"      :label "Shadow"      :kind :string}
    {:var "--x-context-menu-radius"      :label "Radius"      :kind :length}]})

(def ^:private x-notification-center
  {:category :feedback
   :label    "Notification center"
   :properties
   [{:name "position" :kind :enum
     :choices ["top-right" "top-left" "bottom-right" "bottom-left"
               "top-center" "bottom-center"]
     :default "top-right"}
    {:name "max"      :kind :number}]
   :css-vars
   [{:var "--x-notification-center-gap"      :label "Gap"        :kind :length}
    {:var "--x-notification-center-width"    :label "Width"      :kind :length}
    {:var "--x-notification-center-offset-x" :label "Offset x"   :kind :length}
    {:var "--x-notification-center-offset-y" :label "Offset y"   :kind :length}
    {:var "--x-notification-center-z-index"  :label "Z-index"    :kind :string}]})

(def ^:private x-cancel-dialogue
  {:category :feedback
   :label    "Cancel dialogue"
   :properties
   [{:name "open"         :kind :boolean :default false}
    {:name "disabled"     :kind :boolean :default false}
    {:name "headline"     :kind :string-short}
    {:name "message"      :kind :string-long}
    {:name "confirm-text" :kind :string-short}
    {:name "cancel-text"  :kind :string-short}
    {:name "danger"       :kind :boolean :default false}]
   :css-vars
   [{:var "--x-cancel-dialogue-bg"              :label "Background"       :kind :color}
    {:var "--x-cancel-dialogue-backdrop-bg"     :label "Backdrop"         :kind :color}
    {:var "--x-cancel-dialogue-confirm-bg"      :label "Confirm bg"       :kind :color}
    {:var "--x-cancel-dialogue-confirm-bg-hover" :label "Confirm hover"   :kind :color}
    {:var "--x-cancel-dialogue-confirm-fg"      :label "Confirm text"     :kind :color}
    {:var "--x-cancel-dialogue-cancel-bg"       :label "Cancel bg"        :kind :color}
    {:var "--x-cancel-dialogue-cancel-bg-hover" :label "Cancel hover"     :kind :color}
    {:var "--x-cancel-dialogue-cancel-fg"       :label "Cancel text"      :kind :color}
    {:var "--x-cancel-dialogue-danger-bg"       :label "Danger bg"        :kind :color}
    {:var "--x-cancel-dialogue-danger-bg-hover" :label "Danger hover"     :kind :color}
    {:var "--x-cancel-dialogue-danger-fg"       :label "Danger text"      :kind :color}
    {:var "--x-cancel-dialogue-btn-height"      :label "Button height"    :kind :length}
    {:var "--x-cancel-dialogue-btn-radius"      :label "Button radius"    :kind :length}
    {:var "--x-cancel-dialogue-btn-font-size"   :label "Button font size" :kind :length}]})

(def ^:private bento-gap-choices
  ["none" "xs" "sm" "md" "lg" "xl"])

(def ^:private x-bento-grid
  {:category :layout
   :label    "Bento grid"
   :properties
   [{:name "columns"    :kind :string-short}
    {:name "gap"        :kind :enum :choices bento-gap-choices :default "md"}
    ;; row-gap / column-gap override the general `gap` for one axis.
    ;; BareDOM's x-bento-grid normalizes these through the same token
    ;; set as `gap` — arbitrary CSS lengths are silently rejected and
    ;; fall through to `gap`. Model as enum, no default so the
    ;; attribute stays absent (= "inherit from gap") until the user
    ;; explicitly picks a token.
    {:name "row-gap"    :kind :enum :choices bento-gap-choices}
    {:name "column-gap" :kind :enum :choices bento-gap-choices}
    {:name "row-height" :kind :string-short}]
   :css-vars
   [{:var "--x-bento-grid-columns"    :label "Columns"    :kind :string}
    {:var "--x-bento-grid-row-gap"    :label "Row gap"    :kind :length}
    {:var "--x-bento-grid-column-gap" :label "Column gap" :kind :length}
    {:var "--x-bento-grid-row-height" :label "Row height" :kind :length}]})

(def ^:private x-bento-item
  {:category :layout
   :label    "Bento item"
   :properties
   [{:name "col-span" :kind :number}
    {:name "row-span" :kind :number}
    {:name "order"    :kind :number}]
   :css-vars []})

(def ^:private x-copy
  {:category :utility
   :label    "Copy"
   :properties
   [{:name "text"            :kind :string-short}
    {:name "from"            :kind :string-short}
    {:name "from-attr"       :kind :string-short}
    {:name "mode"            :kind :enum :choices ["text" "html"] :default "text"}
    {:name "show-tooltip"    :kind :boolean :default true}
    {:name "tooltip-ms"      :kind :number}
    {:name "success-message" :kind :string-short}
    {:name "error-message"   :kind :string-short}
    {:name "hotkey"          :kind :string-short}
    {:name "disabled"        :kind :boolean :default false}]
   :css-vars
   [{:var "--x-copy-tooltip-bg"         :label "Tooltip bg"         :kind :color}
    {:var "--x-copy-tooltip-fg"         :label "Tooltip text"       :kind :color}
    {:var "--x-copy-tooltip-success-bg" :label "Success bg"         :kind :color}
    {:var "--x-copy-tooltip-success-fg" :label "Success text"       :kind :color}
    {:var "--x-copy-tooltip-error-bg"   :label "Error bg"           :kind :color}
    {:var "--x-copy-tooltip-error-fg"   :label "Error text"         :kind :color}
    {:var "--x-copy-tooltip-font-size"  :label "Tooltip font size"  :kind :length}
    {:var "--x-copy-tooltip-padding"    :label "Tooltip padding"    :kind :length}
    {:var "--x-copy-tooltip-radius"     :label "Tooltip radius"     :kind :length}]})

(def ^:private x-particle-button
  {:category :form
   :label    "Particle button"
   :properties
   [{:name "variant"          :kind :enum
     :choices ["primary" "secondary" "tertiary" "ghost" "danger"
               "success" "warning"]
     :default "primary"}
    {:name "size"             :kind :enum :choices ["sm" "md" "lg"] :default "md"}
    {:name "type"             :kind :enum :choices ["button" "submit" "reset"]
     :default "button"}
    {:name "mode"             :kind :enum
     :choices ["dust" "spark" "ember" "burst" "disperse"] :default "dust"}
    {:name "particle-count"   :kind :number}
    {:name "particle-size"    :kind :number}
    {:name "intensity"        :kind :number}
    {:name "reassemble-speed" :kind :number}
    {:name "label"            :kind :string-short}
    {:name "disabled"         :kind :boolean :default false}
    {:name "loading"          :kind :boolean :default false}
    {:name "pressed"          :kind :boolean :default false}]
   :css-vars
   [{:var "--x-particle-button-bg"     :label "Background" :kind :color}
    {:var "--x-particle-button-fg"     :label "Text"       :kind :color}
    {:var "--x-particle-button-border" :label "Border"     :kind :color}]})

(def ^:private x-chart
  {:category :data
   :label    "Chart"
   :properties
   [{:name "type"     :kind :enum :choices ["line" "bar" "area"] :default "line"}
    {:name "data"     :kind :string-long}
    {:name "height"   :kind :string-short}
    {:name "padding"  :kind :string-short}
    {:name "x-format" :kind :string-short}
    {:name "y-format" :kind :string-short}
    {:name "grid"     :kind :boolean :default true}
    {:name "axes"     :kind :boolean :default true}
    {:name "tooltip"  :kind :boolean :default true}
    {:name "cursor"   :kind :enum :choices ["nearest" "x" "none"] :default "nearest"}
    {:name "disabled" :kind :boolean :default false}
    {:name "loading"  :kind :boolean :default false}
    {:name "selected" :kind :string-short}]
   :css-vars
   [{:var "--x-chart-border"           :label "Border"          :kind :color}
    {:var "--x-chart-grid"             :label "Grid"            :kind :color}
    {:var "--x-chart-axis-label"       :label "Axis labels"     :kind :color}
    {:var "--x-chart-crosshair-color"  :label "Crosshair"       :kind :color}
    {:var "--x-chart-tooltip-bg"       :label "Tooltip bg"      :kind :color}
    {:var "--x-chart-tooltip-border"   :label "Tooltip border"  :kind :color}
    {:var "--x-chart-radius"           :label "Radius"          :kind :length}
    {:var "--x-chart-tooltip-font-size" :label "Tooltip font size" :kind :length}
    {:var "--x-chart-crosshair-width"  :label "Crosshair width" :kind :length}]})

(def ^:private x-organic-progress
  {:category :feedback
   :label    "Organic progress"
   :properties
   [{:name "progress" :kind :number}
    {:name "variant"  :kind :enum :choices ["vine" "honeycomb"] :default "vine"}
    {:name "color"    :kind :string-short}
    {:name "bloom"    :kind :boolean :default false}
    {:name "density"  :kind :enum :choices ["sparse" "normal" "dense"] :default "normal"}
    {:name "seed"     :kind :number}
    {:name "label"    :kind :string-short}]
   :css-vars
   [{:var "--x-organic-progress-color-primary"   :label "Primary"   :kind :color}
    {:var "--x-organic-progress-color-secondary" :label "Secondary" :kind :color}
    {:var "--x-organic-progress-bloom-color"     :label "Bloom"     :kind :color}]})

(def ^:private x-kinetic-typography
  {:category :text
   :label    "Kinetic typography"
   :properties
   [{:name "text"         :kind :string-long}
    {:name "preset"       :kind :enum
     :choices ["wave" "circle" "arc" "infinity" "spiral" "sine" "line" "crawl"]
     :default "wave"}
    {:name "animation"    :kind :enum
     :choices ["none" "scroll" "bounce" "oscillate"] :default "scroll"}
    {:name "direction"    :kind :enum :choices ["normal" "reverse"] :default "normal"}
    {:name "speed"        :kind :number}
    {:name "effect"       :kind :string-long}
    {:name "path"         :kind :string-long}
    {:name "font-size"    :kind :string-short}
    {:name "start-size"   :kind :string-short}
    {:name "end-size"     :kind :string-short}
    {:name "repeat"       :kind :boolean :default false}
    {:name "echo-count"   :kind :number}
    {:name "echo-delay"   :kind :number}
    {:name "echo-opacity" :kind :number}
    {:name "echo-scale"   :kind :number}]
   :css-vars
   [{:var "--x-kinetic-typography-color"            :label "Colour"        :kind :color}
    {:var "--x-kinetic-typography-color-shift-from" :label "Shift from"    :kind :color}
    {:var "--x-kinetic-typography-color-shift-to"   :label "Shift to"      :kind :color}
    {:var "--x-kinetic-typography-path-stroke"      :label "Path stroke"   :kind :color}
    {:var "--x-kinetic-typography-font-family"      :label "Font family"   :kind :string}
    {:var "--x-kinetic-typography-font-size"        :label "Font size"     :kind :length}
    {:var "--x-kinetic-typography-font-weight"      :label "Font weight"   :kind :string}
    {:var "--x-kinetic-typography-letter-spacing"   :label "Letter spacing" :kind :length}
    {:var "--x-kinetic-typography-duration"         :label "Duration"      :kind :string}
    {:var "--x-kinetic-typography-path-stroke-width" :label "Stroke width" :kind :length}
    {:var "--x-kinetic-typography-opacity"          :label "Opacity"       :kind :string}]})

(def ^:private x-kinetic-font
  {:category :text
   :label    "Kinetic font"
   :properties
   [{:name "text"        :kind :string-long}
    {:name "trigger"     :kind :enum :choices ["cursor" "scroll" "both"]
     :default "cursor"}
    {:name "mode"        :kind :string-short}
    {:name "per-char"    :kind :boolean :default false}
    {:name "mass"        :kind :number}
    {:name "tension"     :kind :number}
    {:name "friction"    :kind :number}
    {:name "intensity"   :kind :number}
    {:name "radius"      :kind :number}
    {:name "font-family" :kind :string-short}]
   :css-vars []})

(def ^:private x-organic-divider
  {:category :layout
   :label    "Organic divider"
   :properties
   [{:name "shape"     :kind :enum
     :choices ["wave" "waves" "blob-edge" "mountain" "drip" "slant" "scallop" "cloud"]
     :default "wave"}
    {:name "layers"    :kind :number}
    {:name "height"    :kind :string-short}
    {:name "flip"      :kind :boolean :default false}
    {:name "mirror"    :kind :boolean :default false}
    {:name "animation" :kind :enum :choices ["none" "drift" "morph"] :default "none"}
    {:name "path"      :kind :string-long}]
   :css-vars
   [{:var "--x-organic-divider-color"             :label "Colour"          :kind :color}
    {:var "--x-organic-divider-height"            :label "Height"          :kind :length}
    {:var "--x-organic-divider-animate-duration"  :label "Animate duration" :kind :string}]})

(def ^:private x-file-download
  {:category :utility
   :label    "File download"
   :properties
   [{:name "href"       :kind :url}
    {:name "filename"   :kind :string-short}
    {:name "disabled"   :kind :boolean :default false}
    {:name "aria-label" :kind :string-short}]
   :css-vars
   [{:var "--x-file-download-bg"           :label "Background"   :kind :color}
    {:var "--x-file-download-color"        :label "Text"         :kind :color}
    {:var "--x-file-download-hover-bg"     :label "Hover bg"     :kind :color}
    {:var "--x-file-download-active-bg"    :label "Active bg"    :kind :color}
    {:var "--x-file-download-padding"      :label "Padding"      :kind :length}
    {:var "--x-file-download-border-radius" :label "Radius"      :kind :length}
    {:var "--x-file-download-font-size"    :label "Font size"    :kind :length}
    {:var "--x-file-download-icon-size"    :label "Icon size"    :kind :length}
    {:var "--x-file-download-gap"          :label "Gap"          :kind :length}]})

;; ============================================================
;; Decorative effects + scroll components — light augmentation
;; These 16 have rich behavioural attributes but few visually
;; meaningful CSS variables. The curation surfaces their attribute
;; knobs as typed inspector editors and exposes a small set of
;; universally-applicable css-vars when available.
;; ============================================================

(def ^:private x-gaussian-blur
  {:category :effects
   :label    "Gaussian blur"
   :properties
   [{:name "colors"    :kind :string-long}
    {:name "blur"      :kind :number}
    {:name "speed"     :kind :number}
    {:name "count"     :kind :number}
    {:name "size"      :kind :number}
    {:name "opacity"   :kind :number}
    {:name "animation" :kind :enum :choices ["float" "pulse" "none"] :default "float"}
    {:name "blend"     :kind :enum
     :choices ["normal" "multiply" "screen" "overlay" "soft-light"]
     :default "normal"}
    {:name "paused"    :kind :boolean :default false}]
   :css-vars []})

(def ^:private x-organic-shape
  {:category :effects
   :label    "Organic shape"
   :properties
   [{:name "shape"     :kind :enum
     :choices ["blob-1" "blob-2" "blob-3" "pebble" "leaf" "droplet" "cloud" "wave"]
     :default "blob-1"}
    {:name "path"      :kind :string-long}
    {:name "animation" :kind :enum :choices ["none" "morph" "pulse" "float" "spin"]
     :default "none"}
    {:name "ratio"     :kind :string-short}
    {:name "width"     :kind :string-short}
    {:name "height"    :kind :string-short}]
   :css-vars []})

(def ^:private x-ripple-effect
  {:category :effects
   :label    "Ripple effect"
   :properties
   [{:name "intensity" :kind :number}
    {:name "duration"  :kind :number}
    {:name "frequency" :kind :number}
    {:name "disabled"  :kind :boolean :default false}]
   :css-vars []})

(def ^:private x-splash
  {:category :effects
   :label    "Splash"
   :properties
   [{:name "active"   :kind :boolean :default false}
    {:name "variant"  :kind :enum :choices ["default" "branded" "minimal"]
     :default "default"}
    {:name "progress" :kind :number}
    {:name "spinner"  :kind :boolean :default false}
    {:name "overlay"  :kind :enum :choices ["solid" "blur" "transparent"]
     :default "solid"}]
   :css-vars []})

(def ^:private x-metaball-cursor
  {:category :effects
   :label    "Metaball cursor"
   :properties
   [{:name "blob-count"      :kind :number}
    {:name "blob-size"       :kind :number}
    {:name "color"           :kind :string-short}
    {:name "noise"           :kind :boolean :default false}
    {:name "noise-scale"     :kind :number}
    {:name "noise-speed"     :kind :number}
    {:name "noise-intensity" :kind :number}
    {:name "blur"            :kind :number}
    {:name "threshold"       :kind :number}
    {:name "palette"         :kind :string-long}]
   :css-vars []})

(def ^:private x-neural-glow
  {:category :effects
   :label    "Neural glow"
   :properties
   [{:name "orb-count"           :kind :number}
    {:name "color-primary"       :kind :string-short}
    {:name "color-secondary"     :kind :string-short}
    {:name "color-background"    :kind :string-short}
    {:name "pulse-speed"         :kind :number}
    {:name "rest-rate"           :kind :number}
    {:name "connection-distance" :kind :number}
    {:name "orb-size"            :kind :number}
    {:name "opacity"             :kind :number}
    {:name "interactive"         :kind :boolean :default false}]
   :css-vars []})

(def ^:private x-soft-body
  {:category :effects
   :label    "Soft body"
   :properties
   [{:name "stiffness"   :kind :number}
    {:name "damping"     :kind :number}
    {:name "radius"      :kind :number}
    {:name "intensity"   :kind :number}
    {:name "grab-radius" :kind :number}
    {:name "disabled"    :kind :boolean :default false}]
   :css-vars []})

(def ^:private x-liquid-glass
  {:category :effects
   :label    "Liquid glass"
   :properties
   [{:name "blobs"              :kind :number}
    {:name "speed"              :kind :number}
    {:name "amplitude"          :kind :number}
    {:name "blur"               :kind :number}
    {:name "goo"                :kind :number}
    {:name "tint"               :kind :string-short}
    {:name "specular"           :kind :boolean :default false}
    {:name "specular-size"      :kind :number}
    {:name "specular-intensity" :kind :number}
    {:name "frost"              :kind :number}
    {:name "mode"               :kind :string-short}
    {:name "color-1"            :kind :string-short}
    {:name "color-2"            :kind :string-short}
    {:name "disabled"           :kind :boolean :default false}]
   :css-vars []})

(def ^:private x-liquid-dock
  {:category :effects
   :label    "Liquid dock"
   :properties
   [{:name "position"        :kind :enum :choices ["bottom" "top" "left" "right"]
     :default "bottom"}
    {:name "gap"             :kind :number}
    {:name "blur"            :kind :number}
    {:name "threshold"       :kind :number}
    {:name "ripple-scale"    :kind :number}
    {:name "ripple-speed"    :kind :number}
    {:name "color"           :kind :string-short}
    {:name "magnet-radius"   :kind :number}
    {:name "magnet-strength" :kind :number}
    {:name "bob-intensity"   :kind :number}
    {:name "disabled"        :kind :boolean :default false}]
   :css-vars []})

(def ^:private x-liquid-fill
  {:category :effects
   :label    "Liquid fill"
   :properties
   [{:name "target"           :kind :string-short}
    {:name "orientation"      :kind :enum :choices ["vertical" "horizontal"]
     :default "vertical"}
    {:name "mode"             :kind :enum :choices ["fill" "bar"] :default "fill"}
    {:name "theme"            :kind :enum :choices ["gold" "water" "lava" "custom"]
     :default "gold"}
    {:name "wave-intensity"   :kind :number}
    {:name "splash-intensity" :kind :number}
    {:name "layers"           :kind :number}
    {:name "disabled"         :kind :boolean :default false}]
   :css-vars []})

(def ^:private x-morph-stack
  {:category :effects
   :label    "Morph stack"
   :properties
   [{:name "active-state" :kind :string-short}
    {:name "active-index" :kind :number}
    {:name "variant"      :kind :enum :choices ["clean" "organic" "liquid"]
     :default "clean"}
    {:name "stiffness"    :kind :number}
    {:name "damping"      :kind :number}
    {:name "mass"         :kind :number}
    {:name "duration"     :kind :number}
    {:name "disabled"     :kind :boolean :default false}]
   :css-vars []})

(def ^:private x-scroll
  {:category :scroll
   :label    "Scroll"
   :properties
   [{:name "mode"            :kind :enum :choices ["horizontal" "vertical"]
     :default "horizontal"}
    {:name "snap"            :kind :enum :choices ["none" "start" "center" "end"]
     :default "none"}
    {:name "loop"            :kind :boolean :default false}
    {:name "auto-play"       :kind :boolean :default false}
    {:name "interval"        :kind :number}
    {:name "show-controls"   :kind :boolean :default false}
    {:name "show-indicators" :kind :boolean :default false}
    {:name "active-index"    :kind :number}
    {:name "gap"             :kind :string-short}
    {:name "disabled"        :kind :boolean :default false}
    {:name "label"           :kind :string-short}]
   :css-vars []})

(def ^:private x-scroll-parallax
  {:category :scroll
   :label    "Scroll parallax"
   :properties
   [{:name "direction" :kind :enum :choices ["vertical" "horizontal"]
     :default "vertical"}
    {:name "source"    :kind :enum :choices ["document"] :default "document"}
    {:name "easing"    :kind :enum :choices ["none" "smooth"] :default "none"}
    {:name "disabled"  :kind :boolean :default false}
    {:name "label"     :kind :string-short}]
   :css-vars []})

(def ^:private x-scroll-story
  {:category :scroll
   :label    "Scroll story"
   :properties
   [{:name "layout"             :kind :enum :choices ["left" "right" "top"]
     :default "left"}
    {:name "threshold"          :kind :number}
    {:name "split"              :kind :string-short}
    {:name "autoplay"           :kind :boolean :default false}
    {:name "autoplay-speed"     :kind :number}
    {:name "autoplay-loop"      :kind :boolean :default false}
    {:name "autoplay-indicator" :kind :boolean :default false}
    {:name "disabled"           :kind :boolean :default false}
    {:name "label"              :kind :string-short}]
   :css-vars []})

(def ^:private x-scroll-stack
  {:category :scroll
   :label    "Scroll stack"
   :properties
   [{:name "peek"            :kind :number}
    {:name "rotation"        :kind :number}
    {:name "scroll-distance" :kind :number}
    {:name "align"           :kind :string-short}
    {:name "disabled"        :kind :boolean :default false}]
   :css-vars []})

(def ^:private x-scroll-timeline
  {:category :scroll
   :label    "Scroll timeline"
   :properties
   [{:name "layout"             :kind :enum :choices ["alternating" "left" "right"]
     :default "alternating"}
    {:name "track"              :kind :enum :choices ["straight" "curved"]
     :default "straight"}
    {:name "marker"             :kind :enum :choices ["dot" "ring" "none"]
     :default "dot"}
    {:name "threshold"          :kind :number}
    {:name "no-progress"        :kind :boolean :default false}
    {:name "autoplay"           :kind :boolean :default false}
    {:name "autoplay-speed"     :kind :number}
    {:name "autoplay-loop"      :kind :boolean :default false}
    {:name "autoplay-indicator" :kind :boolean :default false}
    {:name "disabled"           :kind :boolean :default false}
    {:name "label"              :kind :string-short}]
   :css-vars []})

(def ^:private x-icon
  {:category :data
   :label    "Icon"
   :raw-html-slot? true
   :properties
   [{:name "size" :kind :string-short}
    {:name "color" :kind :string-short}
    {:name "label" :kind :string-short}]})

(def ^:private x-image
  {:category :data
   :label    "Image"
   :properties
   [{:name "src" :kind :url}
    {:name "alt" :kind :string-short}
    {:name "decorative" :kind :boolean :default false}
    {:name "ratio" :kind :string-short}
    {:name "fit" :kind :enum :choices ["cover" "contain" "fill" "scale-down" "none"] :default "cover"}
    {:name "position" :kind :string-short}
    {:name "loading" :kind :enum :choices ["lazy" "eager"] :default "lazy"}]})

(def ^:private x-tooltip
  {:category :overlay
   :label    "Tooltip"
   :properties
   [{:name "text"      :kind :string-short}
    {:name "placement" :kind :enum
     :choices ["top" "bottom" "left" "right"]
     :default "top"}
    {:name "delay"     :kind :number :default 400}
    {:name "disabled"  :kind :boolean :default false}
    {:name "open"      :kind :boolean :default false}]})

(def ^:private x-combobox
  {:category :form
   :label    "Combobox"
   :properties
   [{:name "value"       :kind :string-short}
    {:name "placeholder" :kind :string-short}
    {:name "name"        :kind :string-short}
    {:name "disabled"    :kind :boolean :default false}
    {:name "required"    :kind :boolean :default false}
    {:name "open"        :kind :boolean :default false}
    {:name "placement"   :kind :enum
     :choices ["bottom-start" "bottom-end" "top-start" "top-end"]
     :default "bottom-start"}]})

(def ^:private x-skeleton-group
  {:category :feedback
   :label    "Skeleton group"
   :properties
   [{:name "preset"    :kind :enum
     :choices ["card" "list-item" "paragraph" "table-row" "profile"]}
    {:name "animation" :kind :enum
     :choices ["pulse" "wave" "none"]
     :default "pulse"}
    {:name "count"     :kind :number :default 1}]})

(def ^:private x-welcome-tour
  {:category :overlay
   :label    "Welcome tour"
   :properties
   [{:name "open" :kind :boolean}
    {:name "step" :kind :number}
    {:name "connector" :kind :enum
     :choices ["arrow" "line" "curve" "none"]}
    {:name "prev-label" :kind :string-short}
    {:name "next-label" :kind :string-short}
    {:name "done-label" :kind :string-short}
    {:name "skip-label" :kind :string-short}
    {:name "counter" :kind :boolean}
    {:name "dots" :kind :boolean}]})

(def ^:private x-file-upload
  {:category :form
   :label    "File upload"
   :properties
   [{:name "accept" :kind :string-short}
    {:name "multiple" :kind :boolean}
    {:name "max-size" :kind :string-short}
    {:name "max-files" :kind :string-short}
    {:name "disabled" :kind :boolean}
    {:name "required" :kind :boolean}
    {:name "name" :kind :string-short}]})

(def ^:private x-kinetic-canvas
  {:category :effects
   :label    "Kinetic canvas"
   :properties
   [{:name "type"       :kind :enum
     :choices ["starfield" "bubbles" "matrix"] :default "starfield"}
    {:name "variant"    :kind :enum
     :choices ["motion" "twinkle"]}
    {:name "speed"      :kind :string-short}
    {:name "density"    :kind :string-short}
    {:name "fullscreen" :kind :boolean}
    {:name "paused"     :kind :boolean}]})

(def ^:private x-i18n-provider
  {:category :utility
   :label    "I18n provider"
   :properties
   [{:name "src" :kind :url}
    {:name "locale" :kind :string-short}
    {:name "fallback-locale" :kind :string-short}]})

(def ^:private x-i18n
  {:category :text
   :label    "I18n"
   :properties
   [{:name "key" :kind :string-short}
    {:name "params" :kind :string-short}]})

(def ^:private x-multi-combobox
  {:category :form
   :label    "Multi combobox"
   :properties
   [{:name "value"       :kind :string-short}
    {:name "placeholder" :kind :string-short}
    {:name "name"        :kind :string-short}
    {:name "disabled"    :kind :boolean}
    {:name "required"    :kind :boolean}
    {:name "open"        :kind :boolean}
    {:name "placement"   :kind :enum
     :choices ["bottom-start" "bottom-end" "top-start" "top-end"]
     :default "bottom-start"}
    {:name "max"         :kind :number}]})

(def augment
  "{tag-name → augmentation-map}. Hand-curated. Omissions are intentional —
   tags not present here fall through to the raw-attribute inspector."
  {"x-button"           x-button
   "x-card"             x-card
   "x-container"        x-container
   "x-grid"             x-grid
   "x-navbar"           x-navbar
   "x-typography"       x-typography
   "x-switch"           x-switch
   "x-alert"            x-alert
   "x-checkbox"         x-checkbox
   "x-radio"            x-radio
   "x-slider"           x-slider
   "x-select"           x-select
   "x-modal"            x-modal
   "x-badge"            x-badge
   "x-chip"             x-chip
   "x-avatar"           x-avatar
   "x-drawer"           x-drawer
   "x-tabs"             x-tabs
   "x-tab"              x-tab
   "x-breadcrumbs"      x-breadcrumbs
   "x-dropdown"         x-dropdown
   "x-divider"          x-divider
   "x-spacer"           x-spacer
   "x-progress"         x-progress
   "x-progress-circle"  x-progress-circle
   "x-spinner"          x-spinner
   "x-skeleton"         x-skeleton
   "x-toast"            x-toast
   "x-toaster"          x-toaster
   "x-table"            x-table
   "x-table-row"        x-table-row
   "x-table-cell"       x-table-cell
   "x-stat"             x-stat
   "x-avatar-group"     x-avatar-group
   "x-popover"          x-popover
   "x-search-field"     x-search-field
   "x-text-area"        x-text-area
   "x-color-picker"     x-color-picker
   "x-date-picker"      x-date-picker
   "x-currency-field"   x-currency-field
   "x-form-field"       x-form-field
   "x-sidebar"          x-sidebar
   "x-menu"             x-menu
   "x-menu-item"        x-menu-item
   "x-stepper"          x-stepper
   "x-pagination"       x-pagination
   "x-command-palette"  x-command-palette
   "x-form"               x-form
   "x-fieldset"           x-fieldset
   "x-collapse"           x-collapse
   "x-carousel"           x-carousel
   "x-timeline"           x-timeline
   "x-timeline-item"      x-timeline-item
   "x-context-menu"       x-context-menu
   "x-notification-center" x-notification-center
   "x-cancel-dialogue"    x-cancel-dialogue
   "x-bento-grid"         x-bento-grid
   "x-bento-item"         x-bento-item
   "x-copy"               x-copy
   "x-particle-button"    x-particle-button
   "x-chart"              x-chart
   "x-organic-progress"   x-organic-progress
   "x-kinetic-typography" x-kinetic-typography
   "x-kinetic-font"       x-kinetic-font
   "x-organic-divider"    x-organic-divider
   "x-file-download"      x-file-download
   "x-gaussian-blur"      x-gaussian-blur
   "x-organic-shape"      x-organic-shape
   "x-ripple-effect"      x-ripple-effect
   "x-splash"             x-splash
   "x-metaball-cursor"    x-metaball-cursor
   "x-neural-glow"        x-neural-glow
   "x-soft-body"          x-soft-body
   "x-liquid-glass"       x-liquid-glass
   "x-liquid-dock"        x-liquid-dock
   "x-liquid-fill"        x-liquid-fill
   "x-morph-stack"        x-morph-stack
   "x-scroll"             x-scroll
   "x-scroll-parallax"    x-scroll-parallax
   "x-scroll-story"       x-scroll-story
   "x-scroll-stack"       x-scroll-stack
   "x-scroll-timeline"    x-scroll-timeline
   "x-icon"               x-icon
   "x-image"              x-image
   "x-tooltip"            x-tooltip
   "x-combobox"           x-combobox
   "x-skeleton-group"     x-skeleton-group
   "x-welcome-tour"       x-welcome-tour
   "x-file-upload"        x-file-upload
   "x-kinetic-canvas"     x-kinetic-canvas
   "x-i18n-provider"      x-i18n-provider
   "x-i18n"               x-i18n
   "x-multi-combobox"     x-multi-combobox})
