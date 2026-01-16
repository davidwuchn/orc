(ns store.runs.subs
  "Runs screen subscriptions - derived state for BT execution traces."
  (:require [re-frame.core :as rf]))

;; =============================================================================
;; List Subscriptions
;; =============================================================================

(rf/reg-sub
  ::runs-list
  (fn [db _]
    (get-in db [:runs :list] [])))

(rf/reg-sub
  ::runs-loading?
  (fn [db _]
    (get-in db [:runs :loading?] false)))

(rf/reg-sub
  ::runs-error
  (fn [db _]
    (get-in db [:runs :error])))

(rf/reg-sub
  ::runs-total
  (fn [db _]
    (get-in db [:runs :total] 0)))

;; =============================================================================
;; Selection Subscriptions
;; =============================================================================

(rf/reg-sub
  ::selected-trace-id
  (fn [db _]
    (get-in db [:runs :selected-trace-id])))

(rf/reg-sub
  ::selected-trace
  (fn [db _]
    (get-in db [:runs :selected-trace])))

(rf/reg-sub
  ::selected-trace-loading?
  (fn [db _]
    (get-in db [:runs :selected-trace-loading?] false)))

;; =============================================================================
;; Filter Subscriptions
;; =============================================================================

(rf/reg-sub
  ::status-filter
  (fn [db _]
    (get-in db [:runs :filters :status])))

;; =============================================================================
;; Node Detail Subscriptions (On-demand loading)
;; =============================================================================

(rf/reg-sub
  ::node-detail-loading?
  (fn [db _]
    (get-in db [:runs :node-detail :loading?] false)))

(rf/reg-sub
  ::node-detail-data
  (fn [db _]
    (get-in db [:runs :node-detail :data])))

(rf/reg-sub
  ::node-detail-error
  (fn [db _]
    (get-in db [:runs :node-detail :error])))

(rf/reg-sub
  ::selected-node-id
  (fn [db _]
    (get-in db [:runs :node-detail :node-id])))

;; =============================================================================
;; Derived Data: Flame Graph
;; =============================================================================

(defn- compute-depths
  "Compute depth for each node based on parent relationships.
   Uses trace-instance-id for unique instance lookups."
  [node-traces]
  ;; Build map by trace-instance-id (unique per execution)
  (let [by-instance-id (into {} (map (juxt :trace-instance-id identity) node-traces))]
    (loop [result {}
           to-process (map :trace-instance-id node-traces)]
      (if (empty? to-process)
        result
        (let [instance-id (first to-process)
              node (get by-instance-id instance-id)]
          (if-let [parent-instance-id (:parent-trace-instance-id node)]
            (if-let [parent-depth (get result parent-instance-id)]
              (recur (assoc result instance-id (inc parent-depth))
                     (rest to-process))
              ;; Parent not processed yet, defer this node
              (recur result (concat (rest to-process) [instance-id])))
            ;; Root node (no parent)
            (recur (assoc result instance-id 0)
                   (rest to-process))))))))

(defn- parse-timestamp
  "Parse a timestamp (string or inst) to milliseconds."
  [ts]
  (cond
    (nil? ts) nil
    (number? ts) ts
    (inst? ts) (inst-ms ts)
    (string? ts) (.getTime (js/Date. ts))
    :else nil))

(defn- ms-since
  "Calculate milliseconds between two instants/timestamps."
  [start end]
  (let [start-ms (parse-timestamp start)
        end-ms (parse-timestamp end)]
    (if (and start-ms end-ms)
      (- end-ms start-ms)
      0)))

(defn- tree-order-sort
  "Sort nodes in tree order: parents before children, siblings by start-ms.
   Uses trace-instance-id for proper parent-child linking."
  [nodes]
  (let [by-parent (group-by :parent-trace-instance-id nodes)
        walk (fn walk [parent-instance-id]
               (let [children (get by-parent parent-instance-id [])
                     sorted-children (sort-by :start-ms children)]
                 (mapcat (fn [child]
                           (cons child (walk (:trace-instance-id child))))
                         sorted-children)))]
    ;; Start from root nodes (parent-trace-instance-id = nil)
    (vec (walk nil))))

(defn transform-for-flame-graph
  "Transform node traces into flame graph compatible format."
  [trace]
  (when trace
    (let [trace-start (:started-at trace)
          node-traces (:node-traces trace)
          depth-map (compute-depths node-traces)
          transformed (mapv (fn [nt]
                              {:id (:node-id nt)
                               :trace-instance-id (:trace-instance-id nt)
                               :parent-trace-instance-id (:parent-trace-instance-id nt)
                               :name (:node-name nt)
                               :type (:node-type nt)
                               :status (:status nt)
                               :start-ms (ms-since trace-start (:started-at nt))
                               :end-ms (ms-since trace-start (:completed-at nt))
                               :duration (:duration-ms nt)
                               :depth (get depth-map (:trace-instance-id nt) 0)
                               :parent-id (:parent-id nt)
                               :inputs (:inputs nt)
                               :outputs (:outputs nt)
                               :error (:error nt)})
                            node-traces)]
      ;; Sort in tree order so children appear directly under parents
      (tree-order-sort transformed))))

(rf/reg-sub
  ::flame-graph-data
  :<- [::selected-trace]
  (fn [trace _]
    (transform-for-flame-graph trace)))

(rf/reg-sub
  ::max-depth
  :<- [::flame-graph-data]
  (fn [data _]
    (if (seq data)
      (apply max (map :depth data))
      0)))

;; =============================================================================
;; Derived Data: Blackboard Timeline
;; =============================================================================

(defn build-blackboard-timeline
  "Build timeline of blackboard state changes from trace."
  [trace]
  (when trace
    (let [initial-snapshot (:input-snapshot trace)
          trace-start (:started-at trace)
          ;; Sort node traces by completion time
          sorted-traces (->> (:node-traces trace)
                             (filter :completed-at)
                             (sort-by :completed-at))]
      ;; Reduce through, accumulating state and recording changes
      (:entries
       (reduce
        (fn [{:keys [snapshot entries]} nt]
          (let [outputs (:outputs nt {})
                changes (for [[k v] outputs
                              :let [old-v (get snapshot k)]]
                          {:key k :old-value old-v :new-value v})
                new-snapshot (merge snapshot outputs)]
            {:snapshot new-snapshot
             :entries (if (seq outputs)
                        (conj entries
                              {:timestamp (ms-since trace-start (:completed-at nt))
                               :node-name (:node-name nt)
                               :node-id (:node-id nt)
                               :status (:status nt)
                               :changes (vec changes)
                               :snapshot new-snapshot})
                        entries)}))
        {:snapshot initial-snapshot :entries []}
        sorted-traces)))))

(rf/reg-sub
  ::blackboard-timeline
  :<- [::selected-trace]
  (fn [trace _]
    (build-blackboard-timeline trace)))

;; =============================================================================
;; Status Helper
;; =============================================================================

(rf/reg-sub
  ::status-options
  (fn [_ _]
    [{:value nil :label "All"}
     {:value :success :label "Success"}
     {:value :failure :label "Failure"}
     {:value :timeout :label "Timeout"}]))
