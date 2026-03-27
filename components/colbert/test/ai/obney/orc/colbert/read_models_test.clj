(ns ai.obney.orc.colbert.read-models-test
  "Unit tests for ColBERT read model event projections.

   These tests verify that events are correctly applied to build
   the indexes and trainings read models."
  (:require [clojure.test :refer [deftest testing is are]]
            [ai.obney.orc.colbert.core.read-models :as rm]))

;; =============================================================================
;; Test Data Fixtures
;; =============================================================================

(def test-index-id (random-uuid))
(def test-index-id-2 (random-uuid))
(def test-training-id (random-uuid))

(defn make-index-created-event
  "Create a test :colbert/index-created event."
  [& {:keys [index-id index-name documents document-ids]
      :or {index-id test-index-id
           index-name "test-index"
           documents ["Doc 1" "Doc 2" "Doc 3"]
           document-ids ["id1" "id2" "id3"]}}]
  {:event/type :colbert/index-created
   :index-id index-id
   :index-name index-name
   :index-path "/tmp/test-index"
   :documents documents
   :document-ids document-ids
   :document-metadatas nil
   :document-count (count documents)
   :passage-count (count documents)
   :model-name "colbert-ir/colbertv2.0"
   :config {:split-documents? true
            :max-document-length 256
            :use-faiss? false}
   :created-at "2024-01-01T00:00:00Z"})

(defn make-index-updated-event
  "Create a test :colbert/index-updated event."
  [update-type & {:keys [index-id documents-added document-ids-added
                         document-ids-removed]
                  :or {index-id test-index-id}}]
  (cond-> {:event/type :colbert/index-updated
           :index-id index-id
           :update-type update-type
           :updated-at "2024-01-02T00:00:00Z"}
    (= :add update-type)
    (merge {:documents-added documents-added
            :document-ids-added document-ids-added
            :document-metadatas-added nil})

    (= :remove update-type)
    (merge {:document-ids-removed document-ids-removed})))

(defn make-index-deleted-event
  "Create a test :colbert/index-deleted event."
  [& {:keys [index-id] :or {index-id test-index-id}}]
  {:event/type :colbert/index-deleted
   :index-id index-id
   :deleted-at "2024-01-03T00:00:00Z"})

(defn make-training-started-event
  "Create a test :colbert/training-started event."
  [& {:keys [training-id] :or {training-id test-training-id}}]
  {:event/type :colbert/training-started
   :training-id training-id
   :model-name "my-model"
   :base-model "colbert-ir/colbertv2.0"
   :training-data-path "/tmp/training"
   :training-data-size 1000
   :config {:batch-size 32}
   :started-at "2024-01-01T00:00:00Z"})

(defn make-training-completed-event
  "Create a test :colbert/training-completed event."
  [& {:keys [training-id] :or {training-id test-training-id}}]
  {:event/type :colbert/training-completed
   :training-id training-id
   :checkpoint-path "/tmp/checkpoint"
   :final-step 5000
   :duration-ms 360000
   :completed-at "2024-01-02T00:00:00Z"})

(defn make-training-failed-event
  "Create a test :colbert/training-failed event."
  [& {:keys [training-id] :or {training-id test-training-id}}]
  {:event/type :colbert/training-failed
   :training-id training-id
   :error-message "CUDA out of memory"
   :failed-at "2024-01-02T00:00:00Z"})

;; =============================================================================
;; Index Read Model Tests
;; =============================================================================

(deftest apply-index-created-event-test
  (testing "Index created event creates index entry"
    (let [event (make-index-created-event)
          state (rm/apply-index-event {} event)
          index (get-in state [:indexes test-index-id])]
      (is (some? index))
      (is (= test-index-id (:index-id index)))
      (is (= "test-index" (:index-name index)))
      (is (= ["Doc 1" "Doc 2" "Doc 3"] (:documents index)))
      (is (= ["id1" "id2" "id3"] (:document-ids index)))
      (is (= 3 (:document-count index)))
      (is (= :active (:status index))))))

(deftest apply-index-updated-add-event-test
  (testing "Index updated (add) event appends documents"
    (let [created (make-index-created-event)
          updated (make-index-updated-event :add
                    :documents-added ["Doc 4" "Doc 5"]
                    :document-ids-added ["id4" "id5"])
          state (-> {}
                    (rm/apply-index-event created)
                    (rm/apply-index-event updated))
          index (get-in state [:indexes test-index-id])]
      (is (= 5 (count (:documents index))))
      (is (= 5 (count (:document-ids index))))
      (is (= ["Doc 1" "Doc 2" "Doc 3" "Doc 4" "Doc 5"] (:documents index)))
      (is (= 5 (:document-count index))))))

(deftest apply-index-updated-remove-event-test
  (testing "Index updated (remove) event removes documents"
    (let [created (make-index-created-event)
          updated (make-index-updated-event :remove
                    :document-ids-removed ["id2"])
          state (-> {}
                    (rm/apply-index-event created)
                    (rm/apply-index-event updated))
          index (get-in state [:indexes test-index-id])]
      (is (= 2 (count (:documents index))))
      (is (= ["Doc 1" "Doc 3"] (:documents index)))
      (is (= ["id1" "id3"] (:document-ids index)))
      (is (= 2 (:document-count index))))))

(deftest apply-index-deleted-event-test
  (testing "Index deleted event marks index as deleted"
    (let [created (make-index-created-event)
          deleted (make-index-deleted-event)
          state (-> {}
                    (rm/apply-index-event created)
                    (rm/apply-index-event deleted))
          index (get-in state [:indexes test-index-id])]
      (is (= :deleted (:status index)))
      (is (some? (:deleted-at index))))))

(deftest apply-index-events-pipeline-test
  (testing "Full event pipeline produces correct state"
    (let [events [(make-index-created-event)
                  (make-index-updated-event :add
                    :documents-added ["Doc 4"]
                    :document-ids-added ["id4"])
                  (make-index-created-event :index-id test-index-id-2
                                            :index-name "second-index"
                                            :documents ["A" "B"]
                                            :document-ids ["a" "b"])]
          state (rm/apply-index-events events)]
      (is (= 2 (count (:indexes state))))
      (is (= 4 (count (get-in state [:indexes test-index-id :documents]))))
      (is (= 2 (count (get-in state [:indexes test-index-id-2 :documents])))))))

(deftest apply-index-event-unknown-event-test
  (testing "Unknown event types are ignored"
    (let [state {:indexes {}}
          result (rm/apply-index-event state {:event/type :unknown/event})]
      (is (= state result)))))

;; =============================================================================
;; Training Read Model Tests
;; =============================================================================

(deftest apply-training-started-event-test
  (testing "Training started event creates training entry"
    (let [event (make-training-started-event)
          state (rm/apply-training-event {} event)
          training (get-in state [:trainings test-training-id])]
      (is (some? training))
      (is (= test-training-id (:training-id training)))
      (is (= "my-model" (:model-name training)))
      (is (= :running (:status training))))))

(deftest apply-training-completed-event-test
  (testing "Training completed event updates status"
    (let [started (make-training-started-event)
          completed (make-training-completed-event)
          state (-> {}
                    (rm/apply-training-event started)
                    (rm/apply-training-event completed))
          training (get-in state [:trainings test-training-id])]
      (is (= :completed (:status training)))
      (is (= "/tmp/checkpoint" (:checkpoint-path training)))
      (is (= 5000 (:final-step training)))
      (is (= 360000 (:duration-ms training))))))

(deftest apply-training-failed-event-test
  (testing "Training failed event updates status"
    (let [started (make-training-started-event)
          failed (make-training-failed-event)
          state (-> {}
                    (rm/apply-training-event started)
                    (rm/apply-training-event failed))
          training (get-in state [:trainings test-training-id])]
      (is (= :failed (:status training)))
      (is (= "CUDA out of memory" (:error-message training))))))

(deftest apply-training-events-pipeline-test
  (testing "Full training event pipeline"
    (let [training-id-1 (random-uuid)
          training-id-2 (random-uuid)
          events [(make-training-started-event :training-id training-id-1)
                  (make-training-completed-event :training-id training-id-1)
                  (make-training-started-event :training-id training-id-2)
                  (make-training-failed-event :training-id training-id-2)]
          state (rm/apply-training-events events)]
      (is (= 2 (count (:trainings state))))
      (is (= :completed (get-in state [:trainings training-id-1 :status])))
      (is (= :failed (get-in state [:trainings training-id-2 :status]))))))

;; =============================================================================
;; Event Type Constants Tests
;; =============================================================================

(deftest event-types-test
  (testing "Event type sets are defined correctly"
    (is (contains? rm/colbert-event-types :colbert/index-created))
    (is (contains? rm/colbert-event-types :colbert/index-deleted))
    (is (contains? rm/colbert-event-types :colbert/search-performed))
    (is (contains? rm/index-event-types :colbert/index-created))
    (is (contains? rm/training-event-types :colbert/training-started))))
