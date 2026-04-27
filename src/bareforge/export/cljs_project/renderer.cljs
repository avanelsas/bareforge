(ns bareforge.export.cljs-project.renderer
  "Hiccup-to-DOM renderer with reconciliation. Views are pure
   functions returning hiccup vectors. Adapted from bare-demo."
  (:require [clojure.string :as str]))

;;; ── Prop helpers ──────────────────────────────────────────────────────────

(defn- on-key? [k]
  (str/starts-with? (name k) "on-"))

(defn- event-name [k]
  (subs (name k) 3))

(def ^:private listeners-key "__bd_listeners")
(def ^:private props-key     "__bd_props")

;; The reconciler tracks the children it created on each parent via
;; `children-key`. Reading this array (instead of live `.childNodes`)
;; matters when a custom element rearranges its own children — e.g.
;; `x-popover` with `portal=true` appendChild's default-slot content
;; into a floating panel on open. `.childNodes` of the original parent
;; would no longer contain those nodes; our array still does. On
;; removal / replace we anchor at `node.parentNode` rather than the
;; original parent, so we reach the node wherever it currently lives.
(def ^:private children-key "__bd_children")

(defn- set-listener! [^js el k handler]
  (let [ename (event-name k)
        store (or (aget el listeners-key) #js {})]
    (when-let [old (aget store ename)]
      (.removeEventListener el ename old))
    (.addEventListener el ename handler)
    (aset store ename handler)
    (aset el listeners-key store)))

(defn- remove-listener! [^js el k]
  (let [ename (event-name k)
        store (aget el listeners-key)]
    (when-let [old (and store (aget store ename))]
      (.removeEventListener el ename old)
      (js-delete store ename))))

(defn- set-prop! [^js el k v]
  (cond
    (on-key? k)  (set-listener! el k v)
    (nil? v)     (.removeAttribute el (name k))
    (true? v)    (.setAttribute el (name k) "")
    (false? v)   (.removeAttribute el (name k))
    :else        (.setAttribute el (name k) (str v))))

(defn- store-props! [^js el props]
  (aset el props-key props))

(defn- get-stored-props [^js el]
  (or (aget el props-key) {}))

(defn- store-children! [^js el nodes]
  (aset el children-key (to-array nodes)))

(defn- get-stored-children [^js el]
  (or (aget el children-key) (array)))

;;; ── Hiccup parsing ───────────────────────────────────────────────────────

(defn- parse-vnode [v]
  (let [[tag & args] v
        has-props? (and (seq args) (map? (first args)))
        props      (if has-props? (first args) {})
        children   (if has-props? (rest args) args)]
    [tag props children]))

(defn- flatten-children [children]
  (persistent!
   (reduce (fn [acc x]
             (cond
               (nil? x)    acc
               (false? x)  acc
               (string? x) (conj! acc x)
               (number? x) (conj! acc (str x))
               (vector? x) (conj! acc x)
               (seq? x)    (reduce conj! acc (flatten-children x))
               :else       acc))
           (transient [])
           children)))

;;; ── DOM creation ──────────────────────────────────────────────────────────

(def ^:private svg-ns "http://www.w3.org/2000/svg")

(def ^:private svg-tags
  #{"svg" "path" "circle" "ellipse" "line" "polygon" "polyline"
    "rect" "g" "defs" "use" "text" "tspan" "clipPath" "mask"
    "pattern" "linearGradient" "radialGradient" "stop" "filter"
    "feGaussianBlur" "feColorMatrix" "feComposite" "feOffset"
    "feMerge" "feMergeNode" "foreignObject" "marker" "symbol"})

(defn- svg-tag? [tag-name]
  (contains? svg-tags tag-name))

(declare create-node)

(defn- create-element [v]
  (let [[tag props children] (parse-vnode v)
        tag-name (name tag)
        ^js el (if (svg-tag? tag-name)
                 (.createElementNS js/document svg-ns tag-name)
                 (.createElement js/document tag-name))]
    (doseq [[k val] props]
      (set-prop! el k val))
    (store-props! el props)
    (let [created (mapv create-node (flatten-children children))]
      (doseq [child created]
        (.appendChild el child))
      (store-children! el created))
    el))

(defn- create-node [x]
  (cond
    (string? x) (.createTextNode js/document x)
    (vector? x) (create-element x)
    :else       (.createTextNode js/document "")))

;;; ── Reconciler ────────────────────────────────────────────────────────────

(defn- patch-props! [^js el old-props new-props]
  (doseq [[k _] old-props]
    (when-not (contains? new-props k)
      (if (on-key? k)
        (remove-listener! el k)
        (.removeAttribute el (name k)))))
  (doseq [[k v] new-props]
    (let [old-v (get old-props k)]
      (when (not= v old-v)
        (set-prop! el k v)))))

(declare patch-children!)

(defn- same-tag? [^js dom-node vnode]
  (and (= 1 (.-nodeType dom-node))
       (vector? vnode)
       (= (str/upper-case (name (first vnode)))
          (.-tagName dom-node))))

(defn- text-node? [^js node]
  (= 3 (.-nodeType node)))

(defn- patch-node!
  "Patch `old-node` at its position toward `new-vnode`. Returns the
   node that should sit at this position afterwards — either
   `old-node` (patched in place) or a fresh replacement. The caller
   uses the returned value to update its stored-children array.

   The replace branch anchors on `old-node.parentNode` rather than the
   passed-in `parent`: components like `x-popover[portal]` may have
   relocated the node, and we need to reach it wherever it actually
   sits so the replace lands in the right subtree."
  [^js parent ^js old-node new-vnode]
  (cond
    (and (string? new-vnode) (text-node? old-node))
    (do (when (not= new-vnode (.-nodeValue old-node))
          (set! (.-nodeValue old-node) new-vnode))
        old-node)

    (same-tag? old-node new-vnode)
    (let [[_ new-props new-children] (parse-vnode new-vnode)
          old-props (get-stored-props old-node)]
      (patch-props! old-node old-props new-props)
      (store-props! old-node new-props)
      (patch-children! old-node (flatten-children new-children))
      old-node)

    :else
    (let [target-parent (or (.-parentNode old-node) parent)
          new-node      (create-node new-vnode)]
      (.replaceChild target-parent new-node old-node)
      new-node)))

(defn- patch-children!
  "Reconcile `parent`'s children (as we last left them) toward
   `new-vnodes`. The 'old' side comes from our stored-children array
   on `parent`, not from live `.childNodes` — see `children-key` for
   why. Removals detach via each node's current `parentNode` so a
   node that a component has relocated (into a portal panel, say)
   still gets removed from the right place. Appends go onto
   `parent` directly; a component that portals its children on
   `connectedCallback` will see them there the next time it snapshots."
  [^js parent new-vnodes]
  (let [old-nodes (array-seq (get-stored-children parent))
        old-count (count old-nodes)
        new-count (count new-vnodes)
        min-count (min old-count new-count)
        kept      (vec (for [i (range min-count)]
                         (patch-node! parent
                                      (nth old-nodes i)
                                      (nth new-vnodes i))))
        appended  (when (> new-count old-count)
                    (vec (for [vnode (subvec new-vnodes old-count)]
                           (let [n (create-node vnode)]
                             (.appendChild parent n)
                             n))))]
    (when (> old-count new-count)
      (doseq [n (drop new-count old-nodes)]
        (when-let [p (.-parentNode n)]
          (.removeChild p n))))
    (store-children! parent (into kept appended))))

;;; ── Public API ────────────────────────────────────────────────────────────

(defn render!
  "Render `(view-fn)` into `container`. First call bootstraps the
   stored-children array (old side is empty → everything is appended
   fresh); subsequent calls diff against that array.

   The pre-extraction version branched on `container.childNodes.length`
   to distinguish first from subsequent render, and the `:empty`
   branch appended fresh children without storing them. That split is
   unnecessary with stored-children: patch-children! already handles
   an empty baseline as 'append everything'."
  [^js container view-fn]
  (patch-children! container (flatten-children [(view-fn)])))

(defn mount! [^js container view-fn state-atom]
  (render! container view-fn)
  (add-watch state-atom ::render
             (fn [_ _ _ _]
               (render! container view-fn))))
