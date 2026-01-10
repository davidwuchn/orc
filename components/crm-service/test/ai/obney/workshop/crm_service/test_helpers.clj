(ns ai.obney.workshop.crm-service.test-helpers
  "Shared test utilities for CRM service tests."
  (:require [ai.obney.workshop.crm-service.core.commands] ;; For side-effect registration
            [ai.obney.workshop.crm-service.core.queries]  ;; For side-effect registration
            [ai.obney.workshop.crm-service.interface.schemas] ;; Register event schemas
            [ai.obney.grain.event-store-v2.interface :as es]
            [ai.obney.grain.command-processor.interface :as command-processor]
            [ai.obney.grain.query-processor.interface :as query-processor]))

;; =============================================================================
;; Test Context Factory
;; =============================================================================

(defn create-test-context
  "Create a fresh test context with in-memory event store."
  []
  (let [event-store (es/start {:conn {:type :in-memory}
                               :event-pubsub nil
                               :logger nil})]
    {:event-store event-store
     :command-registry (command-processor/global-command-registry)
     :query-registry (query-processor/global-query-registry)}))

(defn stop-context
  "Stop a test context and clean up resources."
  [ctx]
  (es/stop (:event-store ctx)))

(defmacro with-test-context
  "Execute body with a fresh test context, cleaning up afterward."
  [[ctx-sym] & body]
  `(let [~ctx-sym (create-test-context)]
     (try
       ~@body
       (finally
         (stop-context ~ctx-sym)))))

;; =============================================================================
;; Event Helpers
;; =============================================================================

(defn apply-events!
  "Apply events from a command result to the event store."
  [ctx result]
  (when-let [events (seq (:command-result/events result))]
    (es/append (:event-store ctx) {:events (vec events)}))
  result)

(defn get-result-events
  "Extract events from a command result."
  [result]
  (:command-result/events result))

(defn event-of-type?
  "Check if result contains an event of the given type."
  [result event-type]
  (some #(= event-type (:event/type %)) (get-result-events result)))

;; =============================================================================
;; Command Helpers
;; =============================================================================

(defn run-command
  "Run a command with the given context and command data."
  [ctx command-data]
  (let [command-name (:command/name command-data)
        handler-fn (get-in ctx [:command-registry command-name :handler-fn])]
    (if handler-fn
      (handler-fn (assoc ctx :command command-data))
      (throw (ex-info "Unknown command" {:command command-name})))))

(defn run-and-apply!
  "Run a command and apply its events to the store."
  [ctx command-data]
  (let [result (run-command ctx command-data)]
    (when (:command-result/events result)
      (apply-events! ctx result))
    result))

;; =============================================================================
;; Test Data Factories
;; =============================================================================

(defn make-contact-type-command
  "Create a contact type command with defaults."
  [& {:keys [slug name description field-definitions]
      :or {slug "test-type"
           name "Test Type"
           description "A test contact type"
           field-definitions []}}]
  {:slug slug
   :name name
   :description description
   :field-definitions field-definitions})

(defn make-contact-command
  "Create a contact command with defaults."
  [type-slug & {:keys [field-values tags]
                :or {field-values {}
                     tags #{}}}]
  {:type-slug type-slug
   :field-values field-values
   :tags tags})

(defn make-relationship-type-command
  "Create a relationship type command with defaults."
  [& {:keys [slug name inverse-name source-type-slugs target-type-slugs]
      :or {slug "test-rel"
           name "Test Relationship"
           inverse-name "Inverse Test"
           source-type-slugs #{}
           target-type-slugs #{}}}]
  {:slug slug
   :name name
   :inverse-name inverse-name
   :source-type-slugs source-type-slugs
   :target-type-slugs target-type-slugs})

(defn make-relationship-command
  "Create a relationship command with defaults."
  [type-slug source-id target-id & {:keys [properties start-date is-primary]
                                     :or {properties {}
                                          is-primary false}}]
  (cond-> {:type-slug type-slug
           :source-contact-id source-id
           :target-contact-id target-id
           :properties properties}
    start-date (assoc :start-date start-date)
    is-primary (assoc :is-primary is-primary)))
