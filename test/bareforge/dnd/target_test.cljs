(ns bareforge.dnd.target-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.dnd.target :as target]))

;; --- classify-position (moved from drag.cljs) ----------------------------

(def ^:private leaf  {:top 100 :height 40})
(def ^:private big   {:top 0   :height 200})

(deftest leaf-top-half-is-before
  (is (= :before (target/classify-position leaf 105 false)))
  (is (= :before (target/classify-position leaf 119 false))))

(deftest leaf-bottom-half-is-after
  (is (= :after (target/classify-position leaf 121 false)))
  (is (= :after (target/classify-position leaf 139 false))))

(deftest leaf-clamps-to-bounds
  (is (= :before (target/classify-position leaf  10 false)))
  (is (= :after  (target/classify-position leaf 999 false))))

(deftest container-top-quarter-is-before
  (is (= :before (target/classify-position big 10 true)))
  (is (= :before (target/classify-position big 49 true))))

(deftest container-middle-half-is-inside
  (is (= :inside (target/classify-position big 60  true)))
  (is (= :inside (target/classify-position big 100 true)))
  (is (= :inside (target/classify-position big 140 true))))

(deftest container-bottom-quarter-is-after
  (is (= :after (target/classify-position big 160 true)))
  (is (= :after (target/classify-position big 199 true))))

(deftest container-clamps-to-bounds
  (is (= :before (target/classify-position big -50 true)))
  (is (= :after  (target/classify-position big 999 true))))

(deftest zero-height-defaults-to-before-or-after-on-leaf
  ;; ratio is 0.5 with zero-height fallback, so leaves classify as :after.
  (is (#{:before :after} (target/classify-position {:top 0 :height 0} 0 false))))

;; --- classify-drop-target ------------------------------------------------

;; Each test below builds a minimal snapshot and pins exactly the shape
;; `classify-drop-target` returns. The snapshots are crafted by hand from
;; the branches of `resolve-target!` so behaviour is locked in before the
;; orchestrator is rewritten.

(deftest invalid-when-context-outside
  (is (= {:kind :invalid :valid? false}
         (target/classify-drop-target {:hovered-context :outside}))))

(deftest invalid-when-context-missing
  (testing "no :hovered-context key falls through to :invalid"
    (is (= {:kind :invalid :valid? false}
           (target/classify-drop-target {})))))

(deftest canvas-element-stale-id-classifies-invalid
  (testing "hovered inside canvas but the doc node was deleted between
            pointermove and snapshot — clears the highlight"
    (is (= {:kind :invalid :valid? false}
           (target/classify-drop-target
            {:hovered-context :canvas-element
             :hovered-id      "n_7"
             :hovered-rect    {:top 0 :height 40}
             :cursor-y        20
             :node            nil
             :container?      false
             :strips?         false
             :strip-host-id   nil})))))

(deftest canvas-element-before
  (let [snap {:hovered-context :canvas-element
              :hovered-id      "n_3"
              :hovered-el      ::fake-el
              :hovered-rect    {:top 100 :height 40}
              :cursor-y        105
              :node            {:tag "x-typography"}
              :container?      false
              :strips?         false
              :strip-host-id   nil}]
    (is (= {:kind :canvas-element
            :id "n_3" :hovered-el ::fake-el
            :position :before :valid? true}
           (target/classify-drop-target snap)))))

(deftest canvas-element-after
  (let [snap {:hovered-context :canvas-element
              :hovered-id      "n_3"
              :hovered-el      ::fake-el
              :hovered-rect    {:top 100 :height 40}
              :cursor-y        135
              :node            {:tag "x-typography"}
              :container?      false
              :strips?         false
              :strip-host-id   nil}]
    (is (= {:kind :canvas-element
            :id "n_3" :hovered-el ::fake-el
            :position :after :valid? true}
           (target/classify-drop-target snap)))))

(deftest canvas-element-inside-non-strip-container
  (testing "container that is `container?` true but `render-strips?` false
            (e.g. an x-button) must NOT trigger :needs-strips — drops
            land inside the container directly"
    (let [snap {:hovered-context :canvas-element
                :hovered-id      "n_btn"
                :hovered-el      ::fake-el
                :hovered-rect    {:top 0 :height 200}
                :cursor-y        100
                :node            {:tag "x-button"}
                :container?      true
                :strips?         false
                :strip-host-id   nil}]
      (is (= {:kind :canvas-element
              :id "n_btn" :hovered-el ::fake-el
              :position :inside :valid? true}
             (target/classify-drop-target snap))))))

(deftest needs-strips-when-strip-eligible-and-no-host
  (testing "multi-slot container hovered :inside, no strips mounted yet →
            orchestrator must mount and re-classify"
    (let [snap {:hovered-context :canvas-element
                :hovered-id      "n_nav"
                :hovered-el      ::fake-el
                :hovered-rect    {:top 0 :height 200}
                :cursor-y        100
                :node            {:tag "x-navbar"}
                :container?      true
                :strips?         true
                :strip-host-id   nil}]
      (is (= {:kind :needs-strips
              :tag "x-navbar" :id "n_nav" :hovered-el ::fake-el}
             (target/classify-drop-target snap))))))

(deftest needs-strips-when-strips-mounted-for-different-host
  (testing "strips are mounted for some OTHER node; hovering :inside this
            multi-slot container must still trigger :needs-strips so the
            orchestrator unmounts the stale strips and mounts fresh ones"
    (let [snap {:hovered-context :canvas-element
                :hovered-id      "n_nav-2"
                :hovered-el      ::fake-el
                :hovered-rect    {:top 0 :height 200}
                :cursor-y        100
                :node            {:tag "x-navbar"}
                :container?      true
                :strips?         true
                :strip-host-id   "n_nav-1"}]
      (is (= :needs-strips (:kind (target/classify-drop-target snap)))))))

(deftest no-needs-strips-when-strips-already-mounted-for-this-host
  (testing "idempotency under stationary cursor: cursor is still :inside
            the multi-slot container but strips are already mounted for
            it — classifier returns :canvas-element :inside so the
            orchestrator doesn't re-mount + flicker"
    (let [snap {:hovered-context :canvas-element
                :hovered-id      "n_nav"
                :hovered-el      ::fake-el
                :hovered-rect    {:top 0 :height 200}
                :cursor-y        100
                :node            {:tag "x-navbar"}
                :container?      true
                :strips?         true
                :strip-host-id   "n_nav"}]
      (is (= {:kind :canvas-element
              :id "n_nav" :hovered-el ::fake-el
              :position :inside :valid? true}
             (target/classify-drop-target snap))))))

(deftest slot-row-passes-through-slot-info
  (let [snap {:hovered-context :slot-row
              :hovered-id      "n_nav"
              :hovered-slot    "start"
              :hovered-el      ::strip-el
              :strip-host-id   "n_nav"}]
    (is (= {:kind :slot-row
            :slot-node "n_nav" :slot-name "start"
            :hovered-el ::strip-el
            :valid? true
            :hide-stale-strips? false}
           (target/classify-drop-target snap)))))

(deftest slot-row-with-stale-strips-flags-cleanup
  (testing "inspector slot row hovered while canvas strips for a different
            node are still mounted — result asks orchestrator to hide them"
    (let [snap {:hovered-context :slot-row
                :hovered-id      "n_other"
                :hovered-slot    "default"
                :hovered-el      ::inspector-row-el
                :strip-host-id   "n_nav"}]
      (is (true? (:hide-stale-strips? (target/classify-drop-target snap)))))))

(deftest slot-row-no-strips-mounted-flags-no-cleanup
  (testing "no strips currently mounted at all — :hide-stale-strips? is
            false so the orchestrator skips an unnecessary slot-strips/hide!"
    (let [snap {:hovered-context :slot-row
                :hovered-id      "n_x"
                :hovered-slot    "default"
                :hovered-el      ::inspector-row-el
                :strip-host-id   nil}]
      (is (false? (:hide-stale-strips? (target/classify-drop-target snap)))))))

(deftest layer-row-classifies-by-cursor-y
  (let [snap {:hovered-context :layer-row
              :hovered-id      "n_3"
              :hovered-el      ::layer-row-el
              :hovered-rect    {:top 100 :height 40}
              :cursor-y        135
              :node            {:tag "x-typography"}
              :container?      false}]
    (is (= {:kind :layer-row
            :id "n_3" :hovered-el ::layer-row-el
            :position :after :valid? true}
           (target/classify-drop-target snap)))))

(deftest layer-row-on-container-uses-25-50-25-split
  (testing "layers panel row for a container classifies positions the same
            way the canvas does — :inside is the middle 50%"
    (let [snap {:hovered-context :layer-row
                :hovered-id      "n_grid"
                :hovered-el      ::layer-row-el
                :hovered-rect    {:top 0 :height 200}
                :cursor-y        100
                :node            {:tag "x-grid"}
                :container?      true}]
      (is (= :inside (:position (target/classify-drop-target snap)))))))

(deftest layer-row-stale-id-still-classifies
  (testing "layer-row with no :node still produces a layer-row result —
            the orchestrator's existing apply step handles missing nodes;
            classifier doesn't second-guess the panel render"
    (let [snap {:hovered-context :layer-row
                :hovered-id      "n_gone"
                :hovered-el      ::layer-row-el
                :hovered-rect    {:top 0 :height 40}
                :cursor-y        20
                :node            nil
                :container?      false}]
      (is (= :layer-row (:kind (target/classify-drop-target snap)))))))
