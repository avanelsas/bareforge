(ns bareforge.export.html-to-hiccup
  "Parse an HTML/SVG string into a hiccup tree. Pure data → pure
   data; consumed by every export plugin that needs to lower a
   document's `:inner-html` into target-specific source.

   Two consumers today:

   - `bareforge.export.cljs-project` — uses `html->hiccup-str` to
     emit a CLJS source string the cljs-project's hiccup pipeline
     splices verbatim.
   - `bareforge.export.vanilla-js.codegen` — uses `parse-html` to
     get the tree as data, then walks it with its own JS-source
     emitter so the output drops directly into the JS hiccup
     literal.

   Both paths share the same parser. `parse-html` returns a vector
   of either strings (text nodes) or `{:tag :attrs :children}`
   maps; everything downstream is just a tree walk."
  (:require [clojure.string :as str]))

(def ^:private self-closing-tags
  #{"area" "base" "br" "col" "embed" "hr" "img" "input"
    "link" "meta" "param" "source" "track" "wbr"
    "path" "circle" "ellipse" "line" "polygon" "polyline" "rect" "use"})

(defn escape-cljs-str
  "Escape a string for inclusion verbatim inside a generated
   ClojureScript source literal — backslash and double-quote only.
   Shared by this ns (HTML text → hiccup string) and by
   `bareforge.export.cljs-project` (attr/text values → emitted
   CLJS source)."
  [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")))

(defn- parse-attrs
  "Parse an attribute string like 'foo=\"bar\" baz=\"qux\"' into a map."
  [attr-str]
  (when (and attr-str (not (str/blank? attr-str)))
    (let [matches (re-seq #"([\w:.-]+)\s*=\s*\"([^\"]*)\"|(\w[\w:.-]*)" attr-str)]
      (into {}
        (for [m matches]
          (if (nth m 3)
            [(nth m 3) true]
            [(nth m 1) (nth m 2)]))))))

(defn- attrs->hiccup-str
  "Convert an attribute map to a hiccup props string.
   A single prop stays inline; two or more get one-per-line with
   vertical alignment after the opening `{`."
  ([attrs] (attrs->hiccup-str attrs nil 0))
  ([attrs tag depth]
   (when (seq attrs)
     (let [props (vec (for [[k v] (sort-by key attrs)]
                        (cond
                          (true? v)    (str ":" k " true")
                          (string? v)  (str ":" k " \"" (escape-cljs-str v) "\"")
                          :else        (str ":" k " " v))))]
       (if (<= (count props) 1)
         (str "{" (str/join " " props) "}")
         (let [align (apply str (repeat (+ depth 3 (count (or tag ""))) " "))]
           (str "{" (first props) "\n"
                (str/join "\n" (map #(str align %) (rest props)))
                "}")))))))

(defn- tokenize
  "Split HTML into tokens: tags and text segments."
  [html]
  (let [parts (re-seq #"<[^>]+>|[^<]+" html)]
    (remove #(str/blank? %) parts)))

(defn- parse-tag
  "Parse a tag token like '<svg viewBox=\"0 0 24 24\">' into
   {:tag \"svg\" :attrs {...} :self-closing? bool :closing? bool}."
  [token]
  (cond
    (str/starts-with? token "</")
    (let [[_ tag] (re-find #"</\s*(\S+)\s*>" token)]
      {:tag tag :closing? true})

    (str/starts-with? token "<")
    (let [[_ tag attr-str] (re-find #"<([\w:.-]+)\s*(.*?)\s*/?\s*>" token)
          self-close? (or (str/ends-with? (str/trimr token) "/>")
                          (contains? self-closing-tags tag))]
      {:tag       tag
       :attrs     (parse-attrs attr-str)
       :self-closing? self-close?})

    :else nil))

(defn- append-to-top
  "Append `child` to the `:children` of the node at the top of the
   parse stack and return the updated stack."
  [stack child]
  (update-in stack [(dec (count stack)) :children] conj child))

(defn- apply-token
  "Fold one token onto the parse stack. Opening tags push a fresh
   open element; closing tags pop it and splice it into the parent's
   children; self-closing tags and text are appended to the current
   top. Tokens with no textual content are a no-op."
  [stack token]
  (if-not (str/starts-with? token "<")
    (let [text (str/trim token)]
      (cond-> stack
        (seq text) (append-to-top text)))
    (let [{:keys [tag attrs closing? self-closing?]} (parse-tag token)]
      (cond
        closing?      (-> stack pop (append-to-top (peek stack)))
        self-closing? (append-to-top stack
                                     {:tag tag :attrs attrs :children []})
        :else         (conj stack {:tag tag :attrs attrs :children []})))))

(defn- build-tree
  "Build a tree of {:tag :attrs :children} from a flat token list.
   The parse stack is a plain vector accumulator whose tail is the
   current open element; the root sentinel stays at index 0 and its
   `:children` is the final result."
  [tokens]
  (:children
    (peek (reduce apply-token
                  [{:tag :root :children []}]
                  tokens))))

(defn parse-html
  "Public: parse an HTML/SVG string into a vector of top-level
   nodes. Each node is either a string (text) or a map of
   `{:tag :attrs :children}` where `:children` is recursively the
   same shape. Empty input → empty vector. The parser is regex-
   based and tuned for the sanitiser-narrowed SVG-icon use case;
   broader HTML edge cases (comments, CDATA, doctype) are out of
   scope — `bareforge.doc.sanitize/sanitize-svg-fragment` strips
   the dangerous shapes upstream."
  [html]
  (build-tree (tokenize (or html ""))))

(defn- tree->hiccup-str
  "Convert a tree node to a hiccup source string at the given indentation depth."
  [node depth]
  (let [pad (apply str (repeat depth " "))]
    (if (string? node)
      (str pad "\"" (escape-cljs-str node) "\"")
      (let [{:keys [tag attrs children]} node
            props (attrs->hiccup-str attrs tag depth)]
        (cond
          (empty? children)
          (str pad "[:" tag (when props (str " " props)) "]")

          (and (= 1 (count children)) (string? (first children)))
          (str pad "[:" tag (when props (str " " props))
               " \"" (escape-cljs-str (first children)) "\"]")

          :else
          (str pad "[:" tag (when props (str " " props))
               (str/join ""
                 (for [child children]
                   (str "\n" (tree->hiccup-str child (+ depth 1)))))
               "]"))))))

(defn html->hiccup-str
  "Convert an HTML string to a hiccup source string.
   Returns one or more hiccup vectors as a string.
   The `depth` parameter controls initial indentation."
  ([html] (html->hiccup-str html 0))
  ([html depth]
   (str/join "\n"
     (for [node (parse-html html)]
       (tree->hiccup-str node depth)))))
