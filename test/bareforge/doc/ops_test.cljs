(ns bareforge.doc.ops-test
  (:require [cljs.test :refer [deftest is testing]]
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

;; --- duplicate / duplicate-many -----------------------------------------

(deftest duplicate-inserts-clone-as-next-sibling
  (let [d0                 (empty-doc)
        {d1 :doc id :id}   (ops/insert-new d0 "root" "default" 0 "x-button")
        {d2 :doc new-id :id} (ops/duplicate d1 id)]
    (is (= [id new-id]
           (mapv :id (get-in d2 [:root :slots "default"])))
        "clone lands at index+1 as a sibling")
    (is (not= id new-id) "clone has a fresh id")
    (is (= "x-button" (get-in d2 [:root :slots "default" 1 :tag])))))

(deftest duplicate-clones-subtree-with-fresh-ids
  (let [d0                  (empty-doc)
        {d1 :doc id :id}    (ops/insert-new d0 "root" "default" 0 "x-card")
        {d2 :doc child :id} (ops/insert-new d1 id "default" 0 "x-button")
        {d3 :doc clone :id} (ops/duplicate d2 id)
        clone-node          (m/get-node d3 clone)
        clone-child         (first (get-in clone-node [:slots "default"]))]
    (is (not= clone child) "descendant ids are fresh too")
    (is (= "x-button" (:tag clone-child)) "clone preserves child tags")
    (is (= 4 (:next-id d3))
        ":next-id advances by the size of the cloned subtree")))

(deftest duplicate-root-throws
  (is (thrown? js/Error (ops/duplicate (empty-doc) "root"))))

(deftest duplicate-missing-throws
  (is (thrown? js/Error (ops/duplicate (empty-doc) "ghost"))))

(deftest duplicate-many-clones-each-in-input-order
  (let [d0                 (empty-doc)
        {d1 :doc id-a :id} (ops/insert-new d0 "root" "default" 0 "x-a")
        {d2 :doc id-b :id} (ops/insert-new d1 "root" "default" 1 "x-b")
        {doc' :doc new-ids :ids} (ops/duplicate-many d2 [id-a id-b])]
    (is (= 2 (count new-ids)))
    (is (every? string? new-ids))
    (is (= 4 (count (get-in doc' [:root :slots "default"])))
        "each duplicate landed alongside its original")
    (testing ":ids matches input order"
      (let [tags-by-id (into {} (map (juxt :id :tag))
                             (get-in doc' [:root :slots "default"]))]
        (is (= "x-a" (tags-by-id (first new-ids))))
        (is (= "x-b" (tags-by-id (second new-ids))))))))

(deftest duplicate-many-skips-missing
  (let [d0                 (empty-doc)
        {d1 :doc id :id}   (ops/insert-new d0 "root" "default" 0 "x-button")
        {ids :ids}         (ops/duplicate-many d1 [id "ghost" "root"])]
    (is (= 1 (count ids))
        "ghost and root are silently skipped")))

(deftest duplicate-many-empty-is-no-op
  (let [d0 (empty-doc)
        {doc' :doc ids :ids} (ops/duplicate-many d0 [])]
    (is (= d0 doc'))
    (is (= [] ids))))

;; --- wrap-many ----------------------------------------------------------

(deftest wrap-many-wraps-siblings-in-new-container
  (let [d0                 (empty-doc)
        {d1 :doc id-a :id} (ops/insert-new d0 "root" "default" 0 "x-a")
        {d2 :doc id-b :id} (ops/insert-new d1 "root" "default" 1 "x-b")
        {d3 :doc id-c :id} (ops/insert-new d2 "root" "default" 2 "x-c")
        {doc' :doc wrap :id} (ops/wrap-many d3 [id-a id-c] "x-container")
        root-children       (get-in doc' [:root :slots "default"])]
    (is (some? wrap))
    (testing "wrapper sits at the lowest-index sibling's old position"
      (is (= [wrap id-b] (mapv :id root-children))))
    (testing "wrapped children move into the wrapper's default slot"
      (let [wrap-node (m/get-node doc' wrap)]
        (is (= [id-a id-c] (mapv :id (get-in wrap-node [:slots "default"]))))
        (is (= "x-container" (:tag wrap-node)))))))

(deftest wrap-many-preserves-document-order-inside-wrapper
  (let [d0                 (empty-doc)
        {d1 :doc id-a :id} (ops/insert-new d0 "root" "default" 0 "x-a")
        {d2 :doc id-b :id} (ops/insert-new d1 "root" "default" 1 "x-b")
        {d3 :doc id-c :id} (ops/insert-new d2 "root" "default" 2 "x-c")
        ;; Selection arrived in reverse order; the op should still
        ;; reorder children by original index inside the wrapper.
        {doc' :doc wrap :id} (ops/wrap-many d3 [id-c id-a id-b] "x-container")]
    (is (= [id-a id-b id-c]
           (mapv :id (get-in (m/get-node doc' wrap) [:slots "default"]))))))

(deftest wrap-many-single-id-still-wraps
  (let [d0                 (empty-doc)
        {d1 :doc id :id}   (ops/insert-new d0 "root" "default" 0 "x-button")
        {doc' :doc wrap :id} (ops/wrap-many d1 [id] "x-container")]
    (is (some? wrap))
    (is (= [wrap] (mapv :id (get-in doc' [:root :slots "default"]))))
    (is (= [id]   (mapv :id (get-in (m/get-node doc' wrap) [:slots "default"]))))))

(deftest wrap-many-rejects-non-siblings
  (let [d0                  (empty-doc)
        {d1 :doc id-a :id}  (ops/insert-new d0 "root" "default" 0 "x-card")
        {d2 :doc id-inner :id} (ops/insert-new d1 id-a "default" 0 "x-button")
        ;; id-a is a direct child of root; id-inner is nested under id-a.
        ;; Different parents → wrap-many is a no-op.
        {doc' :doc wrap :id} (ops/wrap-many d2 [id-a id-inner] "x-container")]
    (is (nil? wrap))
    (is (= d2 doc') "document unchanged when ids don't share a parent")))

(deftest wrap-many-rejects-when-any-id-is-root
  (let [d0                 (empty-doc)
        {d1 :doc id :id}   (ops/insert-new d0 "root" "default" 0 "x-button")
        {doc' :doc wrap :id} (ops/wrap-many d1 [id "root"] "x-container")]
    (is (nil? wrap))
    (is (= d1 doc'))))

(deftest wrap-many-empty-is-no-op
  (let [d0 (empty-doc)
        {doc' :doc wrap :id} (ops/wrap-many d0 [] "x-container")]
    (is (nil? wrap))
    (is (= d0 doc'))))

;; --- set-attrs / set-props ----------------------------------------------

(deftest set-attrs-applies-each-pair
  (let [d0                (empty-doc)
        {d1 :doc id :id}  (ops/insert-new d0 "root" "default" 0 "x-button")
        d2                (ops/set-attrs d1 id {"variant" "primary"
                                                 "label"   "Click"})
        node              (m/get-node d2 id)]
    (is (= "primary" (get-in node [:attrs "variant"])))
    (is (= "Click"   (get-in node [:attrs "label"])))))

(deftest set-attrs-nil-value-unsets
  (let [d0               (empty-doc)
        {d1 :doc id :id} (ops/insert-new d0 "root" "default" 0 "x-button")
        d2               (ops/set-attr d1 id "variant" "primary")
        d3               (ops/set-attrs d2 id {"variant" nil
                                               "label"   "Click"})
        node             (m/get-node d3 id)]
    (is (nil? (get-in node [:attrs "variant"]))
        "nil values dispatch to unset-attr")
    (is (= "Click" (get-in node [:attrs "label"])))))

(deftest set-attrs-empty-and-nil-noop
  (let [d0               (empty-doc)
        {d1 :doc id :id} (ops/insert-new d0 "root" "default" 0 "x-button")]
    (is (= d1 (ops/set-attrs d1 id {})))
    (is (= d1 (ops/set-attrs d1 id nil)))))

(deftest set-props-applies-each-pair-keyword-keys
  (let [d0               (empty-doc)
        {d1 :doc id :id} (ops/insert-new d0 "root" "default" 0 "x-button")
        d2               (ops/set-props d1 id {:disabled true :loading false})
        node             (m/get-node d2 id)]
    (is (true?  (get-in node [:props :disabled])))
    (is (false? (get-in node [:props :loading])))))

(deftest set-props-nil-value-unsets
  (let [d0               (empty-doc)
        {d1 :doc id :id} (ops/insert-new d0 "root" "default" 0 "x-button")
        d2               (ops/set-prop d1 id :disabled true)
        d3               (ops/set-props d2 id {:disabled nil})]
    (is (nil? (get-in (m/get-node d3 id) [:props :disabled])))))

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
