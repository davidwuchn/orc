(ns ai.obney.workshop.crm-service.interface.periodic-tasks
  (:require [ai.obney.workshop.crm-service.core.periodic-tasks :as core]))

(def periodic-tasks
  {:example-periodic-task {:handler-fn #'core/example-periodic-task
                           :schedule "0 0 * * * ?"  ;; Every hour
                           :description "Example periodic task"}})