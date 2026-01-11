(ns ai.obney.workshop.sheet-service.core.dependency-graph
  "Utilities for managing cell dependencies and detecting cycles.

   The dependency graph represents which cells depend on which other cells.
   Edges flow from source (provider) to target (consumer).

   Graph structure:
   {:nodes {cell-id #{}} ; Set of all cell IDs
    :edges #{{:from source-cell-id
              :to target-cell-id
              :input-name input-name}}})")

;; =============================================================================
;; Basic Graph Operations
;; =============================================================================

(defn get-direct-dependents
  "Get cells that directly depend on (read from) the given cell."
  [graph cell-id]
  (->> (:edges graph)
       (filter #(= (:from %) cell-id))
       (map :to)
       set))

(defn get-direct-dependencies
  "Get cells that the given cell directly depends on (reads from)."
  [graph cell-id]
  (->> (:edges graph)
       (filter #(= (:to %) cell-id))
       (map :from)
       set))

(defn get-edges-to
  "Get all edges pointing to the given cell."
  [graph cell-id]
  (filter #(= (:to %) cell-id) (:edges graph)))

(defn get-edges-from
  "Get all edges originating from the given cell."
  [graph cell-id]
  (filter #(= (:from %) cell-id) (:edges graph)))

;; =============================================================================
;; Transitive Closure
;; =============================================================================

(defn get-all-dependents
  "Get all cells downstream of the given cell (transitive closure).
   Does not include the starting cell."
  [graph cell-id]
  (loop [to-visit #{cell-id}
         visited #{}]
    (if (empty? to-visit)
      (disj visited cell-id)
      (let [current (first to-visit)
            dependents (get-direct-dependents graph current)
            new-dependents (clojure.set/difference dependents visited)]
        (recur (into (disj to-visit current) new-dependents)
               (conj visited current))))))

(defn get-all-dependencies
  "Get all cells upstream of the given cell (transitive closure).
   Does not include the starting cell."
  [graph cell-id]
  (loop [to-visit #{cell-id}
         visited #{}]
    (if (empty? to-visit)
      (disj visited cell-id)
      (let [current (first to-visit)
            dependencies (get-direct-dependencies graph current)
            new-dependencies (clojure.set/difference dependencies visited)]
        (recur (into (disj to-visit current) new-dependencies)
               (conj visited current))))))

;; =============================================================================
;; Cycle Detection
;; =============================================================================

(defn would-create-cycle?
  "Check if adding an edge from source-cell-id to target-cell-id would create a cycle.

   A cycle would be created if target-cell-id can already reach source-cell-id.
   In other words, if source is already a (direct or indirect) dependent of target."
  [graph source-cell-id target-cell-id]
  (let [dependents-of-target (get-all-dependents graph target-cell-id)]
    (contains? dependents-of-target source-cell-id)))

(defn find-cycles
  "Find all cells that are part of dependency cycles.
   Returns a set of cell IDs that are in cycles, or empty set if no cycles."
  [graph]
  (let [nodes (set (keys (:nodes graph)))
        ;; Tarjan's algorithm for finding strongly connected components
        index-counter (atom 0)
        stack (atom [])
        on-stack (atom #{})
        indices (atom {})
        lowlinks (atom {})
        sccs (atom [])]

    (letfn [(strongconnect [v]
              (swap! indices assoc v @index-counter)
              (swap! lowlinks assoc v @index-counter)
              (swap! index-counter inc)
              (swap! stack conj v)
              (swap! on-stack conj v)

              (doseq [w (get-direct-dependents graph v)]
                (cond
                  (not (contains? @indices w))
                  (do
                    (strongconnect w)
                    (swap! lowlinks assoc v (min (get @lowlinks v)
                                                  (get @lowlinks w))))

                  (contains? @on-stack w)
                  (swap! lowlinks assoc v (min (get @lowlinks v)
                                                (get @indices w)))))

              (when (= (get @lowlinks v) (get @indices v))
                (loop [scc []]
                  (let [w (peek @stack)]
                    (swap! stack pop)
                    (swap! on-stack disj w)
                    (if (= w v)
                      (swap! sccs conj (conj scc w))
                      (recur (conj scc w)))))))]

      (doseq [v nodes]
        (when-not (contains? @indices v)
          (strongconnect v))))

    ;; Return cells that are in SCCs with more than 1 member (actual cycles)
    (->> @sccs
         (filter #(> (count %) 1))
         (apply concat)
         set)))

(defn has-cycles?
  "Check if the graph has any cycles."
  [graph]
  (seq (find-cycles graph)))

;; =============================================================================
;; Topological Sort
;; =============================================================================

(defn topological-sort
  "Sort cells in dependency order (dependencies before dependents).
   Returns a vector of cell IDs, or nil if the graph has cycles."
  [graph]
  (let [nodes (set (keys (:nodes graph)))
        ;; Calculate in-degrees
        in-degree (atom (into {} (map #(vector % 0) nodes)))]

    ;; Count incoming edges for each node
    (doseq [edge (:edges graph)]
      (swap! in-degree update (:to edge) (fnil inc 0)))

    ;; Kahn's algorithm
    (loop [queue (vec (filter #(zero? (get @in-degree %)) nodes))
           result []]
      (if (empty? queue)
        (when (= (count result) (count nodes))
          result) ; nil if not all nodes processed (cycle exists)
        (let [current (first queue)
              dependents (get-direct-dependents graph current)]
          (doseq [dep dependents]
            (swap! in-degree update dep dec))
          (let [new-ready (filter #(zero? (get @in-degree %)) dependents)]
            (recur (vec (concat (rest queue) new-ready))
                   (conj result current))))))))

;; =============================================================================
;; Gate Cell Detection
;; =============================================================================

(defn is-gate-cell?
  "Check if a cell can act as a gate (has yes-no output type)."
  [cell]
  (when-let [signature (:signature cell)]
    (some #(= :yes-no (:type %)) (:outputs signature))))

(defn find-gate-cells
  "Find all cells that have yes-no outputs (potential gate cells)."
  [cells-map]
  (->> cells-map
       vals
       (filter is-gate-cell?)
       (map :id)
       set))

(defn find-cycle-gates
  "Find gate cells that are part of cycles.
   These cells can control cycle iteration."
  [graph cells-map]
  (let [cycle-cells (find-cycles graph)
        gate-cells (find-gate-cells cells-map)]
    (clojure.set/intersection cycle-cells gate-cells)))

;; =============================================================================
;; Execution Eligibility
;; =============================================================================

(defn all-inputs-bound?
  "Check if all inputs in a cell's signature are bound."
  [cell]
  (when-let [signature (:signature cell)]
    (let [required-inputs (set (map :name (:inputs signature)))
          bound-inputs (set (keys (:input-bindings cell)))]
      (clojure.set/subset? required-inputs bound-inputs))))

(defn is-eligible-for-execution?
  "Check if a cell is eligible to execute.
   A cell is eligible if:
   - It has a signature
   - All inputs are bound
   - It's not currently running"
  [cell]
  (and (:signature cell)
       (all-inputs-bound? cell)
       (not= :running (:execution-status cell))))

(defn get-eligible-dependents
  "Get all dependents of a cell that are eligible for execution."
  [graph cells-map source-cell-id]
  (let [dependents (get-direct-dependents graph source-cell-id)]
    (filter (fn [cell-id]
              (when-let [cell (get cells-map cell-id)]
                (is-eligible-for-execution? cell)))
            dependents)))
