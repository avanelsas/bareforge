(ns bareforge.doc.model)

(def default-layout
  {:placement :flow :x nil :y nil :w nil :h nil})

(defn make-node
  "Create a node with the given id and tag. Accepts optional overrides for
   :attrs, :props, :text, :inner-html, :slots, :layout. All fields default
   to empty or neutral values."
  ([id tag]
   (make-node id tag nil))
  ([id tag {:keys [attrs props text inner-html slots layout]}]
   {:id         id
    :tag        tag
    :attrs      (or attrs {})
    :props      (or props {})
    :text       text
    :inner-html inner-html
    :slots      (or slots {})
    :layout     (merge default-layout layout)}))

(defn empty-document
  "Initial empty document — root x-container with one default slot."
  []
  {:root    (make-node "root" "x-container"
                       {:attrs {"fluid" ""}
                        :slots {"default" []}})
   :canvas  {:width 1200 :content-col {:left 160 :right 1040}}
   :next-id 0})

(defn path-to
  "Return the path in `doc` that points at the node with `id`, or nil if
   no such node exists. The path can be passed to get-in / assoc-in /
   update-in."
  [doc id]
  (letfn [(walk [node prefix]
            (when (some? node)
              (if (= (:id node) id)
                prefix
                (some (fn [[slot-name children]]
                        (some (fn [[idx child]]
                                (walk child (conj prefix :slots slot-name idx)))
                              (map-indexed vector children)))
                      (:slots node)))))]
    (walk (:root doc) [:root])))

(defn get-node
  "Return the node with the given id, or nil."
  [doc id]
  (when-let [p (path-to doc id)]
    (get-in doc p)))

(defn slot-entries
  "Return `[[slot-name children] ...]` for a node with deterministic order:
   the `default` slot first (if present), then other slot names in
   alphabetical order. Callers that need stable iteration (layers view,
   serialization) should use this instead of `(:slots node)` directly —
   Clojure map order is only reliable for very small maps."
  [node]
  (let [slots   (:slots node)
        ks      (keys slots)
        default (filter #(= "default" %) ks)
        others  (sort (remove #(= "default" %) ks))]
    (map (fn [k] [k (get slots k)]) (concat default others))))

(defn- walk* [node]
  (cons node
        (mapcat walk* (mapcat second (slot-entries node)))))

(defn walk-nodes
  "Depth-first lazy seq of all nodes in the document."
  [doc]
  (walk* (:root doc)))

(defn parent-of
  "Return `{:parent-id :slot :index}` for the node with `id`, describing
   where it lives inside its parent. Returns nil for the root node or
   when the id is not found."
  [doc id]
  (let [p (path-to doc id)]
    (when (and p (> (count p) 1))
      (let [idx          (peek p)
            slot         (peek (pop p))
            parent-path  (vec (drop-last 3 p))
            parent-node  (get-in doc parent-path)]
        {:parent-id (:id parent-node)
         :slot      slot
         :index     idx}))))

(defn subtree-ids
  "Set of all ids in the subtree rooted at the node with the given id.
   Returns nil if the node doesn't exist."
  [doc id]
  (when-let [n (get-node doc id)]
    (into #{} (map :id) (walk* n))))
