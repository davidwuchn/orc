(ns ai.obney.workshop.crm-service.core.read-models
  "CRM read models - projections built from events.

   This namespace provides:
   - Event type sets for querying
   - Multimethod projections for each entity type
   - Helper functions for common queries"
  (:require [ai.obney.grain.event-store-v2.interface :as event-store]))

;; =============================================================================
;; Event Type Sets
;; =============================================================================

(def contact-type-events
  "Events that affect contact type read model"
  #{:crm/contact-type-created
    :crm/contact-type-updated
    :crm/contact-type-deactivated
    :crm/field-definition-added
    :crm/field-definition-updated
    :crm/field-definition-removed})

(def contact-events
  "Events that affect contact read model"
  #{:crm/contact-created
    :crm/contact-updated
    :crm/contact-field-set
    :crm/contact-tagged
    :crm/contact-untagged
    :crm/contact-status-changed
    :crm/contact-archived
    :crm/contact-restored
    :crm/contact-merged
    :crm/contact-deleted})

(def relationship-type-events
  "Events that affect relationship type read model"
  #{:crm/relationship-type-created
    :crm/relationship-type-updated})

(def relationship-events
  "Events that affect relationship read model"
  #{:crm/relationship-created
    :crm/relationship-updated
    :crm/relationship-ended
    :crm/relationship-deleted
    :crm/primary-relationship-set})

(def duplicate-events
  "Events that affect duplicate detection read model"
  #{:crm/duplicate-detected
    :crm/duplicate-resolved})

(def attribution-events
  "Events that affect attribution read model"
  #{:crm/attribution-recorded
    :crm/attribution-updated})

(def communication-events
  "Events that affect communication read model"
  #{:crm/communication-logged})

(def contact-with-attribution-events
  "Combined events for fetching contact with attribution in single query"
  (into contact-events attribution-events))

;; =============================================================================
;; Contact Types Projection
;; =============================================================================

(defmulti contact-types*
  "Apply event to contact types read model"
  (fn [_state event] (:event/type event)))

(defmethod contact-types* :crm/contact-type-created
  [state event]
  (assoc state (:type-id event)
         {:id (:type-id event)
          :slug (:slug event)
          :name (:name event)
          :description (:description event)
          :field-definitions (vec (:field-definitions event))
          :active true
          :created-at (str (:event/timestamp event))
          :updated-at (str (:event/timestamp event))}))

(defmethod contact-types* :crm/contact-type-updated
  [state event]
  (-> state
      (update (:type-id event) merge (:changes event))
      (assoc-in [(:type-id event) :updated-at] (str (:event/timestamp event)))))

(defmethod contact-types* :crm/contact-type-deactivated
  [state event]
  (-> state
      (assoc-in [(:type-id event) :active] false)
      (assoc-in [(:type-id event) :updated-at] (str (:event/timestamp event)))))

(defmethod contact-types* :crm/field-definition-added
  [state event]
  (let [type-id (:type-id event)
        field (:field event)
        position (:position event)
        current-fields (get-in state [type-id :field-definitions] [])]
    (-> state
        (assoc-in [type-id :field-definitions]
                  (vec (concat (take position current-fields)
                               [field]
                               (drop position current-fields))))
        (assoc-in [type-id :updated-at] (str (:event/timestamp event))))))

(defmethod contact-types* :crm/field-definition-updated
  [state event]
  (let [type-id (:type-id event)
        field-slug (:field-slug event)
        updates (:updates event)]
    (-> state
        (update-in [type-id :field-definitions]
                   (fn [fields]
                     (mapv (fn [f]
                             (if (= field-slug (:slug f))
                               (merge f updates)
                               f))
                           fields)))
        (assoc-in [type-id :updated-at] (str (:event/timestamp event))))))

(defmethod contact-types* :crm/field-definition-removed
  [state event]
  (let [type-id (:type-id event)
        field-slug (:field-slug event)]
    (-> state
        (update-in [type-id :field-definitions]
                   (fn [fields]
                     (vec (remove #(= field-slug (:slug %)) fields))))
        (assoc-in [type-id :updated-at] (str (:event/timestamp event))))))

(defmethod contact-types* :default [state _] state)

(defn contact-types
  "Build contact types read model from events"
  [initial-state events]
  (reduce contact-types* initial-state events))

;; =============================================================================
;; Contacts Projection
;; =============================================================================

(defmulti contacts*
  "Apply event to contacts read model"
  (fn [_state event] (:event/type event)))

(defmethod contacts* :crm/contact-created
  [state event]
  (assoc state (:contact-id event)
         {:id (:contact-id event)
          :type-id (:type-id event)
          :type-slug (:type-slug event)
          :display-name (:display-name event)
          :field-values (:field-values event)
          :status :active
          :tags (or (:tags event) #{})
          :created-at (str (:event/timestamp event))
          :updated-at (str (:event/timestamp event))}))

(defmethod contacts* :crm/contact-updated
  [state event]
  (let [contact-id (:contact-id event)]
    (when (contains? state contact-id)
      (-> state
          (update-in [contact-id :field-values] merge (:field-values event))
          (assoc-in [contact-id :updated-at] (str (:event/timestamp event)))))))

(defmethod contacts* :crm/contact-field-set
  [state event]
  (let [contact-id (:contact-id event)
        field-slug (:field-slug event)]
    (when (contains? state contact-id)
      (-> state
          (assoc-in [contact-id :field-values (keyword field-slug)] (:new-value event))
          (assoc-in [contact-id :updated-at] (str (:event/timestamp event)))))))

(defmethod contacts* :crm/contact-tagged
  [state event]
  (let [contact-id (:contact-id event)]
    (when (contains? state contact-id)
      (update-in state [contact-id :tags] (fnil conj #{}) (:tag event)))))

(defmethod contacts* :crm/contact-untagged
  [state event]
  (let [contact-id (:contact-id event)]
    (when (contains? state contact-id)
      (update-in state [contact-id :tags] disj (:tag event)))))

(defmethod contacts* :crm/contact-status-changed
  [state event]
  (let [contact-id (:contact-id event)]
    (when (contains? state contact-id)
      (-> state
          (assoc-in [contact-id :status] (:new-status event))
          (assoc-in [contact-id :updated-at] (str (:event/timestamp event)))))))

(defmethod contacts* :crm/contact-archived
  [state event]
  (let [contact-id (:contact-id event)]
    (when (contains? state contact-id)
      (-> state
          (assoc-in [contact-id :status] :archived)
          (assoc-in [contact-id :updated-at] (str (:event/timestamp event)))))))

(defmethod contacts* :crm/contact-restored
  [state event]
  (let [contact-id (:contact-id event)]
    (when (contains? state contact-id)
      (-> state
          (assoc-in [contact-id :status] :active)
          (assoc-in [contact-id :updated-at] (str (:event/timestamp event)))))))

(defmethod contacts* :crm/contact-merged
  [state event]
  (let [primary-id (:primary-contact-id event)
        secondary-id (:secondary-contact-id event)]
    (-> state
        ;; Update primary contact with merged data
        (update primary-id merge
                {:field-values (:merged-field-values event)
                 :tags (:merged-tags event)
                 :updated-at (str (:event/timestamp event))})
        ;; Mark secondary as merged
        (update secondary-id merge
                {:status :merged
                 :merged-into primary-id
                 :updated-at (str (:event/timestamp event))}))))

(defmethod contacts* :crm/contact-deleted
  [state event]
  (dissoc state (:contact-id event)))

(defmethod contacts* :default [state _] state)

(defn contacts
  "Build contacts read model from events"
  [initial-state events]
  (reduce contacts* initial-state events))

;; =============================================================================
;; Relationship Types Projection
;; =============================================================================

(defmulti relationship-types*
  "Apply event to relationship types read model"
  (fn [_state event] (:event/type event)))

(defmethod relationship-types* :crm/relationship-type-created
  [state event]
  (assoc state (:type-id event)
         {:id (:type-id event)
          :name (:name event)
          :slug (:slug event)
          :inverse-name (:inverse-name event)
          :source-type-slugs (or (:source-type-slugs event) #{})
          :target-type-slugs (or (:target-type-slugs event) #{})
          :allowed-properties (or (:allowed-properties event) [])
          :active true
          :created-at (str (:event/timestamp event))}))

(defmethod relationship-types* :crm/relationship-type-updated
  [state event]
  (update state (:type-id event) merge (:changes event)))

(defmethod relationship-types* :default [state _] state)

(defn relationship-types
  "Build relationship types read model from events"
  [initial-state events]
  (reduce relationship-types* initial-state events))

;; =============================================================================
;; Relationships Projection
;; =============================================================================

(defmulti relationships*
  "Apply event to relationships read model"
  (fn [_state event] (:event/type event)))

(defmethod relationships* :crm/relationship-created
  [state event]
  (assoc state (:relationship-id event)
         {:id (:relationship-id event)
          :type-id (:type-id event)
          :type-slug (:type-slug event)
          :source-contact-id (:source-contact-id event)
          :target-contact-id (:target-contact-id event)
          :properties (or (:properties event) {})
          :start-date (:start-date event)
          :is-primary (or (:is-primary event) false)
          :created-at (str (:event/timestamp event))
          :updated-at (str (:event/timestamp event))}))

(defmethod relationships* :crm/relationship-updated
  [state event]
  (-> state
      (update (:relationship-id event) merge (:changes event))
      (assoc-in [(:relationship-id event) :updated-at] (str (:event/timestamp event)))))

(defmethod relationships* :crm/relationship-ended
  [state event]
  (cond-> state
    true (assoc-in [(:relationship-id event) :end-date] (:end-date event))
    true (assoc-in [(:relationship-id event) :updated-at] (str (:event/timestamp event)))
    (:reason event) (assoc-in [(:relationship-id event) :reason] (:reason event))))

(defmethod relationships* :crm/relationship-deleted
  [state event]
  (dissoc state (:relationship-id event)))

(defmethod relationships* :crm/primary-relationship-set
  [state event]
  (let [rel-id (:relationship-id event)
        prev-id (:previous-primary-id event)]
    (cond-> state
      prev-id (assoc-in [prev-id :is-primary] false)
      true (assoc-in [rel-id :is-primary] true))))

(defmethod relationships* :default [state _] state)

(defn relationships
  "Build relationships read model from events"
  [initial-state events]
  (reduce relationships* initial-state events))

;; =============================================================================
;; Duplicates Projection
;; =============================================================================

(defmulti duplicates*
  "Apply event to duplicates read model"
  (fn [_state event] (:event/type event)))

(defmethod duplicates* :crm/duplicate-detected
  [state event]
  (assoc state (:duplicate-id event)
         {:id (:duplicate-id event)
          :contact-id (:contact-id event)
          :potential-duplicate-id (:potential-duplicate-id event)
          :match-type (:match-type event)
          :match-value (:match-value event)
          :confidence (:confidence event)
          :status :pending
          :detected-at (str (:event/timestamp event))}))

(defmethod duplicates* :crm/duplicate-resolved
  [state event]
  (-> state
      (assoc-in [(:duplicate-id event) :status] (:resolution event))
      (assoc-in [(:duplicate-id event) :resolved-at] (str (:event/timestamp event)))))

(defmethod duplicates* :default [state _] state)

(defn duplicates
  "Build duplicates read model from events"
  [initial-state events]
  (reduce duplicates* initial-state events))

;; =============================================================================
;; Attribution Projection
;; =============================================================================

(defmulti attributions*
  "Apply event to attributions read model"
  (fn [_state event] (:event/type event)))

(defmethod attributions* :crm/attribution-recorded
  [state event]
  (assoc state (:contact-id event) (:attribution event)))

(defmethod attributions* :crm/attribution-updated
  [state event]
  (assoc state (:contact-id event) (:new-attribution event)))

(defmethod attributions* :default [state _] state)

(defn attributions
  "Build attributions read model from events"
  [initial-state events]
  (reduce attributions* initial-state events))

;; =============================================================================
;; Communications Projection
;; =============================================================================

(defmulti communications*
  "Apply event to communications read model"
  (fn [_state event] (:event/type event)))

(defmethod communications* :crm/communication-logged
  [state event]
  (let [contact-id (:contact-id event)
        comm {:id (:communication-id event)
              :contact-id contact-id
              :communication-type (:communication-type event)
              :direction (:direction event)
              :sender (:sender event)
              :recipient (:recipient event)
              :logged-by-contact-id (:logged-by-contact-id event)
              :subject (:subject event)
              :content (:content event)
              :occurred-at (:occurred-at event)
              :logged-at (:logged-at event)
              :metadata (:metadata event)}]
    (update state contact-id (fnil conj []) comm)))

(defmethod communications* :default [state _] state)

(defn communications
  "Build communications read model from events"
  [initial-state events]
  (reduce communications* initial-state events))

;; =============================================================================
;; Query Helper Functions
;; =============================================================================

(defn get-contact-type
  "Get a single contact type by ID or slug"
  [event-store {:keys [type-id type-slug]}]
  (let [events (event-store/read event-store {:types contact-type-events})
        types (contact-types {} events)]
    (if type-id
      (get types type-id)
      (first (filter #(= type-slug (:slug %)) (vals types))))))

(defn get-contact-types-all
  "Get all contact types"
  [event-store & {:keys [include-inactive] :or {include-inactive false}}]
  (let [events (event-store/read event-store {:types contact-type-events})
        types (vals (contact-types {} events))]
    (if include-inactive
      types
      (filter :active types))))

(defn get-contacts-batch
  "Get multiple contacts by IDs in a single event-store read.
   Returns a map of contact-id -> contact."
  [event-store contact-ids]
  (when (seq contact-ids)
    (let [tags (set (map #(vector :contact %) contact-ids))
          events (event-store/read event-store
                                   {:types contact-events
                                    :tags tags})]
      (contacts {} events))))

(defn get-contacts-batch-with-attribution
  "Get multiple contacts with their attributions in a single event-store read.
   Returns a map of contact-id -> contact (with :attribution key when present)."
  [event-store contact-ids]
  (when (seq contact-ids)
    (let [tags (set (map #(vector :contact %) contact-ids))
          events (event-store/read event-store
                                   {:types contact-with-attribution-events
                                    :tags tags})
          contact-map (contacts {} events)
          attr-map (attributions {} events)]
      ;; Merge attributions into contacts
      (reduce-kv (fn [acc contact-id contact]
                   (let [attr (get attr-map contact-id)]
                     (assoc acc contact-id
                            (cond-> contact
                              attr (assoc :attribution attr)))))
                 {}
                 contact-map))))

(defn get-contact
  "Get a single contact by ID (with attribution if exists)"
  [event-store contact-id]
  ;; Single event-store call for both contact and attribution events
  (let [events (event-store/read event-store
                                 {:types contact-with-attribution-events
                                  :tags #{[:contact contact-id]}})
        contact (get (contacts {} events) contact-id)]
    (when contact
      (let [attr (get (attributions {} events) contact-id)]
        (cond-> contact
          attr (assoc :attribution attr))))))

(defn get-contacts-all
  "Get all contacts with optional filters"
  [event-store & {:keys [type-slug status tags]}]
  (let [events (event-store/read event-store {:types contact-events})
        all-contacts (vals (contacts {} events))]
    (cond->> all-contacts
      type-slug (filter #(= type-slug (:type-slug %)))
      status (filter #(= status (:status %)))
      tags (filter #(some tags (:tags %))))))

(defn get-contacts-by-type
  "Get all contacts of a specific type"
  [event-store type-slug]
  (get-contacts-all event-store :type-slug type-slug))

(defn get-relationship-type
  "Get a single relationship type by ID or slug"
  [event-store {:keys [type-id type-slug]}]
  (let [events (event-store/read event-store {:types relationship-type-events})
        types (relationship-types {} events)]
    (if type-id
      (get types type-id)
      (first (filter #(= type-slug (:slug %)) (vals types))))))

(defn get-relationship-types-all
  "Get all relationship types"
  [event-store & {:keys [include-inactive] :or {include-inactive false}}]
  (let [events (event-store/read event-store {:types relationship-type-events})
        types (vals (relationship-types {} events))]
    (if include-inactive
      types
      (filter :active types))))

(defn get-relationship
  "Get a single relationship by ID"
  [event-store relationship-id]
  (let [events (event-store/read event-store
                                 {:types relationship-events
                                  :tags #{[:relationship relationship-id]}})]
    (get (relationships {} events) relationship-id)))

(defn get-relationships-as-source
  "Get relationships where contact is the source.
   Uses granular [:relationship-source] tag for efficient queries on new events."
  [event-store contact-id]
  (let [events (event-store/read event-store
                                 {:types relationship-events
                                  :tags #{[:relationship-source contact-id]}})]
    (vals (relationships {} events))))

(defn get-relationships-as-target
  "Get relationships where contact is the target.
   Uses granular [:relationship-target] tag for efficient queries on new events."
  [event-store contact-id]
  (let [events (event-store/read event-store
                                 {:types relationship-events
                                  :tags #{[:relationship-target contact-id]}})]
    (vals (relationships {} events))))

(defn get-relationships-for-contact
  "Get all relationships for a contact (as source or target).
   Combines results from source and target queries."
  [event-store contact-id]
  ;; Two separate queries since tags set does AND, we need OR
  (let [as-source (get-relationships-as-source event-store contact-id)
        as-target (get-relationships-as-target event-store contact-id)]
    ;; Deduplicate by relationship id in case of overlap
    (vals (into {} (map (juxt :id identity)) (concat as-source as-target)))))

(defn get-relationships-between
  "Get relationships between two contacts of a specific type"
  [event-store source-id target-id type-slug]
  (->> (get-relationships-for-contact event-store source-id)
       (filter #(and (= type-slug (:type-slug %))
                     (= target-id (:target-contact-id %))))))

(defn get-primary-relationship-for-contact
  "Get primary relationship of a type for a contact"
  [event-store contact-id type-slug]
  (->> (get-relationships-for-contact event-store contact-id)
       (filter #(and (= type-slug (:type-slug %))
                     (:is-primary %)))
       first))

(defn get-duplicates-all
  "Get all duplicate candidates with optional status filter"
  [event-store & {:keys [status]}]
  (let [events (event-store/read event-store {:types duplicate-events})
        all-dups (vals (duplicates {} events))]
    (if status
      (filter #(= status (:status %)) all-dups)
      all-dups)))

(defn get-duplicate
  "Get a single duplicate record by ID"
  [event-store duplicate-id]
  (let [events (event-store/read event-store
                                 {:types duplicate-events
                                  :tags #{[:duplicate duplicate-id]}})]
    (get (duplicates {} events) duplicate-id)))

(defn get-communications-for-contact
  "Get all communications for a contact, sorted by occurred-at descending.
   Uses [:communication] tag for efficient queries."
  [event-store contact-id]
  (let [events (event-store/read event-store
                                 {:types communication-events
                                  :tags #{[:communication contact-id]}})
        comms (get (communications {} events) contact-id [])]
    (sort-by :occurred-at #(compare %2 %1) comms)))
