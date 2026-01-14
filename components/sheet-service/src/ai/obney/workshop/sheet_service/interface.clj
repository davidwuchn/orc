(ns ai.obney.workshop.sheet-service.interface
  "Behavior Tree Sheet Service public interface.

   This namespace loads all core modules for side-effect registration
   and re-exports the public API."
  (:require ;; Load core namespaces for side-effect registration of commands/queries
            [ai.obney.workshop.sheet-service.core.commands]
            [ai.obney.workshop.sheet-service.core.queries]
            ;; Load schemas for registration
            [ai.obney.workshop.sheet-service.interface.schemas]
            ;; Re-export from interface sub-namespaces
            [ai.obney.workshop.sheet-service.interface.todo-processors :as tp]
            [ai.obney.workshop.sheet-service.interface.read-models :as rm]
            ;; Tree layout utilities
            [ai.obney.workshop.sheet-service.core.tree-layout :as layout]
            ;; Runtime for synchronous execution
            [ai.obney.workshop.sheet-service.core.runtime :as runtime]
            ;; DSL for workflow building
            [ai.obney.workshop.sheet-service.core.dsl :as dsl]))

;; =============================================================================
;; Todo Processors
;; =============================================================================

(def todo-processors tp/todo-processors)

;; =============================================================================
;; Read Models
;; =============================================================================

;; Sheet functions
(def get-sheet rm/get-sheet)
(def get-sheets-all rm/get-sheets-all)
(def get-sheet-by-name rm/get-sheet-by-name)

;; Node functions
(def get-node rm/get-node)
(def get-nodes-for-sheet rm/get-nodes-for-sheet)
(def get-nodes-by-id rm/get-nodes-by-id)
(def get-root-node rm/get-root-node)
(def get-children rm/get-children)
(def get-descendants rm/get-descendants)

;; Blackboard functions
(def get-blackboard-for-sheet rm/get-blackboard-for-sheet)
(def get-blackboard-by-key rm/get-blackboard-by-key)

;; Tick functions
(def get-tick rm/get-tick)

;; Version functions
(def get-versions-for-sheet rm/get-versions-for-sheet)
(def get-version rm/get-version)
(def get-latest-version rm/get-latest-version)
(def get-stash rm/get-stash)

;; =============================================================================
;; Tree Layout
;; =============================================================================

(def compute-layout layout/compute-layout)
(def compute-tree-depth layout/compute-tree-depth)

;; =============================================================================
;; Synchronous Execution
;; =============================================================================

(def execute
  "Execute a sheet (behavior tree) with inputs and return outputs.

   This is a synchronous, blocking call that:
   1. Creates an isolated execution context (doesn't mutate sheet's blackboard)
   2. Runs the tree to completion
   3. Returns output values
   4. Supports execution mode (draft/published)

   Args:
     context - Map with :event-store and optional :dscloj-provider
     sheet-id - UUID of the sheet to execute
     inputs - Map of blackboard key -> value for initial inputs

   Options:
     :timeout-ms - Max execution time in ms (default 300000 = 5 minutes)
     :use-version - Specific version number to execute (overrides execution-mode)
     :force-draft - Force draft execution even if execution-mode is :published

   Returns:
     {:status :success | :failure | :timeout
      :outputs {\"key\" value ...}
      :duration-ms 1234
      :error string?
      :executed-version int?}  ;; Present if published version was used

   Example:
     (sheet/execute ctx sheet-id {\"student-id\" student-id} :timeout-ms 60000)
     (sheet/execute ctx sheet-id inputs :use-version 2)  ;; Execute specific version"
  runtime/execute)

;; =============================================================================
;; Workflow DSL
;; =============================================================================

;; Node builders
(def llm dsl/llm)
(def code dsl/code)
(def condition dsl/condition)
(def llm-condition dsl/llm-condition)
(def sequence dsl/sequence)
(def fallback dsl/fallback)
(def parallel dsl/parallel)
(def map-each dsl/map-each)

;; Schema builder
(def blackboard dsl/blackboard)

;; Workflow definition
(def workflow dsl/workflow)

;; Build functions
(def build-workflow! dsl/build-workflow!)
(def build-workflow!! dsl/build-workflow!!)

;; Utilities
(def print-tree dsl/print-tree)
(def describe-workflow dsl/describe-workflow)

;; Export/Import
(def export-sheet dsl/export-sheet)
(def import-sheet dsl/import-sheet)
(def save-sheet! dsl/save-sheet!)
(def load-sheet! dsl/load-sheet!)
(def save-all-sheets! dsl/save-all-sheets!)
(def load-all-sheets! dsl/load-all-sheets!)
