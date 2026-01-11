(ns ai.obney.workshop.sheet-service.interface
  "Sheet Service public interface.

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
            ;; Agent runtime for custom configurations
            [ai.obney.workshop.sheet-service.core.agent-runtime :as agent]
            ;; Dependency graph utilities
            [ai.obney.workshop.sheet-service.core.dependency-graph :as dg]))

;; =============================================================================
;; Todo Processors
;; =============================================================================

(def todo-processors tp/todo-processors)

;; =============================================================================
;; Read Models
;; =============================================================================

(def get-sheet rm/get-sheet)
(def get-sheets-all rm/get-sheets-all)
(def get-cell rm/get-cell)
(def get-cells-for-sheet rm/get-cells-for-sheet)
(def get-dependency-graph-for-sheet rm/get-dependency-graph-for-sheet)
(def get-dependent-cells rm/get-dependent-cells)
(def get-execution rm/get-execution)
(def get-executions-for-cell rm/get-executions-for-cell)
(def get-executions-for-sheet rm/get-executions-for-sheet)
(def get-sheet-view rm/get-sheet-view)

;; =============================================================================
;; Agent Runtime
;; =============================================================================

(def create-mock-runtime agent/create-mock-runtime)
(def create-configurable-runtime agent/create-configurable-runtime)
(def create-echo-runtime agent/create-echo-runtime)

;; =============================================================================
;; Dependency Graph Utilities
;; =============================================================================

(def would-create-cycle? dg/would-create-cycle?)
(def find-cycles dg/find-cycles)
(def has-cycles? dg/has-cycles?)
(def topological-sort dg/topological-sort)
(def all-inputs-bound? dg/all-inputs-bound?)
(def is-eligible-for-execution? dg/is-eligible-for-execution?)
(def is-gate-cell? dg/is-gate-cell?)
