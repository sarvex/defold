(ns integration.gui-clipping-test
  (:require [clojure.test :refer :all]
            [clojure.data :as data]
            [dynamo.graph :as g]
            [support.test-support :refer [with-clean-system tx-nodes]]
            [integration.test-util :as test-util]
            [editor.workspace :as workspace]
            [editor.defold-project :as project]
            [editor.gui :as gui]
            [editor.gl.pass :as pass]
            [editor.handler :as handler]
            [editor.types :as types]))

(defn- gui-node [scene id]
  (let [id->node (->> (get-in (g/node-value scene :node-outline) [:children 0])
                   (tree-seq (constantly true) :children)
                   (map :node-id)
                   (map (fn [node-id] [(g/node-value node-id :id) node-id]))
                   (into {}))]
    (id->node id)))

(defn- add-box! [project scene parent]
  (gui/add-gui-node! project scene (or parent (g/node-value scene :node-tree)) :type-box))

(defn- add-clipper!
  ([project scene parent]
    (add-clipper! project scene parent false false))
  ([project scene parent inverted?]
    (add-clipper! project scene parent inverted? false))
  ([project scene parent inverted? visible?]
    (let [parent (or parent (g/node-value scene :node-tree))
          node (add-box! project scene parent)]
      (g/set-property! node
                       :clipping-mode :clipping-mode-stencil
                       :clipping-visible visible?
                       :clipping-inverted inverted?)
      node)))

(defn- add-inv-clipper!
  ([project scene parent]
    (add-inv-clipper! project scene parent false))
  ([project scene parent visible?]
    (add-clipper! project scene parent true visible?)))

(defn- add-layers! [project scene layers]
  (let [parent (g/node-value scene :layers-node)]
    (doseq [layer layers]
      (gui/add-layer! project scene parent layer))))

(defn- set-layer! [node-id layer]
  (g/set-property! node-id :layer layer))

(defn- ->stencil-buffer []
  (vec (repeat 8 0)))

(defn- ->frame-buffer []
  [0 0 0])

(defn- render [scene-id shapes]
  (let [scene (g/node-value scene-id :scene)
        gpu {:sb (->stencil-buffer)
             :fb (->frame-buffer)}
        scenes (filter #(contains? shapes (:node-id %)) (tree-seq (constantly true) :children scene))]
    (-> (reduce (fn [gpu s]
                  (let [colors (shapes (:node-id s))
                        shape (reduce bit-or colors)
                        c (get-in s [:renderable :user-data :clipping-state])
                        ref (bit-and (:ref-val c) (:mask c))
                        test (mapv (fn [t p] (and t (= ref (bit-and p (:mask c)))))
                                   (map #(bit-test shape %) (reverse (range 8)))
                                   (:sb gpu))]
                    (cond-> gpu
                      (:clear c)
                      (assoc :sb (->stencil-buffer))

                      (reduce #(or %1 %2) test)
                      (->
                        (update :sb (partial mapv (fn [t v] (if t
                                                              (bit-or (bit-and (:ref-val c) (:write-mask c))
                                                                      (bit-and v (bit-not (:write-mask c))))
                                                              v))
                                             test))
                        (update :fb (fn [fb]
                                      (let [test (reduce (fn [r t] (bit-or (bit-shift-left r 1) (if t 1 0))) 0 test)]
                                        (mapv (fn [t fb p] (if t
                                                             (bit-or (bit-and test p)
                                                                     (bit-and (bit-not test) fb))
                                                             fb))
                                              (:color-mask c) fb colors))))))))
                gpu scenes)
      :fb)))

(defn- clipping-states [s]
  (into {} (map (fn [s] (let [ud (get-in s [:renderable :user-data])]
                          [(:node-id s) [(:clipping-state ud) (:clipping-child-state ud)]]))
                (tree-seq (constantly true) :children s))))

(defn- scene->clipping-states [scene-id]
  (-> (g/node-value scene-id :scene)
    clipping-states))

(defn- assert-clipping [scene-id states]
  (let [expected (into {} (map (fn [[nid [ref mask write-mask child-ref child-mask child-write-mask]]]
                                 [nid [{:ref-val ref :mask mask :write-mask write-mask :color-mask [false false false false]}
                                       {:ref-val child-ref :mask child-mask :write-mask child-write-mask :color-mask [true true true true]}]])
                               states))
        actual (-> (scene->clipping-states scene-id)
                 (select-keys (keys expected)))
        [exp act both] (data/diff expected actual)]
    (is (nil? exp))
    (is (nil? act))))

(defn- assert-ref-vals [scene-id ref-vals]
  (let [expected ref-vals
        actual (into {} (map (fn [[nid [s cs]]] [nid (:ref-val s)])
                             (-> (scene->clipping-states scene-id)
                               (select-keys (keys expected)))))
        [exp act both] (data/diff expected actual)]
    (is (nil? exp))
    (is (nil? act))))

(defn- scene-seq [scene]
  (tree-seq (constantly true) :children scene))

(defn- visual-seq [scene]
  (->> scene
    scene-seq
    (filter (fn [s] (let [wm (get-in s [:renderable :user-data :clipping-state :write-mask])]
                      (or (nil? wm) (= 0 wm)))))))

(defn- clipper-seq [scene]
  (->> scene
    scene-seq
    (filter (fn [s] (let [wm (get-in s [:renderable :user-data :clipping-state :write-mask])]
                      (and (some? wm) (> wm 0)))))))

(defn- seq->render-order [scene-seq]
  (into {} (map (fn [s] [(:node-id s) (get-in s [:renderable :index])]) scene-seq)))

(defn- assert-render-order [scene-id order]
  (let [actual (-> (g/node-value scene-id :scene)
                 visual-seq
                 seq->render-order
                 (select-keys order)
                 ((partial sort-by (fn [[nid order]] order)))
                 ((partial map first))
                 vec)]
    (is (= actual order))))

(defn- assert-clipping-order [scene-id clipper visual]
  (let [scene (g/node-value scene-id :scene)
        clipper-order (-> scene
                        clipper-seq
                        seq->render-order
                        (get clipper))
        visual-order (-> scene
                       visual-seq
                       seq->render-order
                       (get visual))]
    (is (< clipper-order visual-order))))

;; Test the clipping states of the following hierarchy:
;; - a (inv)
;;  - b
;; - c
(deftest minimal
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-inv-clipper! project scene nil)
          b (add-clipper! project scene a)
          c (add-clipper! project scene nil)]
      (assert-clipping scene {a [2r10000000 2r00000000 2r11111111 2r00000000 2r10000000 0x0]
                              b [2r00000001 2r10000000 2r11111111 2r00000001 2r10000011 0x0]
                              c [2r00000010 2r00000000 2r11111111 2r00000010 2r00000011 0x0]}))))

;; Test the clipping states of the following hierarchy:
;; - a
;;   - b
;;     - c
;;       - d
;;       - e
;; - f
;;   - g
;;     - h
;;   - i
;;   - j
;;   - k
;; - l
;;
;; Expected values are listed in the design doc: (***REMOVED***)/1mzeoLx4HNV4Fbl9aEgtsCNZ4jQDw112SehPggtQAiYE/edit#
(deftest simple-hierarchy
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-clipper! project scene nil)
          b (add-clipper! project scene a)
          c (add-clipper! project scene b)
          d (add-clipper! project scene c)
          e (add-clipper! project scene c)
          f (add-clipper! project scene nil)
          g (add-clipper! project scene f)
          h (add-clipper! project scene g)
          i (add-clipper! project scene f)
          j (add-clipper! project scene f)
          k (add-clipper! project scene f)
          l (add-clipper! project scene nil)]
      (assert-clipping scene {a [2r00000001 2r00000000 2r11111111 2r00000001 2r00000011 0x0]
                              b [2r00000101 2r00000011 2r11111111 2r00000101 2r00000111 0x0]
                              c [2r00001101 2r00000111 2r11111111 2r00001101 2r00001111 0x0]
                              d [2r00011101 2r00001111 2r11111111 2r00011101 2r00111111 0x0]
                              e [2r00101101 2r00001111 2r11111111 2r00101101 2r00111111 0x0]
                              f [2r00000010 2r00000000 2r11111111 2r00000010 2r00000011 0x0]
                              g [2r00000110 2r00000011 2r11111111 2r00000110 2r00011111 0x0]
                              h [2r00100110 2r00011111 2r11111111 2r00100110 2r00111111 0x0]
                              i [2r00001010 2r00000011 2r11111111 2r00001010 2r00011111 0x0]
                              j [2r00001110 2r00000011 2r11111111 2r00001110 2r00011111 0x0]
                              k [2r00010010 2r00000011 2r11111111 2r00010010 2r00011111 0x0]
                              l [2r00000011 2r00000000 2r11111111 2r00000011 2r00000011 0x0]}))))

;; Test the clipping states of the following hierarchy:
;; - a
;;   - b
;;   - c
;;   - d
;;   - e (inv)
;;   - f (inv)
;;   - g (inv)
;;     - h (inv)
;;
;; Expected values are listed in the design doc: (***REMOVED***)/1mzeoLx4HNV4Fbl9aEgtsCNZ4jQDw112SehPggtQAiYE/edit#
(deftest simple-inv-hierarchy
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-clipper! project scene nil)
          b (add-clipper! project scene a)
          c (add-clipper! project scene a)
          d (add-clipper! project scene a)
          e (add-inv-clipper! project scene a)
          f (add-inv-clipper! project scene a)
          g (add-inv-clipper! project scene a)
          h (add-inv-clipper! project scene g)]
      (assert-clipping scene {a [2r00000001 2r00000000 2r11111111 2r00000001 2r00000001 0x0]
                              b [2r00000011 2r00000001 2r11111111 2r00000011 2r00000111 0x0]
                              c [2r00000101 2r00000001 2r11111111 2r00000101 2r00000111 0x0]
                              d [2r00000111 2r00000001 2r11111111 2r00000111 2r00000111 0x0]
                              e [2r10000001 2r00000001 2r11111111 2r00000001 2r10000001 0x0]
                              f [2r01000001 2r00000001 2r11111111 2r00000001 2r01000001 0x0]
                              g [2r00100001 2r00000001 2r11111111 2r00000001 2r00100001 0x0]
                              h [2r00010001 2r00100001 2r11111111 2r00000001 2r00110001 0x0]}))))

;; The following hierarchy notes which bit indices are assigned to which nodes:
;; - a (inv, 7)
;;   - b (inv, 6)
;;     - c (0)
;;       - d (inv, 1)
;;     - e (inv, 5)
;; - f (inv, 4)
;;   - g (inv, 3)
;; - h (inv, 2)
(deftest ref-ids-and-bit-collision
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-inv-clipper! project scene nil)
          b (add-inv-clipper! project scene a)
          c (add-clipper! project scene b)
          d (add-inv-clipper! project scene c)
          e (add-inv-clipper! project scene b)
          f (add-inv-clipper! project scene nil)
          g (add-inv-clipper! project scene f)
          h (add-inv-clipper! project scene nil)]
      (assert-ref-vals scene {a 2r10000000
                              b 2r01000000
                              c 2r00000001
                              d 2r00000011
                              e 2r00100000
                              f 2r00010000
                              g 2r00001000
                              h 2r00000100}))))

;; Test that previous nodes do not corrupt an inverted hierarchy.
;;
;; -a
;;   - b
;; - c (inv)
;;   - d (inv)
;;
;; (b) must not interfere with the test for (d).
(deftest ref-ids-and-bit-collision
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-clipper! project scene nil)
          b (add-clipper! project scene a)
          c (add-inv-clipper! project scene nil)
          d (add-inv-clipper! project scene c)
          states (scene->clipping-states scene)
          prev (first (get states b))
          inv (second (get states d))]
      (is (= (bit-and (:ref-val inv) (:mask inv)) (bit-and (:ref-val prev) (:mask inv)))))))


;; STENCIL BUFFER TESTS

;; Hierarchy:
;;
;; - a
;;   - b
;;
;; Shapes:
;;
;; a [RRRRR   ]
;; b [   GGG  ]
(deftest render-non-inv-non-inv
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-clipper! project scene nil false true)
          b (add-clipper! project scene a false true)
          fb (render scene {a [2r11111000 0          0]
                            b [0          2r00011100 0]})]
      (is (= fb [2r11100000 2r00011000 0])))))

;; Hierarchy:
;;
;; - a
;;   - b (inv)
;;     - c (box)
;;
;; Shapes:
;;
;; a [RRRRR   ]
;; b [   GG   ]
;; c [  BB    ]
(deftest render-non-inv-inv
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-clipper! project scene nil false true)
          b (add-inv-clipper! project scene a true)
          c (add-box! project scene b)
          fb (render scene {a [2r11111000 0          0         ]
                            b [0          2r00011000 0         ]
                            c [0          0          2r00110000]})]
      (is (= fb [2r11000000 2r00011000 2r00100000])))))

;; Hierarchy:
;;
;; - a (inv)
;;   - b
;;     - c (box)
;;
;; Shapes:
;;
;; a [RRRRR   ]
;; b [   GGGG ]
;; c [      BB]
(deftest render-inv-non-inv
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-inv-clipper! project scene nil true)
          b (add-clipper! project scene a false true)
          c (add-box! project scene b)
          fb (render scene {a [2r11111000 0          0         ]
                            b [0          2r00011110 0         ]
                            c [0          0          2r00000011]})]
      (is (= fb [2r11111000 2r00000100 2r000000010])))))

;; Hierarchy:
;;
;; - a (inv)
;;   - b (inv)
;;     - c (box)
;;
;; Shapes:
;;
;; a [RRRRR   ]
;; b [   GGGG ]
;; c [      BB]
(deftest render-inv-inv
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-inv-clipper! project scene nil true)
          b (add-inv-clipper! project scene a true)
          c (add-box! project scene b)
          fb (render scene {a [2r11111000 0          0         ]
                            b [0          2r00011110 0         ]
                            c [0          0          2r00000011]})]
      (is (= fb [2r11111000 2r00000110 2r00000001])))))

;; Hierarchy:
;;
;; - a (inv)
;;   - b (inv)
;;     - c (box)
;;
;; Shapes:
;;
;; a [ RR     ]
;; b [     GG ]
;; c [  BBBB  ]
(deftest render-inv-separate
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-inv-clipper! project scene nil true)
          b (add-inv-clipper! project scene a true)
          c (add-box! project scene b)
          fb (render scene {a [2r01100000 0          0         ]
                            b [0          2r00000110 0         ]
                            c [0          0          2r00111100]})]
      (is (= fb [2r01100000 2r00000110 2r00011000])))))

;; Hierarchy:
;;
;; - a
;;   - box
;;   - b
;;     - box
;; - c (inv)
;;   - box
;;   - d (inv)
;;     - box
;;
;; Shapes:
;;
;; a [RRR     ]
;; b [RR      ]
;; c [  GG    ]
;; d [   BB   ]
;; boxes have corresponding colors, but cover all bits (255)
(deftest render-inv-consistency
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-clipper! project scene nil false false)
          a-box (add-box! project scene a)
          b (add-clipper! project scene a false false)
          b-box (add-box! project scene b)
          c (add-inv-clipper! project scene nil false)
          c-box (add-box! project scene c)
          d (add-inv-clipper! project scene c false)
          d-box (add-box! project scene d)
          fb (render scene {a     [2r11100000 0          0         ]
                            a-box [2r11111111 0          0         ]
                            b     [2r11000000 0          0         ]
                            b-box [2r11111111 0          0         ]
                            c     [0          2r00110000 0         ]
                            c-box [0          2r11111111 0         ]
                            d     [0          0          2r00011000]
                            d-box [0          0          2r11111111]})]
      (is (= fb [2r00100000 2r00001000 2r11000111]))
      (assert-clipping scene {a [2r00000001 2r00000000 2r11111111 2r00000001 2r00000001 0x0]
                              b [2r00000011 2r00000001 2r11111111 2r00000011 2r00000011 0x0]
                              c [2r10000000 2r00000000 2r11111111 2r00000000 2r10000000 0x0]
                              d [2r01000000 2r10000000 2r11111111 2r00000000 2r11000000 0x0]})
      (assert-render-order scene [a-box b-box c-box d-box]))))

;; RENDER ORDER TESTS

;; Render order with hierarchy and null layer:
;;
;; - a (layer1)
;;   - b
;;
;; Expected order: a, b (b inherits layer1)
(deftest render-order-inherit-layer
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-box! project scene nil)
          b (add-box! project scene a)]
      (add-layers! project scene ["layer1"])
      (set-layer! a "layer1")
      (assert-render-order scene [a b]))))

;; Render order with hierarchy and layers:
;;
;; - a (layer2)
;;   - b (layer1)
;;
;; Expected order: b, a
(deftest render-order-layers
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-box! project scene nil)
          b (add-box! project scene a)]
      (add-layers! project scene ["layer1" "layer2"])
      (set-layer! a "layer2")
      (set-layer! b "layer1")
      (assert-render-order scene [b a]))))

;; Render order for the following hierarchy:
;; - a (clipper)
;;   - b
;; - c
;;
;; Expected order: a, b, c
(deftest render-order-clipper-straight
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-clipper! project scene nil false true)
          b (add-box! project scene a)
          c (add-box! project scene nil)]
      (assert-render-order scene [a b c])
      (assert-clipping-order scene a a)
      (assert-clipping-order scene a b))))

;; Render order for the following hierarchy:
;; - a (clipper)
;;   - b (layer1)
;; - c
;;
;; Expected order: a, b, c
(deftest render-order-one-layer
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-clipper! project scene nil false true)
          b (add-box! project scene a)
          c (add-box! project scene nil)]
      (add-layers! project scene ["layer1"])
      (set-layer! b "layer1")
      (assert-render-order scene [a b c])
      (assert-clipping-order scene a a)
      (assert-clipping-order scene a b))))

;; Render order for the following hierarchy:
;; - a (clipper, layer1)
;;   - b
;; - c
;;
;; Expected order: a, b, c
(deftest render-order-one-clipper-layer
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-clipper! project scene nil false true)
          b (add-box! project scene a)
          c (add-box! project scene nil)]
      (add-layers! project scene ["layer1"])
      (set-layer! a "layer1")
      (assert-render-order scene [a b c])
      (assert-clipping-order scene a a)
      (assert-clipping-order scene a b))))

;; Render order for the following hierarchy:
;; - a (clipper, layer2)
;;   - b (layer1)
;; - c
;;
;; Expected order: b, a, c
(deftest render-order-both-layers
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-clipper! project scene nil false true)
          b (add-box! project scene a)
          c (add-box! project scene nil)]
      (add-layers! project scene ["layer1" "layer2"])
      (set-layer! a "layer2")
      (set-layer! b "layer1")
      (assert-render-order scene [b a c])
      (assert-clipping-order scene a a)
      (assert-clipping-order scene a b))))

;; Render order for the following hierarchy:
;; - c
;; - a (clipper, layer2)
;;   - b (layer1)
;;
;; Expected order: c, b, a
(deftest render-order-both-layers
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          c (add-box! project scene nil)
          a (add-clipper! project scene nil false true)
          b (add-box! project scene a)]
      (add-layers! project scene ["layer1" "layer2"])
      (set-layer! a "layer2")
      (set-layer! b "layer1")
      (assert-render-order scene [c b a])
      (assert-clipping-order scene a a)
      (assert-clipping-order scene a b))))

;; Render order for the following hierarchy:
;; - a (inv-clipper, layer2)
;;   - b (layer1)
;; - c
;;
;; Expected order: b, a, c
(deftest render-order-both-layers-inv-clipper
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-inv-clipper! project scene nil true)
          b (add-box! project scene a)
          c (add-box! project scene nil)]
      (add-layers! project scene ["layer1" "layer2"])
      (set-layer! a "layer2")
      (set-layer! b "layer1")
      (assert-render-order scene [b a c])
      (assert-clipping-order scene a a)
      (assert-clipping-order scene a b))))

;; Render order for the following hierarchy:
;; - z (clipper)
;;   - a (clipper, layer2)
;;     - b (layer1)
;;   - c
;;
;; Expected order: z, b, a, c
(deftest render-order-both-layers-sub
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          z (add-clipper! project scene nil false true)
          a (add-clipper! project scene z false true)
          b (add-box! project scene a)
          c (add-box! project scene z)]
      (add-layers! project scene ["layer1" "layer2"])
      (set-layer! a "layer2")
      (set-layer! b "layer1")
      (assert-render-order scene [z b a c])
      (assert-clipping-order scene z a)
      (assert-clipping-order scene z b)
      (assert-clipping-order scene z c)
      (assert-clipping-order scene a a)
      (assert-clipping-order scene a b))))

;; Render order for the following hierarchy:
;; - a (clipper, layer2)
;;   - b (clipper, layer4)
;;     - c (layer3)
;;   - d (layer1)
;; - e
;;
;; Expected order: c, b, d, a, e
(deftest render-order-complex
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-clipper! project scene nil false true)
          b (add-clipper! project scene a false true)
          c (add-box! project scene b)
          d (add-box! project scene a)
          e (add-box! project scene nil)]
      (add-layers! project scene ["layer1" "layer2" "layer3" "layer4"])
      (set-layer! a "layer2")
      (set-layer! b "layer4")
      (set-layer! c "layer3")
      (set-layer! d "layer1")
      (assert-render-order scene [c b d a e])
      (assert-clipping-order scene a d)
      (assert-clipping-order scene a e)
      (assert-clipping-order scene b d)
      (assert-clipping-order scene b c))))

;; Render order for the following hierarchy:
;; - a (layer2)
;;   - b (clipper, layer1)
;;     - c (layer2)
;;
;; Expected order: b, c, a
(deftest render-order-complex-2
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-box! project scene nil)
          b (add-clipper! project scene a false true)
          c (add-box! project scene b)]
      (add-layers! project scene ["layer1" "layer2"])
      (set-layer! a "layer2")
      (set-layer! b "layer1")
      (set-layer! c "layer2")
      (assert-render-order scene [b c a]))))

;; Render order for the following hierarchy:
;; - a
;;   - b
;;   - c (clipper)
;;   - d
;;
;; Expected order: a, b, c, d
(deftest render-order-non-clipper-siblings
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-box! project scene nil)
          b (add-box! project scene a)
          c (add-clipper! project scene a false true)
          d (add-box! project scene a)]
      (assert-render-order scene [a b c d]))))

;; Render order for the following hierarchy:
;; - a (clipper)
;;   - b
;;   - c (clipper)
;;   - d
;;
;; Expected order: a, b, c, d
(deftest render-order-non-clipper-siblings-under-clipper
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-clipper! project scene nil false true)
          b (add-box! project scene a)
          c (add-clipper! project scene a false true)
          d (add-box! project scene a)]
      (assert-render-order scene [a b c d]))))

;; Render order for the following hierarchy:
;; - a (clipper)
;;   - b (inv-clipper)
;;     - c (clipper)
;;   - d (inv-clipper)
;;
;; Expected order: a, b, c, d
(deftest render-order-inverted-siblings
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-clipper! project scene nil false true)
          b (add-inv-clipper! project scene a true)
          c (add-clipper! project scene a false true)
          d (add-inv-clipper! project scene a true)]
      (assert-render-order scene [a b c d]))))

;; Render order for the following hierarchy:
;; - a (clipper)
;;   - b (inv)
;;     - c (inv)
;;   - d (inv-clipper)
;;
;; Expected order: a, b, c, d
(deftest render-order-inverted-siblings-complex
  (with-clean-system
    (let [workspace (test-util/setup-workspace! world)
          project (test-util/setup-project! workspace)
          scene (test-util/resource-node project "/gui/empty.gui")
          a (add-clipper! project scene nil false true)
          b (add-inv-clipper! project scene a true)
          c (add-inv-clipper! project scene b true)
          d (add-inv-clipper! project scene a true)]
      (assert-render-order scene [a b c d]))))
