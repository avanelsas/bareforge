(ns bareforge.meta.public-api
  "The authoritative map of {tag-name → public-api} for BareDOM. The
   pinned version is centralised in `bareforge.meta.versions/baredom-version`
   (and enforced against `deps.edn` by `bareforge.meta.versions-test`).

   Design note: we require each component's `model` namespace directly
   rather than going through `baredom.exports.x-*`. The exports wrappers
   also pull in the element-definition files, which run side-effectful
   top-level code (`js/document`, `customElements/define`) that crashes
   inside a Node test runtime. Model files are pure data — they never
   touch browser globals — so they load cleanly in both node and browser.

   When BareDOM eventually exposes a single `all-public-apis` map in
   `baredom.exports.all`, this whole namespace collapses to a one-line
   alias (and we revisit the test-time loading question). Until then,
   every new BareDOM component bump needs one require and one api-map
   entry added below. The `expected-tag-count` test catches drift."
  (:require [baredom.components.x-alert.model               :as x-alert]
            [baredom.components.x-avatar.model              :as x-avatar]
            [baredom.components.x-avatar-group.model        :as x-avatar-group]
            [baredom.components.x-badge.model               :as x-badge]
            [baredom.components.x-bento-grid.model          :as x-bento-grid]
            [baredom.components.x-bento-item.model          :as x-bento-item]
            [baredom.components.x-breadcrumbs.model         :as x-breadcrumbs]
            [baredom.components.x-button.model              :as x-button]
            [baredom.components.x-cancel-dialogue.model     :as x-cancel-dialogue]
            [baredom.components.x-card.model                :as x-card]
            [baredom.components.x-carousel.model            :as x-carousel]
            [baredom.components.x-chart.model               :as x-chart]
            [baredom.components.x-checkbox.model            :as x-checkbox]
            [baredom.components.x-chip.model                :as x-chip]
            [baredom.components.x-collapse.model            :as x-collapse]
            [baredom.components.x-color-picker.model        :as x-color-picker]
            [baredom.components.x-combobox.model          :as x-combobox]
            [baredom.components.x-command-palette.model     :as x-command-palette]
            [baredom.components.x-container.model           :as x-container]
            [baredom.components.x-context-menu.model        :as x-context-menu]
            [baredom.components.x-copy.model                :as x-copy]
            [baredom.components.x-currency-field.model      :as x-currency-field]
            [baredom.components.x-date-picker.model         :as x-date-picker]
            [baredom.components.x-divider.model             :as x-divider]
            [baredom.components.x-drawer.model              :as x-drawer]
            [baredom.components.x-dropdown.model            :as x-dropdown]
            [baredom.components.x-fieldset.model            :as x-fieldset]
            [baredom.components.x-file-download.model       :as x-file-download]
            [baredom.components.x-file-upload.model       :as x-file-upload]
            [baredom.components.x-form.model                :as x-form]
            [baredom.components.x-form-field.model          :as x-form-field]
            [baredom.components.x-gaussian-blur.model       :as x-gaussian-blur]
            [baredom.components.x-grid.model                :as x-grid]
            [baredom.components.x-icon.model              :as x-icon]
            [baredom.components.x-image.model             :as x-image]
            [baredom.components.x-kinetic-font.model        :as x-kinetic-font]
            [baredom.components.x-kinetic-typography.model  :as x-kinetic-typography]
            [baredom.components.x-liquid-dock.model         :as x-liquid-dock]
            [baredom.components.x-liquid-fill.model         :as x-liquid-fill]
            [baredom.components.x-liquid-glass.model        :as x-liquid-glass]
            [baredom.components.x-menu.model                :as x-menu]
            [baredom.components.x-menu-item.model           :as x-menu-item]
            [baredom.components.x-metaball-cursor.model     :as x-metaball-cursor]
            [baredom.components.x-modal.model               :as x-modal]
            [baredom.components.x-morph-stack.model         :as x-morph-stack]
            [baredom.components.x-navbar.model              :as x-navbar]
            [baredom.components.x-neural-glow.model         :as x-neural-glow]
            [baredom.components.x-notification-center.model :as x-notification-center]
            [baredom.components.x-organic-divider.model     :as x-organic-divider]
            [baredom.components.x-organic-progress.model    :as x-organic-progress]
            [baredom.components.x-organic-shape.model       :as x-organic-shape]
            [baredom.components.x-pagination.model          :as x-pagination]
            [baredom.components.x-particle-button.model     :as x-particle-button]
            [baredom.components.x-popover.model             :as x-popover]
            [baredom.components.x-progress.model            :as x-progress]
            [baredom.components.x-progress-circle.model     :as x-progress-circle]
            [baredom.components.x-radio.model               :as x-radio]
            [baredom.components.x-ripple-effect.model       :as x-ripple-effect]
            [baredom.components.x-scroll.model              :as x-scroll]
            [baredom.components.x-scroll-parallax.model     :as x-scroll-parallax]
            [baredom.components.x-scroll-stack.model        :as x-scroll-stack]
            [baredom.components.x-scroll-story.model        :as x-scroll-story]
            [baredom.components.x-scroll-timeline.model     :as x-scroll-timeline]
            [baredom.components.x-search-field.model        :as x-search-field]
            [baredom.components.x-select.model              :as x-select]
            [baredom.components.x-sidebar.model             :as x-sidebar]
            [baredom.components.x-skeleton.model            :as x-skeleton]
            [baredom.components.x-skeleton-group.model    :as x-skeleton-group]
            [baredom.components.x-slider.model              :as x-slider]
            [baredom.components.x-soft-body.model           :as x-soft-body]
            [baredom.components.x-spacer.model              :as x-spacer]
            [baredom.components.x-spinner.model             :as x-spinner]
            [baredom.components.x-splash.model              :as x-splash]
            [baredom.components.x-stat.model                :as x-stat]
            [baredom.components.x-stepper.model             :as x-stepper]
            [baredom.components.x-switch.model              :as x-switch]
            [baredom.components.x-tab.model                 :as x-tab]
            [baredom.components.x-table.model               :as x-table]
            [baredom.components.x-table-cell.model          :as x-table-cell]
            [baredom.components.x-table-row.model           :as x-table-row]
            [baredom.components.x-tabs.model                :as x-tabs]
            [baredom.components.x-text-area.model           :as x-text-area]
            [baredom.components.x-theme.model               :as x-theme]
            [baredom.components.x-timeline.model            :as x-timeline]
            [baredom.components.x-timeline-item.model       :as x-timeline-item]
            [baredom.components.x-toast.model               :as x-toast]
            [baredom.components.x-toaster.model             :as x-toaster]
            [baredom.components.x-tooltip.model           :as x-tooltip]
            [baredom.components.x-typography.model          :as x-typography]
            [baredom.components.x-welcome-tour.model      :as x-welcome-tour]))

(defn- api
  "Build a public-api map from a component's model vars."
  [tag-name properties observed]
  {:tag-name            tag-name
   :properties          properties
   :observed-attributes observed})

(def api-map
  "{tag-name → public-api}. Keys are exact HTML element names reported by
   each model's `tag-name`. Values are the minimal subset of data the
   Bareforge inspector actually consumes (:tag-name, :properties,
   :observed-attributes). Event schemas are omitted — they are not yet
   consumed anywhere in the builder."
  {x-alert/tag-name              (api x-alert/tag-name              x-alert/property-api              x-alert/observed-attributes)
   x-avatar/tag-name             (api x-avatar/tag-name             x-avatar/property-api             x-avatar/observed-attributes)
   x-avatar-group/tag-name       (api x-avatar-group/tag-name       x-avatar-group/property-api       x-avatar-group/observed-attributes)
   x-badge/tag-name              (api x-badge/tag-name              x-badge/property-api              x-badge/observed-attributes)
   x-bento-grid/tag-name         (api x-bento-grid/tag-name         x-bento-grid/property-api         x-bento-grid/observed-attributes)
   x-bento-item/tag-name         (api x-bento-item/tag-name         x-bento-item/property-api         x-bento-item/observed-attributes)
   x-breadcrumbs/tag-name        (api x-breadcrumbs/tag-name        x-breadcrumbs/property-api        x-breadcrumbs/observed-attributes)
   x-button/tag-name             (api x-button/tag-name             x-button/property-api             x-button/observed-attributes)
   x-cancel-dialogue/tag-name    (api x-cancel-dialogue/tag-name    x-cancel-dialogue/property-api    x-cancel-dialogue/observed-attributes)
   x-card/tag-name               (api x-card/tag-name               x-card/property-api               x-card/observed-attributes)
   x-carousel/tag-name           (api x-carousel/tag-name           x-carousel/property-api           x-carousel/observed-attributes)
   x-chart/tag-name              (api x-chart/tag-name              x-chart/property-api              x-chart/observed-attributes)
   x-checkbox/tag-name           (api x-checkbox/tag-name           x-checkbox/property-api           x-checkbox/observed-attributes)
   x-chip/tag-name               (api x-chip/tag-name               x-chip/property-api               x-chip/observed-attributes)
   x-collapse/tag-name           (api x-collapse/tag-name           x-collapse/property-api           x-collapse/observed-attributes)
   x-color-picker/tag-name       (api x-color-picker/tag-name       x-color-picker/property-api       x-color-picker/observed-attributes)
   x-combobox/tag-name          (api x-combobox/tag-name x-combobox/property-api x-combobox/observed-attributes)
   x-command-palette/tag-name    (api x-command-palette/tag-name    x-command-palette/property-api    x-command-palette/observed-attributes)
   x-container/tag-name          (api x-container/tag-name          x-container/property-api          x-container/observed-attributes)
   x-context-menu/tag-name       (api x-context-menu/tag-name       x-context-menu/property-api       x-context-menu/observed-attributes)
   x-copy/tag-name               (api x-copy/tag-name               x-copy/property-api               x-copy/observed-attributes)
   x-currency-field/tag-name     (api x-currency-field/tag-name     x-currency-field/property-api     x-currency-field/observed-attributes)
   x-date-picker/tag-name        (api x-date-picker/tag-name        x-date-picker/property-api        x-date-picker/observed-attributes)
   x-divider/tag-name            (api x-divider/tag-name            x-divider/property-api            x-divider/observed-attributes)
   x-drawer/tag-name             (api x-drawer/tag-name             x-drawer/property-api             x-drawer/observed-attributes)
   x-dropdown/tag-name           (api x-dropdown/tag-name           x-dropdown/property-api           x-dropdown/observed-attributes)
   x-fieldset/tag-name           (api x-fieldset/tag-name           x-fieldset/property-api           x-fieldset/observed-attributes)
   x-file-download/tag-name      (api x-file-download/tag-name      x-file-download/property-api      x-file-download/observed-attributes)
   x-file-upload/tag-name       (api x-file-upload/tag-name x-file-upload/property-api x-file-upload/observed-attributes)
   x-form/tag-name               (api x-form/tag-name               x-form/property-api               x-form/observed-attributes)
   x-form-field/tag-name         (api x-form-field/tag-name         x-form-field/property-api         x-form-field/observed-attributes)
   x-gaussian-blur/tag-name      (api x-gaussian-blur/tag-name      x-gaussian-blur/property-api      x-gaussian-blur/observed-attributes)
   x-grid/tag-name               (api x-grid/tag-name               x-grid/property-api               x-grid/observed-attributes)
   x-icon/tag-name              (api x-icon/tag-name x-icon/property-api x-icon/observed-attributes)
   x-image/tag-name             (api x-image/tag-name x-image/property-api x-image/observed-attributes)
   x-kinetic-font/tag-name       (api x-kinetic-font/tag-name       x-kinetic-font/property-api       x-kinetic-font/observed-attributes)
   x-kinetic-typography/tag-name (api x-kinetic-typography/tag-name x-kinetic-typography/property-api x-kinetic-typography/observed-attributes)
   x-liquid-dock/tag-name        (api x-liquid-dock/tag-name        x-liquid-dock/property-api        x-liquid-dock/observed-attributes)
   x-liquid-fill/tag-name        (api x-liquid-fill/tag-name        x-liquid-fill/property-api        x-liquid-fill/observed-attributes)
   x-liquid-glass/tag-name       (api x-liquid-glass/tag-name       x-liquid-glass/property-api       x-liquid-glass/observed-attributes)
   x-menu/tag-name               (api x-menu/tag-name               x-menu/property-api               x-menu/observed-attributes)
   x-menu-item/tag-name          (api x-menu-item/tag-name          x-menu-item/property-api          x-menu-item/observed-attributes)
   x-metaball-cursor/tag-name    (api x-metaball-cursor/tag-name    x-metaball-cursor/property-api    x-metaball-cursor/observed-attributes)
   x-modal/tag-name              (api x-modal/tag-name              x-modal/property-api              x-modal/observed-attributes)
   x-morph-stack/tag-name        (api x-morph-stack/tag-name        x-morph-stack/property-api        x-morph-stack/observed-attributes)
   x-navbar/tag-name             (api x-navbar/tag-name             x-navbar/property-api             x-navbar/observed-attributes)
   x-neural-glow/tag-name        (api x-neural-glow/tag-name        x-neural-glow/property-api        x-neural-glow/observed-attributes)
   x-notification-center/tag-name (api x-notification-center/tag-name x-notification-center/property-api x-notification-center/observed-attributes)
   x-organic-divider/tag-name    (api x-organic-divider/tag-name    x-organic-divider/property-api    x-organic-divider/observed-attributes)
   x-organic-progress/tag-name   (api x-organic-progress/tag-name   x-organic-progress/property-api   x-organic-progress/observed-attributes)
   x-organic-shape/tag-name      (api x-organic-shape/tag-name      x-organic-shape/property-api      x-organic-shape/observed-attributes)
   x-pagination/tag-name         (api x-pagination/tag-name         x-pagination/property-api         x-pagination/observed-attributes)
   x-particle-button/tag-name    (api x-particle-button/tag-name    x-particle-button/property-api    x-particle-button/observed-attributes)
   x-popover/tag-name            (api x-popover/tag-name            x-popover/property-api            x-popover/observed-attributes)
   x-progress/tag-name           (api x-progress/tag-name           x-progress/property-api           x-progress/observed-attributes)
   x-progress-circle/tag-name    (api x-progress-circle/tag-name    x-progress-circle/property-api    x-progress-circle/observed-attributes)
   x-radio/tag-name              (api x-radio/tag-name              x-radio/property-api              x-radio/observed-attributes)
   x-ripple-effect/tag-name      (api x-ripple-effect/tag-name      x-ripple-effect/property-api      x-ripple-effect/observed-attributes)
   x-scroll/tag-name             (api x-scroll/tag-name             x-scroll/property-api             x-scroll/observed-attributes)
   x-scroll-parallax/tag-name    (api x-scroll-parallax/tag-name    x-scroll-parallax/property-api    x-scroll-parallax/observed-attributes)
   x-scroll-stack/tag-name       (api x-scroll-stack/tag-name       x-scroll-stack/property-api       x-scroll-stack/observed-attributes)
   x-scroll-story/tag-name       (api x-scroll-story/tag-name       x-scroll-story/property-api       x-scroll-story/observed-attributes)
   x-scroll-timeline/tag-name    (api x-scroll-timeline/tag-name    x-scroll-timeline/property-api    x-scroll-timeline/observed-attributes)
   x-search-field/tag-name       (api x-search-field/tag-name       x-search-field/property-api       x-search-field/observed-attributes)
   x-select/tag-name             (api x-select/tag-name             x-select/property-api             x-select/observed-attributes)
   x-sidebar/tag-name            (api x-sidebar/tag-name            x-sidebar/property-api            x-sidebar/observed-attributes)
   x-skeleton/tag-name           (api x-skeleton/tag-name           x-skeleton/property-api           x-skeleton/observed-attributes)
   x-skeleton-group/tag-name    (api x-skeleton-group/tag-name x-skeleton-group/property-api x-skeleton-group/observed-attributes)
   x-slider/tag-name             (api x-slider/tag-name             x-slider/property-api             x-slider/observed-attributes)
   x-soft-body/tag-name          (api x-soft-body/tag-name          x-soft-body/property-api          x-soft-body/observed-attributes)
   x-spacer/tag-name             (api x-spacer/tag-name             x-spacer/property-api             x-spacer/observed-attributes)
   x-spinner/tag-name            (api x-spinner/tag-name            x-spinner/property-api            x-spinner/observed-attributes)
   x-splash/tag-name             (api x-splash/tag-name             x-splash/property-api             x-splash/observed-attributes)
   x-stat/tag-name               (api x-stat/tag-name               x-stat/property-api               x-stat/observed-attributes)
   x-stepper/tag-name            (api x-stepper/tag-name            x-stepper/property-api            x-stepper/observed-attributes)
   x-switch/tag-name             (api x-switch/tag-name             x-switch/property-api             x-switch/observed-attributes)
   x-tab/tag-name                (api x-tab/tag-name                x-tab/property-api                x-tab/observed-attributes)
   x-table/tag-name              (api x-table/tag-name              x-table/property-api              x-table/observed-attributes)
   x-table-cell/tag-name         (api x-table-cell/tag-name         x-table-cell/property-api         x-table-cell/observed-attributes)
   x-table-row/tag-name          (api x-table-row/tag-name          x-table-row/property-api          x-table-row/observed-attributes)
   x-tabs/tag-name               (api x-tabs/tag-name               x-tabs/property-api               x-tabs/observed-attributes)
   x-text-area/tag-name          (api x-text-area/tag-name          x-text-area/property-api          x-text-area/observed-attributes)
   x-theme/tag-name              (api x-theme/tag-name              x-theme/property-api              x-theme/observed-attributes)
   x-timeline/tag-name           (api x-timeline/tag-name           x-timeline/property-api           x-timeline/observed-attributes)
   x-timeline-item/tag-name      (api x-timeline-item/tag-name      x-timeline-item/property-api      x-timeline-item/observed-attributes)
   x-toast/tag-name              (api x-toast/tag-name              x-toast/property-api              x-toast/observed-attributes)
   x-toaster/tag-name            (api x-toaster/tag-name            x-toaster/property-api            x-toaster/observed-attributes)
   x-tooltip/tag-name           (api x-tooltip/tag-name x-tooltip/property-api x-tooltip/observed-attributes)
   x-typography/tag-name         (api x-typography/tag-name         x-typography/property-api         x-typography/observed-attributes)
   x-welcome-tour/tag-name       (api x-welcome-tour/tag-name       x-welcome-tour/property-api       x-welcome-tour/observed-attributes)})
