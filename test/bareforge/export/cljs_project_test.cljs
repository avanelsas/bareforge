(ns bareforge.export.cljs-project-test
  (:require [cljs.test :refer [deftest is testing]]
            [cljs.reader :as edn]
            [clojure.string :as str]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.export.cljs-project :as cp]))

(defn- read-fixture []
  (let [fs (js/require "fs")]
    (edn/read-string (.readFileSync fs "test/fixtures/export/demo-store.json" "utf8"))))

(defn- fixture-doc [] (:document (read-fixture)))

;; --- group detection -------------------------------------------------------

(deftest detect-groups-finds-named-nodes-only
  (let [{:keys [groups root-order]} (cp/detect-groups (fixture-doc))
        ns-names (set (map :ns-name groups))]
    (testing "every detected group has a user-declared :name"
      (is (contains? ns-names "cart"))
      (is (contains? ns-names "cart-item"))
      (is (contains? ns-names "product"))
      (is (contains? ns-names "product-feed")))
    (testing "unnamed root-level containers (x-navbar, empty-state x-card) are NOT groups"
      (is (not (contains? ns-names "navbar")))
      (is (not (contains? ns-names "card"))))
    (testing "each group has a namespace name derived from its :name"
      (doseq [g groups]
        (is (string? (:ns-name g)))
        (is (seq (:ns-name g)))))
    (testing "root-order preserves all direct children"
      (is (= 4 (count root-order)))
      (is (= 1 (count (filter :group? root-order)))
          "only the explicitly-named product-feed grid is a group at root"))))

(deftest detect-groups-non-containers-excluded
  (let [{:keys [groups]} (cp/detect-groups (fixture-doc))
        ids (set (map :id groups))]
    (testing "leaf nodes are not groups"
      (is (not (contains? ids "2")))
      (is (not (contains? ids "3")))
      (is (not (contains? ids "4"))))
    (testing "product template group has a single canvas instance"
      (let [product-groups (filter #(= "product" (:ns-name %)) groups)]
        (is (= 1 (count product-groups)))
        (let [instance-ids (set (:instance-ids (first product-groups)))]
          (is (contains? instance-ids "8"))
          (is (= 1 (count instance-ids))
              "seed records live on product-feed.:products, not on duplicate canvas cards"))))))

;; --- generate full project -------------------------------------------------

(deftest generate-produces-expected-files
  (let [files (cp/generate (fixture-doc) {:app-ns "app" :title "Demo Store"})]
    (testing "project config files"
      (is (contains? files "deps.edn"))
      (is (contains? files "shadow-cljs.edn"))
      (is (contains? files "public/index.html")))
    (testing "framework and renderer"
      (is (contains? files "src/app/framework.cljs"))
      (is (contains? files "src/app/renderer.cljs")))
    (testing "root db and core"
      (is (contains? files "src/app/db.cljs"))
      (is (contains? files "src/app/core.cljs")))
    (testing "core/subs.cljs is not emitted — tier-1 slice subs are gone"
      (is (not (contains? files "src/app/core/subs.cljs"))))
    (testing "per-group files exist only for user-named groups"
      (is (contains? files "src/app/cart/views.cljs"))
      (is (contains? files "src/app/cart/db.cljs"))
      (is (contains? files "src/app/cart/subs.cljs")
          "cart group has a readable cart-count field")
      (is (contains? files "src/app/cart/events.cljs")
          "cart has fields → auto <field>-changed setters emitted"))
    (testing "no namespace files generated for unnamed root containers"
      (is (not (contains? files "src/app/navbar/views.cljs"))
          "x-navbar at root is unnamed — stays inline hiccup in core.cljs")
      (is (not (contains? files "src/app/navbar/db.cljs")))
      (is (not (contains? files "src/app/card/views.cljs"))
          "empty-state x-card is unnamed — stays inline hiccup in core.cljs")
      (is (not (contains? files "src/app/card/db.cljs"))))))

;; --- generated content checks ----------------------------------------------

(deftest generated-deps-edn-has-baredom
  (let [files (cp/generate (fixture-doc))
        deps  (get files "deps.edn")]
    (is (str/includes? deps "baredom"))))

(deftest generated-shadow-cljs-has-init-fn
  (let [files  (cp/generate (fixture-doc) {:app-ns "app"})
        shadow (get files "shadow-cljs.edn")]
    (is (str/includes? shadow "app.core/init"))))

(deftest generated-index-html-has-theme
  (let [files (cp/generate (fixture-doc) {:title "Demo Store"})
        html  (get files "public/index.html")]
    (is (str/includes? html "<x-theme"))
    (is (str/includes? html "Demo Store"))))

(deftest generated-core-requires-all-groups
  (let [files (cp/generate (fixture-doc) {:app-ns "app"})
        core  (get files "src/app/core.cljs")]
    (is (str/includes? core "baredom.exports.all"))
    (is (str/includes? core "app.renderer"))
    (is (str/includes? core "init"))
    (is (str/includes? core "app.db"))))

(deftest generated-views-use-hiccup
  (let [files (cp/generate (fixture-doc) {:app-ns "app"})
        ;; x-navbar is unnamed in the fixture, so its hiccup now
        ;; lives inline in app/core.cljs rather than in its own
        ;; views file.
        core  (get files "src/app/core.cljs")]
    (is (some? core))
    (testing "uses hiccup, not raw JS"
      (is (str/includes? core ":x-navbar"))
      (is (not (str/includes? core "js/document.createElement"))))))

(deftest generated-core-uses-renderer
  (let [files (cp/generate (fixture-doc) {:app-ns "app"})
        core  (get files "src/app/core.cljs")]
    (is (str/includes? core "[app.framework :as rf]")
        "core must require the framework to reach the store")
    (is (str/includes? core "renderer/mount!")
        "entry point mounts (reactive) rather than calling the one-shot render!")
    (is (str/includes? core "(rf/get-store)")
        "wires the framework store so the renderer re-paints on change")))

(deftest generated-aliases-use-dot-notation
  (let [files (cp/generate (fixture-doc) {:app-ns "app"})
        db    (get files "src/app/db.cljs")
        core  (get files "src/app/core.cljs")]
    (testing "db.cljs uses dot-notation aliases"
      (is (str/includes? db "cart.db"))
      (is (not (str/includes? db "cart-db"))))
    (testing "core.cljs uses dot-notation aliases for named-group view requires"
      ;; cart is a named group nested inside the unnamed x-navbar —
      ;; the inline walk picks it up as a named descendant and pulls
      ;; in the alias.
      (is (str/includes? core "cart.views"))
      (is (not (str/includes? core "cart-views"))))))

(deftest generated-framework-has-core-api
  (let [files (cp/generate (fixture-doc) {:app-ns "app"})
        fw    (get files "src/app/framework.cljs")]
    (is (str/includes? fw "reg-sub"))
    (is (str/includes? fw "reg-event"))
    (is (str/includes? fw "dispatch"))
    (is (str/includes? fw "subscribe!"))
    (is (str/includes? fw "init-store!"))))

(deftest generated-root-db-merges-group-dbs
  (let [files (cp/generate (fixture-doc) {:app-ns "app"})
        db    (get files "src/app/db.cljs")]
    (is (str/includes? db "init-store!"))
    (is (str/includes? db "merge"))))

(deftest generated-subs-direct-extraction-for-stored
  (let [files (cp/generate (fixture-doc) {:app-ns "app"})
        cart-subs (get files "src/app/cart/subs.cljs")]
    (is (some? cart-subs))
    (testing "stored field gets 3-arity :-> direct extraction from root db"
      (is (str/includes? cart-subs "(rf/reg-sub\n ::cart-items\n :-> ::cart.db/cart-items)")
          "cart-items is stored → direct extraction"))
    (testing "no tier-1 indirection"
      (is (not (str/includes? cart-subs "core.subs"))
          "no core.subs require anywhere"))))

(deftest generated-subs-derived-for-computed
  (let [files     (cp/generate (fixture-doc) {:app-ns "app"})
        cart-subs (get files "src/app/cart/subs.cljs")
        cart-db   (get files "src/app/cart/db.cljs")]
    (testing "computed field emits a derived :<- / :-> sub on the source"
      (is (str/includes? cart-subs
                         "(rf/reg-sub\n ::cart-count\n :<- [::cart-items]\n :-> count)")
          "cart-count is derived via count-of :cart-items"))
    (testing "computed field does NOT get a direct :-> ::cart.db/... sub"
      (is (not (str/includes? cart-subs ":-> ::cart.db/cart-count"))))
    (testing "computed field has no default-db entry"
      (is (not (str/includes? cart-db "::cart-count"))
          "cart-count is not stored in the root db"))
    (testing "computed field has no spec def (it's not in the db)"
      (is (not (str/includes? cart-db "(s/def ::cart-count"))))))

(deftest generated-events-include-field-setters
  (let [files      (cp/generate (fixture-doc) {:app-ns "app"})
        cart-events (get files "src/app/cart/events.cljs")]
    (is (some? cart-events)
        "cart group has fields, so events.cljs must be emitted even without explicit event bindings")
    (testing "auto setter for each STORED field, opens on its own line"
      (is (str/includes? cart-events "(rf/reg-event\n ::cart-items-changed")
          "emits the cart-items-changed setter with name on a new line"))
    (testing "computed fields get no auto setter"
      (is (not (str/includes? cart-events "::cart-count-changed"))
          "cart-count is computed → no setter event"))
    (testing "interceptor chain is [rf/trim-v (rf/path ...)]"
      (is (str/includes? cart-events "(rf/path ::cart.db/cart-items)")))
    (testing "handler body is the positional value — path writes it back"
      (is (str/includes? cart-events " (fn [_ [new-cart-items]]\n   new-cart-items)")
          "handler returns the new value directly; no explicit assoc needed"))
    (testing "requires the group's own db namespace"
      (is (str/includes? cart-events "[app.cart.db :as cart.db]")))))

(deftest generated-events-skip-setter-on-declared-action-collision
  ;; Patch the cart group node to declare an action named cart-count-changed,
  ;; colliding with the auto setter for the :cart-count field. The declared
  ;; action should win; the auto setter is skipped.
  (let [doc       (fixture-doc)
        cart-path (m/path-to doc "13")
        patched   (assoc-in doc (conj cart-path :actions)
                            [{:name :cart-count-changed
                              :operation :increment
                              :target-field :cart-count}])
        files       (cp/generate patched {:app-ns "app"})
        cart-events (get files "src/app/cart/events.cljs")]
    (is (some? cart-events))
    (testing "only ONE reg-event for ::cart-count-changed exists (declared wins)"
      (is (= 1 (count (re-seq #"::cart-count-changed" cart-events)))
          "the auto setter was skipped in favour of the declared action"))
    (testing "declared action body wins — uses (inc v), not positional assoc"
      (is (str/includes? cart-events "(inc v)")
          "increment operation emitted in the handler"))))

(deftest generated-framework-supports-three-arities
  (let [files (cp/generate (fixture-doc) {:app-ns "app"})
        fw    (get files "src/app/framework.cljs")]
    (testing "no forward declares anywhere — rule 3, order defs so each is defined before use"
      (is (not (re-find #"\(declare " fw))
          "blanket: no `(declare ` form anywhere in the emitted framework")
      (let [compute-idx (str/index-of fw "(defn- compute-sub")
            reg-sub-idx (str/index-of fw "(defn reg-sub")]
        (is (and compute-idx reg-sub-idx (< compute-idx reg-sub-idx))
            "compute-sub defined before reg-sub")))
    (testing "reg-sub is variadic — supports plain, :->, :<-/:->, multi-:<-"
      ;; The canonical framework.cljs carries a docstring between
      ;; `(defn reg-sub` and its arg vector, so match the vector
      ;; shape on its own rather than concatenated with the name.
      (is (re-find #"\[id & args\]" fw)
          "variadic signature")
      (is (str/includes? fw "parse-sub-args")
          "arg parsing helper"))
    (testing "reg-event supports interceptors + trim-v + path are exported"
      (is (str/includes? fw "(def trim-v")
          "trim-v is a var other code can reference as rf/trim-v")
      (is (str/includes? fw "(defn path")
          "path is a fn that returns an interceptor, callable as rf/path")
      (is (str/includes? fw "([id interceptors handler-fn]")
          "3-arity reg-event arm takes an interceptor vector"))
    (testing "inlined canonical carries `reset-all!` (the pre-inline copy stripped it)"
      (is (str/includes? fw "(defn reset-all!")
          "test-only helper is emitted alongside the library API"))
    (testing "ns rewritten — no reference to Bareforge's own framework ns"
      (is (not (str/includes? fw "bareforge.export.cljs-project.framework"))
          "the source-of-truth ns is replaced with the app-ns prefix"))))

(deftest generated-framework-matches-canonical-source
  ;; Rule 1 pins the emitted framework as the re-frame subset every
  ;; exported project carries. `generate-framework` uses
  ;; shadow.resource/inline + one ns-prefix str/replace-first, so the
  ;; emitted file must equal the canonical source with just the `ns`
  ;; line rewritten. Any body-touching drift (a re-introduced manual
  ;; copy, a post-process step, injected comments) will fail this
  ;; assertion with a clean diff instead of silently shipping a
  ;; divergent second copy into every exported project.
  (let [fs        (js/require "fs")
        canonical (.readFileSync fs "src/bareforge/export/cljs_project/framework.cljs" "utf8")
        expected  (str/replace-first canonical
                                     "bareforge.export.cljs-project.framework"
                                     "myapp.framework")
        files     (cp/generate (fixture-doc) {:app-ns "myapp"})
        emitted   (get files "src/myapp/framework.cljs")]
    (is (= expected emitted)
        "emitted framework = canonical with only the ns prefix rewritten")))

(deftest generated-renderer-matches-canonical-source
  ;; Same lockstep as the framework. `generate-renderer` inlines
  ;; `src/bareforge/export/cljs_project/renderer.cljs` and only
  ;; rewrites the ns line. Any body-touching drift (reconciler
  ;; semantics getting re-embedded as a string literal, accidental
  ;; post-processing) fails here with a clean diff.
  (let [fs        (js/require "fs")
        canonical (.readFileSync fs "src/bareforge/export/cljs_project/renderer.cljs" "utf8")
        expected  (str/replace-first canonical
                                     "bareforge.export.cljs-project.renderer"
                                     "myapp.renderer")
        files     (cp/generate (fixture-doc) {:app-ns "myapp"})
        emitted   (get files "src/myapp/renderer.cljs")]
    (is (= expected emitted)
        "emitted renderer = canonical with only the ns prefix rewritten")))

(deftest generated-trigger-honours-prevent-default
  ;; The demo-store popover's `x-popover-toggle` trigger carries
  ;; `:prevent-default? true` so we can pin the cart without the
  ;; popover's own open/close fighting our db.
  (let [files (cp/generate (fixture-doc) {:app-ns "app"})
        cart  (get files "src/app/cart/views.cljs")]
    (is (some? cart))
    (is (str/includes?
          cart
          ":on-x-popover-toggle (fn [^js e] (.preventDefault e) (rf/dispatch [::cart.events/cart-pinned-toggled])")
        "trigger handler calls preventDefault before dispatching")))

(deftest generated-view-dispatches-cross-group-action
  ;; The fixture declares `::add-to-cart` on the cart group and binds the
  ;; product button's :on-press to `::app.cart.events/add-to-cart` with
  ;; payload [{:field :id :owner "product"}]. Product is a collection
  ;; group: the view's record arg is destructured via namespaced
  ;; `{:keys [::product.db/id ...]}`, so the dispatch reads the plain
  ;; `id` symbol.
  (let [files     (cp/generate (fixture-doc) {:app-ns "app"})
        prod-view (get files "src/app/product/views.cljs")]
    (is (some? prod-view))
    (testing "view requires the target group's events namespace"
      (is (str/includes? prod-view "[app.cart.events :as cart.events]")
          "cart.events added to the require list"))
    (testing "collection view destructures its own db's namespaced keys"
      (is (str/includes? prod-view "[app.product.db :as product.db]"))
      (is (str/includes? prod-view
                         "(defn product [{:keys [::product.db/id ::product.db/title ::product.db/price] :as record}]")))
    (testing "dispatch reads the plain destructured symbol"
      (is (str/includes? prod-view
                         "#(rf/dispatch [::cart.events/add-to-cart id])")))))

(deftest generated-events-action-add-body
  (let [files       (cp/generate (fixture-doc) {:app-ns "app"})
        cart-events (get files "src/app/cart/events.cljs")]
    (is (some? cart-events))
    (testing "declared :add action emits conj on the narrowed field"
      (is (str/includes? cart-events "::add-to-cart")
          "action reg-event emitted")
      (is (re-find #"\[rf/trim-v\n\s+\(rf/path ::cart\.db/cart-items\)\]"
                   cart-events)
          "interceptor chain with trim-v and path-to-cart-items")
      (is (re-find #"\(fn \[v \[x\]\]\n\s+\(conj v \(rf/qualify-map x \"app\.cart-item\.db\"\)\)\)"
                   cart-events)
          "body conj's the rf/qualify-map'd payload onto the narrowed field"))))

(deftest generated-collection-db-holds-records-on-owning-group
  ;; Seed records for the :products collection live on product-feed's
  ;; db (its owning field's :default), not on the product template.
  (let [files   (cp/generate (fixture-doc) {:app-ns "app"})
        feed-db (get files "src/app/product_feed/db.cljs")
        prod-db (get files "src/app/product/db.cljs")]
    (is (some? feed-db))
    (testing "db key is the user-declared field name, not a plural inferred from the group"
      (is (str/includes? feed-db "::products ["))
      (is (str/includes? feed-db "(s/coll-of ::product.db/record :kind vector?)")))
    (testing "three seed records carried from :default with ::-auto-resolved keys"
      (is (str/includes? feed-db "::product.db/title \"Widget\""))
      (is (str/includes? feed-db "::product.db/title \"Gadget\""))
      (is (str/includes? feed-db "::product.db/title \"Gizmo\""))
      (is (str/includes? feed-db "::product.db/price \"$9.99\""))
      (is (str/includes? feed-db "::product.db/price \"$24.99\""))
      (is (str/includes? feed-db "::product.db/price \"$14.99\"")))
    (testing "each record has an auto-resolved ::id from the seed"
      (is (str/includes? feed-db "::product.db/id 1"))
      (is (str/includes? feed-db "::product.db/id 2"))
      (is (str/includes? feed-db "::product.db/id 3")))
    (testing "keys within a record are emitted one per line, no commas"
      (is (str/includes? feed-db "::product.db/id 1\n")
          "first key followed by newline — not a comma — before the next key")
      (is (not (str/includes? feed-db ", ::product.db/"))
          "no commas between keys"))
    (testing "product's own db is specs-only (no default-db, no plural key)"
      (is (some? prod-db))
      (is (not (str/includes? prod-db "default-db")))
      (is (not (str/includes? prod-db "::products"))))))

(deftest generated-collection-subs-expose-vector
  ;; Subs live on the owning group (product-feed), not the template.
  (let [files     (cp/generate (fixture-doc) {:app-ns "app"})
        feed-subs (get files "src/app/product_feed/subs.cljs")
        prod-subs (get files "src/app/product/subs.cljs")]
    (is (some? feed-subs))
    (is (nil? prod-subs)
        "product is a template group — no subs file")
    (testing "single sub returning the vector, named after the field"
      (is (str/includes? feed-subs
                         "(rf/reg-sub\n ::products\n :-> ::product-feed.db/products)")))))

(deftest generated-collection-view-reads-from-record
  (let [files     (cp/generate (fixture-doc) {:app-ns "app"})
        prod-view (get files "src/app/product/views.cljs")]
    (is (some? prod-view))
    (testing "view fn destructures the record with namespaced :keys"
      (is (str/includes? prod-view
                         "(defn product [{:keys [::product.db/id ::product.db/title ::product.db/price] :as record}]")))
    (testing "text-field-bound typography text reads the destructured symbol"
      (is (str/includes? prod-view "{:variant \"h5\"} title]"))
      (is (str/includes? prod-view "{:variant \"body1\"} price]")))
    (testing "literal Widget/Gadget/Gizmo text is gone from the view"
      (is (not (str/includes? prod-view "\"Widget\"")))
      (is (not (str/includes? prod-view "\"Gadget\"")))
      (is (not (str/includes? prod-view "\"Gizmo\""))))))

(deftest generated-parent-iterates-collection
  (let [files (cp/generate (fixture-doc) {:app-ns "app"})
        feed  (get files "src/app/product_feed/views.cljs")]
    (is (some? feed)
        "parent of the collection is emitted")
    (testing "iterates the owning group's sub — the field name drives the sub name"
      (is (re-find #"\(for \[p \(rf/query \[::product-feed\.subs/products\]\)\]"
                   feed)))
    (testing "only ONE call to product.views/product, inside the for"
      (is (= 1 (count (re-seq #"product\.views/product" feed)))))))

(deftest generated-x-grid-columns-coerces-bare-integer
  ;; The fixture's product-feed carries `columns "1"` — a bare integer
  ;; string, which is invalid CSS for `grid-template-columns` (forces
  ;; one child per row). The generator coerces it to `repeat(N, 1fr)`
  ;; so the emitted hiccup is valid regardless of what the canvas doc
  ;; stored.
  (let [files (cp/generate (fixture-doc) {:app-ns "app"})
        feed  (get files "src/app/product_feed/views.cljs")]
    (is (some? feed))
    (testing "bare integer columns is coerced to repeat(N, 1fr)"
      (is (str/includes? feed ":columns \"repeat(1, 1fr)\"")
          "the generator substitutes a valid CSS track list"))
    (testing "the literal bad value never reaches the emitted hiccup"
      (is (not (re-find #":columns\s+\"1\"(?!\w|fr|,)" feed))
          "no `:columns \"1\"` (bare integer string) in the output"))))

(deftest generated-x-grid-columns-track-list-passes-through
  ;; When the doc already carries a valid track list (e.g. the
  ;; cart-item grid's `"1fr auto auto"`), the generator leaves it
  ;; alone — coercion only kicks in for bare integers.
  (let [files     (cp/generate (fixture-doc) {:app-ns "app"})
        cart-item (get files "src/app/cart_item/views.cljs")]
    (is (some? cart-item))
    (is (str/includes? cart-item ":columns \"1fr auto auto\"")
        "valid track list is preserved verbatim")))

(deftest generated-source-sub-collection-omits-subs-file
  ;; `cart-item` is a collection group backed by `:source-sub
  ;; :app.cart.subs/cart-with-products` — its records live behind the
  ;; cart group's join sub, not in its own db. An `(rf/reg-sub ::cart-items
  ;; :-> ::cart-item.db/cart-items)` would always return nil, so no
  ;; subs.cljs is emitted for this group.
  (let [files (cp/generate (fixture-doc) {:app-ns "app"})]
    (is (not (contains? files "src/app/cart_item/subs.cljs"))
        "sub-backed collection group emits no subs.cljs")
    (is (contains? files "src/app/cart_item/db.cljs")
        "db.cljs still emitted (default-db {} keeps the root merge well-formed)")
    (is (contains? files "src/app/cart_item/views.cljs")
        "views.cljs still emitted — the record view is the whole point")))

(deftest core-never-imports-unnamed-wrapper-namespaces
  ;; With the "naming = group" rule, unnamed root-level wrappers
  ;; (x-navbar, empty-state x-card) render as inline hiccup inside
  ;; core.cljs. They have no namespace file, so core mustn't import
  ;; one for them.
  (let [files (cp/generate (fixture-doc) {:app-ns "app"})
        core  (get files "src/app/core.cljs")]
    (is (some? core))
    (is (not (str/includes? core "[app.navbar.views"))
        "no phantom navbar namespace")
    (is (not (str/includes? core "[app.card.views"))
        "no phantom card namespace")
    (testing "core does import the real named-group views used in the inline walk"
      (is (str/includes? core "[app.cart.views :as cart.views]")
          "cart is nested inside navbar → pulled in as an inline named descendant")
      (is (str/includes? core "[app.product-feed.views :as product-feed.views]")))))

(deftest generated-view-with-triggers-keeps-framework-require
  ;; Product dispatches to cart.events → uses rf/dispatch → rf must
  ;; be required even when the view has no let-bindings.
  (let [files (cp/generate (fixture-doc) {:app-ns "app"})
        prod  (get files "src/app/product/views.cljs")]
    (is (some? prod))
    (is (str/includes? prod "[app.framework :as rf]")
        "dispatches require rf")))

(deftest generated-parent-of-collection-keeps-framework-require
  ;; product-feed itself has no bindings or events, but it iterates
  ;; the product collection with `(rf/query …)` → rf is required.
  (let [files (cp/generate (fixture-doc) {:app-ns "app"})
        feed  (get files "src/app/product_feed/views.cljs")]
    (is (some? feed))
    (is (str/includes? feed "[app.framework :as rf]")
        "collection iteration uses rf/query → require is kept")))

(deftest generated-collection-has-no-auto-setters
  (let [files    (cp/generate (fixture-doc) {:app-ns "app"})
        prod-ev  (get files "src/app/product/events.cljs")]
    (is (nil? prod-ev)
        "no events.cljs emitted — collection groups skip auto-setters and product has no declared actions")))

(deftest generated-iteration-wraps-in-display-contents-div
  ;; Every template-group iteration is wrapped in a `display: contents`
  ;; div so the reconciler has a stable position for the for-loop.
  ;; Without the wrapper, a shrinking list's tail shifts into trigger-
  ;; slotted siblings (x-icon, x-badge), and portal-using components
  ;; like x-popover end up with replace-branch fallout that accumulates
  ;; stale DOM in the floating panel. See the reconciler's
  ;; `stored children` comment in renderer.cljs for the companion half.
  (let [files     (cp/generate (fixture-doc) {:app-ns "app"})
        cart-view (get files "src/app/cart/views.cljs")
        feed-view (get files "src/app/product_feed/views.cljs")]
    (is (some? cart-view))
    (testing "cart iteration is wrapped"
      (is (re-find #"\[:div \{:style \"display: contents\"\}\n\s+\(for \[p \(rf/query \[::cart\.subs/"
                   cart-view)
          "cart/views.cljs emits the wrapper div immediately before the for"))
    (testing "product-feed iteration is wrapped the same way"
      (is (re-find #"\[:div \{:style \"display: contents\"\}\n\s+\(for \[p \(rf/query \[::product-feed\.subs/"
                   feed-view)
          "product_feed/views.cljs emits the same wrapper pattern"))))

(deftest generated-events-action-remove-body
  (let [files       (cp/generate (fixture-doc) {:app-ns "app"})
        cart-events (get files "src/app/cart/events.cljs")]
    (is (some? cart-events))
    (testing ":remove op emits filterv on the narrowed field"
      (is (str/includes? cart-events "::remove-from-cart"))
      (is (re-find #"\(fn \[v \[x\]\]\n\s+\(filterv #\(not= % \(rf/qualify-map x \"app\.cart-item\.db\"\)\) v\)\)"
                   cart-events)
          "body filterv wraps x with rf/qualify-map so equality matches qualified records"))))


(deftest generated-hiccup-props-one-per-line-when-multiple
  (let [files  (cp/generate (fixture-doc) {:app-ns "app"})
        ;; x-navbar is unnamed in the fixture — its hiccup lives in
        ;; core.cljs via the inline walk.
        navbar (get files "src/app/core.cljs")]
    (is (some? navbar))
    (testing "a 2-prop x-navbar is emitted one-prop-per-line"
      ;; If the two navbar attrs were inline we'd see both on one line.
      (is (re-find #"\[:x-navbar \{:elevated true\n\s+:variant \"default\"\}"
                   navbar)
          "x-navbar props spread across lines"))))

;; --- v1 op set: empty-of, sum-of with :project-field ----------------------

(defn- doc-with-group
  "Build a minimal document around a single named x-container group
   whose :fields vector is `fields`. The group carries one inner leaf
   so `group-candidate?` considers it a real group. Used to test
   targeted generator behaviour without relying on the demo-store
   fixture."
  [group-name fields]
  {:root    {:id    "root"
             :tag   "x-container"
             :attrs {}
             :props {}
             :text  nil
             :layout {:placement :flow}
             :slots  {"default"
                      [{:id    "g1"
                        :tag   "x-grid"
                        :name  group-name
                        :attrs {}
                        :props {}
                        :text  nil
                        :layout {:placement :flow}
                        :slots  {"default"
                                 [{:id    "leaf"
                                   :tag   "x-typography"
                                   :attrs {} :props {} :text ""
                                   :layout {:placement :flow}
                                   :slots  {}}]}
                        :fields fields}]}}
   :canvas  {:width 1200 :content-col {:left 160 :right 1040}}
   :next-id 3})

(deftest emit-empty-of-extractor
  (let [doc   (doc-with-group "feed"
                              [{:name :items    :type :vector :default []}
                               {:name :empty?   :type :boolean
                                :computed {:operation :empty-of :source-field :items}}])
        files (cp/generate doc {:app-ns "app"})
        subs  (get files "src/app/feed/subs.cljs")]
    (is (some? subs) "feed/subs.cljs was emitted")
    (is (str/includes? subs "::empty?") "computed sub present")
    (is (str/includes? subs ":-> empty?")
        "empty-of compiles to the `empty?` extractor")))

(deftest emit-sum-of-with-project-field
  (let [doc   (doc-with-group "cart"
                              [{:name :items     :type :vector :of-group "product"
                                :default []}
                               {:name :total     :type :number
                                :computed {:operation :sum-of
                                           :source-field :items
                                           :project-field :price}}])
        files (cp/generate doc {:app-ns "app"})
        subs  (get files "src/app/cart/subs.cljs")]
    (is (some? subs))
    (is (str/includes? subs "#(transduce (map ::product.db/price) + 0 %)")
        "sum-of over records emits the namespaced transduce form")))

(deftest emit-sum-of-without-project-field
  (let [doc   (doc-with-group "board"
                              [{:name :scores :type :vector :default []}
                               {:name :total  :type :number
                                :computed {:operation :sum-of :source-field :scores}}])
        files (cp/generate doc {:app-ns "app"})
        subs  (get files "src/app/board/subs.cljs")]
    (is (some? subs))
    (is (str/includes? subs ":-> #(reduce + 0 %)")
        "sum-of without a project-field falls back to the plain reducer")))

(deftest unknown-computed-op-throws-at-export-time
  ;; v1 closes the computed-op set (CLAUDE.md rule 12). A doc carrying
  ;; an unrecognised op (pre-v1 :first-of, a typo, hand-edited JSON)
  ;; must fail loudly at `cp/generate` rather than emit a malformed
  ;; extractor that only blows up when the generated project compiles.
  (let [doc (doc-with-group "feed"
                            [{:name :items :type :vector :default []}
                             {:name :head  :type :any
                              :computed {:operation :first-of :source-field :items}}])]
    (is (thrown-with-msg? js/Error #"Unknown computed operation"
                          (cp/generate doc {:app-ns "app"}))
        "emit-computed-sub throws on unknown ops instead of emitting :!unknown-op-*")))

(deftest action-on-scalar-target-does-not-qualify-payload
  ;; A :toggle on a boolean field stays payload-free — qualify-map
  ;; wrapping only applies to payloads destined for a
  ;; collection-of-group field.
  (let [doc   (doc-with-group "cart"
                              [{:name :open? :type :boolean :default false}])
        doc*  (assoc-in doc [:root :slots "default" 0 :actions]
                        [{:name :toggle-open
                          :operation :toggle
                          :target-field :open?}])
        files (cp/generate doc* {:app-ns "app"})
        ev    (get files "src/app/cart/events.cljs")]
    (is (some? ev))
    (is (str/includes? ev "(fn [v _]\n   (not v))")
        ":toggle body stays payload-free")
    (is (not (str/includes? ev "rf/qualify-map"))
        "no qualify-map wrapping on a scalar target")))

;; --- implicit payload on triggers ------------------------------------------

(deftest implicit-payload-dispatches-record
  (let [prod-view (get (cp/generate (fixture-doc) {:app-ns "app"})
                       "src/app/product/views.cljs")]
    ;; The fixture still carries an explicit {:field :id :owner "product"}
    ;; payload on the Add-to-cart button, so we can't rely on it for the
    ;; implicit path. What we can assert: the template fn signature now
    ;; carries `:as record`, so templates with no :payload would dispatch
    ;; record as-is.
    (is (str/includes? prod-view ":as record}")
        "template view destructures with :as record so implicit payload has a binding")))

(deftest implicit-payload-outside-template-takes-no-arg
  ;; A trigger with no :payload on a node outside any template group
  ;; should dispatch with no extra arg. We model this with a single-
  ;; group doc that has an in-line button triggering one of its own
  ;; actions with no payload.
  (let [doc   {:root    {:id    "root"
                         :tag   "x-container"
                         :attrs {} :props {} :text nil
                         :layout {:placement :flow}
                         :slots  {"default"
                                  [{:id    "g1"
                                    :tag   "x-grid"
                                    :name  "cart"
                                    :attrs {} :props {} :text nil
                                    :layout {:placement :flow}
                                    :fields [{:name :open? :type :boolean
                                              :default false}]
                                    :actions [{:name :toggle-open
                                               :operation :toggle
                                               :target-field :open?}]
                                    :slots  {"default"
                                             [{:id    "b1"
                                               :tag   "x-button"
                                               :attrs {} :props {}
                                               :text  "toggle"
                                               :inner-html nil
                                               :layout {:placement :flow}
                                               :slots {}
                                               :events [{:trigger "press"
                                                         :action-ref :app.cart.events/toggle-open}]}]}}]}}
               :canvas  {:width 1200 :content-col {:left 160 :right 1040}}
               :next-id 3}
        files (cp/generate doc {:app-ns "app"})
        view  (get files "src/app/cart/views.cljs")]
    (is (some? view))
    (is (str/includes? view "#(rf/dispatch [::cart.events/toggle-open])")
        "no template ancestor → dispatch fires with no arg")))

(deftest iteration-falls-back-to-single-collection-when-source-field-missing
  ;; The user adds a collection field pointing at a template group, but
  ;; doesn't wire the template instance's :source-field through the
  ;; inspector. The export should auto-resolve from the (single)
  ;; collection that targets the template, rather than throwing.
  (let [doc   {:root   {:id "root" :tag "x-container"
                        :attrs {} :props {} :text nil :inner-html nil
                        :layout {:placement :flow}
                        :slots
                        {"default"
                         [{:id    "feed"
                           :tag   "x-grid"
                           :name  "product-feed"
                           :attrs {} :props {} :text nil :inner-html nil
                           :layout {:placement :flow}
                           :fields [{:name :products :type :vector
                                     :of-group "product"
                                     :default [{:id 1 :title "Widget"}]}]
                           :slots
                           {"default"
                            [;; product template instance — no :source-field
                             {:id    "prod"
                              :tag   "x-card"
                              :name  "product"
                              :attrs {} :props {} :text nil :inner-html nil
                              :layout {:placement :flow}
                              :fields [{:name :id :type :number :default 0
                                        :locked? true}
                                       {:name :title :type :string :default ""}]
                              :slots
                              {"default"
                               [{:id    "title"
                                 :tag   "x-typography"
                                 :attrs {} :props {}
                                 :text  "placeholder"
                                 :inner-html nil
                                 :layout {:placement :flow}
                                 :slots  {}
                                 :text-field :title}]}}]}}]}}
               :canvas  {:width 1200 :content-col {:left 160 :right 1040}}
               :next-id 5}
        files (cp/generate doc {:app-ns "app"})
        view  (get files "src/app/product_feed/views.cljs")]
    (is (some? view) "product-feed view emits without error")
    (is (str/includes? view "(for [p (rf/query [::product-feed.subs/products])]")
        "auto-resolved to the single collection field pointing at the template")
    (is (str/includes? view "[app.product-feed.subs :as product-feed.subs]")
        "require list includes the fallback-resolved subs namespace")))

(deftest text-field-owner-disambiguates-shared-field-names
  ;; Two stateful groups both declare a :title field. An x-typography
  ;; descendant of the first group has :text-field :title with
  ;; :text-field-owner "alpha" (the user's explicit pick). The export
  ;; must resolve the let-binding against `alpha.subs` — not whichever
  ;; group document-order walks into first.
  (let [doc   {:root   {:id "root" :tag "x-container"
                        :attrs {} :props {} :text nil :inner-html nil
                        :layout {:placement :flow}
                        :slots
                        {"default"
                         [;; Beta comes first in document order so the
                          ;; naive field-owner walk would win "beta" for
                          ;; :title. Beta has a :title field but no
                          ;; typography uses it here.
                          {:id    "beta-grp"
                           :tag   "x-card"
                           :name  "beta"
                           :attrs {} :props {} :text nil :inner-html nil
                           :layout {:placement :flow}
                           :fields [{:name :id :type :number :default 0
                                     :locked? true}
                                    {:name :title :type :string :default ""}]
                           :slots {"default" [{:id "beta-leaf"
                                               :tag "x-typography"
                                               :attrs {} :props {}
                                               :text "x" :inner-html nil
                                               :layout {:placement :flow}
                                               :slots {}}]}}
                          ;; Alpha is declared AFTER beta in the tree.
                          {:id    "alpha-grp"
                           :tag   "x-card"
                           :name  "alpha"
                           :attrs {} :props {} :text nil :inner-html nil
                           :layout {:placement :flow}
                           :fields [{:name :id :type :number :default 0
                                     :locked? true}
                                    {:name :title :type :string :default ""}]
                           :slots
                           {"default"
                            [{:id    "a-title"
                              :tag   "x-typography"
                              :attrs {} :props {} :text "placeholder"
                              :inner-html nil
                              :layout {:placement :flow}
                              :slots {}
                              :text-field :title
                              :text-field-owner "alpha"}]}}]}}
               :canvas  {:width 1200 :content-col {:left 160 :right 1040}}
               :next-id 6}
        files (cp/generate doc {:app-ns "app"})
        alpha-view (get files "src/app/alpha/views.cljs")]
    (is (some? alpha-view))
    (is (str/includes? alpha-view
                       "title (rf/query [::alpha.subs/title])")
        "text-field with :text-field-owner resolves to the named group's subs")
    (is (not (str/includes? alpha-view
                            "title (rf/query [::beta.subs/title])"))
        "does NOT resolve to the document-order-first group")))

(deftest named-group-wrapped-in-unnamed-containers-is-still-rendered-as-group-call
  ;; Decorative design pattern: the user wants a background blur + a
  ;; padded container around their product feed group. Neither wrapper
  ;; carries a :name. The generator must walk through them and still
  ;; emit (product-feed.views/product-feed) at the inner node's
  ;; position — not inline its contents into core.cljs.
  (let [doc   {:root   {:id "root" :tag "x-container"
                        :attrs {} :props {} :text nil :inner-html nil
                        :layout {:placement :flow}
                        :slots
                        {"default"
                         [{:id    "blur"
                           :tag   "x-gaussian-blur"
                           :attrs {"opacity" "0.3"} :props {} :text nil
                           :inner-html nil :layout {:placement :flow}
                           :slots
                           {"default"
                            [{:id    "wrap"
                              :tag   "x-container"
                              :attrs {"padding" "md"} :props {} :text nil
                              :inner-html nil :layout {:placement :flow}
                              :slots
                              {"default"
                               [{:id    "feed"
                                 :tag   "x-grid"
                                 :name  "product-feed"
                                 :attrs {} :props {} :text nil
                                 :inner-html nil :layout {:placement :flow}
                                 :fields [{:name :id :type :number :default 0
                                           :locked? true}]
                                 :slots {"default"
                                         [{:id "leaf" :tag "x-typography"
                                           :attrs {} :props {} :text ""
                                           :layout {:placement :flow}
                                           :slots {}}]}}]}}]}}]}}
               :canvas  {:width 1200 :content-col {:left 160 :right 1040}}
               :next-id 5}
        files (cp/generate doc {:app-ns "app"})
        core  (get files "src/app/core.cljs")]
    (testing "core.cljs inlines the two wrappers but calls product-feed as a group"
      (is (some? core))
      (is (str/includes? core ":x-gaussian-blur")
          "outer wrapper stays inline hiccup")
      (is (str/includes? core ":x-container")
          "intermediate wrapper stays inline hiccup")
      (is (str/includes? core "(product-feed.views/product-feed)")
          "named group at any depth becomes a views/* call"))
    (testing "core.cljs requires the product-feed views alias"
      (is (str/includes? core "[app.product-feed.views :as product-feed.views]")))
    (testing "no phantom namespace for unnamed wrappers"
      (is (not (contains? files "src/app/x-gaussian-blur/views.cljs")))
      (is (not (contains? files "src/app/x-container/views.cljs"))))
    (testing "the product-feed view is still generated end-to-end"
      (is (contains? files "src/app/product_feed/views.cljs"))
      (is (contains? files "src/app/product_feed/db.cljs")))))

(deftest inline-wrapper-text-field-resolves-via-let-in-core
  ;; A :text-field on an x-typography inside an unnamed wrapper needs
  ;; core.cljs to wrap the inline subtree in a (let [<f> (rf/query …)])
  ;; pulling from the owning group's subs. Without this, the walk has
  ;; no field->sym binding and falls back to literal :text.
  (let [doc   {:root   {:id "root" :tag "x-container"
                        :attrs {} :props {} :text nil :inner-html nil
                        :layout {:placement :flow}
                        :slots
                        {"default"
                         [{:id    "wrap"
                           :tag   "x-gaussian-blur"
                           :attrs {} :props {} :text nil
                           :inner-html nil :layout {:placement :flow}
                           :slots
                           {"default"
                            [{:id    "label"
                              :tag   "x-typography"
                              :attrs {} :props {}
                              :text  "placeholder"
                              :inner-html nil
                              :layout {:placement :flow}
                              :slots {}
                              :text-field :items-count
                              :text-field-owner "feed"}
                             ;; The owning named group sits here
                             ;; alongside the typography so its
                             ;; computed field is reachable.
                             {:id    "feed"
                              :tag   "x-grid"
                              :name  "feed"
                              :attrs {} :props {} :text nil
                              :inner-html nil :layout {:placement :flow}
                              :fields [{:name :id :type :number
                                        :default 0 :locked? true}
                                       {:name :items :type :vector
                                        :default []}
                                       {:name :items-count :type :number
                                        :computed {:operation :count-of
                                                   :source-field :items}}]
                              :slots {"default"
                                      [{:id "leaf" :tag "x-typography"
                                        :attrs {} :props {} :text ""
                                        :layout {:placement :flow}
                                        :slots {}}]}}]}}]}}
               :canvas  {:width 1200 :content-col {:left 160 :right 1040}}
               :next-id 5}
        files (cp/generate doc {:app-ns "app"})
        core  (get files "src/app/core.cljs")]
    (is (some? core))
    (testing "core requires the owning group's subs alias"
      (is (str/includes? core "[app.feed.subs :as feed.subs]")))
    (testing "core wraps the inline subtree in a let that queries the field"
      (is (re-find #"\(let \[items-count \(rf/query \[::feed\.subs/items-count\]\)\]"
                   core)))
    (testing "typography body is the let-bound symbol, not the literal"
      (is (re-find #"\[:x-typography(?: \{[^}]*\})? items-count\]" core))
      (is (not (str/includes? core "\"placeholder\""))))))

;; --- filter-by -----------------------------------------------------------

(defn- product-feed-id [doc]
  ;; Fixture uses the human-readable :name "Product feed" which
  ;; slugifies to the ns-name "product-feed". Match via detect-groups
  ;; so we track whatever slugging the generator actually applies.
  (let [{:keys [groups]} (cp/detect-groups doc)]
    (some (fn [g] (when (= "product-feed" (:ns-name g))
                    (first (:instance-ids g))))
          groups)))

(defn- add-filter-by-to-fixture
  "Extend the demo-store fixture's product-feed group with a scalar
   search-term field and a :filter-by computed field over the
   existing :products collection. Returns the mutated doc."
  [doc]
  (let [pid (product-feed-id doc)]
    (-> doc
        (ops/add-field pid {:name :search-term :type :string :default ""})
        (ops/add-field pid {:name     :visible-products
                            :type     :vector
                            :of-group "product"
                            :computed
                            {:operation    :filter-by
                             :source-field :products
                             :filter-spec
                             {:search-field :search-term
                              :match-field  :title
                              :match-kind   :contains-ci}}}))))

(deftest filter-by-emits-multi-signal-sub
  (let [doc   (add-filter-by-to-fixture (fixture-doc))
        files (cp/generate doc {:app-ns "app"})
        subs  (get files "src/app/product_feed/subs.cljs")]
    (is (some? subs) "product-feed subs.cljs is emitted")
    (testing "sub is a multi-signal reg-sub over the source collection + search field"
      (is (str/includes? subs
            "(rf/reg-sub\n ::visible-products\n :<- [::products]\n :<- [::search-term]")))
    (testing "handler short-circuits on blank term"
      (is (str/includes? subs "(if (str/blank? term)"))
      (is (str/includes? subs "items")))
    (testing "non-blank path lowercases needle and compares via str/includes?"
      (is (str/includes? subs "(str/lower-case term)"))
      (is (str/includes? subs "(str/includes?"))
      (is (str/includes? subs "(str/lower-case (str (::product.db/title r)))")))
    (testing "uses filterv so the derived collection stays a vector"
      (is (str/includes? subs "(filterv")))))

(deftest filter-by-adds-required-aliases
  (let [doc   (add-filter-by-to-fixture (fixture-doc))
        files (cp/generate doc {:app-ns "app"})
        subs  (get files "src/app/product_feed/subs.cljs")]
    (testing "requires clojure.string for str/blank? / str/lower-case / str/includes?"
      (is (str/includes? subs "[clojure.string :as str]")))
    (testing "requires the target template's db ns for the match-field keyword"
      (is (str/includes? subs "[app.product.db :as product.db]")))))

(deftest filter-by-has-no-default-db-and-no-setter
  (let [doc         (add-filter-by-to-fixture (fixture-doc))
        files       (cp/generate doc {:app-ns "app"})
        feed-db     (get files "src/app/product_feed/db.cljs")
        feed-events (get files "src/app/product_feed/events.cljs")]
    (testing "computed filter-by field has no root-db entry"
      (is (not (str/includes? feed-db "::visible-products")))
      (is (not (str/includes? feed-db "::visible-products-changed"))))
    (testing "no auto setter is emitted for the filter-by field"
      (is (not (str/includes? feed-events "::visible-products-changed"))))
    (testing "the scalar search-term field DOES get a default-db entry + setter"
      (is (str/includes? feed-db "::search-term"))
      (is (str/includes? feed-events "::search-term-changed")))))

;; --- :write binding → DOM event dispatch ---------------------------------

(defn- bind-search-field-value
  "Return a doc where every x-search-field on the canvas has its
   `value` property bound to `product-feed.search-term` with
   direction :write — the binding shape produced by the inspector
   when a user picks that field via the property picker."
  [doc]
  (letfn [(walk [node]
            (cond-> node
              (= "x-search-field" (:tag node))
              (update :bindings (fnil assoc {})
                      "value" {:field :search-term
                               :direction :write
                               :owner "Product feed"})
              (:slots node)
              (update :slots
                      (fn [slots]
                        (reduce-kv (fn [acc s kids]
                                     (assoc acc s (mapv walk kids)))
                                   {} slots)))))]
    (update doc :root walk)))

(deftest write-binding-emits-on-event-dispatch
  (let [doc   (-> (fixture-doc)
                  add-filter-by-to-fixture
                  bind-search-field-value)
        files (cp/generate doc {:app-ns "app"})
        core  (get files "src/app/core.cljs")]
    (is (some? core))
    (testing "emits an :on-x-search-field-input handler on the bound element"
      (is (str/includes? core ":on-x-search-field-input")))
    (testing "handler dispatches the auto setter event"
      (is (str/includes? core "::product-feed.events/search-term-changed")))
    (testing "handler reads .detail.value from the BareDOM event"
      (is (str/includes? core "(.. e -detail -value)")))
    (testing "uses a typed fn form so the event arg is accessible"
      (is (str/includes? core "(fn [^js e] (rf/dispatch")))
    (testing "requires the owning group's events namespace"
      (is (str/includes? core "[app.product-feed.events :as product-feed.events]")))))

(deftest write-binding-unknown-tag-emits-no-handler
  ;; A write binding on a tag with no registered value-changed event
  ;; should not produce a malformed :on-nil handler — it simply skips
  ;; emission, leaving the user free to add a hand-authored trigger.
  (let [doc   (fixture-doc)
        ;; Inject a binding on the root x-container, which has no
        ;; entry in write-binding-event-names.
        doc'  (update-in doc [:root :bindings] (fnil assoc {})
                          "value" {:field :search-term
                                   :direction :write
                                   :owner "Product feed"})
        files (cp/generate (add-filter-by-to-fixture doc') {:app-ns "app"})
        core  (get files "src/app/core.cljs")]
    (is (not (str/includes? core ":on-nil")))
    (is (not (str/includes? core ":on- ")))))
