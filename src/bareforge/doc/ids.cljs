(ns bareforge.doc.ids)

(defn gen
  "Pure id generator. Takes a non-negative counter and returns
   [short-id next-counter]. Ids look like \"n_0\", \"n_1\", etc."
  [n]
  [(str "n_" n) (inc n)])
