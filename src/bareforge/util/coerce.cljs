(ns bareforge.util.coerce
  "Pure coercion helpers shared by inspector widgets, palette inputs,
   and the storage-load boundary. Each function takes a value (usually
   a string from a DOM `<input>`) and returns the canonical form the
   document model wants to store: nil for an absent / cleared value,
   a number for numeric inputs that parse cleanly, a keyword for
   string-encoded enum picks.

   The audit (Step 6) called these out by name as the kind of pure
   logic that should live outside event-handler closures so it's
   unit-testable without a DOM. Putting them here lets every
   `build-*-field` builder reference one canonical implementation
   instead of re-inlining `(when (and v (not= \"\" v)) v)` and
   `(let [p (js/parseFloat …)] (when-not (js/isNaN p) p))` for the
   tenth time.

   Pure: no atom reads, no DOM, no side effects. JS interop stays
   limited to `js/parseFloat` / `js/isNaN`, which are both side-effect
   free and CLJS-portable — `:node-test` exercises this namespace
   directly.")

(defn nil-if-empty
  "Pure: return `v` when it's a non-empty string, nil for nil or
   the empty string. Mirrors the `(when (and v (not= \"\" v)) v)`
   pattern that appeared a dozen times across the inspector — a
   cleared input should unset the underlying field, not write `\"\"`."
  [v]
  (when (and (string? v) (not= "" v)) v))

(defn keyword-or-nil
  "Pure: project a non-empty string into a keyword, nil otherwise.
   Used by the inspector's enum-style pickers (placement, source-
   field, action target) where the underlying ops/* fn wants a
   keyword and a cleared widget should unset rather than write
   `(keyword \"\")` (which would be a real, distinct keyword that
   round-trips through specs as a value, not as absence)."
  [v]
  (when-let [s (nil-if-empty v)]
    (keyword s)))

(defn parse-number
  "Pure: parse `v` as a JS double. Returns the parsed number when
   `js/parseFloat` produces a non-NaN result, nil otherwise (including
   for nil / empty-string / leading-non-numeric input). Use this when
   a numeric field should clear on bad input — `parse-number-or-zero`
   when the gesture should default to zero instead."
  [v]
  (when (and v (or (number? v) (and (string? v) (not= "" v))))
    (let [p (js/parseFloat v)]
      (when-not (js/isNaN p) p))))

(defn parse-number-or-zero
  "Pure: like `parse-number` but returns 0 instead of nil for missing
   / unparseable input. Suits the numeric scrub gesture's read-fn —
   a fresh / empty field should still engage the drag at 0 so the
   user can scrub a value into existence; a hard nil would silently
   no-op."
  [v]
  (or (parse-number v) 0))

(defn parse-length-value
  "Pure: coerce an inspector length string into a number when it
   parses cleanly to that exact representation, otherwise pass the
   original string through. Empty / nil → nil (clear).

   The exact-representation check (`(= (str n) v)`) means `\"50\"` →
   `50`, but `\"50%\"` → `\"50%\"` and `\"10rem\"` → `\"10rem\"` —
   the user keeps the unit suffix instead of having `parseFloat`
   silently strip it. The reconciler's layout pass treats numbers as
   px and strings as raw CSS, so this preserves authoring intent."
  [v]
  (cond
    (or (nil? v) (= "" v)) nil
    :else                  (let [n (js/parseFloat v)]
                             (if (and (number? n) (not (js/isNaN n))
                                      (= (str n) v))
                               n
                               v))))
