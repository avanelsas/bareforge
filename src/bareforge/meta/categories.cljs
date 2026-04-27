(ns bareforge.meta.categories
  "Flat tag → category map for every BareDOM component. Drives the
   palette grouping in `bareforge.ui.palette`. Augmented entries in
   `bareforge.meta.augment` may override the category if they want
   something different; otherwise the registry uses this map. Adding
   a new BareDOM component without a category here lands it under
   `:other`, which the palette test catches.")

(def tag->category
  "{tag-name → category keyword}. Categories are stable, ordered keys
   the palette knows about. Keep this list in sync with the
   `bareforge.ui.palette/category-order` vector."
  {;; --- layout ----------------------------------------------------------
   "x-container"          :layout
   "x-grid"               :layout
   "x-card"               :layout
   "x-divider"            :layout
   "x-spacer"             :layout
   "x-bento-grid"         :layout
   "x-bento-item"         :layout
   "x-organic-divider"    :layout

   ;; --- navigation ------------------------------------------------------
   "x-navbar"             :navigation
   "x-sidebar"            :navigation
   "x-tabs"               :navigation
   "x-tab"                :navigation
   "x-menu"               :navigation
   "x-menu-item"          :navigation
   "x-dropdown"           :navigation
   "x-breadcrumbs"        :navigation
   "x-stepper"            :navigation
   "x-pagination"         :navigation
   "x-command-palette"    :navigation

   ;; --- form ------------------------------------------------------------
   "x-button"             :form
   "x-particle-button"    :form
   "x-switch"             :form
   "x-checkbox"           :form
   "x-radio"              :form
   "x-slider"             :form
   "x-select"             :form
   "x-search-field"       :form
   "x-text-area"          :form
   "x-currency-field"     :form
   "x-color-picker"       :form
   "x-date-picker"        :form
   "x-form"               :form
   "x-form-field"         :form
   "x-fieldset"           :form

   ;; --- text ------------------------------------------------------------
   "x-typography"         :text
   "x-kinetic-typography" :text
   "x-kinetic-font"       :text

   ;; --- data ------------------------------------------------------------
   "x-table"              :data
   "x-table-row"          :data
   "x-table-cell"         :data
   "x-chart"              :data
   "x-stat"               :data
   "x-timeline"           :data
   "x-timeline-item"      :data
   "x-avatar"             :data
   "x-avatar-group"       :data

   ;; --- feedback --------------------------------------------------------
   "x-alert"              :feedback
   "x-toast"              :feedback
   "x-toaster"            :feedback
   "x-badge"              :feedback
   "x-chip"               :feedback
   "x-progress"           :feedback
   "x-progress-circle"    :feedback
   "x-organic-progress"   :feedback
   "x-spinner"            :feedback
   "x-skeleton"           :feedback
   "x-notification-center" :feedback
   "x-cancel-dialogue"    :feedback

   ;; --- overlay ---------------------------------------------------------
   "x-modal"              :overlay
   "x-drawer"             :overlay
   "x-popover"            :overlay
   "x-context-menu"       :overlay
   "x-collapse"           :overlay
   "x-carousel"           :overlay

   ;; --- effects ---------------------------------------------------------
   "x-gaussian-blur"      :effects
   "x-organic-shape"      :effects
   "x-ripple-effect"      :effects
   "x-splash"             :effects
   "x-metaball-cursor"    :effects
   "x-neural-glow"        :effects
   "x-soft-body"          :effects
   "x-liquid-glass"       :effects
   "x-liquid-dock"        :effects
   "x-liquid-fill"        :effects
   "x-morph-stack"        :effects

   ;; --- scroll ----------------------------------------------------------
   "x-scroll"             :scroll
   "x-scroll-parallax"    :scroll
   "x-scroll-story"       :scroll
   "x-scroll-stack"       :scroll
   "x-scroll-timeline"    :scroll

   ;; --- utility ---------------------------------------------------------
   "x-copy"               :utility
   "x-file-download"      :utility
   "x-theme"              :utility
   "x-icon"               :data
   "x-image"              :data
   "x-tooltip"            :overlay
   "x-combobox"           :form
   "x-skeleton-group"     :feedback
   "x-welcome-tour"       :overlay
   "x-file-upload"        :form})

(defn category-for
  "Return the category keyword for `tag`, or `:other` when unknown."
  [tag]
  (get tag->category tag :other))
