(ns bareforge.ui.align-bar-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.ui.align-bar :as ab]))

(defn- seed
  "Seed an empty document with `nodes` — vector of `[tag layout]`
   pairs — and return `{:doc :ids}`. Free-placement layout numbers
   produce alignable nodes; CSS strings or missing fields produce
   non-alignable ones."
  [nodes]
  (loop [d   (m/empty-document)
         ids []
         pos 0
         remaining nodes]
    (if-let [[tag layout] (first remaining)]
      (let [{d' :doc id :id}
            (ops/insert-new d "root" "default" pos tag {:layout layout})]
        (recur d' (conj ids id) (inc pos) (rest remaining)))
      {:doc d :ids ids})))

(defn- state [doc selection]
  {:document  doc
   :selection (vec selection)})

(deftest bar-state-hidden-with-no-selection
  (let [{:keys [doc]} (seed [["x-typography" {:placement :free :x 0  :y 0  :w 50 :h 50}]
                             ["x-typography" {:placement :free :x 80 :y 0  :w 50 :h 50}]])]
    (is (false? (:visible? (ab/bar-state (state doc [])))))))

(deftest bar-state-hidden-with-single-selection
  (let [{:keys [doc ids]}
        (seed [["x-typography" {:placement :free :x 0 :y 0 :w 50 :h 50}]])]
    (is (false? (:visible? (ab/bar-state (state doc ids)))))))

(deftest bar-state-visible-with-two-free-placement
  (let [{:keys [doc ids]}
        (seed [["x-typography" {:placement :free :x 0  :y 0 :w 50 :h 50}]
               ["x-typography" {:placement :free :x 80 :y 0 :w 50 :h 50}]])]
    (is (true?  (:visible?        (ab/bar-state (state doc ids)))))
    (is (false? (:can-distribute? (ab/bar-state (state doc ids))))
        "two nodes is enough for align, not for distribute")))

(deftest bar-state-distribute-needs-three
  (let [{:keys [doc ids]}
        (seed [["x-typography" {:placement :free :x 0   :y 0 :w 50 :h 50}]
               ["x-typography" {:placement :free :x 100 :y 0 :w 50 :h 50}]
               ["x-typography" {:placement :free :x 200 :y 0 :w 50 :h 50}]])]
    (is (true? (:visible?        (ab/bar-state (state doc ids)))))
    (is (true? (:can-distribute? (ab/bar-state (state doc ids)))))))

(deftest bar-state-skips-non-numeric-layout
  (testing "A flow-placement sibling (CSS-string sized) doesn't count
            toward the 2-node threshold."
    (let [{:keys [doc ids]}
          (seed [["x-typography" {:placement :free :x 0 :y 0 :w 50 :h 50}]
                 ["x-typography" {:width "100px"}]])]
      (is (false? (:visible? (ab/bar-state (state doc ids))))))))

(deftest bar-state-strips-root-from-selection
  (testing "Root in the selection vector doesn't add to the count —
            root has no :layout :x and is never alignable anyway."
    (let [{:keys [doc ids]}
          (seed [["x-typography" {:placement :free :x 0 :y 0 :w 50 :h 50}]])]
      (is (false?
           (:visible? (ab/bar-state (state doc (cons "root" ids)))))))))

(deftest bar-state-deduplicates-selection
  (testing "Same id appearing twice (e.g. a template-clone whose id
            canonicalises to the source) counts once."
    (let [{:keys [doc ids]}
          (seed [["x-typography" {:placement :free :x 0  :y 0 :w 50 :h 50}]
                 ["x-typography" {:placement :free :x 80 :y 0 :w 50 :h 50}]])
          dup-sel (concat ids ids)]
      (is (true? (:visible? (ab/bar-state (state doc dup-sel))))
          "deduped to two distinct ids → still visible"))))
