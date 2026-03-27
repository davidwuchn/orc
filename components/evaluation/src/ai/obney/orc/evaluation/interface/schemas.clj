(ns ai.obney.orc.evaluation.interface.schemas
  "Schema definitions for evaluation commands, events, and queries.

   Defines the contracts for evaluation-related data structures used
   throughout the Grain event sourcing system."
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

;; =============================================================================
;; Dimension Schema (shared)
;; =============================================================================

(def DimensionScore
  "Schema for a single evaluation dimension result."
  [:map
   [:name :string]
   [:weight :double]
   [:score :double]
   [:feedback :string]])

;; =============================================================================
;; Events
;; =============================================================================

(defschemas events
  {:evaluation/trace-evaluated
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:node-name :string]
    [:dimensions [:vector DimensionScore]]
    [:aggregate-score :double]
    [:feedback-summary :string]
    [:evaluated-at :string]]

   :evaluation/batch-completed
   [:map
    [:batch-id :uuid]
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:traces-evaluated :int]
    [:avg-score :double]
    [:min-score :double]
    [:max-score :double]
    [:score-distribution [:map-of :string :int]]
    [:completed-at :string]]})

;; =============================================================================
;; Commands
;; =============================================================================

(defschemas commands
  {:evaluation/evaluate-trace
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:judge-config {:optional true}
     [:map
      [:dimensions {:optional true} [:vector :keyword]]
      [:model {:optional true} :string]]]]

   :evaluation/evaluate-batch
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:trace-ids {:optional true} [:vector :uuid]]
    [:since {:optional true} :string]
    [:limit {:optional true} :int]
    [:judge-config {:optional true}
     [:map
      [:dimensions {:optional true} [:vector :keyword]]
      [:model {:optional true} :string]]]]})

;; =============================================================================
;; Read Models
;; =============================================================================

(defschemas read-models
  {:evaluation/results-by-node
   [:map
    [:node-id :uuid]
    [:evaluations [:vector
                   [:map
                    [:trace-id :uuid]
                    [:aggregate-score :double]
                    [:evaluated-at :string]]]]]})

;; =============================================================================
;; Queries
;; =============================================================================

(defschemas queries
  {:evaluation/get-scores
   [:map
    [:sheet-id :uuid]
    [:node-id {:optional true} :uuid]
    [:since {:optional true} :string]
    [:min-score {:optional true} :double]
    [:max-score {:optional true} :double]
    [:limit {:optional true} :int]]

   :evaluation/get-low-scoring
   [:map
    [:sheet-id :uuid]
    [:node-id {:optional true} :uuid]
    [:threshold {:optional true} :double]
    [:limit {:optional true} :int]]

   :evaluation/get-trends
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:time-bucket {:optional true} [:enum :hour :day :week]]]})
