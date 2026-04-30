(ns bareforge.ui.templates
  "Built-in starter documents. Each template carries a `:category`
   so the panel can group them — `:landing` (marketing-site
   scaffolds, the original eight), `:docs` (documentation /
   long-form pages), `:dashboard` (data-heavy admin layouts), or
   `:demo` (the kinetic-showcase, an explicit feature demo of
   BareDOM's animated surface). Splitting demo concerns from
   realistic-starter concerns keeps the kinetic surface
   discoverable without making every template feel like a feature
   demo.

   Each template is a thunk that returns a fresh
   `doc.model/empty-document` progressively populated via
   `doc.ops/insert-new`, so every template respects the ops invariants
   and id generation automatically. Picking a template in the panel
   replaces `:document` in `state/app-state`, keeps the current theme,
   clears history and selection, and wipes the autosave so the
   selection doesn't bleed back on next reload."
  (:require [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.render.canvas :as canvas]
            [bareforge.state :as state]
            [bareforge.storage.indexeddb :as idb]
            [bareforge.util.dom :as u]))

;; --- helpers ---------------------------------------------------------------

(defn- add-text
  [doc parent-id slot idx variant text]
  (ops/insert-new doc parent-id slot idx "x-typography"
                  {:text text :attrs {"variant" variant}}))

(defn- add-text+align
  [doc parent-id slot idx variant text align]
  (ops/insert-new doc parent-id slot idx "x-typography"
                  {:text text :attrs {"variant" variant "align" align}}))

(defn- add-button
  [doc parent-id slot idx text variant size]
  (ops/insert-new doc parent-id slot idx "x-button"
                  {:text text :attrs {"variant" variant "size" size}}))

(defn- slot-count
  [doc parent-id slot-name]
  (count (get-in (m/get-node doc parent-id) [:slots slot-name] [])))

(defn- add-spacer
  "Insert a vertical x-spacer of the given CSS length. Helper for
   the section-rhythm spacers in the docs / dashboard / blog-post
   templates — section breaks read better with explicit air."
  [doc parent-id slot idx size]
  (ops/insert-new doc parent-id slot idx "x-spacer"
                  {:attrs {"size" size "axis" "vertical"}}))

;; --- builders --------------------------------------------------------------

(defn- saas-hero []
  (let [d0  (m/empty-document)
        ;; navbar
        {d1 :doc nav-id :id}  (ops/insert-new d0 "root" "default" 0 "x-navbar")
        {d2 :doc}             (add-text d1 nav-id "brand" 0 "h5" "Nimbus")
        {d3 :doc}             (ops/insert-new d2 nav-id "end" 0 "x-button"
                                              {:text "Features" :attrs {"variant" "ghost"}})
        {d4 :doc}             (ops/insert-new d3 nav-id "end" 1 "x-button"
                                              {:text "Pricing" :attrs {"variant" "ghost"}})
        {d5 :doc}             (ops/insert-new d4 nav-id "end" 2 "x-button"
                                              {:text "Docs" :attrs {"variant" "ghost"}})
        {d6 :doc}             (add-button d5 nav-id "actions" 0 "Sign up" "primary" "sm")
        ;; hero
        {d7 :doc}             (ops/insert-new d6 "root" "default" 1 "x-spacer"
                                              {:attrs {"size" "3rem" "axis" "vertical"}})
        {d8 :doc}             (ops/insert-new d7 "root" "default" 2 "x-badge"
                                              {:text "Just launched"
                                               :attrs {"variant" "info" "pill" ""}})
        {d9 :doc}             (add-text+align d8 "root" "default" 3 "h1"
                                              "Ship faster with less code"
                                              "center")
        {d10 :doc}            (add-text+align d9 "root" "default" 4 "body1"
                                              "Nimbus gives your team a unified API layer that deploys anywhere. Stop gluing services together — start building."
                                              "center")
        {d11 :doc}            (ops/insert-new d10 "root" "default" 5 "x-spacer"
                                              {:attrs {"size" "2rem" "axis" "vertical"}})
        ;; CTA buttons
        {d12 :doc cta-id :id} (ops/insert-new d11 "root" "default" 6 "x-grid"
                                              {:attrs {"columns" "repeat(2, 1fr)"
                                                       "gap"     "md"
                                                       "row-gap" "md"}})
        {d13 :doc}            (add-button d12 cta-id "default" 0
                                          "Get started free" "primary" "lg")
        {d14 :doc}            (add-button d13 cta-id "default" 1
                                          "Watch demo" "secondary" "lg")
        ;; stats row
        {d15 :doc}            (ops/insert-new d14 "root" "default" 7 "x-spacer"
                                              {:attrs {"size" "2rem" "axis" "vertical"}})
        {d16 :doc stats-id :id} (ops/insert-new d15 "root" "default" 8 "x-grid"
                                                {:attrs {"columns" "repeat(3, 1fr)"
                                                         "gap"     "lg"
                                                         "row-gap" "lg"}})
        {d17 :doc}            (ops/insert-new d16 stats-id "default" 0 "x-stat"
                                              {:attrs {"label" "Active teams" "value" "10,000+"
                                                       "hint"  "and growing"  "align" "center"}})
        {d18 :doc}            (ops/insert-new d17 stats-id "default" 1 "x-stat"
                                              {:attrs {"label" "Uptime"       "value" "99.9%"
                                                       "hint"  "last 12 months" "align" "center"}})
        {d19 :doc}            (ops/insert-new d18 stats-id "default" 2 "x-stat"
                                              {:attrs {"label" "Rating"       "value" "4.8 ★"
                                                       "hint"  "from 2,400 reviews" "align" "center"}})]
    d19))

(defn- bento-features []
  (let [d0  (m/empty-document)
        {d1 :doc}  (ops/insert-new d0 "root" "default" 0 "x-spacer"
                                   {:attrs {"size" "2rem" "axis" "vertical"}})
        {d2 :doc}  (add-text+align d1  "root" "default" 1 "overline" "FEATURES" "center")
        {d3 :doc}  (add-text+align d2  "root" "default" 2 "h2" "Everything you need" "center")
        {d4 :doc}  (add-text+align d3  "root" "default" 3 "body1"
                                   "A complete toolkit for modern development teams." "center")
        {d5 :doc}  (ops/insert-new d4 "root" "default" 4 "x-spacer"
                                   {:attrs {"size" "2rem" "axis" "vertical"}})
        {d6 :doc bento-id :id}
        (ops/insert-new d5 "root" "default" 5 "x-bento-grid"
                        {:attrs {"columns"    "repeat(3, 1fr)"
                                 "gap"        "lg"
                                 "row-gap"    "lg"
                                 "column-gap" "lg"}})

        ;; featured item — spans 2 columns via x-bento-item
        {d7 :doc feat-id :id}
        (ops/insert-new d6 bento-id "default" 0 "x-bento-item"
                        {:attrs {"col-span" "2" "row-span" "1"}})
        {d8 :doc card0-id :id}
        (ops/insert-new d7 feat-id "default" 0 "x-card"
                        {:attrs {"variant" "filled" "padding" "lg"}})
        {d9 :doc}  (add-text d8 card0-id "default" 0 "h4" "Blazing fast builds")
        {d10 :doc} (add-text d9 card0-id "default" 1 "body1"
                             "Sub-second hot reloads and incremental compilation that keeps you in the flow. Your changes are live before you blink.")

        ;; remaining items — single cells as cards directly in bento-grid
        add-cell
        (fn [doc title body]
          (let [idx (slot-count doc bento-id "default")
                {doc' :doc card-id :id}
                (ops/insert-new doc bento-id "default" idx "x-card"
                                {:attrs {"variant" "filled" "padding" "lg"}})
                {doc'' :doc}  (add-text doc' card-id "default" 0 "h4" title)
                {doc''' :doc} (add-text doc'' card-id "default" 1 "body1" body)]
            doc'''))

        d11 (add-cell d10 "Enterprise security"
                      "SOC 2 compliant out of the box. Audit logs, role-based access, and encryption at rest and in transit.")
        d12 (add-cell d11 "Team sync"
                      "Real-time collaboration across branches with conflict-free merging.")
        d13 (add-cell d12 "Analytics"
                      "Built-in metrics and dashboards for every deploy, endpoint, and user flow.")
        d14 (add-cell d13 "Global CDN"
                      "Edge deployments in 40+ regions. Your users get sub-50ms responses, wherever they are.")]
    d14))

(defn- scroll-story []
  (let [d0  (m/empty-document)
        {d1 :doc} (add-text+align d0 "root" "default" 0 "overline" "OUR STORY" "center")
        {d2 :doc} (add-text+align d1 "root" "default" 1 "h2"
                                  "Built by developers, for developers" "center")
        {d3 :doc} (ops/insert-new d2 "root" "default" 2 "x-spacer"
                                  {:attrs {"size" "2rem" "axis" "vertical"}})

        add-section
        (fn [doc idx title body]
          (let [{doc' :doc card-id :id}
                (ops/insert-new doc "root" "default" idx "x-card"
                                {:attrs {"variant" "outlined" "padding" "lg"}})
                {doc'' :doc}  (add-text doc' card-id "default" 0 "h3" title)
                {doc''' :doc} (add-text doc'' card-id "default" 1 "body1" body)]
            doc'''))

        d4 (add-section d3 3 "It started with a problem"
                        "We were tired of gluing together twelve different services just to ship a login page. There had to be a better way — fewer tools, less config, more building.")
        {d5 :doc} (ops/insert-new d4 "root" "default" 4 "x-organic-divider"
                                  {:attrs {"shape" "wave"}})
        d6 (add-section d5 5 "From prototype to platform"
                        "Within six months the internal tool had grown into something our friends wanted to use. We opened it up, listened to feedback, and rebuilt the hard parts twice.")
        {d7 :doc} (ops/insert-new d6 "root" "default" 6 "x-organic-divider"
                                  {:attrs {"shape" "blob-edge"}})
        d8 (add-section d7 7 "Where we are today"
                        "Trusted by over ten thousand teams across 120 countries. Still a small crew, still obsessed with developer experience, still shipping every week.")]
    d8))

(defn- pricing-table []
  (let [d0  (m/empty-document)
        {d1 :doc} (add-text+align d0 "root" "default" 0 "overline" "PRICING" "center")
        {d2 :doc} (add-text+align d1 "root" "default" 1 "h2"
                                  "Simple, transparent pricing" "center")
        {d3 :doc} (add-text+align d2 "root" "default" 2 "body1"
                                  "No hidden fees. Cancel anytime." "center")
        {d4 :doc} (ops/insert-new d3 "root" "default" 3 "x-spacer"
                                  {:attrs {"size" "2rem" "axis" "vertical"}})
        {d5 :doc grid-id :id}
        (ops/insert-new d4 "root" "default" 4 "x-grid"
                        {:attrs {"columns" "repeat(3, 1fr)" "gap" "lg" "row-gap" "lg"}})

        spacer-attrs {"size" "0.5rem" "axis" "vertical"}

        add-pricing-card
        (fn [doc card-variant plan price caption features btn-variant btn-text badge-text]
          (let [idx (slot-count doc grid-id "default")
                {doc' :doc card-id :id}
                (ops/insert-new doc grid-id "default" idx "x-card"
                                {:attrs {"variant" card-variant "padding" "lg"}})
                sc  (fn [d] (slot-count d card-id "default"))
                doc' (if badge-text
                       (let [{d :doc} (ops/insert-new doc' card-id "default"
                                                      (sc doc') "x-badge"
                                                      {:text badge-text
                                                       :attrs {"variant" "info" "pill" ""}})
                             {d :doc} (ops/insert-new d card-id "default"
                                                      (sc d) "x-spacer"
                                                      {:attrs spacer-attrs})]
                         d)
                       doc')
                {doc' :doc} (add-text doc' card-id "default" (sc doc') "h4" plan)
                {doc' :doc} (add-text doc' card-id "default" (sc doc') "h2" price)
                {doc' :doc} (add-text doc' card-id "default" (sc doc') "caption" caption)
                {doc' :doc} (ops/insert-new doc' card-id "default"
                                            (sc doc') "x-spacer" {:attrs spacer-attrs})
                {doc' :doc} (ops/insert-new doc' card-id "default"
                                            (sc doc') "x-divider" nil)
                {doc' :doc} (ops/insert-new doc' card-id "default"
                                            (sc doc') "x-spacer" {:attrs spacer-attrs})
                doc' (reduce (fn [d feature]
                               (:doc (add-text d card-id "default"
                                               (sc d) "body1" feature)))
                             doc' features)
                {doc' :doc} (ops/insert-new doc' card-id "default"
                                            (sc doc') "x-spacer" {:attrs spacer-attrs})
                {doc' :doc} (add-button doc' card-id "default"
                                        (sc doc') btn-text btn-variant "lg")]
            doc'))

        d6  (add-pricing-card d5 "outlined" "Starter" "$0" "Free forever"
                              ["Up to 3 projects"
                               "Community support"
                               "Basic analytics"]
                              "tertiary" "Get started" nil)
        d7  (add-pricing-card d6 "elevated" "Pro" "$29" "per seat / month"
                              ["Unlimited projects"
                               "Priority support"
                               "Advanced analytics"
                               "Custom domains"]
                              "primary" "Start free trial" "Most popular")
        d8  (add-pricing-card d7 "outlined" "Enterprise" "Custom" "tailored to your org"
                              ["Everything in Pro"
                               "SSO & SAML"
                               "Dedicated account manager"
                               "SLA guarantee"]
                              "secondary" "Contact sales" nil)]
    d8))

(defn- testimonials []
  (let [d0  (m/empty-document)
        {d1 :doc} (ops/insert-new d0 "root" "default" 0 "x-spacer"
                                  {:attrs {"size" "2rem" "axis" "vertical"}})
        {d2 :doc} (add-text+align d1 "root" "default" 1 "overline" "TESTIMONIALS" "center")
        {d3 :doc} (add-text+align d2 "root" "default" 2 "h2"
                                  "Loved by teams worldwide" "center")
        {d4 :doc} (ops/insert-new d3 "root" "default" 3 "x-spacer"
                                  {:attrs {"size" "2rem" "axis" "vertical"}})
        {d5 :doc grid-id :id}
        (ops/insert-new d4 "root" "default" 4 "x-grid"
                        {:attrs {"columns" "repeat(3, 1fr)" "gap" "lg" "row-gap" "lg"}})

        add-testimonial
        (fn [doc quote initials name-and-role]
          (let [idx (slot-count doc grid-id "default")
                {doc' :doc card-id :id}
                (ops/insert-new doc grid-id "default" idx "x-card"
                                {:attrs {"variant" "elevated" "padding" "lg"}})
                {doc' :doc}  (add-text doc' card-id "default" 0 "blockquote" quote)
                {doc' :doc}  (ops/insert-new doc' card-id "default" 1 "x-avatar"
                                             {:attrs {"initials" initials "size" "md"}})
                {doc' :doc}  (add-text doc' card-id "default" 2 "caption" name-and-role)]
            doc'))

        d6 (add-testimonial d5
                            "Nimbus cut our deploy time from hours to minutes. The developer experience is unmatched."
                            "SC" "Sarah Chen, CTO at Wavelength")
        d7 (add-testimonial d6
                            "We migrated 200 microservices in a weekend. Could not have done it without Nimbus."
                            "MR" "Marcus Rivera, Staff Engineer at Cobalt")
        d8 (add-testimonial d7
                            "The analytics alone justified the switch. Real-time insights into every deploy."
                            "PP" "Priya Patel, VP Engineering at Helix")]
    d8))

(defn- timeline-how-it-works []
  (let [d0  (m/empty-document)
        {d1 :doc} (ops/insert-new d0 "root" "default" 0 "x-spacer"
                                  {:attrs {"size" "2rem" "axis" "vertical"}})
        {d2 :doc} (add-text+align d1 "root" "default" 1 "overline" "HOW IT WORKS" "center")
        {d3 :doc} (add-text+align d2 "root" "default" 2 "h2"
                                  "Up and running in four steps" "center")
        {d4 :doc} (add-text+align d3 "root" "default" 3 "body1"
                                  "From sign-up to production in under ten minutes." "center")
        {d5 :doc} (ops/insert-new d4 "root" "default" 4 "x-spacer"
                                  {:attrs {"size" "2rem" "axis" "vertical"}})
        {d6 :doc tl-id :id}
        (ops/insert-new d5 "root" "default" 5 "x-timeline"
                        {:attrs {"position" "start"}})

        add-step
        (fn [doc label title body]
          (let [idx (slot-count doc tl-id "default")
                {doc' :doc item-id :id}
                (ops/insert-new doc tl-id "default" idx "x-timeline-item"
                                {:attrs {"label" label "title" title}})
                {doc'' :doc} (add-text doc' item-id "default" 0 "body1" body)]
            doc''))

        d7 (add-step d6 "Step 1" "Create your workspace"
                     "Sign up with your team email and choose a starter template. No credit card needed.")
        d8 (add-step d7 "Step 2" "Connect your repos"
                     "Link your GitHub, GitLab, or Bitbucket repositories. We detect frameworks automatically.")
        d9 (add-step d8 "Step 3" "Configure & deploy"
                     "Tweak build settings or accept our smart defaults. Hit deploy and grab a coffee.")
        d10 (add-step d9 "Step 4" "Monitor & scale"
                      "Watch real-time metrics on your dashboard. Auto-scaling kicks in as traffic grows.")]
    d10))

(defn- contact-enhanced []
  (let [d0  (m/empty-document)
        {d1 :doc} (ops/insert-new d0 "root" "default" 0 "x-spacer"
                                  {:attrs {"size" "2rem" "axis" "vertical"}})
        {d2 :doc} (add-text+align d1 "root" "default" 1 "h2" "Get in touch" "center")
        {d3 :doc} (add-text+align d2 "root" "default" 2 "body1"
                                  "Have a question or want a demo? We would love to hear from you."
                                  "center")
        {d4 :doc} (ops/insert-new d3 "root" "default" 3 "x-spacer"
                                  {:attrs {"size" "2rem" "axis" "vertical"}})
        {d5 :doc grid-id :id}
        (ops/insert-new d4 "root" "default" 4 "x-grid"
                        {:attrs {"columns" "repeat(2, 1fr)" "gap" "xl" "row-gap" "xl"}})
        ;; form side
        {d6 :doc form-card-id :id}
        (ops/insert-new d5 grid-id "default" 0 "x-card"
                        {:attrs {"variant" "ghost" "padding" "lg"}})
        {d7 :doc form-id :id}
        (ops/insert-new d6 form-card-id "default" 0 "x-form")
        {d8 :doc}  (ops/insert-new d7 form-id "default" 0 "x-form-field"
                                   {:attrs {"label" "Full name" "name" "name"
                                            "placeholder" "Jane Smith"
                                            "required" ""}})
        {d9 :doc}  (ops/insert-new d8 form-id "default" 1 "x-form-field"
                                   {:attrs {"label" "Work email" "name" "email"
                                            "type" "email"
                                            "placeholder" "jane@company.com"
                                            "required" ""}})
        {d10 :doc} (ops/insert-new d9 form-id "default" 2 "x-form-field"
                                   {:attrs {"label" "Subject" "name" "subject"
                                            "placeholder" "What is this about?"}})
        {d11 :doc} (ops/insert-new d10 form-id "default" 3 "x-form-field"
                                   {:attrs {"label" "Message" "name" "message"
                                            "placeholder" "Tell us how we can help..."}})
        {d12 :doc} (add-button d11 form-id "default" 4 "Send message" "primary" "lg")
        ;; info side — wrapped in x-container with top spacer to align with form fields
        {d13 :doc info-wrap-id :id}
        (ops/insert-new d12 grid-id "default" 1 "x-container")
        {d14 :doc} (ops/insert-new d13 info-wrap-id "default" 0 "x-spacer"
                                   {:attrs {"size" "2.75rem" "axis" "vertical"}})
        {d15 :doc info-id :id}
        (ops/insert-new d14 info-wrap-id "default" 1 "x-card"
                        {:attrs {"variant" "filled" "padding" "lg"}})
        {d16 :doc} (add-text d15 info-id "default" 0 "h4" "Other ways to reach us")
        {d17 :doc} (ops/insert-new d16 info-id "default" 1 "x-spacer"
                                   {:attrs {"size" "1rem" "axis" "vertical"}})
        {d18 :doc} (add-text d17 info-id "default" 2 "body1" "Email: hello@nimbus.dev")
        {d19 :doc} (add-text d18 info-id "default" 3 "body1" "Phone: +1 (555) 012-3456")
        {d20 :doc} (add-text d19 info-id "default" 4 "body1"
                             "Address: 123 Market St, San Francisco, CA 94105")]
    d20))

(defn- full-landing-page []
  (let [d  (m/empty-document)
        ;; navbar
        {d :doc nav-id :id}  (ops/insert-new d "root" "default" 0 "x-navbar")
        {d :doc}             (add-text d nav-id "brand" 0 "h5" "Nimbus")
        {d :doc}             (ops/insert-new d nav-id "end" 0 "x-button"
                                             {:text "Features" :attrs {"variant" "ghost"}})
        {d :doc}             (ops/insert-new d nav-id "end" 1 "x-button"
                                             {:text "Pricing" :attrs {"variant" "ghost"}})
        {d :doc}             (ops/insert-new d nav-id "end" 2 "x-button"
                                             {:text "About" :attrs {"variant" "ghost"}})
        {d :doc}             (add-button d nav-id "actions" 0 "Get started" "primary" "sm")
        ;; hero
        {d :doc}             (ops/insert-new d "root" "default" 1 "x-spacer"
                                             {:attrs {"size" "3rem" "axis" "vertical"}})
        {d :doc}             (add-text+align d "root" "default" 2 "h1"
                                             "The platform your code deserves"
                                             "center")
        {d :doc}             (add-text+align d "root" "default" 3 "body1"
                                             "Deploy, monitor, and scale — all from one dashboard. Built for teams that ship fast."
                                             "center")
        {d :doc}             (ops/insert-new d "root" "default" 4 "x-spacer"
                                             {:attrs {"size" "0.5rem" "axis" "vertical"}})
        {d :doc cta1-id :id} (ops/insert-new d "root" "default" 5 "x-grid"
                                             {:attrs {"columns" "repeat(1, 1fr)"
                                                      "justify-items" "center"}})
        {d :doc}             (add-button d cta1-id "default" 0
                                         "Start building for free" "primary" "lg")
        ;; divider + stats
        {d :doc}             (ops/insert-new d "root" "default" 6 "x-spacer"
                                             {:attrs {"size" "3rem" "axis" "vertical"}})
        {d :doc}             (ops/insert-new d "root" "default" 7 "x-organic-divider"
                                             {:attrs {"shape" "wave"
                                                      "height" "3rem"}})
        {d :doc sg-id :id}   (ops/insert-new d "root" "default" 8 "x-grid"
                                             {:attrs {"columns" "repeat(4, 1fr)" "gap" "lg"
                                                      "row-gap" "lg"}})
        {d :doc}             (ops/insert-new d sg-id "default" 0 "x-stat"
                                             {:attrs {"label" "Deployments" "value" "2M+"
                                                      "hint" "this year" "align" "center"}})
        {d :doc}             (ops/insert-new d sg-id "default" 1 "x-stat"
                                             {:attrs {"label" "Developers" "value" "50,000+"
                                                      "hint" "active monthly" "align" "center"}})
        {d :doc}             (ops/insert-new d sg-id "default" 2 "x-stat"
                                             {:attrs {"label" "Uptime" "value" "99.99%"
                                                      "hint" "SLA guaranteed" "align" "center"}})
        {d :doc}             (ops/insert-new d sg-id "default" 3 "x-stat"
                                             {:attrs {"label" "Countries" "value" "120+"
                                                      "hint" "global reach" "align" "center"}})
        ;; features
        {d :doc}             (ops/insert-new d "root" "default" 9 "x-spacer"
                                             {:attrs {"size" "3rem" "axis" "vertical"}})
        {d :doc}             (add-text+align d "root" "default" 10 "overline" "FEATURES" "center")
        {d :doc}             (add-text+align d "root" "default" 11 "h2"
                                             "Everything you need to ship" "center")
        {d :doc}             (ops/insert-new d "root" "default" 12 "x-spacer"
                                             {:attrs {"size" "2rem" "axis" "vertical"}})
        {d :doc fg-id :id}   (ops/insert-new d "root" "default" 13 "x-grid"
                                             {:attrs {"columns" "repeat(2, 1fr)" "gap" "lg"
                                                      "row-gap" "lg"}})
        ;; feature cards
        {d :doc fc1 :id}     (ops/insert-new d fg-id "default" 0 "x-card"
                                             {:attrs {"variant" "outlined" "padding" "lg"}})
        {d :doc}             (add-text d fc1 "default" 0 "h4" "Zero-config deploys")
        {d :doc}             (add-text d fc1 "default" 1 "body1"
                                       "Push to main and your app is live. We handle the infrastructure, SSL, and CDN automatically.")
        {d :doc fc2 :id}     (ops/insert-new d fg-id "default" 1 "x-card"
                                             {:attrs {"variant" "outlined" "padding" "lg"}})
        {d :doc}             (add-text d fc2 "default" 0 "h4" "Edge functions")
        {d :doc}             (add-text d fc2 "default" 1 "body1"
                                       "Run serverless code in 40+ regions, close to your users.")
        {d :doc fc3 :id}     (ops/insert-new d fg-id "default" 2 "x-card"
                                             {:attrs {"variant" "outlined" "padding" "lg"}})
        {d :doc}             (add-text d fc3 "default" 0 "h4" "Team dashboards")
        {d :doc}             (add-text d fc3 "default" 1 "body1"
                                       "See who deployed what, when, and where — in real time.")
        {d :doc fc4 :id}     (ops/insert-new d fg-id "default" 3 "x-card"
                                             {:attrs {"variant" "outlined" "padding" "lg"}})
        {d :doc}             (add-text d fc4 "default" 0 "h4" "Built-in observability")
        {d :doc}             (add-text d fc4 "default" 1 "body1"
                                       "Traces, logs, and metrics unified in a single view. No extra vendors needed.")
        ;; testimonial
        {d :doc}             (ops/insert-new d "root" "default" 14 "x-spacer"
                                             {:attrs {"size" "3rem" "axis" "vertical"}})
        {d :doc tc-id :id}   (ops/insert-new d "root" "default" 15 "x-card"
                                             {:attrs {"variant" "elevated" "padding" "lg"}})
        {d :doc}             (add-text d tc-id "default" 0 "blockquote"
                                       "Nimbus transformed how our team ships. We went from weekly to multiple daily deploys with total confidence.")
        {d :doc}             (ops/insert-new d tc-id "default" 1 "x-avatar"
                                             {:attrs {"initials" "AK" "size" "lg"}})
        {d :doc}             (add-text d tc-id "default" 2 "caption"
                                       "Alex Kim, Head of Engineering at Radiant")
        ;; CTA footer
        {d :doc}             (ops/insert-new d "root" "default" 16 "x-spacer"
                                             {:attrs {"size" "3rem" "axis" "vertical"}})
        {d :doc}             (add-text+align d "root" "default" 17 "h2"
                                             "Ready to get started?" "center")
        {d :doc}             (add-text+align d "root" "default" 18 "body1"
                                             "Join thousands of teams shipping with confidence." "center")
        {d :doc}             (ops/insert-new d "root" "default" 19 "x-spacer"
                                             {:attrs {"size" "0.5rem" "axis" "vertical"}})
        {d :doc cta2-id :id} (ops/insert-new d "root" "default" 20 "x-grid"
                                             {:attrs {"columns" "repeat(1, 1fr)"
                                                      "justify-items" "center"}})
        {d :doc}             (add-button d cta2-id "default" 0
                                         "Create free account" "primary" "lg")]
    d))

(defn- kinetic-showcase []
  (let [d (m/empty-document)
        ;; --- background layers ---
        ;; metaball cursor — fluid blobs follow the pointer. NOT
        ;; :placement :background: that route stamps z-index:0 on
        ;; the host (per the canvas-host stylesheet rule), which
        ;; means content (z-index:1) paints over the cursor blobs.
        ;; Instead use position:fixed at z-index:9999 with
        ;; pointer-events:none so the blobs always render above
        ;; everything while clicks fall through to content beneath.
        ;; Fixed positioning is also what a cursor-following effect
        ;; wants — relative to the viewport, not to a parent.
        {d :doc}             (ops/insert-new d "root" "default" 0 "x-metaball-cursor"
                                             {:attrs  {"blob-count" "5"
                                                       "blob-size"  "60"
                                                       "blur"       "20"
                                                       "noise"      ""
                                                       "palette"    "#f43f5e,#a855f7,#06b6d4"}
                                              :layout {:extra-style
                                                       "position: fixed; inset: 0; width: 100%; height: 100%; z-index: 9999; pointer-events: none;"}})
        {d :doc}             (ops/insert-new d "root" "default" 1 "x-gaussian-blur"
                                             {:attrs  {"colors"    "#f43f5e,#a855f7,#06b6d4"
                                                       "blur"      "100"
                                                       "speed"     "3"
                                                       "count"     "3"
                                                       "size"      "45"
                                                       "opacity"   "0.35"
                                                       "animation" "float"}
                                              :layout {:placement :background}})
        ;; --- navbar ---
        {d :doc nav-id :id}  (ops/insert-new d "root" "default" 2 "x-navbar")
        {d :doc}             (add-text d nav-id "brand" 0 "h5" "Pulse")
        {d :doc}             (ops/insert-new d nav-id "end" 0 "x-button"
                                             {:text "Story" :attrs {"variant" "ghost"}})
        {d :doc}             (ops/insert-new d nav-id "end" 1 "x-button"
                                             {:text "Press" :attrs {"variant" "ghost"}})
        {d :doc}             (ops/insert-new d nav-id "actions" 0 "x-particle-button"
                                             {:text "Reserve"
                                              :attrs {"variant" "primary" "size" "sm"
                                                      "mode" "dust"}})
        ;; --- hero — kinetic-typography headline (kept) ---
        {d :doc}             (ops/insert-new d "root" "default" 4 "x-spacer"
                                             {:attrs {"size" "3rem" "axis" "vertical"}})
        {d :doc}             (ops/insert-new d "root" "default" 5 "x-badge"
                                             {:text "Launching January 15, 2027"
                                              :attrs {"variant" "info" "pill" ""}})
        {d :doc}             (ops/insert-new d "root" "default" 6 "x-kinetic-typography"
                                             {:attrs {"text"      "Something extraordinary is coming"
                                                      "preset"    "wave"
                                                      "animation" "scroll"
                                                      "font-size" "2rem"}})
        {d :doc}             (add-text+align d "root" "default" 7 "body1"
                                             "Six years of work, one launch event. Reserve your seat or watch the teaser to see what we've been building."
                                             "center")
        {d :doc}             (ops/insert-new d "root" "default" 8 "x-spacer"
                                             {:attrs {"size" "1rem" "axis" "vertical"}})
        ;; CTA pair — burst + dust particle buttons
        {d :doc cta-id :id}  (ops/insert-new d "root" "default" 9 "x-grid"
                                             {:attrs {"columns" "repeat(2, 1fr)"
                                                      "gap"     "md"
                                                      "row-gap" "md"}})
        {d :doc}             (ops/insert-new d cta-id "default" 0 "x-particle-button"
                                             {:text "Reserve your seat"
                                              :attrs {"variant" "primary" "size" "lg"
                                                      "mode" "burst"}})
        {d :doc}             (ops/insert-new d cta-id "default" 1 "x-particle-button"
                                             {:text "Watch the teaser"
                                              :attrs {"variant" "secondary" "size" "lg"
                                                      "mode" "dust"}})
        ;; --- divider + kinetic-font reveal-section header ---
        {d :doc}             (ops/insert-new d "root" "default" 10 "x-spacer"
                                             {:attrs {"size" "3rem" "axis" "vertical"}})
        {d :doc}             (ops/insert-new d "root" "default" 11 "x-organic-divider"
                                             {:attrs {"shape"     "wave"
                                                      "animation" "drift"
                                                      "height"    "3rem"}})
        {d :doc}             (add-text+align d "root" "default" 12 "overline"
                                             "WHAT'S LAUNCHING" "center")
        ;; physics-driven, per-character header. Pairs with the
        ;; scroll-driven kinetic-typography hero so designers see
        ;; both flavours of kinetic text on the same page.
        {d :doc}             (ops/insert-new d "root" "default" 13 "x-kinetic-font"
                                             {:attrs  {"text"      "Three things we couldn't wait to share"
                                                       "trigger"   "both"
                                                       "mode"      "wave"
                                                       "per-char"  ""
                                                       "mass"      "1"
                                                       "tension"   "180"
                                                       "friction"  "12"
                                                       "intensity" "0.7"}
                                              :layout {:extra-style "font-size: 3rem; text-align: center;"}})
        {d :doc}             (ops/insert-new d "root" "default" 14 "x-spacer"
                                             {:attrs {"size" "1.5rem" "axis" "vertical"}})
        ;; --- featured reveal — bento-grid with liquid-glass hero cell ---
        ;; columns is a single integer (1–12); the grid composes its
        ;; own track-list internally. row-height left at the "auto"
        ;; default so cells size to content rather than padding into
        ;; empty space behind a row-span 2 hero cell.
        {d :doc bento-id :id}
        (ops/insert-new d "root" "default" 15 "x-bento-grid"
                        {:attrs {"columns" "3"
                                 "gap"     "lg"
                                 "row-gap" "lg"}})
        ;; cell 1 — featured (col-span 2, row-span 2) with an
        ;; x-liquid-glass directly hosting the headline. No
        ;; intermediate x-card: an elevated card paints a solid
        ;; background that hides the glass effect. liquid-glass
        ;; already pads its slotted content via [part=content].
        {d :doc feat-item :id}
        (ops/insert-new d bento-id "default" 0 "x-bento-item"
                        {:attrs {"col-span" "2" "row-span" "2"}})
        {d :doc glass-id :id}
        (ops/insert-new d feat-item "default" 0 "x-liquid-glass"
                        {:attrs  {"blobs"             "3"
                                  "speed"             "0.6"
                                  "amplitude"         "20"
                                  "goo"               "0.7"
                                  "specular"          ""
                                  "specular-size"     "0.5"
                                  "specular-intensity" "0.6"
                                  "frost"             "0.3"
                                  "tint"              "#a855f7"
                                  "color-1"           "#f43f5e"
                                  "color-2"           "#06b6d4"}
                         :layout {:extra-style "min-height: 320px;"}})
        {d :doc}             (add-text d glass-id "default" 0 "h3" "Reimagined editor")
        {d :doc}             (add-text d glass-id "default" 1 "body1"
                                       "Six years of feedback distilled into a tool that actually feels good to use. Multi-cursor, conflict-free, and faster than your last keyboard shortcut.")
        {d :doc}             (add-text d glass-id "default" 2 "body2"
                                       "Sub-second sync across machines. Typed open API. Public roadmap from day one.")
        ;; cell 2 — Live collaboration (1×1)
        {d :doc bi2 :id}
        (ops/insert-new d bento-id "default" 1 "x-bento-item"
                        {:attrs {"col-span" "1" "row-span" "1"}})
        ;; bento + testimonial cells use x-soft-body in place of
        ;; x-card so the entire reveal section reads as physically
        ;; deformable. x-soft-body provides the same surface +
        ;; padding role as x-card (filled background, border, shadow)
        ;; while morphing its outline on hover/touch via SVG path
        ;; deformation. Tuned with mid-range stiffness/damping so the
        ;; effect is felt without being distracting at rest.
        {d :doc fc2 :id}     (ops/insert-new d bi2 "default" 0 "x-soft-body"
                                             {:attrs {"stiffness"   "60"
                                                      "damping"     "5"
                                                      "intensity"   "4"
                                                      "grab-radius" "250"
                                                      "radius"      "40"}})
        {d :doc}             (add-text d fc2 "default" 0 "h4" "Live collaboration")
        {d :doc}             (add-text d fc2 "default" 1 "body2"
                                       "Multiple cursors, sub-second sync, and conflict-free history out of the box.")
        ;; cell 3 — Open API (1×1)
        {d :doc bi3 :id}
        (ops/insert-new d bento-id "default" 2 "x-bento-item"
                        {:attrs {"col-span" "1" "row-span" "1"}})
        {d :doc fc3 :id}     (ops/insert-new d bi3 "default" 0 "x-soft-body"
                                             {:attrs {"stiffness"   "60"
                                                      "damping"     "5"
                                                      "intensity"   "4"
                                                      "grab-radius" "250"
                                                      "radius"      "40"}})
        {d :doc}             (add-text d fc3 "default" 0 "h4" "Open API")
        {d :doc}             (add-text d fc3 "default" 1 "body2"
                                       "Every action exposed as a typed endpoint. Build on top from day one.")
        ;; cell 4 — full-width closing strip (col-span 3, row-span 1)
        {d :doc bi4 :id}
        (ops/insert-new d bento-id "default" 3 "x-bento-item"
                        {:attrs {"col-span" "3" "row-span" "1"}})
        {d :doc fc4 :id}     (ops/insert-new d bi4 "default" 0 "x-soft-body"
                                             {:attrs {"stiffness"   "60"
                                                      "damping"     "5"
                                                      "intensity"   "4"
                                                      "grab-radius" "250"
                                                      "radius"      "40"}})
        {d :doc}             (add-text d fc4 "default" 0 "h4" "Roadmap from day one")
        {d :doc}             (add-text d fc4 "default" 1 "body2"
                                       "Public roadmap, open RFCs, and a Discord where the team ships in real time.")
        ;; --- voices — scroll-stack testimonials ---
        {d :doc}             (ops/insert-new d "root" "default" 16 "x-spacer"
                                             {:attrs {"size" "2rem" "axis" "vertical"}})
        {d :doc}             (add-text+align d "root" "default" 17 "overline"
                                             "VOICES" "center")
        {d :doc}             (add-text+align d "root" "default" 18 "h2"
                                             "Early users on what's coming" "center")
        {d :doc}             (ops/insert-new d "root" "default" 19 "x-spacer"
                                             {:attrs {"size" "1rem" "axis" "vertical"}})
        ;; A plain x-grid here instead of x-scroll-stack. Every
        ;; scroll-driven BareDOM component (x-scroll-stack /
        ;; -parallax / -story / -timeline) listens to window scroll
        ;; events; inside Bareforge's editor canvas-host (its own
        ;; overflow:auto scrollport) those events never fire, so a
        ;; scroll-driven section reads as static during editing.
        ;; The x-soft-body cards still react to hover/pointer, so the
        ;; section stays kinetic without depending on scroll.
        {d :doc stack-id :id}
        (ops/insert-new d "root" "default" 20 "x-grid"
                        {:attrs {"columns" "repeat(3, 1fr)"
                                 "gap"     "lg"
                                 "row-gap" "lg"}})
        ;; testimonial 1
        {d :doc t1 :id}      (ops/insert-new d stack-id "default" 0 "x-soft-body"
                                             {:attrs {"stiffness"   "60"
                                                      "damping"     "5"
                                                      "intensity"   "4"
                                                      "grab-radius" "250"
                                                      "radius"      "40"}})
        {d :doc}             (add-text d t1 "default" 0 "blockquote"
                                       "I haven't been this excited about a tool launch in years. The editor genuinely feels like it was made by someone who ships every day.")
        {d :doc}             (ops/insert-new d t1 "default" 1 "x-avatar"
                                             {:attrs {"initials" "MA" "size" "lg"}})
        {d :doc}             (add-text d t1 "default" 2 "caption"
                                       "Mira A., Staff engineer at Lattice")
        ;; testimonial 2
        {d :doc t2 :id}      (ops/insert-new d stack-id "default" 1 "x-soft-body"
                                             {:attrs {"stiffness"   "60"
                                                      "damping"     "5"
                                                      "intensity"   "4"
                                                      "grab-radius" "250"
                                                      "radius"      "40"}})
        {d :doc}             (add-text d t2 "default" 0 "blockquote"
                                       "Live collab that actually works on a slow connection. Our remote team got back two hours of merge-conflict pain a week.")
        {d :doc}             (ops/insert-new d t2 "default" 1 "x-avatar"
                                             {:attrs {"initials" "RD" "size" "lg"}})
        {d :doc}             (add-text d t2 "default" 2 "caption"
                                       "Rohan D., Tech lead at Nimbus")
        ;; testimonial 3
        {d :doc t3 :id}      (ops/insert-new d stack-id "default" 2 "x-soft-body"
                                             {:attrs {"stiffness"   "60"
                                                      "damping"     "5"
                                                      "intensity"   "4"
                                                      "grab-radius" "250"
                                                      "radius"      "40"}})
        {d :doc}             (add-text d t3 "default" 0 "blockquote"
                                       "The open API meant I had a Slack integration running on day two. No SDK, no boilerplate — just a typed endpoint and I was off.")
        {d :doc}             (ops/insert-new d t3 "default" 1 "x-avatar"
                                             {:attrs {"initials" "SK" "size" "lg"}})
        {d :doc}             (add-text d t3 "default" 2 "caption"
                                       "Sara K., Indie dev")
        ;; --- divider into the closing CTA ---
        {d :doc}             (ops/insert-new d "root" "default" 21 "x-spacer"
                                             {:attrs {"size" "3rem" "axis" "vertical"}})
        {d :doc}             (ops/insert-new d "root" "default" 22 "x-organic-divider"
                                             {:attrs {"shape"     "blob-edge"
                                                      "animation" "drift"
                                                      "height"    "3rem"}})
        ;; --- closing CTA ---
        {d :doc}             (add-text+align d "root" "default" 23 "h2"
                                             "Be there from day one" "center")
        {d :doc}             (add-text+align d "root" "default" 24 "body1"
                                             "Drop your email and we'll send you the launch link the moment doors open."
                                             "center")
        {d :doc}             (ops/insert-new d "root" "default" 25 "x-spacer"
                                             {:attrs {"size" "1rem" "axis" "vertical"}})
        {d :doc cta2-id :id} (ops/insert-new d "root" "default" 26 "x-grid"
                                             {:attrs {"columns" "repeat(1, 1fr)"
                                                      "justify-items" "center"}})
        ;; Wrap the closing CTA in x-ripple-effect so clicks on the
        ;; button kick off a goo-style ripple that distorts the
        ;; surrounding shadow DOM. Stacks naturally with the
        ;; particle-button's own burst — particles fly outward while
        ;; the ripple radiates from the click point.
        {d :doc ripple-id :id}
        (ops/insert-new d cta2-id "default" 0 "x-ripple-effect"
                        {:attrs {"intensity" "0.5"
                                 "duration"  "1500"
                                 "frequency" "3"}})
        {d :doc}             (ops/insert-new d ripple-id "default" 0 "x-particle-button"
                                             {:text "Notify me on launch"
                                              :attrs {"variant" "primary" "size" "lg"
                                                      "mode" "burst"}})]
    d))

(defn- docs-home []
  (let [d0           (m/empty-document)
        {d1 :doc nb :id}  (ops/insert-new d0 "root" "default" 0 "x-navbar"
                                          {:attrs {"title" "Bareforge Docs"}})
        {d2 :doc}     (add-button d1 nb "actions" 0 "GitHub" "ghost" "sm")
        {d3 :doc}     (add-spacer d2 "root" "default" 1 "2rem")
        {d4 :doc cnt :id} (ops/insert-new d3 "root" "default" 2 "x-container"
                                          {:attrs {"max-width" "960px" "padding" "lg"}})
        {d5 :doc}     (add-text+align d4 cnt "default" 0 "h1"
                                      "Bareforge documentation"
                                      "left")
        {d6 :doc}     (add-spacer d5 cnt "default" 1 "0.75rem")
        {d7 :doc}     (add-text d6 cnt "default" 2 "subtitle1"
                                "Visual landing-page builder for BareDOM. Pick a topic to get started.")
        {d8 :doc}     (add-spacer d7 cnt "default" 3 "2rem")
        {d9 :doc grid :id} (ops/insert-new d8 cnt "default" 4 "x-grid"
                                           {:attrs {"columns" "repeat(3, 1fr)" "gap" "16px"}})
        cards         [["Getting started" "Install, run the dev server, ship your first page in fifteen minutes."]
                       ["Recipes"         "End-to-end walkthrough that builds a filterable cart with bindings + actions."]
                       ["Architecture"    "Pure / effectful zones, the document model, the reconciler, exports."]]
        d-final       (reduce
                       (fn [d [i [title body]]]
                         (let [{dn :doc card :id} (ops/insert-new d grid "default" i "x-card"
                                                                  {:attrs {"variant" "outlined" "padding" "md"}})
                               {dn :doc}      (add-text dn card "default" 0 "h3" title)
                               {dn :doc}      (add-spacer dn card "default" 1 "0.5rem")
                               {dn :doc}      (add-text dn card "default" 2 "body2" body)]
                           dn))
                       d9 (map-indexed vector cards))]
    d-final))

(defn- changelog []
  (let [d0           (m/empty-document)
        {d1 :doc cnt :id} (ops/insert-new d0 "root" "default" 0 "x-container"
                                          {:attrs {"max-width" "720px" "padding" "lg"}})
        {d2 :doc}     (add-text d1 cnt "default" 0 "h1" "Changelog")
        {d3 :doc}     (add-spacer d2 cnt "default" 1 "0.5rem")
        {d4 :doc}     (add-text d3 cnt "default" 2 "subtitle1"
                                "Releases, in reverse chronological order.")
        {d5 :doc}     (add-spacer d4 cnt "default" 3 "2rem")
        releases      [["0.2.0" "2026-04-29"
                        "Authoring shortcuts: multi-select, drag-to-scrub, Cmd-K command palette, ? cheat sheet, palette pattern flyout, per-tag empty-slot hints."]
                       ["0.1.1" "2026-04-28"
                        "Patch release. Hosted-demo path fixes, BareDOM 2.4.0 → 2.4.1, README polish."]
                       ["0.1.0" "2026-04-27"
                        "First public release. Visual editor, four export plugins at full feature parity, nine starter templates."]]
        d-final       (reduce
                       (fn [d [i [version date body]]]
                         (let [base          (slot-count d cnt "default")
                               {dn :doc grp :id} (ops/insert-new d cnt "default" base "x-card"
                                                                 {:attrs {"variant" "elevated" "padding" "md"}})
                               {dn :doc hdr :id} (ops/insert-new dn grp "default" 0 "x-grid"
                                                                 {:attrs {"columns" "auto auto" "gap" "12px"}})
                               {dn :doc}     (add-text dn hdr "default" 0 "h3" (str "v" version))
                               {dn :doc}     (add-text dn hdr "default" 1 "caption" date)
                               {dn :doc}     (add-spacer dn grp "default" 1 "0.5rem")
                               {dn :doc}     (add-text dn grp "default" 2 "body2" body)
                               ;; gap between releases — except after the last
                               last? (= i (dec (count releases)))
                               {dn :doc}     (if last?
                                               {:doc dn}
                                               (add-spacer dn cnt "default"
                                                           (slot-count dn cnt "default")
                                                           "1rem"))]
                           dn))
                       d5 (map-indexed vector releases))]
    d-final))

(defn- status-page []
  (let [d0           (m/empty-document)
        {d1 :doc cnt :id} (ops/insert-new d0 "root" "default" 0 "x-container"
                                          {:attrs {"max-width" "880px" "padding" "lg"}})
        {d2 :doc}     (add-text d1 cnt "default" 0 "h1" "System status")
        {d3 :doc}     (add-spacer d2 cnt "default" 1 "1rem")
        {d4 :doc}     (ops/insert-new d3 cnt "default" 2 "x-alert"
                                      {:attrs {"text" "All systems operational"
                                               "type" "success"}})
        {d5 :doc}     (add-spacer d4 cnt "default" 3 "2.5rem")
        {d6 :doc}     (add-text+align d5 cnt "default" 4 "h3"
                                      "Services" "left")
        {d7 :doc}     (add-spacer d6 cnt "default" 5 "0.75rem")
        {d8 :doc grid :id} (ops/insert-new d7 cnt "default" 6 "x-grid"
                                           {:attrs {"columns" "1fr auto" "gap" "12px"}})
        services      [["Web app"       "Operational"]
                       ["API"           "Operational"]
                       ["Background jobs" "Operational"]
                       ["CDN"           "Operational"]]
        d9            (reduce
                       (fn [d [i [name status]]]
                         (let [base       (* i 2)
                               {dn :doc}  (add-text d grid "default" base "body1" name)
                               {dn :doc}  (ops/insert-new dn grid "default" (inc base) "x-badge"
                                                          {:attrs {"text"    status
                                                                   "variant" "success"}})]
                           dn))
                       d8 (map-indexed vector services))
        {d10 :doc}    (add-spacer d9 cnt "default" 7 "2.5rem")
        {d11 :doc}    (add-text+align d10 cnt "default" 8 "h3"
                                      "Recent incidents" "left")
        {d12 :doc}    (add-spacer d11 cnt "default" 9 "0.75rem")
        {d13 :doc}    (add-text d12 cnt "default" 10 "body2"
                                "No incidents reported in the last 30 days.")]
    d13))

(defn- blog-post []
  (let [d0           (m/empty-document)
        {d1 :doc cnt :id} (ops/insert-new d0 "root" "default" 0 "x-container"
                                          {:attrs {"max-width" "680px" "padding" "lg"}})
        {d2 :doc}     (add-text d1 cnt "default" 0 "overline" "Engineering · 6 min read")
        {d3 :doc}     (add-spacer d2 cnt "default" 1 "0.5rem")
        {d4 :doc}     (add-text d3 cnt "default" 2 "h1"
                                "Why we picked plain web components over a framework")
        {d5 :doc}     (add-spacer d4 cnt "default" 3 "1.5rem")
        {d6 :doc meta :id} (ops/insert-new d5 cnt "default" 4 "x-grid"
                                           {:attrs {"columns" "auto 1fr auto" "gap" "12px"}})
        {d7 :doc}     (ops/insert-new d6 meta "default" 0 "x-avatar"
                                      {:attrs {"size" "sm" "fallback" "AV"}})
        {d8 :doc}     (add-text d7 meta "default" 1 "body2" "Alex van Elsas")
        {d9 :doc}     (add-text d8 meta "default" 2 "caption" "Apr 30, 2026")
        {d10 :doc}    (add-spacer d9 cnt "default" 5 "1.5rem")
        {d11 :doc}    (ops/insert-new d10 cnt "default" 6 "x-divider" {})
        {d12 :doc}    (add-spacer d11 cnt "default" 7 "1.5rem")
        body          ["Frameworks ship a lot of code. For a 200-page documentation site, the runtime tax is hard to justify when the content is mostly static and the interactivity is mostly local."
                       "Web components answer the encapsulation question at the platform level. The browser already understands custom elements, shadow DOM, slots, and CSS variables — there's no shim layer between you and the rendered output."
                       "We started with one component, then a dozen, then ninety. The pattern held: each component owns its own visual surface, the page composes them, no global state machine in sight."]
        d-final       (reduce
                       (fn [d [i para]]
                         (let [last?     (= i (dec (count body)))
                               base      (slot-count d cnt "default")
                               {dn :doc} (add-text d cnt "default" base "body1" para)
                               {dn :doc} (if last?
                                           {:doc dn}
                                           (add-spacer dn cnt "default"
                                                       (slot-count dn cnt "default")
                                                       "1rem"))]
                           dn))
                       d12 (map-indexed vector body))]
    d-final))

(defn- dashboard-skeleton []
  (let [d0           (m/empty-document)
        {d1 :doc nb :id}  (ops/insert-new d0 "root" "default" 0 "x-navbar"
                                          {:attrs {"title" "Dashboard"}})
        {d2 :doc}     (add-button d1 nb "actions" 0 "Settings" "ghost" "sm")
        {d3 :doc}     (add-spacer d2 "root" "default" 1 "1.5rem")
        {d4 :doc cnt :id} (ops/insert-new d3 "root" "default" 2 "x-container"
                                          {:attrs {"max-width" "1200px" "padding" "lg"}})
        {d5 :doc}     (add-text d4 cnt "default" 0 "h2" "Overview")
        {d6 :doc}     (add-spacer d5 cnt "default" 1 "1rem")
        {d7 :doc grid :id} (ops/insert-new d6 cnt "default" 2 "x-grid"
                                           {:attrs {"columns" "repeat(4, 1fr)" "gap" "16px"}})
        stats         [["Active users" "12,438" "+8% week"]
                       ["Revenue"      "$84.2k" "+12% month"]
                       ["Conversion"   "3.4%"   "−0.2pt"]
                       ["Errors"       "0.02%"  "stable"]]
        d8            (reduce
                       (fn [d [i [label value delta]]]
                         (:doc (ops/insert-new d grid "default" i "x-stat"
                                               {:attrs {"label" label
                                                        "value" value
                                                        "trend" delta}})))
                       d7 (map-indexed vector stats))
        {d9 :doc}     (add-spacer d8 cnt "default" 3 "2rem")
        {d10 :doc tbl :id} (ops/insert-new d9 cnt "default" 4 "x-card"
                                           {:attrs {"variant" "outlined" "padding" "md"}})
        {d11 :doc}    (add-text d10 tbl "default" 0 "h3" "Recent activity")
        {d12 :doc}    (add-spacer d11 tbl "default" 1 "0.5rem")
        {d13 :doc}    (add-text d12 tbl "default" 2 "body2"
                                "Drop a chart, table, or x-timeline here to fill out the activity feed.")]
    d13))

;; --- registry ---------------------------------------------------------------

(def category-order
  "Display order for the templates panel's category tabs. The
   `:all` pseudo-category renders every template; the four real
   categories below partition the registry."
  [:all :landing :docs :dashboard :demo])

(def category-labels
  {:all       "All"
   :landing   "Landing"
   :docs      "Docs"
   :dashboard "Dashboard"
   :demo      "Demo"})

(def templates
  [{:id    :saas-hero
    :label "SaaS Hero"
    :category :landing
    :description "Navbar + hero headline + CTAs + social proof stats"
    :build saas-hero}
   {:id    :bento-features
    :label "Bento Features"
    :category :landing
    :description "Section heading + bento grid with varied feature cards"
    :build bento-features}
   {:id    :scroll-story
    :label "Our Story"
    :category :landing
    :description "Narrative cards separated by organic dividers"
    :build scroll-story}
   {:id    :pricing-table
    :label "Pricing Table"
    :category :landing
    :description "Three-tier pricing cards with feature lists"
    :build pricing-table}
   {:id    :testimonials
    :label "Testimonials"
    :category :landing
    :description "Grid of customer quotes with avatars"
    :build testimonials}
   {:id    :timeline
    :label "How It Works"
    :category :landing
    :description "Step-by-step timeline with descriptive content"
    :build timeline-how-it-works}
   {:id    :contact
    :label "Contact"
    :category :landing
    :description "Two-column layout with form and contact details"
    :build contact-enhanced}
   {:id    :full-landing-page
    :label "Full Landing Page"
    :category :landing
    :description "Complete page with navbar, hero, features, testimonial, and CTA"
    :build full-landing-page}
   {:id    :docs-home
    :label "Docs home"
    :category :docs
    :description "Three-card landing for a documentation site — getting started / recipes / architecture"
    :build docs-home}
   {:id    :changelog
    :label "Changelog"
    :category :docs
    :description "Reverse-chronological release notes with version + date headers"
    :build changelog}
   {:id    :status-page
    :label "Status page"
    :category :docs
    :description "All-systems-operational banner + service grid + recent incidents"
    :build status-page}
   {:id    :blog-post
    :label "Blog post"
    :category :docs
    :description "Article header with avatar / date + body paragraphs in a read-friendly width"
    :build blog-post}
   {:id    :dashboard-skeleton
    :label "Dashboard skeleton"
    :category :dashboard
    :description "Navbar + stat row + recent-activity card — drop in your own chart and table"
    :build dashboard-skeleton}
   {:id    :kinetic-showcase
    :label "Kinetic Launch"
    :category :demo
    :description "Animated hero, particle CTAs, organic dividers — the full kinetic surface in one demo"
    :build kinetic-showcase}])

(defn templates-in-category
  "Pure: filter `templates` by category. `:all` returns every entry
   in declared order; any other category returns only its members.
   Used by the panel's tab filter and unit-tested independently."
  [cat]
  (if (= cat :all)
    (vec templates)
    (filterv #(= cat (:category %)) templates)))

;; --- apply ------------------------------------------------------------------

(defn apply-template!
  "Replace the current document with `template`'s build output,
   preserving the canvas theme. Resets selection and history,
   clears the autosave, prompts if :dirty? is true."
  [template]
  (when (or (not (:dirty? @state/app-state))
            (js/window.confirm (str "Load \"" (:label template)
                                    "\"? Unsaved changes will be lost.")))
    (canvas/clear!)
    (let [theme (:theme @state/app-state)
          doc   ((:build template))]
      (reset! state/app-state
              (-> (state/initial-state)
                  (assoc :document doc)
                  (assoc :theme theme))))
    (idb/clear-autosave!)))

;; --- panel ------------------------------------------------------------------

(defn- template-card [template panel-el]
  (let [card (u/el :div {:class "template-card"}
                   [(u/set-text! (u/el :div {:class "template-card-title"})
                                 (:label template))
                    (u/set-text! (u/el :div {:class "template-card-desc"})
                                 (:description template))])]
    (u/on! card :click
           (fn [_]
             (apply-template! template)
             (.setAttribute panel-el "data-hidden" "")))
    card))

(defn- render-cards! [^js list-el cat panel-el]
  (.replaceChildren list-el)
  (doseq [t (templates-in-category cat)]
    (.appendChild list-el (template-card t panel-el))))

(defn- tab-button [cat label list-el panel-el get-active set-active!]
  (let [btn (u/el :div {:class (str "templates-tab"
                                    (when (= cat (get-active)) " is-active"))
                        :data-category (name cat)}
                  [(u/set-text! (u/el :span) label)])]
    (u/on! btn :click
           (fn [_]
             (set-active! cat)
             ;; Update the active class on every sibling tab so the
             ;; visual selection moves without rebuilding the bar.
             (doseq [^js sib (array-seq (.-children (.-parentNode btn)))]
               (if (= (.getAttribute sib "data-category") (name cat))
                 (.. sib -classList (add "is-active"))
                 (.. sib -classList (remove "is-active"))))
             (render-cards! list-el cat panel-el)))
    btn))

(defn create
  "Build the templates panel (hidden by default). Returns the DOM
   element ready to place into the chrome. Active-category state
   lives in a JS-object closure local to the panel — transient UI
   state with no need to enter `state/app-state`."
  []
  (let [panel       (u/el :div {:class "templates-panel" :data-hidden ""})
        title       (u/set-text! (u/el :div {:class "templates-title"}) "Templates")
        tab-bar     (u/el :div {:class "templates-tabs"})
        list-el     (u/el :div {:class "templates-list"})
        local       #js {:active :all}
        get-active  (fn [] (.-active local))
        set-active! (fn [cat] (set! (.-active local) cat))]
    (.appendChild panel title)
    (doseq [cat category-order]
      (.appendChild tab-bar
                    (tab-button cat (get category-labels cat)
                                list-el panel get-active set-active!)))
    (.appendChild panel tab-bar)
    (.appendChild panel list-el)
    (render-cards! list-el :all panel)
    panel))

(defn toggle!
  "Show or hide the templates panel."
  [^js panel-el]
  (if (.hasAttribute panel-el "data-hidden")
    (.removeAttribute panel-el "data-hidden")
    (.setAttribute panel-el "data-hidden" "")))
