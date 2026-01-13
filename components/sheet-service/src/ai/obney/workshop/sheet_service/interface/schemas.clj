(ns ai.obney.workshop.sheet-service.interface.schemas
  "Behavior Tree Sheet schemas.

   This is a behavior tree visualization system where:
   - The grid represents a tree (rows = depth levels, cells subdivide horizontally)
   - Nodes are leaf (execute), sequence (run all until failure), or fallback (run until success)
   - Data flows through a shared blackboard
   - Nodes return status: success, failure, or running"
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

;; =============================================================================
;; Domain Enums
;; =============================================================================

(def node-type
  "Behavior tree node types"
  [:enum :leaf :sequence :fallback :condition :parallel :map-each])

(def executor-type
  "Executor types for leaf nodes"
  [:enum :ai :code :tool])

(def node-status
  "Node execution status"
  [:enum :idle :running :success :failure])

;; Legacy field-type enum - kept for migration from old format
(def field-type
  "Supported field types for blackboard values (legacy)"
  [:enum :text :number :yesno :list :document :image :table])

;; Migration helper: convert legacy type to Malli schema
(def legacy-type->malli
  "Maps legacy field types to Malli schemas"
  {:text :string
   :number :double
   :yesno :boolean
   :list [:vector :string]
   :document :string
   :image :string
   :table [:vector [:map-of :string :any]]})

(def decorator-type
  "Decorator types that modify node behavior"
  [:enum :retry :timeout :repeat])

(def condition-op
  "Operators for condition node checks"
  [:enum :equals :not-equals :gt :lt :gte :lte :contains :exists :truthy])

(def on-fail-behavior
  "What to return when a condition check fails"
  [:enum :failure :running])

;; =============================================================================
;; Domain Value Objects
;; =============================================================================

(def decorator
  "Decorator that modifies node execution behavior"
  [:map
   [:type decorator-type]
   [:config :map]])

(def field-value
  "A typed field value for blackboard entries.
   Schema can be any valid Malli schema (keyword like :string, or vector like [:map ...])"
  [:map
   [:schema :any]  ;; Malli schema EDN
   [:value :any]])

(def condition-check
  "A condition check definition for condition nodes"
  [:map
   [:key :string]
   [:op condition-op]
   [:value {:optional true} :any]
   [:on-fail {:optional true} on-fail-behavior]])

;; =============================================================================
;; Domain Schemas (for use in query results)
;; =============================================================================

(defschemas domain
  {::sheet
   [:map
    [:id :uuid]
    [:name :string]
    [:root-node-id {:optional true} :uuid]
    [:created-at {:optional true} :string]]

   ::node
   [:map
    [:id :uuid]
    [:sheet-id :uuid]
    [:type node-type]
    [:name :string]
    [:parent-id {:optional true} :uuid]
    [:children-ids [:vector :uuid]]
    [:status node-status]
    ;; Leaf-only fields
    [:instruction {:optional true} :string]
    [:reads [:vector :string]]
    [:writes [:vector :string]]
    [:decorators [:vector decorator]]
    ;; Executor fields (for leaf nodes)
    [:executor {:optional true} executor-type]     ;; :ai, :code, :tool
    [:model {:optional true} :string]              ;; OpenRouter model ID (e.g., "google/gemini-2.5-flash")
    [:fn {:optional true} :string]                 ;; Fully-qualified fn symbol for :code executor
    [:tools {:optional true} [:vector :keyword]]   ;; Tools available to AI for :ai executor
    [:retry {:optional true} [:map
                              [:max-attempts :int]
                              [:backoff-ms [:vector :int]]]]
    ;; Condition-only fields
    [:check {:optional true} condition-check]
    ;; Parallel-only fields
    [:success-policy {:optional true} [:enum :all :any :majority]]
    [:failure-policy {:optional true} [:enum :all :any]]
    ;; Map-each-only fields
    [:source-key {:optional true} :string]         ;; Blackboard key with list to iterate
    [:item-key {:optional true} :string]           ;; Blackboard key for current item
    [:output-key {:optional true} :string]         ;; Blackboard key for collected results
    [:max-concurrency {:optional true} :int]       ;; Max parallel iterations (nil = sequential)
    ;; Execution tracking
    [:last-error {:optional true} :string]]

   ::blackboard-entry
   [:map
    [:key :string]
    [:schema :any]  ;; Malli schema EDN (e.g., :string, [:map [:name :string]])
    [:value {:optional true} :any]
    [:version :int]]

   ::node-layout
   [:map
    [:node-id :uuid]
    [:row :int]
    [:start-col :double]
    [:end-col :double]]})

;; =============================================================================
;; Command Schemas
;; =============================================================================

(defschemas commands
  {;; -------------------------------------------------------------------------
   ;; Sheet Commands
   ;; -------------------------------------------------------------------------

   :sheet/create-sheet
   [:map
    [:name :string]]

   :sheet/rename-sheet
   [:map
    [:sheet-id :uuid]
    [:name :string]]

   :sheet/delete-sheet
   [:map
    [:sheet-id :uuid]]

   ;; -------------------------------------------------------------------------
   ;; Node Commands
   ;; -------------------------------------------------------------------------

   :sheet/create-node
   [:map
    [:sheet-id :uuid]
    [:node-id {:optional true} :uuid]
    [:type node-type]
    [:parent-id {:optional true} :uuid]
    [:index {:optional true} :int]]

   :sheet/move-node
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:new-parent-id :uuid]
    [:index :int]]

   :sheet/reorder-node
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:index :int]]

   :sheet/delete-node
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]]

   :sheet/set-node-name
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:name :string]]

   :sheet/set-node-instruction
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:instruction :string]]

   :sheet/set-node-io
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:reads [:vector :string]]
    [:writes [:vector :string]]]

   :sheet/set-node-decorators
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:decorators [:vector decorator]]]

   :sheet/set-node-check
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:check condition-check]]

   :sheet/set-node-executor
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:executor executor-type]
    [:model {:optional true} :string]
    [:fn {:optional true} :string]
    [:tools {:optional true} [:vector :keyword]]]

   :sheet/set-node-retry
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:retry [:map
             [:max-attempts :int]
             [:backoff-ms [:vector :int]]]]]

   :sheet/set-parallel-config
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:success-policy {:optional true} [:enum :all :any :majority]]
    [:failure-policy {:optional true} [:enum :all :any]]]

   :sheet/set-map-each-config
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:source-key :string]
    [:item-key :string]
    [:output-key :string]
    [:max-concurrency {:optional true} :int]]

   ;; -------------------------------------------------------------------------
   ;; Blackboard Commands
   ;; -------------------------------------------------------------------------

   :sheet/declare-key
   [:map
    [:sheet-id :uuid]
    [:key :string]
    [:schema :any]]  ;; Malli schema EDN

   :sheet/update-key-schema
   [:map
    [:sheet-id :uuid]
    [:key :string]
    [:schema :any]]  ;; New Malli schema EDN

   :sheet/set-key-value
   [:map
    [:sheet-id :uuid]
    [:key :string]
    [:value :any]]

   :sheet/delete-key
   [:map
    [:sheet-id :uuid]
    [:key :string]]

   ;; -------------------------------------------------------------------------
   ;; Execution Commands
   ;; -------------------------------------------------------------------------

   :sheet/tick-tree
   [:map
    [:sheet-id :uuid]
    [:tick-id {:optional true} :uuid]]

   :sheet/tick-node
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:tick-id {:optional true} :uuid]
    [:overrides {:optional true} [:map-of :string :any]]]

   :sheet/cancel-tick
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]]

   ;; Internal commands (issued by todo processors)
   :sheet/complete-node-execution
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:status [:enum :success :failure]]
    [:writes [:map-of :string :any]]
    [:duration-ms {:optional true} :int]]

   :sheet/fail-node-execution
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:error :string]
    [:duration-ms {:optional true} :int]]})

;; =============================================================================
;; Event Schemas
;; =============================================================================

(defschemas events
  {;; -------------------------------------------------------------------------
   ;; Sheet Events
   ;; -------------------------------------------------------------------------

   :sheet/sheet-created
   [:map
    [:sheet-id :uuid]
    [:name :string]]

   :sheet/sheet-renamed
   [:map
    [:sheet-id :uuid]
    [:old-name :string]
    [:new-name :string]]

   :sheet/sheet-deleted
   [:map
    [:sheet-id :uuid]]

   ;; -------------------------------------------------------------------------
   ;; Node Events
   ;; -------------------------------------------------------------------------

   :sheet/node-created
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:type node-type]
    [:parent-id {:optional true} :uuid]
    [:index {:optional true} :int]]

   :sheet/node-moved
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:old-parent-id {:optional true} :uuid]
    [:new-parent-id :uuid]
    [:index :int]]

   :sheet/node-reordered
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:old-index :int]
    [:new-index :int]]

   :sheet/node-deleted
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]]

   :sheet/node-name-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:name :string]
    [:previous-name {:optional true} :string]]

   :sheet/node-instruction-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:instruction :string]
    [:previous-instruction {:optional true} :string]]

   :sheet/node-io-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:reads [:vector :string]]
    [:writes [:vector :string]]
    [:previous-reads {:optional true} [:vector :string]]
    [:previous-writes {:optional true} [:vector :string]]]

   :sheet/node-decorators-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:decorators [:vector decorator]]
    [:previous-decorators {:optional true} [:vector decorator]]]

   :sheet/node-check-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:check condition-check]
    [:previous-check {:optional true} condition-check]]

   :sheet/node-executor-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:executor executor-type]
    [:model {:optional true} :string]
    [:fn {:optional true} :string]
    [:tools {:optional true} [:vector :keyword]]
    [:previous-executor {:optional true} executor-type]
    [:previous-model {:optional true} :string]
    [:previous-fn {:optional true} :string]
    [:previous-tools {:optional true} [:vector :keyword]]]

   :sheet/node-retry-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:retry [:map
             [:max-attempts :int]
             [:backoff-ms [:vector :int]]]]
    [:previous-retry {:optional true} [:map
                                       [:max-attempts :int]
                                       [:backoff-ms [:vector :int]]]]]

   :sheet/parallel-config-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:success-policy {:optional true} [:enum :all :any :majority]]
    [:failure-policy {:optional true} [:enum :all :any]]
    [:previous-success-policy {:optional true} [:enum :all :any :majority]]
    [:previous-failure-policy {:optional true} [:enum :all :any]]]

   :sheet/map-each-config-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:source-key :string]
    [:item-key :string]
    [:output-key :string]
    [:max-concurrency {:optional true} :int]
    [:previous-source-key {:optional true} :string]
    [:previous-item-key {:optional true} :string]
    [:previous-output-key {:optional true} :string]
    [:previous-max-concurrency {:optional true} :int]]

   ;; -------------------------------------------------------------------------
   ;; Blackboard Events
   ;; -------------------------------------------------------------------------

   :sheet/key-declared
   [:map
    [:sheet-id :uuid]
    [:key :string]
    [:schema :any]]  ;; Malli schema EDN

   :sheet/key-schema-updated
   [:map
    [:sheet-id :uuid]
    [:key :string]
    [:schema :any]
    [:previous-schema {:optional true} :any]]

   :sheet/key-value-set
   [:map
    [:sheet-id :uuid]
    [:key :string]
    [:value :any]
    [:previous-value {:optional true} :any]
    [:version :int]]

   :sheet/key-deleted
   [:map
    [:sheet-id :uuid]
    [:key :string]]

   ;; -------------------------------------------------------------------------
   ;; Execution Events
   ;; -------------------------------------------------------------------------

   :sheet/tree-tick-started
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:iteration {:optional true} :int]]

   :sheet/node-execution-started
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:inputs [:map-of :string :any]]]

   :sheet/node-execution-completed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:status [:enum :success :failure :running]]
    [:writes {:optional true} [:map-of :string :any]]
    [:duration-ms {:optional true} :int]]

   :sheet/tree-tick-completed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:iteration {:optional true} :int]
    [:root-status [:enum :success :failure :running]]]

   :sheet/tick-cancelled
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]]})

;; =============================================================================
;; Query Schemas (Fat Query Model - one query per screen)
;; =============================================================================

(defschemas queries
  {;; -------------------------------------------------------------------------
   ;; Sheets List Screen (/sheets)
   ;; -------------------------------------------------------------------------

   :sheet/sheets-list-screen
   [:map]

   :sheet/sheets-list-screen-result
   [:map
    [:sheets [:vector ::sheet]]
    [:total :int]]

   ;; -------------------------------------------------------------------------
   ;; Sheet View Screen (/sheet?sheet-id=...)
   ;; -------------------------------------------------------------------------

   :sheet/sheet-view-screen
   [:map
    [:sheet-id :uuid]]

   :sheet/sheet-view-screen-result
   [:map
    [:sheet ::sheet]
    [:nodes [:vector ::node]]
    [:blackboard [:vector ::blackboard-entry]]
    [:layout [:vector ::node-layout]]]

   ;; -------------------------------------------------------------------------
   ;; Export Sheet (for download/backup)
   ;; -------------------------------------------------------------------------

   :sheet/export-sheet
   [:map
    [:sheet-id :uuid]]

   :sheet/export-sheet-result
   [:map
    [:version :int]
    [:exported-at :any]
    [:sheet [:map
             [:name :string]
             [:id :uuid]]]
    [:blackboard-schema [:map-of :keyword :any]]
    [:nodes {:optional true} :any]]})
