(ns bareforge.meta.heuristics
  "Pure helpers shared by the runtime registry fallback (CLJS) and the
   offline `scripts/scaffold_component.clj` scaffolder (JVM Clojure).
   Kept as `.cljc` so the two callers never drift apart on what
   `:boolean` means, how an attribute name maps to a property kind,
   or how a raw tag gets humanized into a label.

   Everything here is data-only — no interop, no runtime-specific
   requires — so both CLJS and Clojure read the same implementation."
  (:require [clojure.string :as str]))

(def ^:private boolean-attrs
  "Attribute names that consistently encode an on/off flag across
   the BareDOM catalogue. Kept as a literal set for fast lookup."
  #{"disabled" "readonly" "required" "checked" "loading" "pressed"
    "open" "active" "sticky" "elevated" "selected" "expanded"
    "collapsed"})

(def ^:private number-attrs
  "Attribute names that BareDOM treats as numeric in one or more
   components. Only includes names that are unambiguously numeric
   (e.g. not `value`, which is text for inputs and number for
   sliders)."
  #{"max" "min" "step"})

(def ^:private url-attrs
  "Attribute names that carry URLs. `:url` drives the inspector's
   URL-validating widget."
  #{"src" "href"})

(defn infer-kind
  "Pure: guess a property kind from an attribute name alone.
   Known booleans / URLs / number fields route to their real kinds;
   anything with a `-ms` suffix is treated as a millisecond duration;
   everything else defaults to `:string-short` so the fallback at
   least renders a plain text field instead of an opaque `:unknown`."
  [attr-name]
  (cond
    (contains? url-attrs     attr-name) :url
    (contains? boolean-attrs attr-name) :boolean
    (contains? number-attrs  attr-name) :number
    (str/ends-with? attr-name "-ms")    :number
    :else                               :string-short))

(defn humanize-tag
  "Pure: `\"x-bento-grid\"` → `\"Bento grid\"`. Drop the `x-` prefix
   if present, turn hyphens into spaces, upper-case the first
   letter. Falls through cleanly on empty input."
  [tag]
  (let [bare   (if (str/starts-with? tag "x-") (subs tag 2) tag)
        spaced (str/replace bare #"-" " ")]
    (if (seq spaced)
      (str (str/upper-case (subs spaced 0 1)) (subs spaced 1))
      spaced)))
