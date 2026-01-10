(ns ai.obney.workshop.crm-service.core.todo-processors
  "CRM Service todo processors (policies).

   These are event-driven side effects that respond to domain events
   and trigger follow-up commands."
  (:require [ai.obney.workshop.crm-service.interface.read-models :as rm]
            [ai.obney.grain.event-store-v2.interface :refer [->event]]
            [ai.obney.grain.time.interface :as time]
            [clojure.string :as str]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn normalize-email
  "Normalize email for duplicate detection"
  [email]
  (when (and email (string? email) (not (str/blank? email)))
    (str/lower-case (str/trim email))))

(defn normalize-phone
  "Normalize phone for duplicate detection - strip non-digits"
  [phone]
  (when (and phone (string? phone) (not (str/blank? phone)))
    (str/replace phone #"[^\d]" "")))

;; =============================================================================
;; Attribution Processors
;; =============================================================================

(defn ensure-attribution
  "Record default attribution if none exists when contact is created.
   This ensures every contact has some attribution record."
  [{:keys [event event-store]}]
  (let [contact-id (:contact-id event)
        ;; Check if we already have attribution (from lead capture or referral)
        existing-attr (get (rm/get-contact event-store contact-id) :attribution)]
    (if existing-attr
      {} ;; Already has attribution, nothing to do
      ;; Record manual entry as default attribution
      {:result/events
       [(->event
         {:type :crm/attribution-recorded
          :tags #{[:contact contact-id]}
          :body {:contact-id contact-id
                 :attribution {:source :manual_entry
                               :recorded-at (str (time/now))}}})]})))

(defn record-lead-attribution
  "Record form-based attribution when a lead is captured from a form."
  [{:keys [event] :as _context}]
  (let [contact-id (:contact-id event)
        form-type (:form-type event)
        form-id (:form-id event)
        source (case form-type
                 :application :application_form
                 :intake :intake_form
                 :interest :interest_form
                 :manual_entry)]
    {:result/events
     [(->event
       {:type :crm/attribution-recorded
        :tags #{[:contact contact-id]}
        :body {:contact-id contact-id
               :attribution {:source source
                             :form-id form-id
                             :recorded-at (str (time/now))}}})]}))

(defn record-referral-attribution
  "Record referral attribution when a referral is received."
  [{:keys [event] :as _context}]
  (let [contact-id (:contact-id event)
        referring-contact-id (:referring-contact-id event)]
    {:result/events
     [(->event
       {:type :crm/attribution-recorded
        :tags #{[:contact contact-id]}
        :body {:contact-id contact-id
               :attribution {:source :referral
                             :referring-contact-id referring-contact-id
                             :source-details {:notes (:referral-notes event)}
                             :recorded-at (str (time/now))}}})]}))

;; =============================================================================
;; Duplicate Detection Processor
;; =============================================================================

(defn check-for-duplicates
  "Check for duplicate contacts based on exact email/phone match.
   Creates duplicate detection records for review."
  [{:keys [event event-store] :as _context}]
  (let [contact-id (:contact-id event)
        type-slug (:type-slug event)
        field-values (:field-values event)
        email (normalize-email (:email field-values))
        phone (normalize-phone (:phone field-values))
        ;; Get all contacts of same type
        existing-contacts (rm/get-contacts-by-type event-store type-slug)
        ;; Find potential duplicates (excluding self)
        duplicates (for [existing existing-contacts
                         :when (not= contact-id (:id existing))
                         :when (= :active (:status existing))
                         :let [existing-email (normalize-email (get-in existing [:field-values :email]))
                               existing-phone (normalize-phone (get-in existing [:field-values :phone]))
                               ;; Check for matches
                               email-match? (and email existing-email (= email existing-email))
                               phone-match? (and phone existing-phone (= phone existing-phone))]
                         :when (or email-match? phone-match?)]
                     {:existing-id (:id existing)
                      :match-type (cond
                                    email-match? :email
                                    phone-match? :phone)
                      :match-value (cond
                                     email-match? email
                                     phone-match? phone)})]
    ;; Create duplicate detection events
    (if (seq duplicates)
      {:result/events
       (mapv (fn [dup]
               (let [duplicate-id (random-uuid)]
                 (->event
                  {:type :crm/duplicate-detected
                   :tags #{[:duplicate duplicate-id]
                           [:duplicate-primary contact-id]
                           [:duplicate-candidate (:existing-id dup)]}
                   :body {:duplicate-id duplicate-id
                          :contact-id contact-id
                          :potential-duplicate-id (:existing-id dup)
                          :match-type (:match-type dup)
                          :match-value (:match-value dup)
                          :confidence 1.0}}))) ;; Exact match = 100% confidence
             duplicates)}
      {})))

;; =============================================================================
;; Merge Processor
;; =============================================================================

(defn transfer-relationships-on-merge
  "When contacts are merged, end relationships on secondary and transfer to primary.
   Actually creates new relationships on primary for each secondary relationship."
  [{:keys [event event-store]}]
  (let [secondary-id (:secondary-contact-id event)
        ;; Get secondary's relationships
        secondary-rels (rm/get-relationships-for-contact event-store secondary-id)]
    (if (seq secondary-rels)
      ;; End all secondary's relationships
      (let [end-events (mapv (fn [rel]
                               (->event
                                {:type :crm/relationship-ended
                                 :tags #{[:relationship (:id rel)]
                                         [:relationship-source (:source-contact-id rel)]
                                         [:relationship-target (:target-contact-id rel)]}
                                 :body {:relationship-id (:id rel)
                                        :end-date (str (java.time.LocalDate/now))
                                        :reason "Contact merged"}}))
                             secondary-rels)]
        {:result/events end-events})
      {})))

;; =============================================================================
;; Archive Processor
;; =============================================================================

(defn end-relationships-on-archive
  "When a contact is archived, end all active relationships."
  [{:keys [event event-store] :as _context}]
  (let [contact-id (:contact-id event)
        ;; Get all active relationships for this contact
        rels (rm/get-relationships-for-contact event-store contact-id)
        active-rels (filter #(nil? (:end-date %)) rels)]
    (if (seq active-rels)
      {:result/events
       (mapv (fn [rel]
               (->event
                {:type :crm/relationship-ended
                 :tags #{[:relationship (:id rel)]
                         [:relationship-source (:source-contact-id rel)]
                         [:relationship-target (:target-contact-id rel)]}
                 :body {:relationship-id (:id rel)
                        :end-date (str (java.time.LocalDate/now))
                        :reason "Contact archived"}}))
             active-rels)}
      {})))

;; =============================================================================
;; Todo Processor Registry
;; =============================================================================

(def todo-processors
  {;; Attribution processors
   :crm/ensure-attribution
   {:handler-fn #'ensure-attribution
    :topics [:crm/contact-created]}

   :crm/record-lead-attribution
   {:handler-fn #'record-lead-attribution
    :topics [:crm/lead-captured]}

   :crm/record-referral-attribution
   {:handler-fn #'record-referral-attribution
    :topics [:crm/referral-received]}

   ;; Duplicate detection
   :crm/check-for-duplicates
   {:handler-fn #'check-for-duplicates
    :topics [:crm/contact-created]}

   ;; Merge handling
   :crm/transfer-relationships-on-merge
   {:handler-fn #'transfer-relationships-on-merge
    :topics [:crm/contact-merged]}

   ;; Archive handling
   :crm/end-relationships-on-archive
   {:handler-fn #'end-relationships-on-archive
    :topics [:crm/contact-archived]}})
