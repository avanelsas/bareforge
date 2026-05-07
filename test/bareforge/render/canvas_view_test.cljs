(ns bareforge.render.canvas-view-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.render.canvas-view :as cv]))

(defn- near?
  "Test helper: numeric near-equality with a reasonable epsilon for
   floating-point arithmetic in the viewport math."
  [a b]
  (< (Math/abs (- a b)) 1e-6))

(deftest clamp-zoom-bounds
  (testing "zoom is clamped to [min-zoom, max-zoom]"
    (is (= cv/min-zoom (cv/clamp-zoom 0)))
    (is (= cv/min-zoom (cv/clamp-zoom -5)))
    (is (= cv/max-zoom (cv/clamp-zoom 100)))
    (is (= 1.0 (cv/clamp-zoom 1.0)))
    (is (= 0.5 (cv/clamp-zoom 0.5)))
    (is (= 2.0 (cv/clamp-zoom 2.0)))))

;; --- apply-zoom-at -------------------------------------------------------

(deftest apply-zoom-at-keeps-cursor-anchored
  (testing "After zooming at a cursor point, the content position
            previously rendered under the cursor is still rendered
            under the cursor."
    (doseq [[zoom new-zoom cx cy pan-x pan-y]
            [[1.0 2.0 100 100 0   0]
             [1.0 0.5 200 150 50  -30]
             [2.0 1.0 80  80  -40 -40]
             [0.5 4.0 320 240 0   0]]]
      (let [view  {:zoom zoom :pan-x pan-x :pan-y pan-y}
            ;; Content position currently under cursor.
            cont-x (/ (- cx pan-x) zoom)
            cont-y (/ (- cy pan-y) zoom)
            view'  (cv/apply-zoom-at view new-zoom cx cy)
            ;; Where that same content position is rendered after.
            new-screen-x (+ (:pan-x view') (* cont-x (:zoom view')))
            new-screen-y (+ (:pan-y view') (* cont-y (:zoom view')))]
        (is (near? cx new-screen-x)
            (str "x anchor for zoom=" zoom "→" new-zoom))
        (is (near? cy new-screen-y)
            (str "y anchor for zoom=" zoom "→" new-zoom))))))

(deftest apply-zoom-at-clamps-zoom
  (testing "Out-of-range targets pin at the limits without throwing"
    (let [view {:zoom 1.0 :pan-x 0 :pan-y 0}]
      (is (= cv/max-zoom (:zoom (cv/apply-zoom-at view 100 0 0))))
      (is (= cv/min-zoom (:zoom (cv/apply-zoom-at view 0   0 0)))))))

;; --- wheel-zoom-factor ---------------------------------------------------

(deftest wheel-zoom-factor-sign
  (testing "Negative deltaY (wheel up) zooms in (factor > 1);
            positive zooms out (factor < 1); zero is identity."
    (is (> (cv/wheel-zoom-factor -100) 1))
    (is (< (cv/wheel-zoom-factor 100)  1))
    (is (near? 1.0 (cv/wheel-zoom-factor 0)))))

(deftest wheel-zoom-factor-symmetric
  (testing "+d and -d give reciprocal factors so a zoom-in followed by
            an equal zoom-out lands back at the start."
    (let [d 80]
      (is (near? 1.0
                 (* (cv/wheel-zoom-factor d)
                    (cv/wheel-zoom-factor (- d))))))))

;; --- apply-wheel ---------------------------------------------------------

(deftest apply-wheel-pan-when-no-modifier
  (testing "Plain wheel moves pan opposite the wheel direction"
    (let [view {:zoom 1.0 :pan-x 100 :pan-y 50}
          out  (cv/apply-wheel view {:zoom?   false
                                     :cx      0 :cy 0
                                     :delta-x 30 :delta-y 40})]
      (is (= 1.0 (:zoom out))   "zoom unchanged on pan branch")
      (is (= 70  (:pan-x out))  "pan-x decremented by deltaX")
      (is (= 10  (:pan-y out))  "pan-y decremented by deltaY"))))

(deftest apply-wheel-zoom-when-modifier
  (testing "Cmd/Ctrl wheel zooms anchored at cursor; pan adjusts to
            keep the cursor's content point in place."
    (let [view  {:zoom 1.0 :pan-x 0 :pan-y 0}
          out   (cv/apply-wheel view {:zoom?   true
                                      :cx      100 :cy 100
                                      :delta-x 0   :delta-y -100})]
      (is (not= 1.0 (:zoom out))
          "zoom changes on the zoom branch (factor != 1 for non-zero deltaY)")
      (is (> (:zoom out) 1.0)
          "negative deltaY (wheel up) zooms in"))))

;; --- format-zoom-percent -------------------------------------------------

(deftest format-zoom-percent-rounds
  (is (= "100%" (cv/format-zoom-percent 1.0)))
  (is (= "50%"  (cv/format-zoom-percent 0.5)))
  (is (= "200%" (cv/format-zoom-percent 2.0)))
  (is (= "83%"  (cv/format-zoom-percent 0.834)))
  (is (= "25%"  (cv/format-zoom-percent cv/min-zoom)))
  (is (= "400%" (cv/format-zoom-percent cv/max-zoom))))
