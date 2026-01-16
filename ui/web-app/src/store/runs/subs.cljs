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
  "Compute depth for each node based on parent relationships."
  [node-traces]
  (let [by-id (into {} (map (juxt :node-id identity) node-traces))]
    (loop [result {}
           to-process (map :node-id node-traces)]
      (if (empty? to-process)
        result
        (let [node-id (first to-process)
              node (get by-id node-id)]
          (if-let [parent-id (:parent-id node)]
            (if-let [parent-depth (get result parent-id)]
              (recur (assoc result node-id (inc parent-depth))
                     (rest to-process))
              ;; Parent not processed yet, defer this node
              (recur result (concat (rest to-process) [node-id])))
            ;; Root node (no parent)
            (recur (assoc result node-id 0)
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

(defn transform-for-flame-graph
  "Transform node traces into flame graph compatible format."
  [trace]
  (when trace
    (let [trace-start (:started-at trace)
          node-traces (:node-traces trace)
          depth-map (compute-depths node-traces)]
      (mapv (fn [nt]
              {:id (:node-id nt)
               :name (:node-name nt)
               :type (:node-type nt)
               :status (:status nt)
               :start-ms (ms-since trace-start (:started-at nt))
               :end-ms (ms-since trace-start (:completed-at nt))
               :duration (:duration-ms nt)
               :depth (get depth-map (:node-id nt) 0)
               :parent-id (:parent-id nt)
               :inputs (:inputs nt)
               :outputs (:outputs nt)
               :error (:error nt)})
            node-traces))))

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
