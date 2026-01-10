(ns ai.obney.workshop.user-service.interface
  (:require ;; Load core namespaces for side-effect registration of commands/queries
            [ai.obney.workshop.user-service.core.commands]
            [ai.obney.workshop.user-service.core.queries]
            [ai.obney.workshop.user-service.core.periodic-tasks :as tasks]
            [ai.obney.workshop.user-service.core.read-models :as rm]
            [ai.obney.workshop.user-service.core.todo-processors :as tp]))

(def periodic-tasks
  {:example-periodic-task {:handler-fn #'tasks/example-periodic-task
                           :schedule "0 0 * * * ?"  ;; Every hour
                           :description "Example periodic task"}})

(def todo-processors tp/todo-processors)

;;
;; Read Models
;;

(defn apply-events
  [events]
  (rm/apply-events events))

(def user-event-types rm/user-event-types)