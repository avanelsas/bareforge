(ns bareforge.doc.spec
  "Clojure.spec.alpha schemas for the Bareforge document model and the
   persistable project-file wrapper.

   The layout map and the canvas map both carry a `:width` field,
   but with very different semantics — layout uses a CSS length
   string (`\"200px\"`, `\"100%\"`, nil) while canvas uses a pixel
   integer. They are registered under different keyword namespaces
   (`:layout/width` vs `::width`) so `s/keys :req-un` can resolve
   each to its own spec without a collision."
  (:require [clojure.spec.alpha :as s]))

;; --- node fields ---------------------------------------------------------

(s/def ::id    string?)
(s/def ::tag   string?)
(s/def ::attrs (s/map-of string? (s/nilable string?)))
(s/def ::props (s/map-of keyword? any?))
(s/def ::text       (s/nilable string?))
(s/def ::inner-html (s/nilable string?))

;; --- layout --------------------------------------------------------------

(s/def ::placement
  #{:flow :top-full-width :bottom-full-width :free :background})

(s/def ::x (s/nilable number?))
(s/def ::y (s/nilable number?))
(s/def ::w (s/nilable number?))
(s/def ::h (s/nilable number?))

;; CSS length strings, under a dedicated :layout/ keyword namespace so
;; they don't clash with the pixel-integer canvas width below.
(s/def :layout/width  (s/nilable string?))
(s/def :layout/height (s/nilable string?))

(s/def ::padding     (s/nilable string?))
(s/def ::margin      (s/nilable string?))
(s/def ::extra-style (s/nilable string?))
(s/def ::css-vars    (s/nilable (s/map-of string? (s/nilable string?))))

(s/def ::layout
  (s/keys :req-un [::placement]
          :opt-un [::x ::y ::w ::h
                   :layout/width :layout/height
                   ::padding ::margin
                   ::extra-style ::css-vars]))

;; --- component naming + data bindings ------------------------------------

(s/def ::name (s/nilable string?))

(s/def ::text-field (s/nilable keyword?))
;; Optional sibling to `:text-field` that records which named group
;; the user picked the field from, so cross-group ambiguity (two
;; groups declaring the same field name) survives save/load without
;; a doc walk guessing the wrong owner.
(s/def ::text-field-owner (s/nilable string?))

(s/def ::source-sub (s/nilable qualified-keyword?))

(s/def ::source-field (s/nilable keyword?))

(s/def :binding/field keyword?)
(s/def :binding/direction #{:read :write :read-write})
(s/def :binding/owner (s/nilable string?))

(s/def ::binding
  (s/keys :req-un [:binding/field :binding/direction]
          :opt-un [:binding/owner]))

(s/def ::bindings (s/nilable (s/map-of string? ::binding)))

;; --- triggers: DOM event → action dispatch -------------------------------

;; A payload entry is one of three shapes:
;;  - a field reference: value comes from the enclosing view's fields,
;;    either a let-bound rf/query sym or a destructured record key.
;;  - a literal value: any EDN value (true/false/string/number/keyword).
;;  - an event-detail read: value extracted from the DOM event's
;;    .detail property at dispatch time.

(s/def :payload-entry/field keyword?)
(s/def :payload-entry/owner (s/nilable string?))
(s/def :payload-entry/literal any?)
(s/def :payload-entry/event-detail keyword?)

(s/def ::field-payload-entry
  (s/keys :req-un [:payload-entry/field]
          :opt-un [:payload-entry/owner]))

(s/def ::literal-payload-entry
  (s/keys :req-un [:payload-entry/literal]))

(s/def ::event-detail-payload-entry
  (s/keys :req-un [:payload-entry/event-detail]))

(s/def ::payload-entry
  (s/or :field ::field-payload-entry
        :literal ::literal-payload-entry
        :event-detail ::event-detail-payload-entry))

(s/def :trigger/trigger string?)
(s/def :trigger/action-ref qualified-keyword?)
(s/def :trigger/payload
  (s/nilable (s/coll-of ::payload-entry :kind vector?)))
(s/def :trigger/prevent-default? (s/nilable boolean?))

(s/def ::trigger
  (s/keys :req-un [:trigger/trigger :trigger/action-ref]
          :opt-un [:trigger/payload :trigger/prevent-default?]))

(s/def ::events (s/nilable (s/coll-of ::trigger :kind vector?)))

;; --- group-level field + action definitions ------------------------------

(s/def :field-def/name keyword?)
(s/def :field-def/type #{:string :number :boolean :keyword :vector})
(s/def :field-def/default any?)
(s/def :field-def/of-group (s/nilable string?))
;; A locked field is owned by Bareforge and cannot be renamed or deleted
;; through the inspector. The auto-inserted ::id on every named group is
;; the only current use.
(s/def :field-def/locked? (s/nilable boolean?))

(s/def :computed/operation
  #{:count-of :sum-of :empty-of :negation :join-on :any-of :filter-by})
(s/def :computed/source-field keyword?)
(s/def :computed/source-fields (s/coll-of keyword? :kind vector?))
;; :sum-of over a collection of records needs one more pick: which
;; numeric field of the record to project out before summing. nil when
;; the source is already a collection of numbers.
(s/def :computed/project-field (s/nilable keyword?))

(s/def :join-target/group-name string?)
(s/def :join-target/match-field keyword?)
(s/def :join-target/of-group (s/nilable string?))
(s/def :computed/join-target
  (s/keys :req-un [:join-target/group-name :join-target/match-field]
          :opt-un [:join-target/of-group]))

;; :filter-by derives a collection by filtering `:source-field` (a
;; local `:of-group` collection) against a scalar `:search-field` on
;; the same group, comparing against `:match-field` on the template
;; record. v1 supports one `:match-kind` (`:contains-ci`); the enum
;; is closed so unknown kinds are rejected at load.
(s/def :filter-spec/search-field keyword?)
(s/def :filter-spec/match-field keyword?)
(s/def :filter-spec/match-kind #{:contains-ci})
(s/def :computed/filter-spec
  (s/keys :req-un [:filter-spec/search-field
                   :filter-spec/match-field
                   :filter-spec/match-kind]))

(s/def ::computed
  (s/keys :req-un [:computed/operation]
          :opt-un [:computed/source-field :computed/source-fields
                   :computed/project-field :computed/join-target
                   :computed/filter-spec]))

(s/def ::field-def
  (s/keys :req-un [:field-def/name :field-def/type]
          :opt-un [:field-def/default :field-def/of-group
                   :field-def/locked? ::computed]))

(s/def ::fields (s/nilable (s/coll-of ::field-def :kind vector?)))

(s/def :action/name keyword?)
(s/def :action/operation
  #{:set :toggle :increment :decrement :clear :add :remove})
(s/def :action/target-field keyword?)

;; Multi-step action shape (v2). A step is one operation against one
;; field; `:payload` overrides the dispatched trigger arg with a vector
;; of payload entries (only `{:literal v}` is honoured at the step
;; level for now — it covers the canonical "add to cart, then set
;; popover-open false" use case). Single-step actions can continue to
;; carry `:operation` + `:target-field` directly; both shapes are
;; valid and the generators canonicalise via `actions/step-list`.
(s/def :action-step/operation
  #{:set :toggle :increment :decrement :clear :add :remove})
(s/def :action-step/target-field keyword?)
(s/def :action-step/payload (s/coll-of map? :kind vector?))

(s/def ::action-step
  (s/keys :req-un [:action-step/operation :action-step/target-field]
          :opt-un [:action-step/payload]))

(s/def :action/steps (s/coll-of ::action-step :kind vector? :min-count 1))

(s/def ::action
  (s/and
   (s/keys :req-un [:action/name]
           :opt-un [:action/operation :action/target-field :action/steps])
   (fn [a]
     ;; Either single-step (operation + target-field) OR multi-step (steps)
     ;; — never both, never neither.
     (let [single? (and (contains? a :operation) (contains? a :target-field))
           multi?  (contains? a :steps)]
       (and (or single? multi?)
            (not (and single? multi?)))))))

(s/def ::actions (s/nilable (s/coll-of ::action :kind vector?)))

;; --- nodes + slots -------------------------------------------------------

(s/def ::slot-name string?)

(s/def ::node
  (s/keys :req-un [::id ::tag ::attrs ::props ::slots ::layout]
          :opt-un [::text ::inner-html ::name ::text-field ::text-field-owner
                   ::source-sub ::source-field
                   ::bindings ::events ::fields ::actions]))

(s/def ::slots
  (s/map-of ::slot-name (s/coll-of ::node :kind vector?)))

;; --- document ------------------------------------------------------------

(s/def ::root ::node)

;; Canvas pixel dimensions. Different keyword namespace from layout's
;; :layout/width / :layout/height string lengths.
(s/def ::width       pos-int?)
(s/def ::left        nat-int?)
(s/def ::right       nat-int?)
(s/def ::content-col (s/keys :req-un [::left ::right]))
(s/def ::canvas      (s/keys :req-un [::width ::content-col]))

(s/def ::next-id nat-int?)

(s/def ::document (s/keys :req-un [::root ::canvas ::next-id]))

;; --- persistable project-file wrapper -----------------------------------

;; A project file on disk has shape
;;   {:format "bareforge-project"
;;    :version 1
;;    :document <::document>
;;    :theme    <map>}
;; Used by storage/project-file to validate the `.json` payload on
;; load so a malformed file is refused instead of silently polluting
;; state.

(s/def :pf/format  #{"bareforge-project"})
(s/def :pf/version pos-int?)
(s/def :pf/theme   (s/nilable map?))

(s/def ::project-file
  (s/keys :req-un [:pf/format ::document]
          :opt-un [:pf/version :pf/theme]))
