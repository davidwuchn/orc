(ns ai.obney.workshop.crm-service.core.queries
  "CRM Service query handlers.

   All queries return {:query/result ...} on success."
  (:require [ai.obney.workshop.crm-service.core.read-models :as rm]
            [ai.obney.workshop.crypto.interface :as crypto]
            [ai.obney.grain.query-processor.interface :refer [defquery]]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Encrypted Field Helpers
;; =============================================================================

(defn- get-encrypted-field-slugs
  "Get set of field slugs that are encrypted for a contact type"
  [contact-type]
  (->> (:field-definitions contact-type)
       (filter #(= :encrypted (:data-type %)))
       (map :slug)
       (map keyword)
       set))

(defn- process-encrypted-fields
  "Process encrypted fields in a contact's field-values.
   If decrypt-fields is true, decrypt them using crypto-provider.
   Otherwise, omit them from the response."
  [contact contact-type crypto-provider decrypt-fields]
  (let [encrypted-slugs (get-encrypted-field-slugs contact-type)]
    (if (empty? encrypted-slugs)
      contact
      (if decrypt-fields
        ;; Decrypt encrypted fields
        (update contact :field-values
                (fn [fv]
                  (reduce (fn [acc slug]
                            (if-let [encrypted-value (get acc slug)]
                              (assoc acc slug (crypto/decrypt crypto-provider encrypted-value))
                              acc))
                          fv
                          encrypted-slugs)))
        ;; Omit encrypted fields
        (update contact :field-values
                (fn [fv]
                  (apply dissoc fv encrypted-slugs)))))))

(defn- get-contact-types-by-id
  "Get all contact types as a map of type-id -> contact-type.
   Used for batch lookups to avoid N+1 queries."
  [event-store]
  (let [types (rm/get-contact-types-all event-store :include-inactive true)]
    (into {} (map (juxt :id identity)) types)))

(defn- process-contacts-batch
  "Process a batch of contacts with encrypted fields.
   Uses pre-loaded contact types map to avoid N+1 queries."
  [contacts types-by-id crypto-provider decrypt-fields]
  (mapv (fn [contact]
          (if-let [contact-type (get types-by-id (:type-id contact))]
            (process-encrypted-fields contact contact-type crypto-provider decrypt-fields)
            contact))
        contacts))

;; =============================================================================
;; Contact Type Queries
;; =============================================================================

(defquery :crm list-contact-types
  "List all contact types, optionally including inactive ones."
  [{{:keys [include-inactive]} :query
    :keys [event-store]}]
  (let [types (rm/get-contact-types-all event-store :include-inactive (boolean include-inactive))]
    {:query/result {:contact-types (vec types)}}))

(defquery :crm get-contact-type
  "Get a single contact type by ID or slug."
  [{{:keys [type-id type-slug]} :query
    :keys [event-store]}]
  (let [contact-type (rm/get-contact-type event-store {:type-id type-id
                                                        :type-slug type-slug})]
    (if contact-type
      {:query/result {:contact-type contact-type}}
      {::anom/category ::anom/not-found
       ::anom/message "Contact type not found"})))

;; =============================================================================
;; Contact Queries
;; =============================================================================

(defquery :crm list-contacts
  "List contacts with optional filters."
  [{{:keys [type-slug status tags limit offset decrypt-fields]} :query
    :keys [event-store crypto-provider]}]
  (let [;; Pre-load all contact types (1 query instead of N)
        types-by-id (get-contact-types-by-id event-store)
        all-contacts (cond->> (rm/get-contacts-all event-store)
                       type-slug (filter #(= type-slug (:type-slug %)))
                       status (filter #(= status (:status %)))
                       tags (filter #(some tags (:tags %))))
        all-contacts-vec (vec all-contacts)
        ;; Apply pagination
        paginated (cond->> all-contacts-vec
                    offset (drop offset)
                    limit (take limit))
        ;; Process encrypted fields using pre-loaded types
        processed (process-contacts-batch paginated types-by-id crypto-provider decrypt-fields)]
    {:query/result {:contacts processed
                    :total (count all-contacts-vec)}}))

(defquery :crm get-contact
  "Get a single contact with full details."
  [{{:keys [contact-id decrypt-fields]} :query
    :keys [event-store crypto-provider]}]
  (let [contact (rm/get-contact event-store contact-id)]
    (if contact
      ;; Also get relationships for this contact
      (let [relationships (rm/get-relationships-for-contact event-store contact-id)
            contact-type (rm/get-contact-type event-store {:type-id (:type-id contact)})
            processed-contact (process-encrypted-fields contact contact-type crypto-provider decrypt-fields)]
        {:query/result {:contact (assoc processed-contact
                                        :relationships (vec relationships)
                                        :contact-type contact-type)}})
      {::anom/category ::anom/not-found
       ::anom/message "Contact not found"})))

(defquery :crm get-contact-relationships
  "Get all relationships for a contact, optionally filtered by type."
  [{{:keys [contact-id type-slug]} :query
    :keys [event-store]}]
  (let [contact (rm/get-contact event-store contact-id)]
    (if-not contact
      {::anom/category ::anom/not-found
       ::anom/message "Contact not found"}
      (let [all-rels (rm/get-relationships-for-contact event-store contact-id)
            filtered (if type-slug
                       (filter #(= type-slug (:type-slug %)) all-rels)
                       all-rels)]
        {:query/result {:relationships (vec filtered)}}))))

(defquery :crm get-contact-graph
  "Get contact relationship graph via BFS traversal."
  [{{:keys [contact-id depth]} :query
    :keys [event-store]}]
  (let [max-depth (or depth 2)
        contact (rm/get-contact event-store contact-id)]
    (if-not contact
      {::anom/category ::anom/not-found
       ::anom/message "Contact not found"}
      ;; BFS traversal
      (loop [queue [[contact-id 0]]
             visited #{contact-id}
             nodes [{:id contact-id
                     :contact contact
                     :depth 0}]
             edges []]
        (if (empty? queue)
          {:query/result {:nodes nodes
                          :edges edges}}
          (let [[current-id current-depth] (first queue)
                remaining (rest queue)]
            (if (>= current-depth max-depth)
              (recur remaining visited nodes edges)
              (let [rels (rm/get-relationships-for-contact event-store current-id)
                    ;; Get connected contact IDs
                    connected-ids (->> rels
                                       (mapcat (fn [r]
                                                 [(when (= current-id (:source-contact-id r))
                                                    (:target-contact-id r))
                                                  (when (= current-id (:target-contact-id r))
                                                    (:source-contact-id r))]))
                                       (remove nil?)
                                       (remove visited)
                                       distinct)
                    ;; Batch load all connected contacts (1 query instead of N)
                    connected-ids-vec (vec connected-ids)
                    contacts-map (when (seq connected-ids-vec)
                                   (rm/get-contacts-batch event-store connected-ids-vec))
                    ;; Build new nodes from batch-loaded contacts
                    new-nodes (for [cid connected-ids-vec
                                    :let [c (get contacts-map cid)]
                                    :when c]
                                {:id cid
                                 :contact c
                                 :depth (inc current-depth)})
                    ;; Get edges from current node
                    new-edges (for [r rels]
                                {:id (:id r)
                                 :relationship r
                                 :source-id (:source-contact-id r)
                                 :target-id (:target-contact-id r)})]
                (recur (into (vec remaining) (map #(vector (:id %) (inc current-depth)) new-nodes))
                       (into visited connected-ids)
                       (into nodes new-nodes)
                       (into edges new-edges))))))))))

;; =============================================================================
;; Relationship Type Queries
;; =============================================================================

(defquery :crm list-relationship-types
  "List all relationship types."
  [{{:keys [include-inactive]} :query
    :keys [event-store]}]
  (let [types (rm/get-relationship-types-all event-store :include-inactive (boolean include-inactive))]
    {:query/result {:relationship-types (vec types)}}))

(defquery :crm get-relationship-type
  "Get a single relationship type by ID or slug."
  [{{:keys [type-id type-slug]} :query
    :keys [event-store]}]
  (let [rel-type (rm/get-relationship-type event-store {:type-id type-id
                                                         :type-slug type-slug})]
    (if rel-type
      {:query/result {:relationship-type rel-type}}
      {::anom/category ::anom/not-found
       ::anom/message "Relationship type not found"})))

;; =============================================================================
;; Duplicate Queries
;; =============================================================================

(defquery :crm list-duplicate-candidates
  "List duplicate candidates, optionally filtered by status."
  [{{:keys [status]} :query
    :keys [event-store]}]
  (let [dups (rm/get-duplicates-all event-store :status status)]
    {:query/result {:duplicates (vec dups)}}))

;; =============================================================================
;; Cross-Type Queries
;; =============================================================================

(defquery :crm search-contacts
  "Search contacts across all types by multiple field criteria (AND logic)."
  [{{:keys [filters type-slugs status limit offset decrypt-fields]} :query
    :keys [event-store crypto-provider]}]
  (let [;; Pre-load all contact types (1 query instead of N)
        types-by-id (get-contact-types-by-id event-store)
        all-contacts (rm/get-contacts-all event-store)
        ;; Apply type filter
        type-filtered (if type-slugs
                        (filter #(type-slugs (:type-slug %)) all-contacts)
                        all-contacts)
        ;; Apply status filter
        status-filtered (if status
                          (filter #(= status (:status %)) type-filtered)
                          type-filtered)
        ;; Apply field filters (AND logic - all must match)
        matches (filter (fn [contact]
                          (every? (fn [[field-slug value]]
                                    (= value (get-in contact [:field-values field-slug])))
                                  filters))
                        status-filtered)
        matches-vec (vec matches)
        ;; Pagination
        paginated (cond->> matches-vec
                    offset (drop offset)
                    limit (take limit))
        ;; Process encrypted fields using pre-loaded types
        processed (process-contacts-batch paginated types-by-id crypto-provider decrypt-fields)]
    {:query/result {:contacts processed
                    :total (count matches-vec)}}))

(defquery :crm list-all-contacts
  "List all contacts across all types with optional filters."
  [{{:keys [type-slugs status limit offset decrypt-fields]} :query
    :keys [event-store crypto-provider]}]
  (let [;; Pre-load all contact types (1 query instead of N)
        types-by-id (get-contact-types-by-id event-store)
        all-contacts (rm/get-contacts-all event-store)
        filtered (cond->> all-contacts
                   type-slugs (filter #(type-slugs (:type-slug %)))
                   status (filter #(= status (:status %))))
        by-type (frequencies (map :type-slug filtered))
        filtered-vec (vec filtered)
        paginated (cond->> filtered-vec
                    offset (drop offset)
                    limit (take limit))
        ;; Process encrypted fields using pre-loaded types
        processed (process-contacts-batch paginated types-by-id crypto-provider decrypt-fields)]
    {:query/result {:contacts processed
                    :total (count filtered-vec)
                    :by-type by-type}}))

;; =============================================================================
;; Communication Queries
;; =============================================================================

(defquery :crm get-contact-communications
  "Get communications for a contact."
  [{{:keys [contact-id limit offset]} :query
    :keys [event-store]}]
  (let [contact (rm/get-contact event-store contact-id)]
    (if-not contact
      {::anom/category ::anom/not-found
       ::anom/message "Contact not found"}
      (let [comms (rm/get-communications-for-contact event-store contact-id)
            ;; Batch load all staff contacts (1 query instead of N)
            staff-ids (->> comms
                           (map :logged-by-contact-id)
                           (remove nil?)
                           distinct
                           vec)
            staff-map (when (seq staff-ids)
                        (rm/get-contacts-batch event-store staff-ids))
            ;; Enrich communications with staff contact name
            enriched (mapv (fn [comm]
                            (if-let [logged-by-id (:logged-by-contact-id comm)]
                              (if-let [staff-contact (get staff-map logged-by-id)]
                                (assoc comm :logged-by-name (or (get-in staff-contact [:field-values :name])
                                                                (:display-name staff-contact)))
                                comm)
                              comm))
                          comms)
            paginated (cond->> enriched
                        offset (drop offset)
                        limit (take limit))]
        {:query/result {:communications (vec paginated)
                        :total (count comms)}}))))

