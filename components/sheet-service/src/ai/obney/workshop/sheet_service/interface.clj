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
            [ai.obney.workshop.sheet-service.core.tree-layout :as layout]))

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

;; =============================================================================
;; Tree Layout
;; =============================================================================

(def compute-layout layout/compute-layout)
(def compute-tree-depth layout/compute-tree-depth)
