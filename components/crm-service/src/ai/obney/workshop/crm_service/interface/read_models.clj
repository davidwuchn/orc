(ns ai.obney.workshop.crm-service.interface.read-models
  (:require [ai.obney.workshop.crm-service.core.read-models :as core]))

(defn get-contact-types-all
  "Get all contact types"
  [event-store & {:keys [include-inactive] :or {include-inactive false}}]
  (core/get-contact-types-all event-store :include-inactive include-inactive))

(defn get-relationship-types-all
  "Get all relationship types"
  [event-store & {:keys [include-inactive] :or {include-inactive false}}]
  (core/get-relationship-types-all event-store :include-inactive include-inactive))

(defn get-contact
  "Get a single contact by ID"
  [event-store contact-id]
  (core/get-contact event-store contact-id))

(defn get-contacts-by-type
  "Get all contacts of a specific type"
  [event-store type-slug]
  (core/get-contacts-by-type event-store type-slug))

(defn get-relationships-for-contact
  "Get all relationships for a contact"
  [event-store contact-id]
  (core/get-relationships-for-contact event-store contact-id))

(defn get-contact-type
  "Get a single contact type by ID or slug"
  [event-store opts]
  (core/get-contact-type event-store opts))

(defn get-relationship-type
  "Get a single relationship type by ID or slug"
  [event-store opts]
  (core/get-relationship-type event-store opts))

(defn get-duplicates-all
  "Get all duplicate detection records"
  [event-store & {:keys [status]}]
  (core/get-duplicates-all event-store :status status))

(defn get-communications-for-contact
  "Get all communications for a contact, sorted by occurred-at descending"
  [event-store contact-id]
  (core/get-communications-for-contact event-store contact-id))