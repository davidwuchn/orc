(ns ai.obney.workshop.sheet-service.core.runtime
  "Synchronous runtime for executing behavior trees.

   This module provides a blocking execute function that:
   1. Creates an isolated execution context (doesn't mutate sheet's blackboard)
   2. Runs the tree to completion
   3. Returns output values

   This is designed for calling behavior trees from command handlers,
   todo processors, or other code contexts where you need synchronous
   execution with inputs and outputs."
  (:require [ai.obney.workshop.sheet-service.core.read-models :as rm]
            [ai.obney.workshop.sheet-service.core.executor :as executor]
            [clojure.core.async :as async :refer [<!! >!! chan go timeout alts!!]]))

;; =============================================================================
;; Node Execution (Synchronous)
;; =============================================================================

(defn- gather-inputs
  "Gather input values from blackboard for a node's reads."
  [node blackboard]
  (reduce (fn [acc key-name]
            (if-let [entry (get blackboard key-name)]
              (assoc acc key-name (:value entry))
              acc))
          {}
          (:reads node)))

(defn- execute-leaf-sync
  "Execute a leaf node synchronously.
   Returns {:status :success/:failure :outputs {...} :error ...}"
  [node blackboard context]
  (let [executor-type (or (:executor node) :ai)
        provider (:dscloj-provider context)]
    (case executor-type
      :ai (if provider
            (executor/execute-leaf node blackboard provider
                                   :context context)
            (executor/execute-leaf-mock node blackboard))
      :code (executor/execute-leaf node blackboard nil :context context)
      :tool {:status :failure :error "Tool executor not yet implemented"}
      ;; Default
      (executor/execute-leaf-mock node blackboard))))

(defn- execute-condition-sync
  "Execute a condition node synchronously."
  [node blackboard]
  (let [check (:check node)
        on-fail (get check :on-fail :failure)]
    (if check
      (let [passed? (let [{:keys [key op value]} check
                          entry (get blackboard key)
                          bb-value (:value entry)]
                      (case op
                        :equals (= bb-value value)
                        :not-equals (not= bb-value value)
                        :gt (and (number? bb-value) (number? value) (> bb-value value))
                        :lt (and (number? bb-value) (number? value) (< bb-value value))
                        :gte (and (number? bb-value) (number? value) (>= bb-value value))
                        :lte (and (number? bb-value) (number? value) (<= bb-value value))
                        :contains (and (string? bb-value) (string? value) (.contains bb-value value))
                        :exists (some? bb-value)
                        :truthy (boolean bb-value)
                        false))]
        {:status (if passed? :success on-fail)})
      {:status :failure :error "No check defined"})))

(declare execute-node-sync)

(defn- execute-sequence-sync
  "Execute a sequence node synchronously."
  [node nodes-by-id blackboard context]
  (let [children-ids (:children-ids node)]
    (if (empty? children-ids)
      {:status :success :blackboard blackboard}
      (loop [remaining children-ids
             current-bb blackboard]
        (if (empty? remaining)
          {:status :success :blackboard current-bb}
          (let [child-id (first remaining)
                result (execute-node-sync child-id nodes-by-id current-bb context)]
            (case (:status result)
              :success (recur (rest remaining) (:blackboard result))
              :failure result
              :running result
              result)))))))

(defn- execute-fallback-sync
  "Execute a fallback node synchronously."
  [node nodes-by-id blackboard context]
  (let [children-ids (:children-ids node)]
    (if (empty? children-ids)
      {:status :failure :blackboard blackboard}
      (loop [remaining children-ids
             current-bb blackboard]
        (if (empty? remaining)
          {:status :failure :blackboard current-bb}
          (let [child-id (first remaining)
                result (execute-node-sync child-id nodes-by-id current-bb context)]
            (case (:status result)
              :success result
              :failure (recur (rest remaining) (:blackboard result))
              :running result
              result)))))))

(defn- merge-blackboard-values
  "Merge blackboard values from multiple results.
   For each key, takes the value with the highest version (most recent write)."
  [base-bb result-bbs]
  (reduce (fn [acc result-bb]
            (reduce-kv (fn [bb key entry]
                         (let [current-entry (get bb key)
                               current-version (or (:version current-entry) 0)
                               new-version (or (:version entry) 0)]
                           ;; Only update if the new version is higher
                           (if (> new-version current-version)
                             (assoc bb key entry)
                             bb)))
                       acc
                       result-bb))
          base-bb
          result-bbs))

(defn- execute-parallel-sync
  "Execute a parallel node synchronously using futures for concurrency."
  [node nodes-by-id blackboard context]
  (let [children-ids (:children-ids node)
        success-policy (or (:success-policy node) :all)
        failure-policy (or (:failure-policy node) :any)]
    (if (empty? children-ids)
      {:status :success :blackboard blackboard}
      ;; Execute all children in parallel using futures
      (let [futures (mapv (fn [child-id]
                            (future
                              (execute-node-sync child-id nodes-by-id blackboard context)))
                          children-ids)
            results (mapv deref futures)
            success-count (count (filter #(= :success (:status %)) results))
            failure-count (count (filter #(= :failure (:status %)) results))
            total (count results)
            ;; Merge blackboards by taking highest version for each key
            result-bbs (map :blackboard results)
            merged-bb (merge-blackboard-values blackboard result-bbs)
            ;; Evaluate policies
            final-status (cond
                           ;; Failure policy
                           (and (= failure-policy :any) (> failure-count 0))
                           :failure

                           (and (= failure-policy :all) (= failure-count total))
                           :failure

                           ;; Success policy
                           (and (= success-policy :all) (= success-count total))
                           :success

                           (and (= success-policy :any) (> success-count 0))
                           :success

                           (and (= success-policy :majority) (> success-count (/ total 2)))
                           :success

                           :else :failure)]
        {:status final-status :blackboard merged-bb}))))

(defn- execute-map-each-sync
  "Execute a map-each node synchronously."
  [node nodes-by-id blackboard context]
  (let [source-key (:source-key node)
        item-key (:item-key node)
        output-key (:output-key node)
        max-concurrency (or (:max-concurrency node) 1)
        children-ids (:children-ids node)
        child-id (first children-ids)
        source-list (get-in blackboard [source-key :value])]
    (cond
      (not (sequential? source-list))
      {:status :failure
       :error (str "Source key '" source-key "' is not a list")
       :blackboard blackboard}

      (empty? source-list)
      ;; Empty list - succeed with empty results
      (let [new-bb (assoc-in blackboard [output-key :value] [])]
        {:status :success :blackboard new-bb})

      (not child-id)
      ;; No child - succeed with original list
      (let [new-bb (assoc-in blackboard [output-key :value] (vec source-list))]
        {:status :success :blackboard new-bb})

      :else
      ;; Process items with concurrency
      (let [items (vec source-list)
            ;; Track initial versions to detect which keys were written during subtree
            initial-versions (reduce-kv (fn [acc k entry]
                                          (assoc acc k (or (:version entry) 0)))
                                        {}
                                        blackboard)
            process-item (fn [item]
                           ;; Set item value in blackboard with incremented version
                           (let [item-bb (-> blackboard
                                             (assoc-in [item-key :value] item)
                                             (update-in [item-key :version] (fnil inc 0)))
                                 result (execute-node-sync child-id nodes-by-id item-bb context)]
                             (if (= :success (:status result))
                               ;; Collect all values written during subtree execution
                               ;; and merge them into the item
                               (let [result-bb (:blackboard result)
                                     ;; Find keys that were written (version increased)
                                     written-values (reduce-kv
                                                     (fn [acc k entry]
                                                       (let [initial-v (get initial-versions k 0)
                                                             current-v (or (:version entry) 0)]
                                                         ;; Include if version increased and not the item-key itself
                                                         (if (and (> current-v initial-v)
                                                                  (not= k item-key)
                                                                  (not= k source-key)
                                                                  (not= k output-key))
                                                           (assoc acc (keyword k) (:value entry))
                                                           acc)))
                                                     {}
                                                     result-bb)
                                     ;; Get the updated item (may have been modified by subtree)
                                     updated-item (get-in result-bb [item-key :value])]
                                 ;; Merge written values into the item
                                 (if (map? updated-item)
                                   (merge updated-item written-values)
                                   (if (seq written-values)
                                     (merge {:__original updated-item} written-values)
                                     updated-item)))
                               ;; Failed - return original item with error marker
                               (assoc (if (map? item) item {:__original item})
                                      :__status :failure
                                      :__error (:error result)))))
            ;; Process with concurrency
            results (if (= max-concurrency 1)
                      ;; Sequential
                      (mapv process-item items)
                      ;; Parallel with partitioning
                      (let [batches (partition-all max-concurrency items)]
                        (vec (mapcat (fn [batch]
                                       (let [futures (mapv #(future (process-item %)) batch)]
                                         (mapv deref futures)))
                                     batches))))
            new-bb (-> blackboard
                       (assoc-in [output-key :value] results)
                       (update-in [output-key :version] (fnil inc 0)))]
        {:status :success :blackboard new-bb}))))

(defn- execute-node-sync
  "Execute a single node synchronously.
   Returns {:status :success/:failure/:running :blackboard updated-bb :error ...}"
  [node-id nodes-by-id blackboard context]
  (let [node (get nodes-by-id node-id)]
    (if-not node
      {:status :failure :error (str "Node not found: " node-id) :blackboard blackboard}
      (case (:type node)
        :leaf
        (let [inputs (gather-inputs node blackboard)
              ;; Add inputs to blackboard temporarily for code executor
              bb-with-inputs (reduce (fn [bb [k v]]
                                       (assoc-in bb [k :value] v))
                                     blackboard
                                     inputs)
              result (execute-leaf-sync node bb-with-inputs context)]
          (if (= :success (:status result))
            ;; Write outputs to blackboard
            (let [new-bb (reduce (fn [bb [k v]]
                                   (if (get bb k)
                                     (-> bb
                                         (assoc-in [k :value] v)
                                         (update-in [k :version] (fnil inc 0)))
                                     bb))
                                 blackboard
                                 (:outputs result))]
              {:status :success :blackboard new-bb})
            {:status :failure :error (:error result) :blackboard blackboard}))

        :condition
        (let [result (execute-condition-sync node blackboard)]
          (assoc result :blackboard blackboard))

        :sequence
        (execute-sequence-sync node nodes-by-id blackboard context)

        :fallback
        (execute-fallback-sync node nodes-by-id blackboard context)

        :parallel
        (execute-parallel-sync node nodes-by-id blackboard context)

        :map-each
        (execute-map-each-sync node nodes-by-id blackboard context)

        ;; Unknown type
        {:status :failure :error (str "Unknown node type: " (:type node)) :blackboard blackboard}))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn execute
  "Execute a sheet (behavior tree) with inputs and return outputs.

   This is a synchronous, blocking call that:
   1. Creates an isolated execution context (doesn't mutate sheet's blackboard)
   2. Runs the tree to completion
   3. Returns output values

   Args:
     context - Map with :event-store and optional :dscloj-provider
     sheet-id - UUID of the sheet to execute
     inputs - Map of blackboard key -> value for initial inputs

   Options:
     :timeout-ms - Max execution time in ms (default 300000 = 5 minutes)

   Returns:
     {:status :success | :failure | :timeout
      :outputs {\"key\" value ...}
      :duration-ms 1234
      :error string?}        ;; Present if status is :failure"
  [context sheet-id inputs & {:keys [timeout-ms] :or {timeout-ms 300000}}]
  (let [start-time (System/currentTimeMillis)
        event-store (:event-store context)

        ;; Load sheet structure
        sheet (rm/get-sheet event-store sheet-id)
        _ (when-not sheet
            (throw (ex-info "Sheet not found" {:sheet-id sheet-id})))

        root-id (:root-node-id sheet)
        _ (when-not root-id
            (throw (ex-info "Sheet has no root node" {:sheet-id sheet-id})))

        ;; Load all nodes
        nodes-by-id (rm/get-nodes-by-id event-store sheet-id)

        ;; Load blackboard schema (without values - we'll use inputs)
        blackboard-entries (rm/get-blackboard-by-key event-store sheet-id)

        ;; Create isolated blackboard with inputs
        blackboard (reduce (fn [bb [key-name value]]
                            (if (get bb key-name)
                              (-> bb
                                  (assoc-in [key-name :value] value)
                                  (assoc-in [key-name :version] 1))
                              bb))
                          blackboard-entries
                          inputs)

        ;; Execute with timeout
        result-chan (chan 1)
        _ (future
            (try
              (let [result (execute-node-sync root-id nodes-by-id blackboard context)]
                (>!! result-chan result))
              (catch Exception e
                (>!! result-chan {:status :failure :error (.getMessage e) :blackboard blackboard}))))

        [result _] (alts!! [result-chan (timeout timeout-ms)])
        duration-ms (- (System/currentTimeMillis) start-time)]

    (if result
      ;; Got a result
      (let [final-bb (:blackboard result)
            ;; Extract output values
            outputs (reduce (fn [acc [k entry]]
                              (assoc acc k (:value entry)))
                            {}
                            final-bb)]
        (cond-> {:status (:status result)
                 :outputs outputs
                 :duration-ms duration-ms}
          (:error result) (assoc :error (:error result))))
      ;; Timeout
      {:status :timeout
       :error "Execution timed out"
       :duration-ms duration-ms})))
