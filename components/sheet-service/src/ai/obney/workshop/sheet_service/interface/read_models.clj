(ns ai.obney.workshop.sheet-service.interface.read-models
  "Public interface for sheet service read models."
  (:require [ai.obney.workshop.sheet-service.core.read-models :as core]))

;; Sheet read model functions
(defn get-sheet
  "Get a single sheet by ID"
  [event-store sheet-id]
  (core/get-sheet event-store sheet-id))

(defn get-sheets-all
  "Get all sheets"
  [event-store]
  (core/get-sheets-all event-store))

;; Cell read model functions
(defn get-cell
  "Get a single cell by ID"
  [event-store sheet-id cell-id]
  (core/get-cell event-store sheet-id cell-id))

(defn get-cells-for-sheet
  "Get all cells in a sheet"
  [event-store sheet-id]
  (core/get-cells-for-sheet event-store sheet-id))

;; Dependency graph functions
(defn get-dependency-graph-for-sheet
  "Get the dependency graph for a sheet"
  [event-store sheet-id]
  (core/get-dependency-graph-for-sheet event-store sheet-id))

(defn get-dependent-cells
  "Get cells that depend on the given cell"
  [event-store sheet-id cell-id]
  (core/get-dependent-cells event-store sheet-id cell-id))

;; Execution read model functions
(defn get-execution
  "Get a single execution by ID"
  [event-store execution-id]
  (core/get-execution event-store execution-id))

(defn get-executions-for-cell
  "Get all executions for a cell"
  [event-store cell-id]
  (core/get-executions-for-cell event-store cell-id))

(defn get-executions-for-sheet
  "Get all executions in a sheet"
  [event-store sheet-id]
  (core/get-executions-for-sheet event-store sheet-id))

;; Complete view
(defn get-sheet-view
  "Get complete sheet view with all cells and dependency graph"
  [event-store sheet-id]
  (core/get-sheet-view event-store sheet-id))
