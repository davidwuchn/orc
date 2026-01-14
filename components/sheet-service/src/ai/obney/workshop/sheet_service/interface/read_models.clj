(ns ai.obney.workshop.sheet-service.interface.read-models
  "Public interface for behavior tree sheet service read models."
  (:require [ai.obney.workshop.sheet-service.core.read-models :as core]))

;; =============================================================================
;; Sheet Read Model Functions
;; =============================================================================

(defn get-sheet
  "Get a single sheet by ID"
  [event-store sheet-id]
  (core/get-sheet event-store sheet-id))

(defn get-sheets-all
  "Get all sheets"
  [event-store]
  (core/get-sheets-all event-store))

(defn get-sheet-by-name
  "Find a sheet by name. Returns nil if not found."
  [event-store name]
  (core/get-sheet-by-name event-store name))

;; =============================================================================
;; Node Read Model Functions
;; =============================================================================

(defn get-node
  "Get a single node by ID"
  [event-store sheet-id node-id]
  (core/get-node event-store sheet-id node-id))

(defn get-nodes-for-sheet
  "Get all nodes in a sheet"
  [event-store sheet-id]
  (core/get-nodes-for-sheet event-store sheet-id))

(defn get-nodes-by-id
  "Get all nodes in a sheet as a map keyed by node-id"
  [event-store sheet-id]
  (core/get-nodes-by-id event-store sheet-id))

(defn get-root-node
  "Get the root node for a sheet"
  [event-store sheet-id]
  (core/get-root-node event-store sheet-id))

(defn get-children
  "Get child nodes of a parent node"
  [event-store sheet-id parent-id]
  (core/get-children event-store sheet-id parent-id))

(defn get-descendants
  "Get all descendant nodes of a node (recursive)"
  [event-store sheet-id node-id]
  (core/get-descendants event-store sheet-id node-id))

;; =============================================================================
;; Blackboard Read Model Functions
;; =============================================================================

(defn get-blackboard-for-sheet
  "Get the blackboard for a sheet as a list of entries"
  [event-store sheet-id]
  (core/get-blackboard-for-sheet event-store sheet-id))

(defn get-blackboard-by-key
  "Get the blackboard for a sheet as a map keyed by key name"
  [event-store sheet-id]
  (core/get-blackboard-by-key event-store sheet-id))

;; =============================================================================
;; Tick Read Model Functions
;; =============================================================================

(defn get-tick
  "Get a single tick by ID"
  [event-store tick-id]
  (core/get-tick event-store tick-id))

;; =============================================================================
;; Version Read Model Functions
;; =============================================================================

(defn get-versions-for-sheet
  "Get all published versions for a sheet, sorted by version number"
  [event-store sheet-id]
  (core/get-versions-for-sheet event-store sheet-id))

(defn get-version
  "Get a specific published version by version number"
  [event-store sheet-id version-number]
  (core/get-version event-store sheet-id version-number))

(defn get-latest-version
  "Get the latest published version for a sheet"
  [event-store sheet-id]
  (core/get-latest-version event-store sheet-id))

(defn get-stash
  "Get the stash for a sheet, if any"
  [event-store sheet-id]
  (core/get-stash event-store sheet-id))
