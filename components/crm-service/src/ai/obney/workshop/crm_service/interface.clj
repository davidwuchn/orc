(ns ai.obney.workshop.crm-service.interface
  (:require ;; Load core namespaces for side-effect registration of commands/queries
            [ai.obney.workshop.crm-service.core.commands]
            [ai.obney.workshop.crm-service.core.queries]
            ;; Re-export from interface sub-namespaces
            [ai.obney.workshop.crm-service.interface.todo-processors :as tp]
            [ai.obney.workshop.crm-service.interface.periodic-tasks :as tasks]
            [ai.obney.workshop.crm-service.interface.read-models :as rm]))

(def todo-processors tp/todo-processors)

(def periodic-tasks tasks/periodic-tasks)

;;
;; Read Models
;;

(def get-contact rm/get-contact)
(def get-contact-type rm/get-contact-type)
(def get-contact-types-all rm/get-contact-types-all)
(def get-contacts-by-type rm/get-contacts-by-type)
(def get-relationships-for-contact rm/get-relationships-for-contact)
