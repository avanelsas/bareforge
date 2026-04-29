(ns bareforge.meta.design-tokens
  "Authoritative list of BareDOM theme tokens, mirrored from
   `baredom.components.x-theme.model`. Each entry is `{:name :category}`
   where `:name` is the CSS custom-property string (e.g. `--x-color-primary`)
   and `:category` is one of `:color :length :font :shadow :motion :z
   :opacity`.

   Why mirror instead of reading from x-theme directly? The `tk-*` defs
   are individual vars — exposing them via `(ns-publics)` would only work
   in dev (Closure Advanced strips that machinery). Hardcoded strings are
   stable; a unit test (`bareforge.meta.design-tokens-test`) compares
   them against a regex on the x-theme source so a BareDOM bump that
   adds tokens shows up as a missing-coverage failure rather than
   silent autocomplete drift.")

(def all-tokens
  "Vector of every BareDOM theme token, ordered by category for a
   readable inspector datalist."
  [;; --- colour ---
   {:name "--x-color-primary"          :category :color}
   {:name "--x-color-primary-hover"    :category :color}
   {:name "--x-color-primary-active"   :category :color}
   {:name "--x-color-secondary"        :category :color}
   {:name "--x-color-secondary-hover"  :category :color}
   {:name "--x-color-secondary-active" :category :color}
   {:name "--x-color-tertiary"         :category :color}
   {:name "--x-color-tertiary-hover"   :category :color}
   {:name "--x-color-tertiary-active"  :category :color}
   {:name "--x-color-surface"          :category :color}
   {:name "--x-color-surface-hover"    :category :color}
   {:name "--x-color-surface-active"   :category :color}
   {:name "--x-color-bg"               :category :color}
   {:name "--x-color-text"             :category :color}
   {:name "--x-color-text-muted"       :category :color}
   {:name "--x-color-border"           :category :color}
   {:name "--x-color-focus-ring"       :category :color}
   {:name "--x-color-danger"           :category :color}
   {:name "--x-color-success"          :category :color}
   {:name "--x-color-warning"          :category :color}

   ;; --- length / spacing ---
   {:name "--x-space-xs"               :category :length}
   {:name "--x-space-sm"               :category :length}
   {:name "--x-space-md"               :category :length}
   {:name "--x-space-lg"               :category :length}
   {:name "--x-space-xl"               :category :length}
   {:name "--x-radius-sm"              :category :length}
   {:name "--x-radius-md"              :category :length}
   {:name "--x-radius-lg"              :category :length}
   {:name "--x-radius-full"            :category :length}
   {:name "--x-font-size-xs"           :category :length}
   {:name "--x-font-size-sm"           :category :length}
   {:name "--x-font-size-base"         :category :length}
   {:name "--x-font-size-lg"           :category :length}
   {:name "--x-border-width"           :category :length}

   ;; --- font / typography ---
   {:name "--x-font-family"            :category :font}
   {:name "--x-font-family-mono"       :category :font}
   {:name "--x-font-weight-normal"     :category :font}
   {:name "--x-font-weight-medium"     :category :font}
   {:name "--x-font-weight-semibold"   :category :font}
   {:name "--x-line-height-normal"     :category :font}

   ;; --- shadow ---
   {:name "--x-shadow-sm"              :category :shadow}
   {:name "--x-shadow-md"              :category :shadow}
   {:name "--x-shadow-lg"              :category :shadow}

   ;; --- motion ---
   {:name "--x-transition-duration"    :category :motion}
   {:name "--x-transition-easing"      :category :motion}

   ;; --- z-index ---
   {:name "--x-z-dropdown"             :category :z}
   {:name "--x-z-modal"                :category :z}
   {:name "--x-z-toast"                :category :z}

   ;; --- opacity ---
   {:name "--x-opacity-disabled"       :category :opacity}
   {:name "--x-opacity-placeholder"    :category :opacity}])

(defn tokens-for
  "Pure: filter `all-tokens` to the named category. Returns the
   raw entries (callers project to `:name` as needed)."
  [category]
  (filterv #(= category (:category %)) all-tokens))

(defn var-of
  "Pure: wrap `--x-foo` into the canonical CSS reference string
   `var(--x-foo)`. Handy when emitting datalist `<option value=>`."
  [token-name]
  (str "var(" token-name ")"))
