(ns bareforge.ui.inspector.tokens
  "Native datalist autocomplete for inspector CSS-var fields. Mounts
   one shared `<datalist>` per token category at the top of the
   document body, then clones it into individual `x-search-field`
   shadow roots so typing `var(--x-…)` surfaces the active theme's
   tokens via the browser's own autocomplete UI.

   `install-token-datalists!` is the one-shot install on app boot
   (`bareforge.main/init`); `attach-datalist-to-shadow-input!` is
   called by widget builders that opt a field into autocomplete.

   Extracted from `bareforge.ui.inspector` (M2.4)."
  (:require [bareforge.meta.design-tokens :as design-tokens]))

(def color-datalist-id  "bareforge-tokens-color")
(def length-datalist-id "bareforge-tokens-length")

(defn- ^js build-token-datalist!
  "Build a `<datalist>` element populated with `var(--x-…)` options
   for every token entry. Called once at install time per category."
  [^js doc id entries]
  (let [^js dl (.createElement doc "datalist")]
    (.setAttribute dl "id" id)
    (doseq [{:keys [name]} entries]
      (let [^js o (.createElement doc "option")]
        (.setAttribute o "value" (design-tokens/var-of name))
        (.appendChild dl o)))
    dl))

(defn install-token-datalists!
  "Mount one shared `<datalist>` per token category at the top of the
   document body. Inspector widgets reference these via `list=` so
   typing `var(` in a colour or length field surfaces native browser
   autocomplete with theme tokens. Idempotent — calling twice replaces
   the existing datalists rather than duplicating them."
  []
  (let [^js doc js/document
        ^js body (.-body doc)]
    (doseq [id [color-datalist-id length-datalist-id]]
      (when-let [^js prev (.getElementById doc id)]
        (.removeChild (.-parentNode prev) prev)))
    (.appendChild body (build-token-datalist! doc color-datalist-id
                                              (design-tokens/tokens-for :color)))
    (.appendChild body (build-token-datalist! doc length-datalist-id
                                              (design-tokens/tokens-for :length)))))

(defn attach-datalist-to-shadow-input!
  "BareDOM's `x-search-field` wraps a real `<input part=\"input\">` in
   its open shadow root. Two things are needed for native datalist
   autocomplete to work in that setup:

   1. `list=` has to be on the actual `<input>`, not the custom-element
      host (BareDOM doesn't observe / forward `list`).
   2. The referenced `<datalist>` has to live in the same tree as the
      input. The HTML spec scopes the `list` lookup to the input's
      containing root, so a body-level datalist is invisible to an
      input inside a shadow root.

   We clone the global datalist (mounted by `install-token-datalists!`)
   into the field's shadow root on the next animation frame, and set
   `list=` on the inner input. Idempotent — the clone only happens
   when the shadow root doesn't already carry a datalist with the
   target id."
  [^js host datalist-id]
  (letfn [(try-attach! []
            (if-let [^js sr (.-shadowRoot host)]
              (when-let [^js inner (.querySelector sr "[part=input]")]
                (when-not (.querySelector sr
                                          (str "datalist[id='" datalist-id "']"))
                  (when-let [^js src (js/document.getElementById datalist-id)]
                    (.appendChild sr (.cloneNode src true))))
                (.setAttribute inner "list" datalist-id))
              (js/requestAnimationFrame try-attach!)))]
    (js/requestAnimationFrame try-attach!)))
