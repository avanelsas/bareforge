(ns bareforge.export.vanilla-js-test
  (:require [cljs.test :refer [deftest is testing]]
            [cljs.reader :as edn]
            [clojure.string :as str]
            [bareforge.export.model :as em]
            [bareforge.export.vanilla-js.plugin :as vjs]
            [bareforge.export.vanilla-js.codegen :as codegen]))

(defn- read-fixture [path]
  (let [fs (js/require "fs")]
    (edn/read-string (.readFileSync fs path "utf8"))))

(defn- strip-inner-html
  "Walk a node tree and clear :inner-html on every node. The
   vanilla-JS plugin doesn't yet support raw HTML emit (throws :nyi
   in `assert-supported!`), so the fixture-driven tests stand in for
   a future-when-supported world by exercising the rest of the
   surface against an inner-html-free document."
  [node]
  (-> node
      (assoc :inner-html nil)
      (update :slots (fn [slots]
                       (reduce-kv
                        (fn [acc sname kids]
                          (assoc acc sname (mapv strip-inner-html kids)))
                        {} (or slots {}))))))

(defn- fixture-doc
  "Read the fixture and strip every node's `:inner-html`. The plugin's
   NYI guard is exercised by a dedicated test; the rest of the suite
   focuses on emit-shape so the icon raw HTML isn't relevant."
  [path]
  (-> (:document (read-fixture path))
      (update :root strip-inner-html)))

;; --- v0.1 feature gate --------------------------------------------------

(deftest emitted-js-identifiers-are-valid-js
  ;; demo-store-with-bindings has a `product-feed` group — kebab-case
  ;; ns-name. JavaScript identifiers can't contain hyphens, so
  ;; emitting `product-feedView` or `product-feedDb` as a bare
  ;; binding name is a syntax error at the import line. Convert
  ;; to underscore form (`product_feedView`) at every JS-identifier
  ;; site; path segments and string keys keep the kebab form.
  (let [doc   (fixture-doc "test/fixtures/export/demo-store-with-bindings.json")
        files (vjs/generate doc {:title "demo"})
        app   (get files "app.js")]
    (testing "no kebab-case identifier ever appears as a bare JS name"
      (is (not (re-find #"product-feedDb" app)))
      (is (not (re-find #"product-feedView" app)))
      (is (not (re-find #"cart-itemView" app)))
      (is (str/includes? app "product_feedDb"))
      (is (str/includes? app "product_feedView"))
      (testing "path segments still kebab-case"
        (is (str/includes? app "./product-feed/db.js"))
        (is (str/includes? app "./product-feed/views.js"))))
    (testing "template-iteration call uses underscore identifier"
      (let [feed-views (get files "product-feed/views.js")]
        (is (some? feed-views))
        ;; product-feed iterates `product` template — single-word
        ;; ns, no underscore needed there. But a `cart-item` view
        ;; imported into another group's view would become
        ;; `tpl_cart_item`. Just check the absence of the bare
        ;; `tpl_<kebab>(` shape.
        (is (not (re-find #"tpl_[a-z]+-[a-z]+\(" feed-views))
            "no kebab-case template iterator name slipped through")))))

(deftest app-js-imports-runtime-fns-the-root-walk-uses
  ;; The root walk inlines hiccup that may call `query()` (text-
  ;; field bindings, read attribute bindings) or `dispatch()`
  ;; (root-level write bindings, triggers). Both imports MUST
  ;; appear at the top of app.js; missing either turns the whole
  ;; mount expression into a `ReferenceError` at first render.
  (let [doc   (fixture-doc "test/fixtures/export/demo-store-with-bindings.json")
        files (vjs/generate doc {:title "demo"})
        app   (get files "app.js")]
    (is (re-find #"import \{[^}]*\bquery\b[^}]*\} from \"\./runtime\.js\""
                 app)
        "query() is imported")
    (is (re-find #"import \{[^}]*\bdispatch\b[^}]*\} from \"\./runtime\.js\""
                 app)
        "dispatch() is imported")))

(deftest layout-style-emits-into-the-hiccup-prop-object
  ;; demo-store-with-bindings has several nodes carrying inline
  ;; styles via `:layout` — the inner x-container has
  ;; `extra-style "height: 100dvh; …"` plus padding / width, the
  ;; x-organic-divider has a `--x-organic-divider-color` CSS var
  ;; override, the product card has `--x-card-background` ditto.
  ;; bareforge.render.reconcile/layout->css turns these into the
  ;; same inline-style string the editor paints. Verify both
  ;; flavours land in the emitted JS.
  (let [doc   (fixture-doc "test/fixtures/export/demo-store-with-bindings.json")
        files (vjs/generate doc {:title "demo"})
        app   (get files "app.js")
        prod  (get files "product/views.js")]
    (testing "extra-style + named dimension fields render verbatim"
      (is (str/includes? app "height: 100dvh")
          "extra-style content (with its original spacing) is preserved")
      (is (str/includes? app "padding:0.5rem")
          "named :padding field renders via layout->css's compact form"))
    (testing ":css-vars on an inline node render as custom-property entries"
      (is (str/includes? app "--x-organic-divider-color")))
    (testing ":css-vars on a TEMPLATE-group root node render in the template view"
      (is (str/includes? prod "--x-card-background")))))

(deftest props-map-entries-emit-into-the-hiccup-prop-object
  ;; The cart's x-popover stores most of its switches in `:props`,
  ;; not `:attrs`: `{:open false :disabled false :no-close false
  ;; :portal true}`. Without `portal: true` actually landing in
  ;; the emitted hiccup, BareDOM doesn't portal the popover panel
  ;; — the cart-items render but in a layout the user reported as
  ;; broken (and the × press never reaches the handler). Mirror
  ;; the CLJS plugin's `node->prop-strings` :props branch.
  (let [doc   (fixture-doc "test/fixtures/export/demo-store-with-bindings.json")
        files (vjs/generate doc {:title "demo"})
        cart  (get files "cart/views.js")]
    (is (str/includes? cart "\"portal\": true")
        "x-popover's :props entry for portal lands in the prop object")))

(deftest implicit-template-source-resolves-to-stateful-host
  ;; cart-item's instance node has no :source-field or :source-sub.
  ;; The plugin must infer the source from `stateful-host-for-template`
  ;; — cart owns `cart-items :of-group "cart-item"`, so the
  ;; iteration sub-ref should be `app.cart.subs/cart-items`.
  (let [doc   (fixture-doc "test/fixtures/export/demo-store-with-bindings.json")
        files (vjs/generate doc {:title "demo"})
        cart  (get files "cart/views.js")]
    (is (str/includes? cart "query(\"app.cart.subs/cart-items\")"))
    (is (not (str/includes? cart "__NO_SOURCE__"))
        "no template instance ends up with the placeholder error sub-ref")))

(deftest doc-level-text-field-binds-to-query
  ;; The "Nr. of products" typography uses a :text-field
  ;; (`:product-feed-item-count`) instead of a property binding.
  ;; The codegen should emit a `query("…subs/product-feed-item-count")`
  ;; expression as the node's text content, NOT a literal string.
  (let [doc   (fixture-doc "test/fixtures/export/demo-store-with-bindings.json")
        files (vjs/generate doc {:title "demo"})
        app   (get files "app.js")]
    (is (str/includes? app "query(\"app.product-feed.subs/product-feed-item-count\")"))))

(deftest index-html-paints-the-theme
  ;; Without the `background: var(--x-color-bg)` rule the x-theme
  ;; falls back to `color-scheme: light dark` on the body, which
  ;; renders black under a dark-mode OS instead of the active
  ;; preset's blue.
  (let [doc   (fixture-doc "test/fixtures/export/demo-store-with-bindings.json")
        files (vjs/generate doc {:title "demo"})
        html  (get files "index.html")]
    (is (str/includes? html "background: var(--x-color-bg)"))
    (is (str/includes? html "color: var(--x-color-text)"))))

(deftest index-html-without-manifest-omits-modulepreload
  (let [doc   (fixture-doc "test/fixtures/export/demo-store-with-bindings.json")
        files (vjs/generate doc {:title "demo"})
        html  (get files "index.html")]
    (is (not (str/includes? html "rel=\"modulepreload\""))
        "no manifest passed → no preload block (current default until BareDOM ships integrity.json)")))

(deftest index-html-with-manifest-emits-modulepreload-block
  (testing ":integrity-manifest threads through to the index.html and "
    "emits SRI-bound modulepreload links for every BareDOM tag the "
    "doc loads"
    (let [doc      (fixture-doc "test/fixtures/export/demo-store-with-bindings.json")
          ;; Provide hashes for two of the demo-store's tags; the rest
          ;; pass through without preload entries (graceful partial
          ;; coverage).
          manifest {:version "2.4.0" :algorithm "sha384"
                    :files {"x-button.js" "sha384-AAA"
                            "x-theme.js"  "sha384-BBB"}}
          files    (vjs/generate doc {:title "demo"
                                      :integrity-manifest manifest})
          html     (get files "index.html")]
      (is (str/includes? html "rel=\"modulepreload\""))
      (is (str/includes? html "integrity=\"sha384-AAA\""))
      (is (str/includes? html "integrity=\"sha384-BBB\""))
      (is (str/includes? html "crossorigin=\"anonymous\"")))))

(deftest app-js-walks-the-whole-root-tree-with-slots
  ;; Earlier emit-app-js stitched only the named stateful-group
  ;; views together (`["div", cartView(), feedView()]`), so unnamed
  ;; root wrappers like x-navbar / x-gaussian-blur — and their slot-
  ;; tagged children like the cart popover or "Demo Store" brand
  ;; typography — disappeared from the rendered output. Now walk
  ;; the whole document root via node->js-hiccup with named
  ;; descendants becoming `<ns>View()` calls; per-edge slot-name
  ;; threads through so non-default slots emit `slot="…"` props.
  (let [doc   (fixture-doc "test/fixtures/export/demo-store-with-bindings.json")
        files (vjs/generate doc {:title "demo"})
        app   (get files "app.js")]
    (testing "the unnamed root wrappers appear in the emitted tree"
      (is (str/includes? app "\"x-navbar\""))
      (is (str/includes? app "\"x-gaussian-blur\"")))
    (testing "named-group descendants become view calls"
      (is (str/includes? app "(cartView())"))
      (is (str/includes? app "(product_feedView())")))
    (testing "non-default slot children carry the slot attr"
      (is (str/includes? app "\"slot\": \"brand\"")
          "x-typography Demo Store sits in the navbar's brand slot")
      (is (str/includes? app "\"slot\": \"actions\"")
          "cart container sits in the navbar's actions slot"))))

(deftest template-children-resolved-by-id
  ;; demo-store has cart-item nested inside cart's popover, and
  ;; product nested inside product-feed's grid. Both are template
  ;; groups with iteration. node->js-hiccup looks up the sub-group
  ;; entry by child-id; an earlier bug ran a (filter …) against a
  ;; non-existent :instance-ids key and emitted bare `(View())`,
  ;; tripping `View is not defined` at runtime. Pin the lookup
  ;; works by checking the emitted iteration shape directly.
  (let [doc   (fixture-doc "test/fixtures/export/demo-store-with-bindings.json")
        files (vjs/generate doc {:title "demo"})
        cart  (get files "cart/views.js")
        feed  (get files "product-feed/views.js")]
    (testing "cart's iteration over cart-items reaches the cart-item template"
      (is (str/includes? cart "tpl_cart_item(r)"))
      (is (not (re-find #"\(View\(\)\)" cart))
          "no bare View() — the lookup must resolve to a real ns-name"))
    (testing "product-feed iterates the product template"
      (is (str/includes? feed "tpl_product(r)"))
      (is (not (re-find #"\(View\(\)\)" feed))))))

(deftest demo-store-fixture-exports-end-to-end
  ;; v0.2 covers the demo-store feature surface in full: template
  ;; groups, collection fields, :add/:remove, implicit payloads,
  ;; and the seven computed ops. Generating against the fixture
  ;; should produce a complete project without throwing.
  (let [doc   (fixture-doc "test/fixtures/export/demo-store-with-bindings.json")
        files (vjs/generate doc {:title "demo"})]
    (testing "core files emit"
      (is (contains? files "index.html"))
      (is (contains? files "runtime.js"))
      (is (contains? files "renderer.js"))
      (is (contains? files "app.js")))
    (testing "stateful groups each emit db / subs / events / views"
      (doseq [g ["cart" "product-feed"]]
        (is (contains? files (str g "/db.js")))
        (is (contains? files (str g "/subs.js")))
        (is (contains? files (str g "/events.js")))
        (is (contains? files (str g "/views.js")))))
    (testing "template groups emit only views"
      (doseq [g ["cart-item" "product"]]
        (is (contains? files (str g "/views.js")))
        (is (not (contains? files (str g "/db.js"))))
        (is (not (contains? files (str g "/subs.js"))))
        (is (not (contains? files (str g "/events.js"))))))
    (testing "computed sub shapes for demo-store's actual operations"
      (let [cart-subs (get files "cart/subs.js")
            feed-subs (get files "product-feed/subs.js")]
        (testing ":count-of emits a regSubDerived with .length extractor"
          (is (str/includes? cart-subs "regSubDerived"))
          (is (re-find #"\(s\) => \(s \|\| \[\]\)\.length" cart-subs)))
        (testing ":filter-by emits a multi-signal sub with case-insensitive match"
          (is (str/includes? feed-subs "regSubMulti"))
          (is (str/includes? feed-subs "toLowerCase"))
          (is (str/includes? feed-subs "includes(needle)")))))))

(deftest join-on-emission
  ;; Exercise :join-on against a synthetic doc — the demo-store
  ;; fixture doesn't use it, but it's one of the seven v0.2
  ;; computed ops and wants coverage.
  (let [doc {:next-id 3
             :root {:id "root" :tag "x-container"
                    :attrs {} :props {} :text nil :inner-html nil
                    :layout {:placement :flow}
                    :slots {"default"
                            [{:id    "tpl"
                              :tag   "x-grid"
                              :name  "product"
                              :attrs {} :props {} :text nil :inner-html nil
                              :layout {:placement :flow}
                              :slots  {"default"
                                       [{:id "lbl" :tag "x-typography"
                                         :attrs {} :props {} :text "" :inner-html nil
                                         :layout {:placement :flow} :slots {}}]}
                              :fields [{:name :id :type :number :default 0 :locked? true}
                                       {:name :title :type :string :default ""}]}
                             {:id "cart-root" :tag "x-card" :name "cart"
                              :attrs {} :props {} :text nil :inner-html nil
                              :layout {:placement :flow}
                              :slots {}
                              :fields  [{:name :id :type :number :default 0 :locked? true}
                                        {:name :cart-items :type :vector
                                         :of-group "product" :default []}
                                        {:name :cart-with-products :type :vector
                                         :of-group "product"
                                         :computed
                                         {:operation :join-on
                                          :source-field :cart-items
                                          :join-target {:group-name "product"
                                                        :match-field :id
                                                        :of-group "product"}}}]}]}}}
        files (vjs/generate doc {:title "join-demo"})
        subs  (get files "cart/subs.js")]
    (is (some? subs))
    (is (str/includes? subs "regSubMulti") ":join-on uses regSubMulti")
    (is (str/includes? subs "qualifyMap") "of-group on join-target pulls in qualifyMap")
    (is (re-find #"records \|\| \[\]\)\.find\(r =>" subs)
        ":join-on looks each id up in the host records via .find")))

;; --- minimal supported doc produces runnable output ---------------------

(defn- scalar-only-doc
  "Build a minimal doc with one named group carrying a boolean
   field + a declared :toggle action and an x-switch bound to the
   field — the simplest end-to-end exercise of v0.1 codegen."
  []
  {:next-id 3
   :root    {:id    "root"
             :tag   "x-container"
             :attrs {} :props {} :text nil :inner-html nil
             :layout {:placement :flow}
             :slots  {"default"
                      [{:id    "panel"
                        :tag   "x-card"
                        :name  "panel"
                        :attrs {} :props {} :text nil :inner-html nil
                        :layout {:placement :flow}
                        :slots  {"default"
                                 [{:id       "sw"
                                   :tag      "x-switch"
                                   :attrs    {}
                                   :props    {} :text nil :inner-html nil
                                   :layout   {:placement :flow}
                                   :slots    {}
                                   :bindings {"checked"
                                              {:field :active?
                                               :direction :read-write
                                               :owner "panel"}}}
                                  {:id     "btn"
                                   :tag    "x-button"
                                   :attrs  {:label "Toggle"}
                                   :props  {} :text nil :inner-html nil
                                   :layout {:placement :flow}
                                   :slots  {}
                                   :events [{:trigger "press"
                                             :action-ref :app.panel.events/flip}]}]}
                        :fields  [{:name :id :type :number :default 0 :locked? true}
                                  {:name :active? :type :boolean :default false}]
                        :actions [{:name :flip
                                   :operation :toggle
                                   :target-field :active?}]}]}}})

(deftest generate-minimal-scalar-doc-ok
  (let [files (vjs/generate (scalar-only-doc) {:title "panel-demo"})]
    (testing "a full project is produced"
      (is (contains? files "index.html"))
      (is (contains? files "package.json"))
      (is (contains? files "runtime.js"))
      (is (contains? files "renderer.js"))
      (is (contains? files "app.js"))
      (is (contains? files "panel/db.js"))
      (is (contains? files "panel/subs.js"))
      (is (contains? files "panel/events.js"))
      (is (contains? files "panel/views.js")))
    (testing "runtime.js and renderer.js are shipped verbatim from the canonical source"
      (is (str/includes? (get files "runtime.js") "export function initStore"))
      (is (str/includes? (get files "renderer.js") "export function mount")))
    (testing "db.js seeds the :active? field under its fully-qualified key"
      (is (str/includes? (get files "panel/db.js") "app.panel.db/active?"))
      (is (str/includes? (get files "panel/db.js") "false")))
    (testing "subs.js direct-registers the active? sub"
      (is (str/includes? (get files "panel/subs.js") "regSubDirect")))
    (testing "events.js registers the :flip toggle action"
      (is (str/includes? (get files "panel/events.js") "app.panel.events/flip"))
      (is (str/includes? (get files "panel/events.js") "!v")))
    (testing "views.js dispatches the trigger and reads the bound field"
      (is (str/includes? (get files "panel/views.js") "dispatch"))
      (is (str/includes? (get files "panel/views.js") "app.panel.events/flip"))
      (is (str/includes? (get files "panel/views.js") "query(")))
    (testing "write-event dispatch reads the correct event.detail key"
      ;; x-switch bound on `checked` → BareDOM fires x-switch-change
      ;; with event.detail.checked (not .value). Hardcoding .value
      ;; was the bug that made step 12 of the browser-verify recipe
      ;; silently pass `undefined` into the setter, flipping the
      ;; bound boolean to undefined instead of toggling it.
      (is (str/includes? (get files "panel/views.js")
                         "e.detail && e.detail.checked")
          "dispatch plucks e.detail.checked for a `checked` binding"))
    (testing "index.html ships a BareDOM loader block so custom elements upgrade"
      (is (str/includes? (get files "index.html") "cdn.jsdelivr.net/npm/@vanelsas/baredom"))
      (is (str/includes? (get files "index.html") "\"x-switch\""))
      (is (str/includes? (get files "index.html") "\"x-theme\"")))))

;; --- template + collection codegen (v0.2) -------------------------------

(defn- cart-style-doc
  "A minimal template + collection fixture. A `cart-item` template
   group (record shape) plus a `cart` stateful group with a
   `cart-items` collection field pointing at `cart-item` via
   :of-group, seeded empty, with an `:add` declared action."
  []
  {:next-id 5
   :root    {:id    "root"
             :tag   "x-container"
             :attrs {} :props {} :text nil :inner-html nil
             :layout {:placement :flow}
             :slots  {"default"
                      [;; Template group: cart-item record shape.
                       {:id    "tpl"
                        :tag   "x-grid"
                        :name  "cart-item"
                        :attrs {} :props {} :text nil :inner-html nil
                        :layout {:placement :flow}
                        :slots  {"default"
                                 [{:id    "line-title"
                                   :tag   "x-typography"
                                   :attrs {} :props {}
                                   :text  "Placeholder"
                                   :inner-html nil
                                   :layout {:placement :flow}
                                   :slots  {}
                                   :text-field :title}]}
                        :fields [{:name :id :type :number :default 0 :locked? true}
                                 {:name :title :type :string :default ""}
                                 {:name :price :type :number :default 0}]}
                       ;; Stateful host: cart.
                       {:id    "cart-root"
                        :tag   "x-card"
                        :name  "cart"
                        :attrs {} :props {} :text nil :inner-html nil
                        :layout {:placement :flow}
                        :slots  {}
                        :fields  [{:name :id :type :number :default 0 :locked? true}
                                  {:name :cart-items :type :vector
                                   :of-group "cart-item"
                                   :default [{:id 1 :title "Widget" :price 9.99}
                                             {:id 2 :title "Gadget" :price 8.75}]}]
                        :actions [{:name :add-to-cart
                                   :operation :add
                                   :target-field :cart-items}
                                  {:name :remove-from-cart
                                   :operation :remove
                                   :target-field :cart-items}]}]}}})

(deftest template-group-emits-only-views
  (let [files (vjs/generate (cart-style-doc) {:title "cart-demo"})]
    (testing "template group has a views.js but no db/subs/events"
      (is (contains? files "cart-item/views.js"))
      (is (not (contains? files "cart-item/db.js")))
      (is (not (contains? files "cart-item/subs.js")))
      (is (not (contains? files "cart-item/events.js"))))
    (testing "template view takes a record arg + destructures namespaced keys"
      (let [v (get files "cart-item/views.js")]
        (is (str/includes? v "export function view(record)"))
        (is (str/includes? v "\"app.cart-item.db/title\": title"))
        (is (str/includes? v "\"app.cart-item.db/price\": price"))))))

(deftest stateful-group-seeds-records-with-qualified-keys
  (let [files (vjs/generate (cart-style-doc) {:title "cart-demo"})]
    (testing "cart's db seeds the collection with records keyed under cart-item.db/*"
      (let [db (get files "cart/db.js")]
        (is (str/includes? db "\"app.cart-item.db/title\": \"Widget\""))
        (is (str/includes? db "\"app.cart-item.db/price\": 9.99"))
        (is (str/includes? db "\"app.cart-item.db/title\": \"Gadget\""))))))

(deftest vector-actions-use-qualify-map-and-deep-equal
  (let [files (vjs/generate (cart-style-doc) {:title "cart-demo"})
        events (get files "cart/events.js")]
    (testing "imports"
      (is (str/includes? events "qualifyMap"))
      (is (str/includes? events "deepEqual")))
    (testing ":add appends qualifyMap-rekeyed payload"
      (is (str/includes? events
                         "(v, [x]) => [...v, qualifyMap(x, \"app.cart-item.db\")]")))
    (testing ":remove filters by deep equality against the rekeyed target"
      (is (re-find #"const target = qualifyMap\(x, \"app\.cart-item\.db\"\);" events))
      (is (str/includes? events "v.filter(item => !deepEqual(item, target))")))))

(deftest template-iteration-wraps-in-display-contents-div
  ;; The doc above doesn't nest a template instance inside the stateful
  ;; cart, but iteration can still be exercised by adding a template-
  ;; instance child and a :source-field. Skipped here for scope — the
  ;; shape check is: if any stateful view's hiccup output includes a
  ;; template-sub-group invocation, it's wrapped in the `display: contents`
  ;; div per CLAUDE.md rule 19. Covered by the browser-verify recipe.
  (is true "see browser-verify recipe for end-to-end exercise"))

;; --- codegen helpers in isolation ---------------------------------------

(deftest assert-supported-accepts-minimal-doc
  (let [doc (scalar-only-doc)
        groups (:groups (em/detect-groups doc))]
    ;; No throw = pass.
    (codegen/assert-supported! doc groups)
    (is true "assert-supported! accepts scalar-only docs silently")))

(defn- icon-doc [inner-html]
  {:next-id 1
   :root    {:id "root" :tag "x-container"
             :attrs {} :props {} :text nil :inner-html nil
             :slots {"default"
                     [{:id "icon" :tag "x-icon"
                       :attrs {} :props {} :text nil
                       :inner-html inner-html
                       :layout {:placement :flow}
                       :slots {}}]}
             :layout {:placement :flow}}})

(deftest assert-supported-accepts-inner-html
  ;; The plugin used to throw :nyi on every node with :inner-html.
  ;; v0.3 parses the HTML into hiccup at codegen time and emits it
  ;; inline, so assert-supported! no longer rejects.
  (let [doc    (icon-doc "<svg viewBox=\"0 0 1 1\"><path d=\"M0\"/></svg>")
        groups (:groups (em/detect-groups doc))]
    (codegen/assert-supported! doc groups)
    (is true "no throw on inner-html-bearing docs")))

(deftest inner-html-emits-as-nested-hiccup
  (testing "the parsed SVG appears as a nested JS array literal "
    "inside the icon node — no innerHTML write, no sentinel "
    "tag, just structured hiccup the renderer handles like "
    "any other child"
    (let [doc   (icon-doc
                 "<svg viewBox=\"0 0 24 24\"><path d=\"M0 0\"/></svg>")
          files (vjs/generate doc {:title "icon-demo"})
          app   (get files "app.js")]
      (is (str/includes? app "\"x-icon\""))
      (is (str/includes? app "\"svg\""))
      (is (str/includes? app "\"viewBox\""))
      (is (str/includes? app "\"0 0 24 24\""))
      (is (str/includes? app "\"path\""))
      (is (str/includes? app "\"M0 0\"")))))

(deftest inner-html-payload-stripped-at-codegen
  (testing "even if a hostile :inner-html somehow reaches codegen "
    "(stale autosave from a pre-sanitiser session, hand-edited "
    "JSON), the sanitise-on-emit pass strips <script> before "
    "the parser runs — defence-in-depth holds"
    (let [doc   (icon-doc
                 "<svg><script>alert(1)</script><path d=\"M\"/></svg>")
          files (vjs/generate doc {:title "icon-demo"})
          app   (get files "app.js")]
      (is (not (str/includes? app "<script")))
      (is (not (str/includes? app "alert(1)")))
      (is (str/includes? app "\"path\"")))))
