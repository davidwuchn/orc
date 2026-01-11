(ns ai.obney.workshop.sheet-service.core.tree-layout
  "Tree layout algorithm for behavior tree visualization.

   Computes grid positions for each node where:
   - Row = depth level (root at row 0)
   - Columns are subdivided among siblings (fractional 0.0 to 1.0)

   Returns a vector of layout entries:
   [{:node-id uuid, :row int, :start-col double, :end-col double}]")

(defn compute-layout
  "Given a root node and a map of nodes-by-id, compute grid positions.

   Arguments:
   - root-node: The root node of the tree
   - nodes-by-id: Map of node-id -> node

   Returns:
   - Vector of {:node-id uuid :row int :start-col double :end-col double}"
  [root-node nodes-by-id]
  (when root-node
    (letfn [(layout-node [node row start-col end-col]
              (let [children-ids (:children-ids node)
                    children (keep #(get nodes-by-id %) children-ids)
                    child-count (count children)
                    col-width (if (pos? child-count)
                                (/ (- end-col start-col) child-count)
                                0)]
                (into [{:node-id (:id node)
                        :row row
                        :start-col (double start-col)
                        :end-col (double end-col)}]
                      (mapcat (fn [i child]
                                (let [child-start (+ start-col (* i col-width))
                                      child-end (+ child-start col-width)]
                                  (layout-node child (inc row) child-start child-end)))
                              (range)
                              children))))]
      (vec (layout-node root-node 0 0.0 1.0)))))

(defn compute-tree-depth
  "Compute the maximum depth of the tree.
   Root is at depth 0."
  [root-node nodes-by-id]
  (when root-node
    (letfn [(node-depth [node]
              (let [children (keep #(get nodes-by-id %) (:children-ids node))]
                (if (empty? children)
                  0
                  (inc (apply max (map node-depth children))))))]
      (node-depth root-node))))

(defn layout-to-grid
  "Convert fractional layout to grid coordinates.

   Arguments:
   - layout: Vector of layout entries
   - num-cols: Total number of columns in grid

   Returns:
   - Vector of {:node-id uuid :row int :col-start int :col-span int}"
  [layout num-cols]
  (mapv (fn [{:keys [node-id row start-col end-col]}]
          (let [col-start (Math/round (* start-col num-cols))
                col-end (Math/round (* end-col num-cols))
                col-span (max 1 (- col-end col-start))]
            {:node-id node-id
             :row row
             :col-start col-start
             :col-span col-span}))
        layout))
