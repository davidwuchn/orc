(ns ai.obney.workshop.sheet-service.interface.schemas
  "Sheet Service schemas for cells, formulas, and agent orchestration.

   This is a spreadsheet-based system for orchestrating AI agents.
   Cells contain either literal values or agent formulas.
   Dependencies between cells form a DAG with controlled cycles via gate cells."
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

;; =============================================================================
;; Domain Schemas
;; =============================================================================

(def field-type
  "Supported field types for cell values"
  [:enum :text :number :list :document :image :table :yes-no])

(def field-value
  "A typed field value"
  [:map
   [:type field-type]
   [:value :any]])

(def field-definition
  "Definition of a field in a signature"
  [:map
   [:name :string]
   [:type field-type]
   [:description {:optional true} :string]])

(def signature
  "Formula cell signature - defines agent behavior"
  [:map
   [:instruction :string]
   [:inputs [:vector field-definition]]
   [:outputs [:vector field-definition]]])

(def input-binding
  "Binding of an input to a source cell field"
  [:map
   [:source-cell-id :uuid]
   [:source-field-name :string]])

(def cell-status
  "Cell value status"
  [:enum :valid :stale :error])

(def execution-status
  "Cell execution status"
  [:enum :idle :pending :running :completed :failed :cancelled])

;; Address validation: A1-style (A-ZZ columns, 1-999 rows)
(def address-regex #"^[A-Z]{1,2}[1-9][0-9]{0,2}$")

;; =============================================================================
;; Domain Schemas (for use in query results)
;; =============================================================================

(defschemas domain
  {::sheet
   [:map
    [:id :uuid]
    [:name :string]
    [:description {:optional true} :string]
    [:created-at {:optional true} :string]
    [:updated-at {:optional true} :string]]

   ::cell
   [:map
    [:id :uuid]
    [:sheet-id :uuid]
    [:address :string]
    [:fields [:map-of :string field-value]]
    [:signature {:optional true} signature]
    [:input-bindings [:map-of :string input-binding]]
    [:status cell-status]
    [:execution-status execution-status]
    [:last-error {:optional true} :string]
    [:last-execution-id {:optional true} :uuid]]

   ::dependency-graph
   [:map
    [:nodes [:map-of :uuid [:set :uuid]]]
    [:edges [:set [:map
                   [:from :uuid]
                   [:to :uuid]
                   [:input-name :string]]]]]})

;; =============================================================================
;; Command Schemas
;; =============================================================================

(defschemas commands
  {;; -------------------------------------------------------------------------
   ;; Sheet Commands
   ;; -------------------------------------------------------------------------

   :sheet/create-sheet
   [:map
    [:name :string]
    [:description {:optional true} :string]]

   :sheet/rename-sheet
   [:map
    [:sheet-id :uuid]
    [:name :string]]

   :sheet/delete-sheet
   [:map
    [:sheet-id :uuid]]

   ;; -------------------------------------------------------------------------
   ;; Cell Commands
   ;; -------------------------------------------------------------------------

   :sheet/create-cell
   [:map
    [:sheet-id :uuid]
    [:address [:re address-regex]]
    [:cell-id {:optional true} :uuid]]

   :sheet/set-cell-literal
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]
    [:fields [:map-of :string field-value]]]

   :sheet/set-cell-signature
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]
    [:signature signature]]

   :sheet/bind-input
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]
    [:input-name :string]
    [:source-cell-id :uuid]
    [:source-field-name :string]]

   :sheet/unbind-input
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]
    [:input-name :string]]

   :sheet/delete-cell
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]]

   ;; -------------------------------------------------------------------------
   ;; Execution Commands
   ;; -------------------------------------------------------------------------

   :sheet/request-cell-execution
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]
    [:execution-id {:optional true} :uuid]]

   :sheet/cancel-cell-execution
   [:map
    [:sheet-id :uuid]
    [:execution-id :uuid]]

   ;; Internal commands (issued by todo processors)
   :sheet/complete-cell-execution
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]
    [:execution-id :uuid]
    [:outputs [:map-of :string field-value]]
    [:duration-ms :int]]

   :sheet/fail-cell-execution
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]
    [:execution-id :uuid]
    [:error :string]
    [:duration-ms :int]]})

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
    [:name :string]
    [:description {:optional true} :string]]

   :sheet/sheet-renamed
   [:map
    [:sheet-id :uuid]
    [:old-name :string]
    [:new-name :string]]

   :sheet/sheet-deleted
   [:map
    [:sheet-id :uuid]]

   ;; -------------------------------------------------------------------------
   ;; Cell Events
   ;; -------------------------------------------------------------------------

   :sheet/cell-created
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]
    [:address :string]]

   :sheet/cell-literal-set
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]
    [:fields [:map-of :string field-value]]
    [:previous-fields {:optional true} [:map-of :string field-value]]]

   :sheet/cell-signature-defined
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]
    [:signature signature]
    [:previous-signature {:optional true} signature]]

   :sheet/input-bound
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]
    [:input-name :string]
    [:source-cell-id :uuid]
    [:source-field-name :string]]

   :sheet/input-unbound
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]
    [:input-name :string]
    [:previous-binding {:optional true} input-binding]]

   :sheet/cell-deleted
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]]

   ;; -------------------------------------------------------------------------
   ;; Execution Events
   ;; -------------------------------------------------------------------------

   :sheet/cell-execution-requested
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]
    [:execution-id :uuid]
    [:inputs [:map-of :string field-value]]
    [:signature signature]]

   :sheet/cell-execution-completed
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]
    [:execution-id :uuid]
    [:outputs [:map-of :string field-value]]
    [:duration-ms :int]]

   :sheet/cell-execution-failed
   [:map
    [:sheet-id :uuid]
    [:cell-id :uuid]
    [:execution-id :uuid]
    [:error :string]
    [:duration-ms :int]]

   :sheet/cell-execution-cancelled
   [:map
    [:sheet-id :uuid]
    [:execution-id :uuid]]})

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
   ;; Sheet View Screen (/sheet/:sheet-id)
   ;; -------------------------------------------------------------------------

   :sheet/sheet-view-screen
   [:map
    [:sheet-id :uuid]]

   :sheet/sheet-view-screen-result
   [:map
    [:sheet ::sheet]
    [:cells [:vector ::cell]]
    [:dependency-graph ::dependency-graph]]})
