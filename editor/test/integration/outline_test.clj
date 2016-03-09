(ns integration.outline-test
  (:require [clojure.test :refer :all]
            [service.log :as log]
            [dynamo.graph :as g]
            [support.test-support :refer [with-clean-system]]
            [editor.defold-project :as project]
            [editor.outline :as outline]
            [integration.test-util :as test-util]))

(def ^:dynamic *project-path* test-util/project-path)

(defn- setup
  ([world] (setup world *project-path*))
  ([world project-path]
   (let [workspace       (test-util/setup-workspace! world project-path)
         project         (test-util/setup-project! workspace)]
     [workspace project])))

(defn- outline
  ([node]
    (outline node []))
  ([node path]
    (loop [outline (g/node-value node :node-outline)
           path path]
      (if-let [segment (first path)]
        (recur (get (vec (:children outline)) segment) (rest path))
        outline))))

(def ^:private ^:dynamic *clipboard* nil)
(def ^:private ^:dynamic *dragboard* nil)
(def ^:private ^:dynamic *drag-source-iterators* nil)

(defrecord TestItemIterator [root-node path]
  outline/ItemIterator
  (value [this] (outline root-node path))
  (parent [this] (when (not (empty? path))
                   (TestItemIterator. root-node (butlast path)))))

(defn- ->iterator [root-node path]
  (TestItemIterator. root-node path))

(defn- delete? [node path]
  (outline/delete? [(->iterator node path)]))

(defn- copy! [node path]
  (let [data (outline/copy [(->iterator node path)])]
    (alter-var-root #'*clipboard* (constantly data))))

(defn- cut? [node path]
  (outline/cut? [(->iterator node path)]))

(defn- cut! [node path]
  (let [data (outline/cut! [(->iterator node path)])]
    (alter-var-root #'*clipboard* (constantly data))))

(defn- paste!
  ([project node]
    (paste! project node []))
  ([project node path]
    (let [it (->iterator node path)]
      (assert (outline/paste? (project/graph project) it *clipboard*))
      (outline/paste! (project/graph project) it *clipboard* (partial project/select project)))))

(defn- copy-paste! [project node path]
  (copy! node path)
  (paste! project node (butlast path)))

(defn- drag? [node path]
  (outline/drag? (g/node-id->graph-id node) [(->iterator node path)]))

(defn- drag! [node path]
  (let [src-item-iterators [(->iterator node path)]
        data (outline/copy src-item-iterators)]
    (alter-var-root #'*dragboard* (constantly data))
    (alter-var-root #'*drag-source-iterators* (constantly src-item-iterators))))

(defn- drop!
  ([project node]
    (drop! project node []))
  ([project node path]
    (outline/drop! (project/graph project) *drag-source-iterators* (->iterator node path) *dragboard* (partial project/select project))))

(defn- drop?
  ([project node]
    (drop? project node []))
  ([project node path]
    (outline/drop? (project/graph project) *drag-source-iterators* (->iterator node path) *dragboard*)))

(defn- child-count
  ([node]
    (child-count node []))
  ([node path]
   (count (:children (outline node path)))))

(defn- outline-seq [root]
  (tree-seq :children :children (g/node-value root :node-outline)))

(deftest copy-paste-double-embed
  (with-clean-system
    (let [[workspace project] (setup world)
          root (test-util/resource-node project "/collection/embedded_embedded_sounds.collection")]
      ; 1 go instance
      (is (= 2 (child-count root)))
      (copy! root [0])
      (paste! project root)
      ; 2 go instances
      (is (= 3 (child-count root))))))

(deftest copy-paste-game-object
  (with-clean-system
    (let [[workspace project] (setup world)
          root (test-util/resource-node project "/logic/atlas_sprite.go")]
      ; 1 comp instance
      (is (= 1 (child-count root)))
      (copy! root [0])
      (paste! project root)
      ; 2 comp instances
      (is (= 2 (child-count root)))
      (is (contains? (outline root [1]) :icon))
      (cut! root [0])
      ; 1 comp instances
      (is (= 1 (child-count root))))))

(deftest copy-paste-collection
  (with-clean-system
    (let [[workspace project] (setup world)
          root (test-util/resource-node project "/logic/atlas_sprite.collection")]
      ; 1 go instance
      (is (= 1 (child-count root)))
      ; 1 sprite comp
      (is (= 1 (child-count root [0])))
      (copy! root [0])
      (paste! project root)
      ; 2 go instances
      (is (= 2 (child-count root)))
      ; 1 sprite comp
      (is (= 1 (child-count root [1])))
      (paste! project root [0])
      ; 1 sprite comp + 1 go instance
      (is (= 2 (child-count root [0])))
      ; sprite can be cut
      (is (cut? root [0 0]))
      (cut! root [0 0])
      ; 1 go instance
      (is (= 1 (child-count root [0]))))))

(deftest copy-paste-collection-instance
  (with-clean-system
    (let [[workspace project] (setup world)
          root (test-util/resource-node project "/collection/sub_props.collection")]
      ; 1 collection instance
      (is (= 1 (child-count root)))
      ; 1 go instance
      (is (= 1 (child-count root [0])))
      (copy! root [0])
      (paste! project root)
      (is (= 2 (child-count root)))
      (is (= 1 (child-count root [1])))
      (cut! root [0 0])
      (paste! project root)
      ; 2 collection instances + 1 go instances
      (is (= 3 (child-count root)))
      ; 0 go instances under coll instance
      (is (= 0 (child-count root [0])))
      (cut! root [2])
      (paste! project root [0])
      ; 2 collection instance
      (is (= 2 (child-count root)))
      ; 1 go instance
      (is (= 1 (child-count root [0]))))))

(deftest dnd-collection
  (with-clean-system
    (let [[workspace project] (setup world)
          root (test-util/resource-node project "/logic/atlas_sprite.collection")]
      (is (= 1 (child-count root)))
      (let [first-id (get (outline root [0]) :label)]
        (drag! root [0])
        (is (not (drop? project root)))
        (is (not (drop? project root [0])))
        (copy-paste! project root [0])
        (is (= 2 (child-count root)))
        (let [second-id (get (outline root [1]) :label)]
          (is (not= first-id second-id))
          (drag! root [1])
          (is (drop? project root [0]))
          (drop! project root [0])
          (is (= 1 (child-count root)))
          (is (= 2 (child-count root [0])))
          (is (= second-id (get (outline root [0 1]) :label)))
          (drag! root [0 1])
          (drop! project root)
          (is (= 2 (child-count root)))
          (is (= second-id (get (outline root [1]) :label))))))))

(defn- read-only? [root path]
  (and (not (delete? root path))
       (not (cut? root path))
       (not (drag? root path))))

(deftest read-only-items
  (with-clean-system
    (let [[workspace project] (setup world)
          root (test-util/resource-node project "/logic/main.gui")]
      (doseq [path [[] [0] [1] [2] [3]]]
        (is (read-only? root path))))))

(deftest dnd-gui
  (with-clean-system
    (let [[workspace project] (setup world)
          root (test-util/resource-node project "/logic/main.gui")]
      (let [first-id (get (outline root [0 1]) :label)]
        (drag! root [0 1])
        (drop! project root [0 0])
        (let [second-id (get (outline root [0 0 0]) :label)]
          (is (= first-id second-id))
          (drag! root [0 0 0])
          (drop! project root [0])
          (is (= second-id (get (outline root [0 1]) :label))))))))

(defn- prop [root path property]
  (let [p (g/node-value (:node-id (outline root path)) :_properties)]
    (get-in p [:properties property :value])))

(deftest copy-paste-gui-template
  (with-clean-system
    (let [[workspace project] (setup world)
          root (test-util/resource-node project "/gui/scene.gui")
          path [0 1]
          orig-sub-id (prop root (conj path 0) :id)]
      (is (= "sub_scene/sub_box" (:label (outline root [0 1 0]))))
      (copy-paste! project root path)
      (is (= orig-sub-id (prop root (conj path 0) :id)))
      (let [copy-path [0 2]
            copy-sub-id (prop root (conj copy-path 0) :id)]
        (is (not (nil? copy-sub-id)))
        (is (not= copy-sub-id orig-sub-id))
        (is (= "sub_scene/sub_box" (:label (outline root [0 1 0]))))
        (is (= "sub_scene1/sub_box" (:label (outline root [0 2 0])))))))
  (with-clean-system
    (let [[workspace project] (setup world)
          root (test-util/resource-node project "/gui/super_scene.gui")
          tmpl-path [0 0]]
      (g/transact (g/set-property (:node-id (outline root (conj tmpl-path 0))) :position [-100.0 0.0 0.0]))
      (copy-paste! project root tmpl-path)
      (let [p (g/node-value (:node-id (outline root [0 1])) :_properties)]
        (is (= -100.0 (get-in p [:properties :template :value :overrides "box" :position 0])))))))

(deftest copy-paste-gui-template-delete-repeat
  (with-clean-system
    (let [[workspace project] (setup world)
          root (test-util/resource-node project "/gui/scene.gui")
          path [0 1]
          orig-sub-id (prop root (conj path 0) :id)]
      (dotimes [i 5]
        (let [[new-tmpl] (g/tx-nodes-added (copy-paste! project root path))]
          (g/node-value new-tmpl :_properties)
          (g/transact (g/delete-node new-tmpl)))))))

(deftest dnd-gui-template
  (with-clean-system
    (let [[workspace project] (setup world)
          root (test-util/resource-node project "/gui/scene.gui")
          tmpl-path [0 1]
          new-pos [-100.0 0.0 0.0]
          super-root (test-util/resource-node project "/gui/super_scene.gui")
          super-tmpl-path [0 0]]
      (is (contains? (:overrides (prop super-root super-tmpl-path :template)) "sub_scene/sub_box"))
      (is (= "sub_scene" (get (outline root tmpl-path) :label)))
      (is (not (nil? (outline root (conj tmpl-path 0)))))
      (let [sub-id (:node-id (outline root (conj tmpl-path 0)))]
        (g/transact (g/set-property sub-id :position new-pos)))
      (drag! root tmpl-path)
      (drop! project root [0 0])
      (let [tmpl-path [0 0 1]]
        (is (= -100.0 (get-in (prop root tmpl-path :template) [:overrides "sub_box" :position 0])))
        (is (= "sub_scene" (get (outline root tmpl-path) :label)))
        (is (not (nil? (outline root (conj tmpl-path 0)))))
        (is (= new-pos (prop root (conj tmpl-path 0) :position))))
      (is (contains? (:overrides (prop super-root super-tmpl-path :template)) "sub_scene/sub_box")))))

(deftest gui-template-overrides
  (with-clean-system
    (let [[workspace project] (setup world)
          root (test-util/resource-node project "/gui/scene.gui")
          paths [[0 1] [0 1 0]]
          new-pos [-100.0 0.0 0.0]
          sub-box (:node-id (outline root [0 1 0]))]
      (g/transact (g/set-property sub-box :position new-pos))
      (doseq [path paths]
        (is (true? (:outline-overridden? (outline root path))))))))

(deftest read-only-gui-template-sub-items
  (with-clean-system
    (let [[workspace project] (setup world)
          root (test-util/resource-node project "/gui/scene.gui")
          sub-path [0 1 0]]
      (is (read-only? root sub-path)))))

(deftest outline-shows-missing-parts
  (with-clean-system
    (let [[workspace project] (log/without-logging (setup world "resources/missing_project"))]  ; no logging as purposely partially broken project
      (testing "Missing go file visible in collection outline"
        (let [root (test-util/resource-node project "/missing_go.collection")]
          (is (= 1 (child-count root)))
          (is (.startsWith (:label (outline root [0])) "non-existent"))))
      (testing "Missing sub collection visible in collection outline"
        (let [root (test-util/resource-node project "/missing_collection.collection")]
          (is (= 1 (child-count root)))
          (is (.startsWith  (:label (outline root [0])) "non-existent"))))
      (testing "Missing script visible in go outline"
        (let [root (test-util/resource-node project "/missing_component.go")]
          (is (= 1 (child-count root)))
          (is (.startsWith (:label (outline root [0])) "non-existent"))))
      (testing "Missing script visible in collection-go-outline"
        (let [root (test-util/resource-node project "/missing_go_component.collection")
              labels (map :label (outline-seq root))
              expected-prefixes ["Collection" "missing_component" "non-existent"]]
          (is (= 3 (count labels))) ; collection + go + script
          (is (every? true? (map #(.startsWith %1 %2) labels expected-prefixes))))))))

(deftest outline-shows-nil-parts
  (with-clean-system
    (let [[workspace project] (log/without-logging (setup world "resources/nil_project"))]  ; no logging as purposely partially broken project
      (testing "Nil go file visible in collection outline"
        (let [root (test-util/resource-node project "/nil_go.collection")]
          (is (= 1 (child-count root)))
          (is (.startsWith (:label (outline root [0])) "nil-go"))))
      (testing "Nil sub collection visible in collection outline"
        (let [root (test-util/resource-node project "/nil_collection.collection")]
          (is (= 1 (child-count root)))
          (is (.startsWith  (:label (outline root [0])) "nil-collection"))))
      (testing "Nil script visible in go outline"
        (let [root (test-util/resource-node project "/nil_component.go")]
          (is (= 1 (child-count root)))
          (is (.startsWith (:label (outline root [0])) "nil-component"))))
      (testing "Nil script visible in collection-go-outline"
        (let [root (test-util/resource-node project "/nil_go_component.collection")
              labels (map :label (outline-seq root))
              expected-prefixes ["Collection" "nil-go" "nil-component"]]
          (is (= 3 (count labels))) ; collection + go + script
          (is (every? true? (map #(.startsWith %1 %2) labels expected-prefixes))))))))
