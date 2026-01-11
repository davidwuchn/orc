(ns store.core
  "Si-frame store initialization and core events"
  (:require [re-frame.core :as rf]))

(defn init-db
  "Initialize the application database with default state"
  []
  {:auth {:status :loading  ;; :loading | true | false
          :user nil}        ;; {:email "..."}
   :sheets {:list []        ;; List of sheets
            :loading? false
            :error nil}
   :sheet {:data nil        ;; Current sheet data
           :cells {}        ;; Cells by id
           :dependency-graph {:nodes {} :edges #{}}
           :selected-cell-id nil
           :loading? false
           :error nil}})

(rf/reg-event-db
 ::initialize
 (fn [_ _]
   (init-db)))
