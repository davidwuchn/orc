(ns ai.obney.workshop.sheet-service.core.runtime
  "Synchronous runtime for executing behavior trees.

   This module provides a blocking execute function that:
   1. Creates an isolated execution context (doesn't mutate sheet's blackboard)
   2. Runs the tree to completion
   3. Returns output values
   4. Always traces execution for event storage
   5. Optionally traces execution to Langfuse

   This is designed for calling behavior trees from command handlers,
   todo processors, or other code contexts where you need synchronous
   execution with inputs and outputs."
  (:require [ai.obney.workshop.sheet-service.core.read-models :as rm]
            [ai.obney.workshop.sheet-service.core.executor :as executor]
            [ai.obney.workshop.sheet-service.core.tracing :as tracing]
            [ai.obney.grain.event-store-v2.interface :as es :refer [->event]]
            [ai.obney.grain.time.interface :as time]
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
   Returns {:status :success/:failure :outputs {...} :error ... :duration-ms ...}"
  [node blackboard context]
  (let [executor-type (or (:executor node) :ai)
        provider (:dscloj-provider context)
        start-time (System/currentTimeMillis)
        result (case executor-type
                 :ai (if provider
                       (executor/execute-leaf node blackboard provider
                                              :context context)
                       (executor/execute-leaf-mock node blackboard))
                 :code (executor/execute-leaf node blackboard nil :context context)
                 :tool {:status :failure :error "Tool executor not yet implemented"}
                 ;; Default
                 (executor/execute-leaf-mock node blackboard))
        end-time (System/currentTimeMillis)]
    (assoc result
           :start-time start-time
           :end-time end-time
           :executor executor-type
           :model (:model node))))

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
  "Execute a sequence node synchronously.
   path-ctx already contains this node's ID and path set by execute-node-sync."
  [node nodes-by-id blackboard context path-ctx]
  (let [children-ids (:children-ids node)]
    (if (empty? children-ids)
      {:status :success :blackboard blackboard}
      (loop [remaining children-ids
             current-bb blackboard
             child-idx 0]
        (if (empty? remaining)
          {:status :success :blackboard current-bb}
          (let [child-id (first remaining)
                ;; Only update child-index, path is already set correctly
                child-path-ctx (assoc path-ctx :child-index child-idx)
                result (execute-node-sync child-id nodes-by-id current-bb context child-path-ctx)]
            (case (:status result)
              :success (recur (rest remaining) (:blackboard result) (inc child-idx))
              :failure result
              :running result
              result)))))))

(defn- execute-fallback-sync
  "Execute a fallback node synchronously.
   path-ctx already contains this node's ID and path set by execute-node-sync."
  [node nodes-by-id blackboard context path-ctx]
  (let [children-ids (:children-ids node)]
    (if (empty? children-ids)
      {:status :failure :blackboard blackboard}
      (loop [remaining children-ids
             current-bb blackboard
             child-idx 0]
        (if (empty? remaining)
          {:status :failure :blackboard current-bb}
          (let [child-id (first remaining)
                child-path-ctx (assoc path-ctx :child-index child-idx)
                result (execute-node-sync child-id nodes-by-id current-bb context child-path-ctx)]
            (case (:status result)
              :success result
              :failure (recur (rest remaining) (:blackboard result) (inc child-idx))
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
  "Execute a parallel node synchronously using futures for concurrency.
   path-ctx already contains this node's ID and path set by execute-node-sync."
  [node nodes-by-id blackboard context path-ctx]
  (let [children-ids (:children-ids node)
        success-policy (or (:success-policy node) :all)
        failure-policy (or (:failure-policy node) :any)]
    (if (empty? children-ids)
      {:status :success :blackboard blackboard}
      ;; Execute all children in parallel using futures
      (let [futures (map-indexed
                     (fn [child-idx child-id]
                       (let [child-path-ctx (assoc path-ctx :child-index child-idx)]
                         (future
                           (execute-node-sync child-id nodes-by-id blackboard context child-path-ctx))))
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
  "Execute a map-each node synchronously.
   path-ctx already contains this node's ID and path set by execute-node-sync."
  [node nodes-by-id blackboard context path-ctx]
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
            process-item (fn [item-idx item]
                           ;; Set item value in blackboard with incremented version
                           (let [item-bb (-> blackboard
                                             (assoc-in [item-key :value] item)
                                             (update-in [item-key :version] (fnil inc 0)))
                                 ;; Child path context includes iteration index
                                 child-path-ctx (assoc path-ctx :child-index item-idx)
                                 result (execute-node-sync child-id nodes-by-id item-bb context child-path-ctx)]
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
                                 ;; Merge written values into the item (if map) or just return written values
                                 (if (map? updated-item)
                                   (merge updated-item written-values)
                                   (if (seq written-values)
                                     written-values
                                     updated-item)))
                               ;; Failed - return error info only
                               {:__status :failure
                                :__error (:error result)})))
            ;; Process with concurrency
            results (if (= max-concurrency 1)
                      ;; Sequential
                      (vec (map-indexed process-item items))
                      ;; Parallel with partitioning
                      (let [indexed-items (map-indexed vector items)
                            batches (partition-all max-concurrency indexed-items)]
                        (vec (mapcat (fn [batch]
                                       (let [futures (mapv (fn [[idx item]] (future (process-item idx item))) batch)]
                                         (mapv deref futures)))
                                     batches))))
            new-bb (-> blackboard
                       (assoc-in [output-key :value] results)
                       (update-in [output-key :version] (fnil inc 0)))]
        {:status :success :blackboard new-bb}))))

(defn- execute-node-sync
  "Execute a single node synchronously.
   Returns {:status :success/:failure/:running :blackboard updated-bb :error ... :trace-data ...}

   path-ctx is a map with:
     :parent-id - UUID of parent node (nil for root)
     :path - Vector of node names from root to this node's parent
     :child-index - Index of this node among its siblings (nil for root)"
  [node-id nodes-by-id blackboard context path-ctx]
  (let [node (get nodes-by-id node-id)
        trace-ctx (:trace-ctx context)
        internal-trace (:internal-trace context)
        start-time (System/currentTimeMillis)
        started-at (time/now)
        ;; Extract path context
        parent-id (:parent-id path-ctx)
        current-path (or (:path path-ctx) [])
        child-index (:child-index path-ctx)
        ;; Parent observation ID for Langfuse nesting
        parent-obs-id (when parent-id (tracing/node-observation-id parent-id))]
    (if-not node
      {:status :failure :error (str "Node not found: " node-id) :blackboard blackboard}
      (let [node-name (:name node)
            node-type (:type node)
            ;; Path context for children
            child-path-ctx {:parent-id node-id
                            :path (conj current-path node-name)
                            :child-index 0}
            result
            (case node-type
              :leaf
              (let [inputs (gather-inputs node blackboard)
                    ;; Add inputs to blackboard temporarily for code executor
                    bb-with-inputs (reduce (fn [bb [k v]]
                                             (assoc-in bb [k :value] v))
                                           blackboard
                                           inputs)
                    leaf-result (execute-leaf-sync node bb-with-inputs context)]
                ;; Trace the leaf execution (Langfuse)
                (when trace-ctx
                  (tracing/trace-node! trace-ctx
                                       {:node-id node-id
                                        :node-name node-name
                                        :node-type :leaf
                                        :executor (:executor leaf-result)
                                        :model (:model leaf-result)
                                        :start-time (:start-time leaf-result)
                                        :end-time (:end-time leaf-result)
                                        :inputs inputs
                                        :outputs (:outputs leaf-result)
                                        :status (:status leaf-result)
                                        :error (:error leaf-result)
                                        :parent-observation-id parent-obs-id}))
                ;; Record to internal trace (always)
                (when internal-trace
                  (tracing/record-node-trace! internal-trace
                                              {:node-id node-id
                                               :node-name node-name
                                               :node-type :leaf
                                               :parent-id parent-id
                                               :path current-path
                                               :child-index child-index
                                               :status (:status leaf-result)
                                               :started-at started-at
                                               :completed-at (time/now)
                                               :duration-ms (- (:end-time leaf-result) (:start-time leaf-result))
                                               :inputs inputs
                                               :outputs (:outputs leaf-result)
                                               :error (:error leaf-result)}))
                (if (= :success (:status leaf-result))
                  ;; Write outputs to blackboard
                  (let [new-bb (reduce (fn [bb [k v]]
                                         (if (get bb k)
                                           (-> bb
                                               (assoc-in [k :value] v)
                                               (update-in [k :version] (fnil inc 0)))
                                           bb))
                                       blackboard
                                       (:outputs leaf-result))]
                    {:status :success :blackboard new-bb})
                  {:status :failure :error (:error leaf-result) :blackboard blackboard}))

              :condition
              (let [cond-result (execute-condition-sync node blackboard)
                    end-time (System/currentTimeMillis)]
                ;; Record to internal trace
                (when internal-trace
                  (tracing/record-node-trace! internal-trace
                                              {:node-id node-id
                                               :node-name node-name
                                               :node-type :condition
                                               :parent-id parent-id
                                               :path current-path
                                               :child-index child-index
                                               :status (:status cond-result)
                                               :started-at started-at
                                               :completed-at (time/now)
                                               :duration-ms (- end-time start-time)
                                               :inputs {:check (:check node)}
                                               :outputs {}
                                               :error (:error cond-result)}))
                (assoc cond-result :blackboard blackboard))

              :llm-condition
              (let [provider (:dscloj-provider context)
                    inputs (gather-inputs node blackboard)
                    llm-start-time (System/currentTimeMillis)
                    llm-result (if provider
                                 (executor/execute-llm-condition node blackboard provider
                                                                 :context context)
                                 {:status :failure :error "No DSCloj provider configured"})
                    llm-end-time (System/currentTimeMillis)
                    passed? (and (= :success (:status llm-result))
                                 (:result llm-result))
                    final-status (if passed? :success :failure)]
                ;; Trace the LLM condition execution (Langfuse)
                (when trace-ctx
                  (tracing/trace-node! trace-ctx
                                       {:node-id node-id
                                        :node-name node-name
                                        :node-type :llm-condition
                                        :executor :ai
                                        :model (:model node)
                                        :start-time llm-start-time
                                        :end-time llm-end-time
                                        :inputs inputs
                                        :outputs {:result (:result llm-result)}
                                        :status final-status
                                        :error (:error llm-result)
                                        :parent-observation-id parent-obs-id}))
                ;; Record to internal trace
                (when internal-trace
                  (tracing/record-node-trace! internal-trace
                                              {:node-id node-id
                                               :node-name node-name
                                               :node-type :llm-condition
                                               :parent-id parent-id
                                               :path current-path
                                               :child-index child-index
                                               :status final-status
                                               :started-at started-at
                                               :completed-at (time/now)
                                               :duration-ms (- llm-end-time llm-start-time)
                                               :inputs inputs
                                               :outputs {:result (:result llm-result)}
                                               :error (:error llm-result)}))
                {:status final-status
                 :blackboard blackboard
                 :error (:error llm-result)})

              :sequence
              (execute-sequence-sync node nodes-by-id blackboard context child-path-ctx)

              :fallback
              (execute-fallback-sync node nodes-by-id blackboard context child-path-ctx)

              :parallel
              (execute-parallel-sync node nodes-by-id blackboard context child-path-ctx)

              :map-each
              (execute-map-each-sync node nodes-by-id blackboard context child-path-ctx)

              ;; Unknown type
              {:status :failure :error (str "Unknown node type: " node-type) :blackboard blackboard})
            end-time (System/currentTimeMillis)]
        ;; Trace composite nodes (non-leaf, non-condition, non-llm-condition which trace themselves)
        (when (and trace-ctx (not (#{:leaf :condition :llm-condition} (:type node))))
          (tracing/trace-node! trace-ctx
                               {:node-id node-id
                                :node-name (:name node)
                                :node-type (:type node)
                                :executor nil
                                :model nil
                                :start-time start-time
                                :end-time end-time
                                :inputs nil
                                :outputs nil
                                :status (:status result)
                                :error (:error result)
                                :parent-observation-id parent-obs-id}))
        ;; Record composite nodes to internal trace (always)
        (when (and internal-trace (not (#{:leaf :condition :llm-condition} (:type node))))
          (tracing/record-node-trace! internal-trace
                                      {:node-id node-id
                                       :node-name node-name
                                       :node-type node-type
                                       :parent-id parent-id
                                       :path current-path
                                       :child-index child-index
                                       :status (:status result)
                                       :started-at started-at
                                       :completed-at (time/now)
                                       :duration-ms (- end-time start-time)
                                       :inputs nil
                                       :outputs nil
                                       :error (:error result)}))
        result))))

;; =============================================================================
;; Snapshot Parsing for Published Version Execution
;; =============================================================================

(defn- parse-snapshot-nodes
  "Parse a nested snapshot tree into a flat nodes-by-id map.
   Generates deterministic UUIDs based on tree position for consistent execution."
  [snapshot-node parent-id index path]
  (when snapshot-node
    (let [;; Generate a deterministic UUID based on path
          node-id (java.util.UUID/nameUUIDFromBytes
                   (.getBytes (str path) "UTF-8"))
          children (or (:children snapshot-node) [])
          node-record {:id node-id
                       :type (:type snapshot-node)
                       :name (:name snapshot-node)
                       :parent-id parent-id
                       :children-ids (mapv (fn [i _]
                                             (java.util.UUID/nameUUIDFromBytes
                                              (.getBytes (str path "/" i) "UTF-8")))
                                           (range (count children))
                                           children)
                       :status :idle
                       ;; Leaf fields
                       :instruction (:instruction snapshot-node)
                       :reads (or (:reads snapshot-node) [])
                       :writes (or (:writes snapshot-node) [])
                       :decorators []
                       :executor (:executor snapshot-node)
                       :model (:model snapshot-node)
                       :fn (:fn snapshot-node)
                       :tools (:tools snapshot-node)
                       :retry (:retry snapshot-node)
                       ;; Condition fields
                       :check (:check snapshot-node)
                       ;; Parallel fields
                       :success-policy (:success-policy snapshot-node)
                       :failure-policy (:failure-policy snapshot-node)
                       ;; Map-each fields
                       :source-key (:source-key snapshot-node)
                       :item-key (:item-key snapshot-node)
                       :output-key (:output-key snapshot-node)
                       :max-concurrency (:max-concurrency snapshot-node)}
          ;; Recursively parse children
          child-records (mapcat (fn [i child]
                                  (parse-snapshot-nodes child node-id i (str path "/" i)))
                                (range)
                                children)]
      (cons [node-id node-record] child-records))))

(defn- parse-snapshot-for-execution
  "Parse a version snapshot into the format expected by execute.
   Returns {:nodes-by-id {...} :root-id uuid :blackboard {...}}"
  [snapshot]
  (let [snapshot-nodes (:nodes snapshot)
        blackboard-schema (:blackboard-schema snapshot)
        ;; Parse nodes
        node-pairs (parse-snapshot-nodes snapshot-nodes nil 0 "root")
        nodes-by-id (into {} node-pairs)
        ;; Get root ID (first node)
        root-id (when (seq node-pairs) (first (first node-pairs)))
        ;; Build blackboard from schema (values will be set from inputs)
        blackboard (reduce (fn [bb [k schema]]
                            (assoc bb (name k) {:key (name k)
                                                :schema schema
                                                :value nil
                                                :version 0}))
                          {}
                          blackboard-schema)]
    {:nodes-by-id nodes-by-id
     :root-id root-id
     :blackboard blackboard}))

;; =============================================================================
;; Public API
;; =============================================================================

(defn- store-execution-trace!
  "Store an execution trace event in the event store."
  [event-store trace-data]
  (let [trace-id (:trace-id trace-data)
        sheet-id (:sheet-id trace-data)
        ;; Build body with only non-nil optional fields
        body (cond-> {:trace-id trace-id
                      :sheet-id sheet-id
                      :started-at (:started-at trace-data)
                      :completed-at (:completed-at trace-data)
                      :duration-ms (:duration-ms trace-data)
                      :status (:status trace-data)
                      :input-snapshot (:input-snapshot trace-data)
                      :output-snapshot (:output-snapshot trace-data)
                      :node-traces (:node-traces trace-data)}
               (:version-number trace-data) (assoc :version-number (:version-number trace-data))
               (:error trace-data) (assoc :error (:error trace-data)))
        event (->event {:type :sheet/execution-traced
                        :tags #{[:sheet sheet-id] [:trace trace-id]}
                        :body body})]
    (es/append event-store {:events [event]})))

(defn execute
  "Execute a sheet (behavior tree) with inputs and return outputs.

   This is a synchronous, blocking call that:
   1. Creates an isolated execution context (doesn't mutate sheet's blackboard)
   2. Runs the tree to completion
   3. Returns output values
   4. Always stores execution trace for analytics
   5. Optionally traces execution to Langfuse

   Args:
     context - Map with :event-store and optional :dscloj-provider
     sheet-id - UUID of the sheet to execute
     inputs - Map of blackboard key -> value for initial inputs

   Options:
     :timeout-ms - Max execution time in ms (default 300000 = 5 minutes)
     :trace? - Enable local trace collection in response (default false)
     :langfuse-client - Langfuse client for sending traces
     :use-version - Specific version number to execute (overrides execution-mode)
     :force-draft - Force draft execution even if execution-mode is :published
     :store-trace? - Store trace in event store (default true)

   Returns:
     {:status :success | :failure | :timeout
      :outputs {\"key\" value ...}
      :duration-ms 1234
      :trace-id uuid             ;; ID of stored trace
      :error string?             ;; Present if status is :failure
      :trace {...}               ;; Present if trace? is true
      :executed-version ...}     ;; Version number if published version was used"
  [context sheet-id inputs & {:keys [timeout-ms trace? langfuse-client use-version force-draft store-trace?]
                              :or {timeout-ms 300000 trace? false store-trace? true}}]
  (let [start-time (System/currentTimeMillis)
        started-at (time/now)
        event-store (:event-store context)

        ;; Use explicit langfuse-client, or fall back to one from context
        effective-langfuse-client (or langfuse-client (:langfuse-client context))

        ;; Create Langfuse trace context if enabled
        trace-ctx (when (or trace? effective-langfuse-client)
                    (if effective-langfuse-client
                      (tracing/create-trace-context effective-langfuse-client)
                      (tracing/create-local-trace)))

        ;; Always create internal trace for event storage
        internal-trace (tracing/create-internal-trace)

        ;; Load sheet structure
        sheet (rm/get-sheet event-store sheet-id)
        _ (when-not sheet
            (throw (ex-info "Sheet not found" {:sheet-id sheet-id})))

        ;; Determine execution source based on mode
        execution-mode (or (:execution-mode sheet) :draft)
        version-to-use (cond
                         ;; Explicit version requested
                         use-version use-version
                         ;; Force draft mode
                         force-draft nil
                         ;; Use published if mode is :published
                         (= :published execution-mode) (:published-version sheet)
                         ;; Default: use draft (nil means use current state)
                         :else nil)

        ;; Load version snapshot if using published version
        version-snapshot (when version-to-use
                          (rm/get-version event-store sheet-id version-to-use))

        ;; Parse execution source (from snapshot or current state)
        {:keys [nodes-by-id root-id blackboard-entries executed-version]}
        (if version-snapshot
          ;; Use published version snapshot
          (let [parsed (parse-snapshot-for-execution (:snapshot version-snapshot))]
            {:nodes-by-id (:nodes-by-id parsed)
             :root-id (:root-id parsed)
             :blackboard-entries (:blackboard parsed)
             :executed-version (:version-number version-snapshot)})
          ;; Use current draft state
          {:nodes-by-id (rm/get-nodes-by-id event-store sheet-id)
           :root-id (:root-node-id sheet)
           :blackboard-entries (rm/get-blackboard-by-key event-store sheet-id)
           :executed-version nil})

        _ (when-not root-id
            (throw (ex-info "Sheet has no root node" {:sheet-id sheet-id})))

        ;; Create isolated blackboard with inputs
        blackboard (reduce (fn [bb [key-name value]]
                            (if (get bb key-name)
                              (-> bb
                                  (assoc-in [key-name :value] value)
                                  (assoc-in [key-name :version] 1))
                              bb))
                          blackboard-entries
                          inputs)

        ;; Capture input snapshot for trace
        input-snapshot (reduce (fn [acc [k entry]]
                                 (assoc acc k (:value entry)))
                               {}
                               blackboard)

        ;; Add trace contexts to execution context
        exec-context (cond-> context
                       trace-ctx (assoc :trace-ctx trace-ctx)
                       internal-trace (assoc :internal-trace internal-trace))

        ;; Start Langfuse trace
        _ (when trace-ctx
            (tracing/start-trace! trace-ctx (:name sheet) inputs))

        ;; Root path context (no parent)
        root-path-ctx {:parent-id nil
                       :path []
                       :child-index nil}

        ;; Execute with timeout
        result-chan (chan 1)
        _ (future
            (try
              (let [result (execute-node-sync root-id nodes-by-id blackboard exec-context root-path-ctx)]
                (>!! result-chan result))
              (catch Exception e
                (>!! result-chan {:status :failure :error (.getMessage e) :blackboard blackboard}))))

        [result _] (alts!! [result-chan (timeout timeout-ms)])
        duration-ms (- (System/currentTimeMillis) start-time)
        completed-at (time/now)]

    (if result
      ;; Got a result
      (let [final-bb (:blackboard result)
            ;; Extract output values
            outputs (reduce (fn [acc [k entry]]
                              (assoc acc k (:value entry)))
                            {}
                            final-bb)
            trace-id (:trace-id internal-trace)]
        ;; End Langfuse trace
        (when trace-ctx
          (tracing/end-trace! trace-ctx (:status result) outputs duration-ms (:error result)))

        ;; Store execution trace event
        (when store-trace?
          (store-execution-trace! event-store
                                  {:trace-id trace-id
                                   :sheet-id sheet-id
                                   :version-number executed-version
                                   :started-at started-at
                                   :completed-at completed-at
                                   :duration-ms duration-ms
                                   :status (:status result)
                                   :input-snapshot input-snapshot
                                   :output-snapshot outputs
                                   :node-traces (tracing/get-node-traces internal-trace)
                                   :error (:error result)}))

        (cond-> {:status (:status result)
                 :outputs outputs
                 :duration-ms duration-ms
                 :trace-id trace-id}
          (:error result) (assoc :error (:error result))
          executed-version (assoc :executed-version executed-version)
          trace-ctx (assoc :trace {:trace-id (:trace-id trace-ctx)
                                   :events @(:events trace-ctx)})))
      ;; Timeout
      (let [trace-id (:trace-id internal-trace)]
        (when trace-ctx
          (tracing/end-trace! trace-ctx :timeout {} duration-ms "Execution timed out"))

        ;; Store timeout trace
        (when store-trace?
          (store-execution-trace! event-store
                                  {:trace-id trace-id
                                   :sheet-id sheet-id
                                   :version-number executed-version
                                   :started-at started-at
                                   :completed-at completed-at
                                   :duration-ms duration-ms
                                   :status :timeout
                                   :input-snapshot input-snapshot
                                   :output-snapshot {}
                                   :node-traces (tracing/get-node-traces internal-trace)
                                   :error "Execution timed out"}))

        {:status :timeout
         :error "Execution timed out"
         :duration-ms duration-ms
         :trace-id trace-id}))))
