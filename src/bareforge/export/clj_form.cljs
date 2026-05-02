(ns bareforge.export.clj-form
  "Data shape for emitted Clojure(Script) source. Plugin generators
   build values in this shape; `format-form` / `format-file` print
   them as text exactly once at the edge.

   Hiccup is the reference: a vector starting with one of the
   recognised tag keywords is a form; anything else is a leaf value
   formatted via Clojure's natural printer (`pr-str` semantics).
   Recognised tags:

     [:literal x]                — pr-str the value (strings quote +
                                   escape, keywords keep their
                                   namespace, numbers / booleans /
                                   nil format the obvious way)
     [:symbol s]                 — bare symbol, no quoting
     [:keyword k]                — explicit keyword form
     [:auto-keyword s]           — auto-resolved keyword text:
                                   `[:auto-keyword \"cart-count\"]`
                                   prints as `::cart-count` (resolves
                                   to the consuming file's ns); a
                                   slash-qualified name like
                                   `[:auto-keyword \"cart.db/items\"]`
                                   prints as `::cart.db/items` and
                                   uses the file's `:require` alias.
                                   Modelled separately because
                                   `pr-str` on a keyword can never
                                   round-trip through the `::` reader
                                   macro.
     [:vector & forms]           — [a b c]
     [:map & kv-forms]           — {k1 v1 k2 v2 …}  (kv-forms come in pairs)
     [:invoke head & args]       — (head a b c). Inline when short
                                   (≤70 chars), multi-line with
                                   one-space arg-continuation indent
                                   when long.
     [:invoke-block head & args] — same shape as :invoke but ALWAYS
                                   multi-line. Use for declarations
                                   (reg-sub, reg-event, …) where the
                                   line-by-line layout is part of the
                                   readable output even when the
                                   inline form would fit.
     [:def name value]           — (def name value)
     [:defn name docstring? args & body]
                                 — (defn name docstring? [args] body…)
     [:let bindings & body]      — (let [b1 v1 …] body…)
     [:fn args & body]           — (fn [args] body…)
     [:if test then else?]
     [:when test & body]
     [:cond & clauses]           — clauses come in pairs (test expr)
     [:ns name & require-vecs]   — (ns name (:require ...))
     [:require alias & specs]    — one entry inside a :require clause
                                   (vector form like [foo.bar :as fb])
     [:do & body]
     [:thread head & forms]      — (-> head form …)
     [:thread-last head & forms] — (->> head form …)
     [:reader-tag tag form]      — #js {…}, prints as `#tag form`
     [:comment text]             — ;; text  (line comment)
     [:pair k v]                 — `k v` rendered inline as a single
                                   logical arg. Used inside
                                   :invoke-block for forms whose
                                   structure pairs a directive
                                   keyword with its value (e.g.
                                   `:-> ::field`, `:<- [::source]`).
                                   Without :pair the multi-line
                                   layout would split each on its
                                   own line, losing the readable
                                   association.
     [:hiccup tag props text & children]
                                 — `[:tag {…} text child child…]`
                                   custom-element-friendly hiccup with
                                   column-aware multi-prop alignment.
                                   `tag` is a keyword/string (without
                                   leading `:`). `props` is nil or a
                                   seq of `[k v]` clj-form pairs.
                                   `text` is nil or a single clj-form
                                   leaf rendered inline-after-props
                                   when there are no children, or on
                                   its own line above the children
                                   otherwise. Children render one per
                                   line, indented one space under `[`.
                                   Multi-prop maps align subsequent
                                   pairs to `(count tag) + 3` spaces
                                   so they sit under the first prop —
                                   the column-aware layout the cljs-
                                   project view emitter needs.
     [:raw text]                 — verbatim string passthrough; escape
                                   hatch for forms not yet supported

   Indentation follows the Bareforge cljs-project convention (which
   matches cljfmt's defaults for the constructs we emit):

     - `:invoke` — one-space arg-continuation indent under the open
       paren when the form spills to multiple lines (`(reg-sub\n ::x …)`).
     - `:defn` / `:fn` / `:let` / `:when` / `:do` / `:cond` — two-space
       body indent under the opening keyword.
     - `:thread` / `:thread-last` — one-space arg-continuation indent
       under the threading macro.
     - `:vector` / `:map` — one-space indent inside the bracket so
       elements line up with the first element.

   The formatter does NOT attempt to be a general pretty-printer.
   It is opinionated for the shapes the cljs-project pipeline
   produces; new tags get added with their own rule when an emitter
   needs them. That keeps output reproducible and review-friendly."
  (:require [clojure.string :as str]))

;; --- recognised form tags ------------------------------------------------

(def ^:private form-tags
  "Set of head keywords that turn a vector into a tagged form.
   Anything else (a vector starting with a different keyword, a bare
   symbol, a string, etc.) is treated as a leaf and formatted via
   `format-leaf`."
  #{:literal :symbol :keyword :auto-keyword
    :vector :map :pair :hiccup
    :invoke :invoke-block
    :def :defn :let :fn :if :when :cond :ns :require
    :do :thread :thread-last :reader-tag :comment :raw})

(defn- form?
  "True when `x` is a vector whose head is a recognised form tag."
  [x]
  (and (vector? x)
       (pos? (count x))
       (contains? form-tags (first x))))

;; --- leaf value formatting -----------------------------------------------

(defn- format-leaf
  "Print a non-form value via Clojure's natural printer. Strings get
   their quotes + escapes; keywords keep their namespace; numbers,
   booleans, nil, and characters format obviously. Used for the
   children of `:literal` and any non-form value embedded in a form."
  [v]
  (pr-str v))

;; --- forward declaration -------------------------------------------------

(declare format-form indent)

;; --- inline rendering ----------------------------------------------------
;;
;; Each form has an "inline" form (single-line) and a "block" form
;; (multi-line, indented). The formatter tries inline first and falls
;; back to block when the inline width exceeds `inline-width-limit`.

(def ^:private inline-width-limit
  "When a form's inline rendering would exceed this many columns,
   the formatter switches to the multi-line block layout. 70 matches
   cljfmt's default soft margin."
  70)

(defn- inline?
  "Cheap width check on `s`: true when it fits inline."
  [s]
  (and (not (str/includes? s "\n"))
       (<= (count s) inline-width-limit)))

;; --- per-tag formatters --------------------------------------------------

(defn- format-children
  "Format each child via `format-form`, returning a vector of strings.
   The base indent is 0 — callers add their own indentation when
   composing a block layout."
  [children]
  (mapv format-form children))

(defn- inline-join
  "Join formatted children with single-space separators. Used for the
   inline rendering of any form whose children are already strings."
  [parts]
  (str/join " " parts))

(defmulti ^:private format-form*
  "Internal: dispatch on the form's tag head."
  (fn [form] (first form)))

;; --- :literal / :symbol / :keyword ---------------------------------------

(defmethod format-form* :literal
  [[_ v]] (format-leaf v))

(defmethod format-form* :symbol
  [[_ s]] (str s))

(defmethod format-form* :keyword
  [[_ k]] (pr-str k))

(defmethod format-form* :auto-keyword
  [[_ s]] (str "::" s))

;; --- :raw — verbatim passthrough ----------------------------------------

(defmethod format-form* :raw
  [[_ text]] (str text))

;; --- :comment -----------------------------------------------------------

(defmethod format-form* :comment
  [[_ text]] (str ";; " text))

;; --- :reader-tag --------------------------------------------------------

(defmethod format-form* :reader-tag
  [[_ tag form]]
  (str "#" tag " " (format-form form)))

;; --- :pair --------------------------------------------------------------

(defmethod format-form* :pair
  [[_ k v]]
  (str (format-form k) " " (format-form v)))

;; --- :hiccup ------------------------------------------------------------
;;
;; Custom-element hiccup with the cljs-project view emitter's column-aware
;; layout. Rules (relative to the form's own column 0):
;;
;;   - 0 props  → omit the props map entirely.
;;   - 1 prop   → `{:k v}` inline.
;;   - 2+ props → first pair on the tag-line; each subsequent pair on its
;;                own line at column `(count bare-tag) + 3` so it lines
;;                up under the first pair's `:` (matches the alignment
;;                rule from the legacy `format-props-map`).
;;
;;   - text + no children → text inlined with a single space before `]`.
;;   - children present   → each child on its own line, prefixed with
;;                          `\n` + one space so it sits under the `[`.
;;                          A non-nil `text` renders as the first child
;;                          line above the rest.
;;
;; The form is INDENT-AGNOSTIC: it always renders starting at relative
;; column 0. Outer composition (parents, callers) re-indents the
;; resulting block to the absolute column they want it at.

(defn- format-hiccup-props
  "Render the props slot of a `:hiccup` form. Returns nil for an empty
   props seq, an inline `{:k v}` for one pair, or a multi-line block
   for two-or-more pairs aligned to `(count bare-tag) + 3` spaces."
  [bare-tag pairs]
  (when (seq pairs)
    (let [rendered (mapv (fn [[k v]]
                           (str (format-form k) " " (format-form v)))
                         pairs)]
      (if (= 1 (count rendered))
        (str "{" (first rendered) "}")
        (let [align (apply str (repeat (+ 3 (count bare-tag)) " "))]
          (str "{" (first rendered)
               (str/join "" (for [p (rest rendered)]
                              (str "\n" align p)))
               "}"))))))

(defmethod format-form* :hiccup
  [[_ tag props text & children]]
  (let [bare-tag  (cond
                    (keyword? tag) (name tag)
                    (string? tag)  tag
                    :else          (str tag))
        tag-str   (str ":" bare-tag)
        props-str (format-hiccup-props bare-tag props)
        text-str  (when text (format-form text))
        kid-strs  (mapv format-form children)
        head      (str "[" tag-str (when props-str (str " " props-str)))]
    (cond
      (and (empty? kid-strs) (nil? text-str))
      (str head "]")

      (empty? kid-strs)
      (str head " " text-str "]")

      :else
      (str head
           (when text-str (str "\n " (indent text-str 1)))
           (apply str (for [k kid-strs]
                        (str "\n " (indent k 1))))
           "]"))))

;; --- :vector ------------------------------------------------------------

(defmethod format-form* :vector
  [[_ & children]]
  (let [parts  (format-children children)
        inline (str "[" (inline-join parts) "]")]
    (if (inline? inline)
      inline
      ;; Block: each child on its own line, indented one space under [.
      (str "[" (str/join "\n " parts) "]"))))

;; --- :map ---------------------------------------------------------------

(defmethod format-form* :map
  [[_ & kv-forms]]
  (let [pairs (partition 2 kv-forms)
        ;; Keys and values format independently; pairs render as `k v`.
        rendered-pairs (mapv (fn [[k v]]
                               (str (format-form k) " " (format-form v)))
                             pairs)
        inline (str "{" (str/join ", " rendered-pairs) "}")]
    (if (inline? inline)
      inline
      ;; Block: one pair per line, indented one space under {.
      (str "{" (str/join "\n " rendered-pairs) "}"))))

;; --- :invoke ------------------------------------------------------------

(defn- format-invoke-block
  "Multi-line layout shared by :invoke (when long) and :invoke-block
   (always). Function head on its own line, every arg on its own line
   with one-space arg-continuation indent under the open paren — the
   Lisp argument-continuation convention that matches cljfmt's
   default for non-special-indent calls."
  [head-str arg-strs]
  (str "(" head-str "\n "
       (str/join "\n " arg-strs)
       ")"))

(defmethod format-form* :invoke
  [[_ head & args]]
  (let [head-str (format-form head)
        arg-strs (format-children args)
        inline   (str "(" head-str
                      (when (seq arg-strs) (str " " (inline-join arg-strs)))
                      ")")]
    (if (inline? inline)
      inline
      (format-invoke-block head-str arg-strs))))

(defmethod format-form* :invoke-block
  [[_ head & args]]
  (format-invoke-block (format-form head) (format-children args)))

;; --- :def ---------------------------------------------------------------

(defmethod format-form* :def
  [[_ name value]]
  (let [name-str  (format-form name)
        value-str (format-form value)
        inline    (str "(def " name-str " " value-str ")")]
    (if (inline? inline)
      inline
      ;; Block: value indented two spaces under the (def
      (str "(def " name-str "\n  " (indent value-str 2) ")"))))

;; --- :defn --------------------------------------------------------------

(defn- defn-pieces
  "Pull the optional docstring out of a `:defn` form's args. Returns
   `[name-form docstring-or-nil args-vector body-forms]`."
  [[_ name & rest]]
  (let [[doc & rest']  (if (string? (first rest)) rest (cons nil rest))
        [args & body]  rest']
    [name doc args body]))

(defmethod format-form* :defn
  [form]
  (let [[name doc args body] (defn-pieces form)
        name-str (format-form name)
        args-str (format-form args)
        body-strs (format-children body)
        head     (str "(defn " name-str)
        doc-line (when doc (str "\n  " (pr-str doc)))]
    (str head
         doc-line
         "\n  " args-str
         (when (seq body-strs)
           (str "\n  " (str/join "\n  " (map #(indent % 2) body-strs))))
         ")")))

;; --- :fn ----------------------------------------------------------------

(defmethod format-form* :fn
  [[_ args & body]]
  (let [args-str (format-form args)
        body-strs (format-children body)
        inline (str "(fn " args-str
                    (when (seq body-strs) (str " " (inline-join body-strs)))
                    ")")]
    (if (inline? inline)
      inline
      (str "(fn " args-str "\n  "
           (str/join "\n  " (map #(indent % 2) body-strs))
           ")"))))

;; --- :let ---------------------------------------------------------------

(def ^:private let-binding-indent
  ;; "(let [" is 6 chars wide — subsequent binding pairs align under
  ;; the first symbol so the binding vector stays readable.
  6)

(defmethod format-form* :let
  [[_ bindings & body]]
  (let [pairs (cond
                (and (vector? bindings) (= :vector (first bindings)))
                (partition 2 (rest bindings))

                (vector? bindings)
                (partition 2 bindings)

                :else
                (throw (ex-info "let bindings must be a vector"
                                {:bindings bindings})))
        bind-strs (mapv (fn [[sym v]]
                          (let [sym-str (format-form sym)
                                v-str   (format-form v)]
                            ;; Multi-line values re-indent under the
                            ;; symbol's column for readability.
                            (str sym-str " "
                                 (indent v-str (+ (count sym-str) 1)))))
                        pairs)
        pad          (apply str (repeat let-binding-indent " "))
        bindings-str (str "[" (str/join (str "\n" pad) bind-strs) "]")
        body-strs    (format-children body)]
    (str "(let " bindings-str
         (when (seq body-strs)
           (str "\n  " (str/join "\n  " (map #(indent % 2) body-strs))))
         ")")))

;; --- :if / :when --------------------------------------------------------

(defmethod format-form* :if
  [[_ test then & [else]]]
  (let [test-str (format-form test)
        then-str (format-form then)
        else-str (when else (format-form else))
        inline   (str "(if " test-str " " then-str
                      (when else-str (str " " else-str)) ")")]
    (if (inline? inline)
      inline
      (str "(if " test-str
           "\n  " (indent then-str 2)
           (when else-str (str "\n  " (indent else-str 2)))
           ")"))))

(defmethod format-form* :when
  [[_ test & body]]
  (let [test-str  (format-form test)
        body-strs (format-children body)
        inline    (str "(when " test-str
                       (when (seq body-strs) (str " " (inline-join body-strs)))
                       ")")]
    (if (inline? inline)
      inline
      (str "(when " test-str
           "\n  " (str/join "\n  " (map #(indent % 2) body-strs))
           ")"))))

;; --- :cond --------------------------------------------------------------

(defmethod format-form* :cond
  [[_ & clauses]]
  (let [pairs (partition 2 clauses)
        rendered (mapv (fn [[test expr]]
                         (str (format-form test) "\n  " (indent (format-form expr) 2)))
                       pairs)]
    (str "(cond"
         "\n  " (str/join "\n\n  " rendered)
         ")")))

;; --- :do ----------------------------------------------------------------

(defmethod format-form* :do
  [[_ & body]]
  (let [body-strs (format-children body)
        inline (str "(do " (inline-join body-strs) ")")]
    (if (inline? inline)
      inline
      (str "(do\n  "
           (str/join "\n  " (map #(indent % 2) body-strs))
           ")"))))

;; --- :thread / :thread-last --------------------------------------------

(defn- format-thread
  [op head forms]
  (let [head-str  (format-form head)
        form-strs (format-children forms)
        inline    (str "(" op " " head-str
                       (when (seq form-strs) (str " " (inline-join form-strs)))
                       ")")]
    (if (inline? inline)
      inline
      ;; Each step on its own line, one-space arg-continuation indent
      ;; that lines up the form bodies under the head (1 for `(`, len
      ;; of `op` for the operator itself, 1 for the space).
      (let [pad (apply str (repeat (inc (count op)) " "))]
        (str "(" op " " head-str "\n" pad
             (str/join (str "\n" pad) form-strs)
             ")")))))

(defmethod format-form* :thread
  [[_ head & forms]]
  (format-thread "->" head forms))

(defmethod format-form* :thread-last
  [[_ head & forms]]
  (format-thread "->>" head forms))

;; --- :ns + :require -----------------------------------------------------

(defmethod format-form* :require
  [[_ alias & specs]]
  ;; A :require entry is just a vector form: `[ns-name alias specs…]`.
  (let [parts (format-children (cons alias specs))]
    (str "[" (inline-join parts) "]")))

(defmethod format-form* :ns
  [[_ name & require-vecs]]
  (let [name-str     (format-form name)
        require-strs (mapv format-form require-vecs)]
    (if (empty? require-strs)
      (str "(ns " name-str ")")
      (str "(ns " name-str
           "\n  (:require "
           (str/join "\n            " require-strs)
           "))"))))

;; --- public API ---------------------------------------------------------

(defn format-form
  "Pure: render a clj-form data value as a Clojure source string.
   Leaves (non-form values) format via Clojure's natural printer.
   Tagged form vectors render through their per-tag formatter."
  [x]
  (if (form? x)
    (format-form* x)
    (format-leaf x)))

(defn indent
  "Pure: prepend `n` spaces to every line of `s` after the first.
   Used by the per-tag formatters when composing a child onto a line
   that already has its own indent."
  [s n]
  (let [pad (str/join (repeat n " "))]
    (str/replace s "\n" (str "\n" pad))))

(defn format-file
  "Pure: join multiple top-level forms with one blank line between
   them, returning a single string suitable for writing to a `.cljs`
   file. Mirrors the file-level structure of the existing cljs-project
   output (ns, then defs/defns separated by blank lines)."
  [forms]
  (str/join "\n\n" (mapv format-form forms)))
