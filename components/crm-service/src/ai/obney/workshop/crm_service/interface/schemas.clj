(ns ai.obney.workshop.crm-service.interface.schemas
  "CRM Service schemas for contacts, relationships, and contact types.

   This is a flexible, type-agnostic CRM system that supports any kind of contact
   (students, guardians, mentors, etc.) with configurable fields per contact type."
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

;; =============================================================================
;; Register Custom Malli Types for CRM Field Data Types
;; =============================================================================

(defschemas field-types
  {:email [:re #"^[^@\s]+@[^@\s]+\.[^@\s]+$"]
   :phone [:re #"^\d{10,15}$"]
   :encrypted [:map
               [:ciphertext :string]
               [:encrypted-key :string]
               [:iv :string]]})

;; =============================================================================
;; Domain Schemas
;; =============================================================================

;; Slug validation: lowercase, starts with letter, letters/numbers/hyphens
(def slug-regex #"^[a-z][a-z0-9-]*$")

(def field-type
  [:enum :text :long_text :number :date :boolean :email :phone :url :single_select :multi_select :data :keyword_enum :encrypted])

;; =============================================================================
;; Sub-Schema Definitions (for :data field type)
;; =============================================================================

(def sub-schema-field-type
  "Supported field types within a sub-schema."
  [:enum :string :number :boolean :date :keyword :single-select :multi-select])

(def sub-schema-field
  "Definition of a single field within a sub-schema.
   Includes validation rules and UI rendering hints."
  [:map
   [:key :keyword]                                        ;; Field identifier
   [:type sub-schema-field-type]                          ;; Data type for validation
   [:required {:optional true} :boolean]                  ;; Validation: required field
   ;; UI rendering hints
   [:label {:optional true} :string]                      ;; Display label
   [:placeholder {:optional true} :string]                ;; Input placeholder
   [:description {:optional true} :string]                ;; Help text
   [:input-type {:optional true} :string]                 ;; HTML input type (text, textarea, email, tel, etc.)
   [:rows {:optional true} :int]                          ;; Textarea rows
   ;; Options for select types
   [:options {:optional true} [:vector [:or :string :keyword]]]
   [:enum-key {:optional true} :keyword]])                ;; Reference to external enum schema

(def sub-schema-item
  "Schema for items within a vector or set container."
  [:map
   [:type [:enum :map :string :number :keyword]]
   [:fields {:optional true} [:vector sub-schema-field]]])

(def sub-schema-definition
  "Complete sub-schema definition for :data field types.
   Defines container type, item structure, and UI configuration."
  [:map
   [:type [:enum :vector :map :set]]                      ;; Container type
   [:item-schema {:optional true} sub-schema-item]        ;; For :vector and :set types
   [:fields {:optional true} [:vector sub-schema-field]]  ;; For :map type directly
   ;; UI configuration for collection types
   [:min-items {:optional true} :int]
   [:max-items {:optional true} :int]
   [:add-button-text {:optional true} :string]
   [:empty-message {:optional true} :string]
   [:item-label-key {:optional true} :keyword]])          ;; Key to use for item display label

;; =============================================================================
;; Field Definition
;; =============================================================================

(def field-definition
  [:map
   [:name :string]
   [:slug [:re slug-regex]]
   [:data-type field-type]
   [:required {:optional true} :boolean]
   [:unique {:optional true} :boolean]
   [:ui-section {:optional true} :string]
   [:options {:optional true} [:vector [:or :string :keyword]]]
   [:sub-schema {:optional true} sub-schema-definition]]) ;; For :data type fields

(def contact-status
  [:enum :active :inactive :archived :merged])

(def attribution-source
  [:enum :application_form :intake_form :interest_form :referral :manual_entry :data_import])

(def attribution
  [:map
   [:source attribution-source]
   [:source-details {:optional true} :any]
   [:referring-contact-id {:optional true} :uuid]
   [:form-id {:optional true} :uuid]
   [:import-id {:optional true} :uuid]
   [:recorded-at :string]])

;; =============================================================================
;; Command Schemas
;; =============================================================================

(defschemas commands
  {;; -------------------------------------------------------------------------
   ;; Contact Type Commands
   ;; -------------------------------------------------------------------------

   :crm/create-contact-type
   [:map
    [:slug [:re slug-regex]]
    [:name :string]
    [:description {:optional true} :string]
    [:field-definitions [:vector field-definition]]]

   :crm/update-contact-type
   [:map
    [:type-id :uuid]
    [:name {:optional true} :string]
    [:description {:optional true} :string]]

   :crm/deactivate-contact-type
   [:map
    [:type-id :uuid]]

   :crm/add-field-definition
   [:map
    [:type-id :uuid]
    [:field field-definition]
    [:position {:optional true} :int]]

   :crm/update-field-definition
   [:map
    [:type-id :uuid]
    [:field-slug :string]
    [:updates [:map-of :keyword :any]]]

   :crm/remove-field-definition
   [:map
    [:type-id :uuid]
    [:field-slug :string]]

   ;; -------------------------------------------------------------------------
   ;; Contact Commands
   ;; -------------------------------------------------------------------------

   :crm/create-contact
   [:map
    [:type-slug :string]
    [:field-values [:map-of :keyword :any]]
    [:contact-id {:optional true} :uuid]  ;; Optional: use provided ID instead of generating
    [:tags {:optional true} [:set :string]]
    [:attribution {:optional true} attribution]]

   :crm/update-contact
   [:map
    [:contact-id :uuid]
    [:field-values [:map-of :keyword :any]]]

   :crm/set-contact-field
   [:map
    [:contact-id :uuid]
    [:field-slug :string]
    [:value :any]]

   :crm/tag-contact
   [:map
    [:contact-id :uuid]
    [:tag :string]]

   :crm/untag-contact
   [:map
    [:contact-id :uuid]
    [:tag :string]]

   :crm/change-contact-status
   [:map
    [:contact-id :uuid]
    [:new-status contact-status]
    [:reason {:optional true} :string]]

   :crm/archive-contact
   [:map
    [:contact-id :uuid]
    [:reason {:optional true} :string]]

   :crm/restore-contact
   [:map
    [:contact-id :uuid]]

   :crm/merge-contacts
   [:map
    [:primary-contact-id :uuid]
    [:secondary-contact-id :uuid]
    [:field-resolutions {:optional true} [:map-of :keyword [:enum :primary :secondary :combined]]]]

   :crm/delete-contact
   [:map
    [:contact-id :uuid]]

   ;; -------------------------------------------------------------------------
   ;; Entry Commands
   ;; -------------------------------------------------------------------------

   :crm/capture-lead-from-form
   [:map
    [:form-type [:enum :application :intake :interest]]
    [:form-id :uuid]
    [:contact-type-slug :string]
    [:field-values [:map-of :keyword :any]]]

   :crm/receive-referral
   [:map
    [:referring-contact-id :uuid]
    [:contact-type-slug :string]
    [:field-values [:map-of :keyword :any]]
    [:referral-notes {:optional true} :string]]

   :crm/import-contacts
   [:map
    [:import-id :uuid]
    [:contact-type-slug :string]
    [:contacts [:vector [:map-of :keyword :any]]]
    [:source-description :string]]

   :crm/resolve-duplicate
   [:map
    [:duplicate-id :uuid]
    [:resolution [:enum :merge :keep_both :dismiss]]
    [:merge-config {:optional true} [:map-of :keyword :any]]]

   ;; -------------------------------------------------------------------------
   ;; Relationship Type Commands
   ;; -------------------------------------------------------------------------

   :crm/create-relationship-type
   [:map
    [:name :string]
    [:slug [:re slug-regex]]
    [:inverse-name :string]
    [:source-type-slugs {:optional true} [:set :string]]
    [:target-type-slugs {:optional true} [:set :string]]
    [:allowed-properties {:optional true} [:vector field-definition]]]

   :crm/update-relationship-type
   [:map
    [:type-id :uuid]
    [:name {:optional true} :string]
    [:inverse-name {:optional true} :string]
    [:source-type-slugs {:optional true} [:set :string]]
    [:target-type-slugs {:optional true} [:set :string]]]

   ;; -------------------------------------------------------------------------
   ;; Relationship Commands
   ;; -------------------------------------------------------------------------

   :crm/create-relationship
   [:map
    [:type-slug :string]
    [:source-contact-id :uuid]
    [:target-contact-id :uuid]
    [:properties {:optional true} [:map-of :keyword :any]]
    [:start-date {:optional true} :string]
    [:is-primary {:optional true} :boolean]]

   :crm/update-relationship
   [:map
    [:relationship-id :uuid]
    [:properties {:optional true} [:map-of :keyword :any]]
    [:start-date {:optional true} :string]]

   :crm/end-relationship
   [:map
    [:relationship-id :uuid]
    [:end-date {:optional true} :string]
    [:reason {:optional true} :string]]

   :crm/delete-relationship
   [:map
    [:relationship-id :uuid]]

   :crm/set-primary-relationship
   [:map
    [:relationship-id :uuid]
    [:contact-id :uuid]]

   ;; -------------------------------------------------------------------------
   ;; Communication Commands
   ;; -------------------------------------------------------------------------

   :crm/log-communication
   [:map
    [:contact-id :uuid]
    [:communication-type [:enum :email :sms]]
    [:direction [:enum :inbound :outbound]]
    [:sender [:or
              [:map [:contact-id :uuid]]
              [:map
               [:name {:optional true} :string]
               [:email {:optional true} :string]
               [:phone {:optional true} :string]]]]
    [:recipient [:or
                 [:map [:contact-id :uuid]]
                 [:map
                  [:name {:optional true} :string]
                  [:email {:optional true} :string]
                  [:phone {:optional true} :string]]]]
    [:logged-by-contact-id {:optional true} :uuid]
    [:subject {:optional true} [:string {:max 500}]]
    [:content :string]
    [:occurred-at {:optional true} :string]
    [:metadata {:optional true} :any]]})

;; =============================================================================
;; Event Schemas
;; =============================================================================

(defschemas events
  {;; -------------------------------------------------------------------------
   ;; Contact Type Events
   ;; -------------------------------------------------------------------------

   :crm/contact-type-created
   [:map
    [:type-id :uuid]
    [:slug :string]
    [:name :string]
    [:description {:optional true} :string]
    [:field-definitions [:vector field-definition]]]

   :crm/contact-type-updated
   [:map
    [:type-id :uuid]
    [:changes [:map-of :keyword :any]]]

   :crm/contact-type-deactivated
   [:map
    [:type-id :uuid]]

   :crm/field-definition-added
   [:map
    [:type-id :uuid]
    [:field field-definition]
    [:position :int]]

   :crm/field-definition-updated
   [:map
    [:type-id :uuid]
    [:field-slug :string]
    [:updates [:map-of :keyword :any]]]

   :crm/field-definition-removed
   [:map
    [:type-id :uuid]
    [:field-slug :string]]

   ;; -------------------------------------------------------------------------
   ;; Contact Events
   ;; -------------------------------------------------------------------------

   :crm/contact-created
   [:map
    [:contact-id :uuid]
    [:type-id :uuid]
    [:type-slug :string]
    [:display-name :string]
    [:field-values [:map-of :keyword :any]]
    [:tags [:set :string]]]

   :crm/contact-updated
   [:map
    [:contact-id :uuid]
    [:field-values [:map-of :keyword :any]]]

   :crm/contact-field-set
   [:map
    [:contact-id :uuid]
    [:field-slug :string]
    [:old-value {:optional true} :any]
    [:new-value :any]]

   :crm/contact-tagged
   [:map
    [:contact-id :uuid]
    [:tag :string]]

   :crm/contact-untagged
   [:map
    [:contact-id :uuid]
    [:tag :string]]

   :crm/contact-status-changed
   [:map
    [:contact-id :uuid]
    [:old-status contact-status]
    [:new-status contact-status]
    [:reason {:optional true} :string]]

   :crm/contact-archived
   [:map
    [:contact-id :uuid]
    [:reason {:optional true} :string]]

   :crm/contact-restored
   [:map
    [:contact-id :uuid]]

   :crm/contact-merged
   [:map
    [:primary-contact-id :uuid]
    [:secondary-contact-id :uuid]
    [:merged-field-values [:map-of :keyword :any]]
    [:merged-tags [:set :string]]]

   :crm/contact-deleted
   [:map
    [:contact-id :uuid]]

   ;; -------------------------------------------------------------------------
   ;; Entry Events
   ;; -------------------------------------------------------------------------

   :crm/lead-captured
   [:map
    [:contact-id :uuid]
    [:form-type [:enum :application :intake :interest]]
    [:form-id :uuid]
    [:contact-type-slug :string]
    [:field-values [:map-of :keyword :any]]]

   :crm/referral-received
   [:map
    [:contact-id :uuid]
    [:referring-contact-id :uuid]
    [:contact-type-slug :string]
    [:field-values [:map-of :keyword :any]]
    [:referral-notes {:optional true} :string]]

   :crm/contacts-imported
   [:map
    [:import-id :uuid]
    [:contact-type-slug :string]
    [:contact-ids [:vector :uuid]]
    [:source-description :string]
    [:import-count :int]]

   :crm/duplicate-detected
   [:map
    [:duplicate-id :uuid]
    [:contact-id :uuid]
    [:potential-duplicate-id :uuid]
    [:match-type [:enum :email :phone]]
    [:match-value :string]
    [:confidence :double]]

   :crm/duplicate-resolved
   [:map
    [:duplicate-id :uuid]
    [:resolution [:enum :merge :keep_both :dismiss]]]

   :crm/attribution-recorded
   [:map
    [:contact-id :uuid]
    [:attribution attribution]]

   :crm/attribution-updated
   [:map
    [:contact-id :uuid]
    [:old-attribution attribution]
    [:new-attribution attribution]]

   :crm/interaction-logged
   [:map
    [:contact-id :uuid]
    [:interaction-type :string]
    [:description :string]
    [:metadata {:optional true} :any]]

   :crm/communication-logged
   [:map
    [:contact-id :uuid]
    [:communication-id :uuid]
    [:communication-type [:enum :email :sms]]
    [:direction [:enum :inbound :outbound]]
    [:sender :any]
    [:recipient :any]
    [:logged-by-contact-id :uuid]
    [:subject {:optional true} [:maybe :string]]
    [:content :string]
    [:occurred-at :string]
    [:logged-at :string]
    [:metadata {:optional true} :any]]

   ;; -------------------------------------------------------------------------
   ;; Relationship Type Events
   ;; -------------------------------------------------------------------------

   :crm/relationship-type-created
   [:map
    [:type-id :uuid]
    [:name :string]
    [:slug :string]
    [:inverse-name :string]
    [:source-type-slugs {:optional true} [:set :string]]
    [:target-type-slugs {:optional true} [:set :string]]
    [:allowed-properties {:optional true} [:vector field-definition]]]

   :crm/relationship-type-updated
   [:map
    [:type-id :uuid]
    [:changes [:map-of :keyword :any]]]

   ;; -------------------------------------------------------------------------
   ;; Relationship Events
   ;; -------------------------------------------------------------------------

   :crm/relationship-created
   [:map
    [:relationship-id :uuid]
    [:type-id :uuid]
    [:type-slug :string]
    [:source-contact-id :uuid]
    [:target-contact-id :uuid]
    [:properties {:optional true} [:map-of :keyword :any]]
    [:start-date :string]
    [:is-primary :boolean]]

   :crm/relationship-updated
   [:map
    [:relationship-id :uuid]
    [:changes [:map-of :keyword :any]]]

   :crm/relationship-ended
   [:map
    [:relationship-id :uuid]
    [:end-date :string]
    [:reason {:optional true} :string]]

   :crm/relationship-deleted
   [:map
    [:relationship-id :uuid]]

   :crm/primary-relationship-set
   [:map
    [:relationship-id :uuid]
    [:contact-id :uuid]
    [:type-id :uuid]
    [:previous-primary-id {:optional true} :uuid]]})

;; =============================================================================
;; Query Schemas
;; =============================================================================

(defschemas queries
  {:crm/list-contacts
   [:map
    [:type-slug {:optional true} :string]
    [:status {:optional true} contact-status]
    [:tags {:optional true} [:set :string]]
    [:limit {:optional true} :int]
    [:offset {:optional true} :int]
    [:decrypt-fields {:optional true} :boolean]]

   :crm/get-contact
   [:map
    [:contact-id :uuid]
    [:decrypt-fields {:optional true} :boolean]]

   :crm/get-contact-relationships
   [:map
    [:contact-id :uuid]
    [:type-slug {:optional true} :string]]

   :crm/get-contact-graph
   [:map
    [:contact-id :uuid]
    [:depth {:optional true} :int]]

   :crm/list-duplicate-candidates
   [:map
    [:status {:optional true} [:enum :pending :resolved :dismissed]]]

   :crm/list-contact-types
   [:map
    [:include-inactive {:optional true} :boolean]]

   :crm/get-contact-type
   [:map
    [:type-id {:optional true} :uuid]
    [:type-slug {:optional true} :string]]

   :crm/list-relationship-types
   [:map
    [:include-inactive {:optional true} :boolean]]

   :crm/get-relationship-type
   [:map
    [:type-id {:optional true} :uuid]
    [:type-slug {:optional true} :string]]

   ;; Cross-type queries
   :crm/search-contacts
   [:map
    [:filters [:map-of :keyword :any]]
    [:type-slugs {:optional true} [:set :string]]
    [:status {:optional true} contact-status]
    [:limit {:optional true} :int]
    [:offset {:optional true} :int]
    [:decrypt-fields {:optional true} :boolean]]

   :crm/list-all-contacts
   [:map
    [:type-slugs {:optional true} [:set :string]]
    [:status {:optional true} contact-status]
    [:limit {:optional true} :int]
    [:offset {:optional true} :int]
    [:decrypt-fields {:optional true} :boolean]]

   :crm/get-contact-communications
   [:map
    [:contact-id :uuid]
    [:limit {:optional true} :int]
    [:offset {:optional true} :int]]})
