(ns ai.obney.orc.grain-test-utils.interface
  "Minimal test utilities for ORC component tests.
   Provides test context lifecycle and command execution helpers."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.command-processor-v2.interface :as command-processor]
            [ai.obney.grain.query-processor.interface :as query-processor]
            [ai.obney.grain.time.interface :as time])
  (:import [java.io File]))

;; =============================================================================
;; Test Context Factory
;; =============================================================================

(defn- delete-dir-recursively
  [^String path]
  (let [f (File. path)]
    (when (.exists f)
      (when (.isDirectory f)
        (doseq [child (.listFiles f)]
          (delete-dir-recursively (.getPath child))))
      (.delete f))))

(defn create-test-context
  "Create a fresh test context with in-memory event store and LMDB cache."
  ([] (create-test-context "test"))
  ([prefix]
   (let [dir (str "/tmp/" prefix "-" (random-uuid))
         event-store (es/start {:conn {:type :in-memory}
                                :event-pubsub nil
                                :logger nil})
         cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))
         test-tenant-id (random-uuid)
         ctx {:event-store event-store
              :cache cache
              :tenant-id test-tenant-id
              :system-tenant-id test-tenant-id
              :command-registry (command-processor/global-command-registry)
              :query-registry (query-processor/global-query-registry)
              ::cache-dir dir}]
     ctx)))

(defn stop-context [ctx]
  (kv/stop (:cache ctx))
  (es/stop (:event-store ctx))
  (delete-dir-recursively (::cache-dir ctx)))

(defmacro with-test-context
  [[ctx-sym & [prefix]] & body]
  `(let [~ctx-sym (create-test-context ~(or prefix "test"))]
     (try
       ~@body
       (finally
         (stop-context ~ctx-sym)))))

;; =============================================================================
;; Command Execution
;; =============================================================================

(defn process-command!
  "Execute a command through the grain command processor."
  [ctx command-data]
  (command-processor/process-command
   (assoc ctx :command
     (merge {:command/id (random-uuid)
             :command/timestamp (time/now)}
            command-data))))

;; =============================================================================
;; Result Helpers
;; =============================================================================

(defn get-result-events [result]
  (:command-result/events result))

(defn event-of-type? [result event-type]
  (some #(= event-type (:event/type %)) (get-result-events result)))

(defn find-event [result event-type]
  (first (filter #(= event-type (:event/type %)) (get-result-events result))))

(defn apply-events!
  "Manually store events from a command result into the event store."
  [ctx result]
  (when-let [events (seq (:command-result/events result))]
    (es/append (:event-store ctx) {:tenant-id (:tenant-id ctx) :events (vec events)}))
  result)

;; =============================================================================
;; Auth Helpers
;; =============================================================================

(defn with-auth
  "Set auth-claims on context."
  [ctx claims]
  (cond-> (assoc ctx :auth-claims claims)
    (:tenant-id claims) (assoc :tenant-id (:tenant-id claims))))
