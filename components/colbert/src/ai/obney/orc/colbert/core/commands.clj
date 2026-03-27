(ns ai.obney.orc.colbert.core.commands
  "Command handlers for ColBERT operations.

   All event emission for the colbert component is consolidated here.
   Commands validate inputs, delegate to operations/bridge for model
   operations, and emit events via the command processor return value."
  (:require [ai.obney.orc.colbert.core.bridge :as bridge]
            [ai.obney.orc.colbert.core.operations :as operations]
            [ai.obney.orc.colbert.core.read-models :as read-models]
            [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]
            [com.brunobonacci.mulog :as mu]))

;; =============================================================================
;; Index Commands
;; =============================================================================

(defcommand :colbert create-index
  "Create a ColBERT index from documents.

   Delegates to operations/create-index! for the bridge call,
   then emits the :colbert/index-created event with full source data
   for regeneration capability."
  [{:keys [event-store] :as ctx
    {:keys [collection document-ids document-metadatas index-name
            model-name split-documents? max-document-length]
     :as command} :command}]
  (try
    (let [result (operations/create-index! ctx
                   {:collection collection
                    :document-ids document-ids
                    :document-metadatas document-metadatas
                    :index-name index-name
                    :model-name model-name
                    :split-documents? split-documents?
                    :max-document-length max-document-length})]

      {:command-result/events
       [(->event {:type :colbert/index-created
                  :tags #{[:index (:index-id result)]}
                  :body {:index-id (:index-id result)
                         :index-name (:index-name result)
                         :index-path (:index-path result)
                         ;; Store full source data for regeneration
                         :documents (vec collection)
                         :document-ids (vec (:document-ids result))
                         :document-metadatas (when (:document-metadatas result)
                                               (vec (:document-metadatas result)))
                         ;; Counts
                         :document-count (:document-count result)
                         :passage-count (:num-passages result)
                         ;; Config for rebuild
                         :model-name (:model-name result)
                         :config (:config result)
                         :created-at (str (time/now))
                         :duration-ms (:duration-ms result)}})]
       :command/result {:index-id (:index-id result)
                        :index-path (:index-path result)}})

    (catch Exception e
      (mu/log ::create-index-failed :error (ex-message e))
      {::anom/category ::anom/fault
       ::anom/message (str "Failed to create index: " (ex-message e))})))

(defcommand :colbert delete-index
  "Mark an index as deleted."
  [{:keys [event-store]
    {:keys [index-id]} :command
    :as ctx}]
  (if-let [index (read-models/get-index ctx index-id)]
    (if (= :deleted (:status index))
      {::anom/category ::anom/conflict
       ::anom/message "Index already deleted"}
      {:command-result/events
       [(->event {:type :colbert/index-deleted
                  :tags #{[:index index-id]}
                  :body {:index-id index-id
                         :deleted-at (str (time/now))}})]})
    {::anom/category ::anom/not-found
     ::anom/message "Index not found"}))

(defcommand :colbert add-to-index
  "Add documents to an existing index."
  [{:keys [event-store]
    {:keys [index-id documents document-ids document-metadatas]} :command
    :as ctx}]
  (if-let [index (read-models/get-index ctx index-id)]
    (if (= :deleted (:status index))
      {::anom/category ::anom/conflict
       ::anom/message "Cannot add to deleted index"}
      (let [alias (str index-id)
            document-ids (or document-ids
                             (mapv #(str (random-uuid)) (range (count documents))))]
        (try
          ;; Ensure model is loaded
          (bridge/load-model! alias :index-path (:index-path index))
          (bridge/add-to-index! alias
            {:collection documents
             :document-ids document-ids
             :document-metadatas document-metadatas})

          {:command-result/events
           [(->event {:type :colbert/index-updated
                      :tags #{[:index index-id]}
                      :body {:index-id index-id
                             :update-type :add
                             :documents-added (vec documents)
                             :document-ids-added (vec document-ids)
                             :document-metadatas-added (when document-metadatas
                                                         (vec document-metadatas))
                             :updated-at (str (time/now))}})]
           :command/result {:documents-added (count documents)}}

          (catch Exception e
            {::anom/category ::anom/fault
             ::anom/message (str "Failed to add to index: " (ex-message e))}))))
    {::anom/category ::anom/not-found
     ::anom/message "Index not found"}))

(defcommand :colbert remove-from-index
  "Remove documents from an index by ID."
  [{:keys [event-store]
    {:keys [index-id document-ids]} :command
    :as ctx}]
  (if-let [index (read-models/get-index ctx index-id)]
    (if (= :deleted (:status index))
      {::anom/category ::anom/conflict
       ::anom/message "Cannot remove from deleted index"}
      (let [alias (str index-id)]
        (try
          ;; Ensure model is loaded
          (bridge/load-model! alias :index-path (:index-path index))
          (bridge/delete-from-index! alias
            {:document-ids document-ids})

          {:command-result/events
           [(->event {:type :colbert/index-updated
                      :tags #{[:index index-id]}
                      :body {:index-id index-id
                             :update-type :remove
                             :document-ids-removed (vec document-ids)
                             :updated-at (str (time/now))}})]
           :command/result {:documents-removed (count document-ids)}}

          (catch Exception e
            {::anom/category ::anom/fault
             ::anom/message (str "Failed to remove from index: " (ex-message e))}))))
    {::anom/category ::anom/not-found
     ::anom/message "Index not found"}))

;; =============================================================================
;; Search Commands (with audit events)
;; =============================================================================

(defcommand :colbert search
  "Search an index and emit audit event.

   Delegates to operations/search for the bridge call, then emits
   a :colbert/search-performed audit event."
  [{:keys [event-store]
    {:keys [index-id query k]} :command
    :as ctx}]
  (let [k (or k 10)
        search-id (random-uuid)
        start-time (System/currentTimeMillis)]
    (try
      (let [results (operations/search ctx {:query query :index-id index-id :k k})
            latency-ms (- (System/currentTimeMillis) start-time)
            top-score (when (seq results) (:score (first results)))]

        {:command-result/events
         [(->event {:type :colbert/search-performed
                    :tags #{[:index index-id] [:search search-id]}
                    :body {:search-id search-id
                           :index-id index-id
                           :query query
                           :k k
                           :result-count (count results)
                           :latency-ms latency-ms
                           :top-score top-score
                           :performed-at (str (time/now))}})]
         :command/result {:results results
                          :search-id search-id}})

      (catch clojure.lang.ExceptionInfo e
        ;; Re-raise not-found / deleted as anomalies
        (let [data (ex-data e)]
          (if (:index-id data)
            {::anom/category ::anom/not-found
             ::anom/message (ex-message e)}
            {::anom/category ::anom/fault
             ::anom/message (str "Search failed: " (ex-message e))})))
      (catch Exception e
        {::anom/category ::anom/fault
         ::anom/message (str "Search failed: " (ex-message e))}))))

(defcommand :colbert rerank
  "Rerank documents and emit audit event.

   Delegates to operations/rerank for the bridge call, then emits
   a :colbert/rerank-performed audit event."
  [{{:keys [query documents k]} :command :as ctx}]
  (let [rerank-id (random-uuid)
        start-time (System/currentTimeMillis)]
    (try
      (let [results (operations/rerank ctx {:query query :documents documents :k k})
            latency-ms (- (System/currentTimeMillis) start-time)
            top-score (when (seq results) (:score (first results)))]

        {:command-result/events
         [(->event {:type :colbert/rerank-performed
                    :tags #{[:rerank rerank-id]}
                    :body {:rerank-id rerank-id
                           :query query
                           :input-count (count documents)
                           :output-count (count results)
                           :latency-ms latency-ms
                           :top-score top-score
                           :performed-at (str (time/now))}})]
         :command/result {:results results
                          :rerank-id rerank-id}})

      (catch Exception e
        {::anom/category ::anom/fault
         ::anom/message (str "Rerank failed: " (ex-message e))}))))

;; =============================================================================
;; Training Commands
;; =============================================================================

(defcommand :colbert start-training
  "Start a training session."
  [{{:keys [model-name base-model training-data-path config]} :command}]
  (let [training-id (random-uuid)
        alias (str training-id)
        base-model (or base-model "colbert-ir/colbertv2.0")
        config (or config {})]
    (try
      ;; Create trainer
      (bridge/create-trainer! alias
        {:model-name model-name
         :pretrained-model-name base-model})

      ;; Count training data size
      (let [data-size 0] ; TODO: Count triplets in training data

        {:command-result/events
         [(->event {:type :colbert/training-started
                    :tags #{[:training training-id]}
                    :body {:training-id training-id
                           :model-name model-name
                           :base-model base-model
                           :training-data-path training-data-path
                           :training-data-size data-size
                           :config config
                           :started-at (str (time/now))}})]
         :command/result {:training-id training-id}})

      (catch Exception e
        {::anom/category ::anom/fault
         ::anom/message (str "Failed to start training: " (ex-message e))}))))

;; =============================================================================
;; Training Command (full lifecycle)
;; =============================================================================

(defcommand :colbert train
  "Train/fine-tune a ColBERT model.

   Emits :colbert/training-started, then runs training via bridge,
   then emits :colbert/training-completed or :colbert/training-failed.

   Note: Training is long-running. This command runs synchronously —
   the started event is emitted together with the completed/failed event
   in a single batch for atomicity."
  [{{:keys [model-name base-model training-data-path config]} :command}]
  (let [training-id (random-uuid)
        alias (str training-id)
        base-model (or base-model "colbert-ir/colbertv2.0")
        config (or config {})
        start-time (System/currentTimeMillis)
        started-event (->event {:type :colbert/training-started
                                :tags #{[:training training-id]}
                                :body {:training-id training-id
                                       :model-name model-name
                                       :base-model base-model
                                       :training-data-path training-data-path
                                       :training-data-size 0  ; TODO: count from path
                                       :config config
                                       :started-at (str (time/now))}})]
    (try
      ;; Create trainer and run training via bridge
      (bridge/create-trainer! alias
        {:model-name model-name
         :pretrained-model-name base-model})

      (let [result (bridge/train! alias config)
            duration-ms (- (System/currentTimeMillis) start-time)]

        {:command-result/events
         [started-event
          (->event {:type :colbert/training-completed
                    :tags #{[:training training-id]}
                    :body {:training-id training-id
                           :checkpoint-path (:checkpoint_path result)
                           :final-step (:final_step result)
                           :duration-ms duration-ms
                           :completed-at (str (time/now))}})]
         :command/result {:training-id training-id
                          :checkpoint-path (:checkpoint_path result)}})

      (catch Exception e
        {:command-result/events
         [started-event
          (->event {:type :colbert/training-failed
                    :tags #{[:training training-id]}
                    :body {:training-id training-id
                           :error-message (ex-message e)
                           :failed-at (str (time/now))}})]
         :command/result {:training-id training-id
                          :error (ex-message e)}}))))

;; =============================================================================
;; Index Regeneration Command
;; =============================================================================

(defcommand :colbert regenerate-index
  "Regenerate a PLAID index from event store data.

   Use this if the PLAID files on disk are lost/corrupted.
   Reads source data from the read model and recreates via bridge."
  [{{:keys [index-id]} :command :as ctx}]
  (if-let [data (read-models/get-index-for-regeneration ctx index-id)]
    (do
      (mu/log ::regenerating-index :index-id index-id
              :document-count (count (:documents data)))
      (try
        (let [alias (str index-id "-regen")]
          (bridge/load-model! alias :model-name (:model-name data))
          (let [result (bridge/create-index! alias
                         {:collection (:documents data)
                          :document-ids (:document-ids data)
                          :document-metadatas (:document-metadatas data)
                          :index-name (str (:index-name data) "-regen")
                          :split-documents? (get-in data [:config :split-documents?] true)
                          :max-document-length (get-in data [:config :max-document-length] 256)})]

            (mu/log ::index-regenerated :index-id index-id
                    :new-path (:index_path result))

            {:command-result/events
             [(->event {:type :colbert/index-regenerated
                        :tags #{[:index index-id]}
                        :body {:index-id index-id
                               :index-path (:index_path result)
                               :num-passages (:num_passages result)
                               :regenerated-at (str (time/now))}})]
             :command/result {:index-path (:index_path result)
                              :num-passages (:num_passages result)}}))

        (catch Exception e
          (mu/log ::regenerate-index-failed :index-id index-id :error (ex-message e))
          {::anom/category ::anom/fault
           ::anom/message (str "Failed to regenerate index: " (ex-message e))})))
    {::anom/category ::anom/not-found
     ::anom/message "Index not found or cannot be regenerated"}))
