(ns bareforge.export.registry-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.export.plugin :as plugin]
            [bareforge.export.registry :as registry]))

(deftest all-built-in-plugins-have-complete-manifests
  (doseq [p (registry/validated-plugins)]
    (testing (str "plugin " (:id p))
      (doseq [k plugin/valid-manifest-keys]
        (is (contains? p k)
            (str "plugin " (:id p) " missing required key " k))))))

(deftest registry-ships-four-built-in-plugins
  (let [ids (set (map :id (registry/validated-plugins)))]
    (is (= #{:html :bundle :cljs :vanilla-js} ids)
        "html / bundle / cljs + vanilla-js are the v1 built-in exporters")))

(deftest plugins-are-ordered-by-their-order-field
  (let [orders (mapv :order (registry/validated-plugins))]
    (is (= orders (sort orders))
        "validated-plugins returns plugins in ascending :order — the
         File menu relies on this rather than sorting at the UI")))

(deftest static-snapshot-plugins-are-not-interactive
  (is (false? (:interactive? (registry/plugin-by-id :html))))
  (is (false? (:interactive? (registry/plugin-by-id :bundle))))
  (is (true?  (:interactive? (registry/plugin-by-id :cljs)))))

(deftest plugin-by-id-returns-nil-for-missing
  (is (nil? (registry/plugin-by-id :react)))
  (is (nil? (registry/plugin-by-id :made-up))))
