(ns bareforge.doc.ops-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.spec.alpha :as s]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.doc.spec :as ds]))

(defn- empty-doc [] (m/empty-document))

(deftest empty-document-conforms-to-spec
  (is (s/valid? ::ds/document (empty-doc))))

(deftest insert-adds-child-and-returns-id
  (let [{:keys [doc id]} (ops/insert-new (empty-doc) "root" "default" 0 "x-button")]
    (is (string? id))
    (is (= id (get-in doc [:root :slots "default" 0 :id])))
    (is (= "x-button" (get-in doc [:root :slots "default" 0 :tag])))
    (is (= 1 (:next-id doc)))
    (is (s/valid? ::ds/document doc))))

(deftest insert-assigns-unique-ids
  (let [d0                (empty-doc)
        {d1 :doc id1 :id} (ops/insert-new d0 "root" "default" 0 "x-button")
        {d2 :doc id2 :id} (ops/insert-new d1 "root" "default" 1 "x-card")]
    (is (not= id1 id2))
    (is (= 2 (count (get-in d2 [:root :slots "default"]))))
    (is (= 2 (:next-id d2)))))

(deftest insert-at-index-middle
  (let [d0                    (empty-doc)
        {d1 :doc}              (ops/insert-new d0 "root" "default" 0 "x-a")
        {d2 :doc}              (ops/insert-new d1 "root" "default" 1 "x-b")
        {d3 :doc id-mid :id}   (ops/insert-new d2 "root" "default" 1 "x-mid")]
    (is (= ["x-a" "x-mid" "x-b"]
           (mapv :tag (get-in d3 [:root :slots "default"]))))
    (is (= id-mid (get-in d3 [:root :slots "default" 1 :id])))))

(deftest insert-missing-parent-throws
  (is (thrown? js/Error
               (ops/insert-new (empty-doc) "nope" "default" 0 "x-button"))))

(deftest remove-removes-node
  (let [d0 (empty-doc)
        {d1 :doc id :id} (ops/insert-new d0 "root" "default" 0 "x-button")
        d2 (ops/remove d1 id)]
    (is (empty? (get-in d2 [:root :slots "default"])))))

(deftest remove-root-throws
  (is (thrown? js/Error (ops/remove (empty-doc) "root"))))

(deftest remove-missing-throws
  (is (thrown? js/Error (ops/remove (empty-doc) "nope"))))

(deftest remove-many-removes-each-id
  (let [d0                    (empty-doc)
        {d1 :doc id-a :id}    (ops/insert-new d0 "root" "default" 0 "x-a")
        {d2 :doc id-b :id}    (ops/insert-new d1 "root" "default" 1 "x-b")
        {d3 :doc id-c :id}    (ops/insert-new d2 "root" "default" 2 "x-c")
        d4                    (ops/remove-many d3 [id-a id-c])]
    (is (= [id-b] (mapv :id (get-in d4 [:root :slots "default"])))
        "removed first and last; middle survives")))

(deftest remove-many-tolerates-missing-or-already-removed
  (let [d0                 (empty-doc)
        {d1 :doc id :id}   (ops/insert-new d0 "root" "default" 0 "x-button")
        ;; Removing a parent cascades — descendants in the same set
        ;; are no-ops on subsequent passes.
        {d2 :doc inner :id}(ops/insert-new d1 id "default" 0 "x-inner")
        d3                 (ops/remove-many d2 [id inner "ghost"])]
    (is (empty? (get-in d3 [:root :slots "default"])))))

(deftest remove-many-skips-root
  (let [d0 (empty-doc)
        d1 (ops/remove-many d0 ["root" "ghost"])]
    (is (= d0 d1)
        "root is silently skipped — no throw, document unchanged")))

(deftest remove-many-empty-coll-is-no-op
  (let [d0                  (empty-doc)
        {d1 :doc}           (ops/insert-new d0 "root" "default" 0 "x-button")]
    (is (= d1 (ops/remove-many d1 [])))
    (is (= d1 (ops/remove-many d1 nil)))))

(deftest move-within-same-slot
  (let [d0                     (empty-doc)
        {d1 :doc id-a :id}     (ops/insert-new d0 "root" "default" 0 "x-a")
        {d2 :doc id-b :id}     (ops/insert-new d1 "root" "default" 1 "x-b")
        {d3 :doc id-c :id}     (ops/insert-new d2 "root" "default" 2 "x-c")
        d4                     (ops/move d3 id-a "root" "default" 2)]
    (is (= [id-b id-c id-a]
           (mapv :id (get-in d4 [:root :slots "default"]))))))

(deftest move-into-own-subtree-throws
  (let [d0                         (empty-doc)
        {d1 :doc id-outer :id}     (ops/insert-new d0 "root" "default" 0 "x-card")
        {d2 :doc}                  (ops/insert-new d1 id-outer "default" 0 "x-inner")]
    (is (thrown? js/Error (ops/move d2 id-outer id-outer "default" 0)))))

(deftest set-and-unset-attr
  (let [d0 (empty-doc)
        {d1 :doc id :id} (ops/insert-new d0 "root" "default" 0 "x-button")
        d2 (ops/set-attr d1 id "variant" "primary")
        d3 (ops/unset-attr d2 id "variant")]
    (is (= "primary" (get-in d2 (conj (m/path-to d2 id) :attrs "variant"))))
    (is (nil? (get-in d3 (conj (m/path-to d3 id) :attrs "variant"))))))

(deftest set-prop-text-layout
  (let [d0 (empty-doc)
        {d1 :doc id :id} (ops/insert-new d0 "root" "default" 0 "x-button")
        d2 (-> d1
               (ops/set-prop id :disabled true)
               (ops/set-text id "Click me")
               (ops/set-layout id :placement :free))
        p  (m/path-to d2 id)]
    (is (true? (get-in d2 (conj p :props :disabled))))
    (is (= "Click me" (get-in d2 (conj p :text))))
    (is (= :free (get-in d2 (conj p :layout :placement))))))

(deftest set-attr-missing-node-throws
  (is (thrown? js/Error (ops/set-attr (empty-doc) "nope" "x" "y"))))

(deftest set-inner-html-sets-updates-and-clears
  (let [d0               (empty-doc)
        {d1 :doc id :id} (ops/insert-new d0 "root" "default" 0 "x-icon")
        d2               (ops/set-inner-html d1 id "<svg><circle/></svg>")
        d3               (ops/set-inner-html d2 id "<svg><rect/></svg>")
        d4               (ops/set-inner-html d3 id "")
        d5               (ops/set-inner-html d3 id nil)]
    (is (= "<svg><circle/></svg>" (get-in d2 (conj (m/path-to d2 id) :inner-html))))
    (is (= "<svg><rect/></svg>"   (get-in d3 (conj (m/path-to d3 id) :inner-html))))
    (is (nil? (get-in d4 (conj (m/path-to d4 id) :inner-html)))
        "blank string normalises to nil")
    (is (nil? (get-in d5 (conj (m/path-to d5 id) :inner-html))))
    (is (s/valid? ::ds/document d2))))

(deftest set-inner-html-missing-node-throws
  (is (thrown? js/Error (ops/set-inner-html (empty-doc) "nope" "<svg/>"))))

(deftest set-inner-html-strips-script-payload
  (let [d0               (empty-doc)
        {d1 :doc id :id} (ops/insert-new d0 "root" "default" 0 "x-icon")
        d2               (ops/set-inner-html d1 id
                                             "<svg><script>alert(1)</script><path d=\"M0\"/></svg>")
        stored           (get-in d2 (conj (m/path-to d2 id) :inner-html))]
    (is (not (re-find #"(?i)<script" stored))
        "set-inner-html sanitises on commit so the inspector can't ship XSS")
    (is (re-find #"<path" stored)
        "the legitimate svg payload survives")))

(deftest set-attr-rejects-javascript-href
  (let [d0               (empty-doc)
        {d1 :doc id :id} (ops/insert-new d0 "root" "default" 0 "x-link")
        d2               (ops/set-attr d1 id "href" "javascript:alert(1)")
        stored           (get-in d2 (conj (m/path-to d2 id) :attrs "href"))]
    (is (nil? stored)
        "set-attr drops unsafe URL schemes silently — the previous value is left intact")))

(deftest set-attr-passes-safe-href
  (let [d0               (empty-doc)
        {d1 :doc id :id} (ops/insert-new d0 "root" "default" 0 "x-link")
        d2               (ops/set-attr d1 id "href" "/page")]
    (is (= "/page" (get-in d2 (conj (m/path-to d2 id) :attrs "href"))))))

;; --- auto-id on named groups --------------------------------------------

(defn- node-at [doc id] (get-in doc (m/path-to doc id)))

(deftest set-name-inserts-locked-id-on-first-name
  (let [{d :doc id :id} (ops/insert-new (empty-doc) "root" "default" 0 "x-card")
        d' (ops/set-name d id "product")
        fs (:fields (node-at d' id))]
    (is (= "product" (:name (node-at d' id))))
    (is (= [{:name :id :type :number :default 0 :locked? true}]
           (vec fs)))
    (is (s/valid? ::ds/document d'))))

(deftest set-name-keeps-locked-id-head-when-renaming
  (let [{d :doc id :id} (ops/insert-new (empty-doc) "root" "default" 0 "x-card")
        d1 (ops/set-name d id "product")
        d2 (ops/add-field d1 id {:name :title :type :string :default ""})
        d3 (ops/set-name d2 id "item")
        fs (:fields (node-at d3 id))]
    (is (= "item" (:name (node-at d3 id))))
    (is (:locked? (first fs)))
    (is (= :id (:name (first fs))))
    (is (= :title (:name (second fs))))))

(deftest set-name-clearing-drops-locked-id
  (let [{d :doc id :id} (ops/insert-new (empty-doc) "root" "default" 0 "x-card")
        d1 (ops/set-name d id "product")
        d2 (ops/set-name d1 id nil)]
    (is (nil? (:name (node-at d2 id))))
    (is (nil? (:fields (node-at d2 id))))))

(deftest set-name-clearing-preserves-user-fields
  (let [{d :doc id :id} (ops/insert-new (empty-doc) "root" "default" 0 "x-card")
        d1 (ops/set-name d id "product")
        d2 (ops/add-field d1 id {:name :title :type :string :default ""})
        d3 (ops/set-name d2 id nil)
        fs (:fields (node-at d3 id))]
    (is (nil? (:name (node-at d3 id))))
    (is (= [:title] (mapv :name fs))
        "user fields survive unnaming; only the locked ::id is dropped")))

(deftest remove-field-refuses-locked
  (let [{d :doc id :id} (ops/insert-new (empty-doc) "root" "default" 0 "x-card")
        d1 (ops/set-name d id "product")]
    (is (thrown? js/Error (ops/remove-field d1 id 0))
        "cannot remove the locked ::id field at index 0")))

(deftest set-name-upgrades-legacy-unlocked-id
  ;; An older doc can have an :id field without :locked?. Renaming its
  ;; group must replace it with the locked version, not duplicate it.
  (let [{d :doc id :id} (ops/insert-new (empty-doc) "root" "default" 0 "x-card")
        d1 (-> d
               (ops/set-name id "product")
               ;; Simulate a legacy document shape: unlocked :id + other.
               (assoc-in (conj (m/path-to d id) :fields)
                         [{:name :id :type :number :default 0}
                          {:name :title :type :string :default ""}]))
        d2 (ops/set-name d1 id "item")
        fs (:fields (node-at d2 id))]
    (is (= 2 (count fs)) "no duplicate id field")
    (is (:locked? (first fs)) "id is locked after rename")
    (is (= :id (:name (first fs))))
    (is (= :title (:name (second fs))))))
