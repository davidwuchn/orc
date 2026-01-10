(ns ai.obney.workshop.crm-service.core.commands
  "CRM Service command handlers.

   All commands follow the Grain pattern:
   - Take context including event-store and command data
   - Return {:command-result/events [...]} on success
   - Return cognitect anomaly on failure
   - Last write wins (no optimistic concurrency)"
  (:require [ai.obney.workshop.crm-service.core.read-models :as rm]
            [ai.obney.grain.event-store-v2.interface :as es :refer [->event]]
            [ai.obney.grain.command-processor.interface :refer [defcommand]]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]
            [clojure.string :as str]
            [clojure.set :as set]
            [malli.core :as m]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn normalize-email
  "Normalize email for duplicate detection"
  [email]
  (when email
    (str/lower-case (str/trim email))))

(defn normalize-phone
  "Normalize phone for duplicate detection - strip non-digits"
  [phone]
  (when phone
    (str/replace phone #"[^\d]" "")))

(defn derive-display-name
  "Derive display name from field values.
   Checks for: name, full-name, first-name + last-name, email, company-name"
  [field-values]
  (or (:name field-values)
      (:full-name field-values)
      (:full_name field-values)
      (when (and (or (:first-name field-values) (:first_name field-values))
                 (or (:last-name field-values) (:last_name field-values)))
        (str (or (:first-name field-values) (:first_name field-values))
             " "
             (or (:last-name field-values) (:last_name field-values))))
      (or (:first-name field-values) (:first_name field-values))
      (:email field-values)
      (:company-name field-values)
      (:company_name field-values)
      "Unnamed Contact"))

(defn validate-required-fields
  "Validate that required fields have non-empty values"
  [field-definitions field-values]
  (let [required-slugs (->> field-definitions
                            (filter :required)
                            (map :slug)
                            (map keyword))]
    (every? #(not (str/blank? (str (get field-values %)))) required-slugs)))

(defn check-unique-field-violation
  "Check if a field value violates uniqueness constraint.
   Returns the existing contact with same value, or nil if no violation."
  [event-store type-slug field-slug value exclude-contact-id]
  (when value
    (let [contacts (rm/get-contacts-by-type event-store type-slug)]
      (->> contacts
           (filter #(and (not= :archived (:status %))
                         (not= exclude-contact-id (:id %))
                         (= value (get-in % [:field-values (keyword field-slug)]))))
           first))))

;; =============================================================================
;; Field Type Validation
;; =============================================================================

(def ^:private email-regex #"^[^@\s]+@[^@\s]+\.[^@\s]+$")
(def ^:private phone-regex #"^\d{10}$")
(def ^:private date-regex #"^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$")
(def ^:private url-regex #"^https?://[^\s]+$")

;; =============================================================================
;; Sub-Schema Validation (for :data field types)
;; =============================================================================

(defn sub-schema-field->malli
  "Convert a sub-schema field definition to a Malli schema.
   Handles field types: :string, :number, :boolean, :date, :keyword, :single-select, :multi-select"
  [{:keys [type required options]}]
  (let [base-schema (case type
                      :string :string
                      :number [:fn number?]  ;; Malli uses :fn for predicates, not :number
                      :boolean :boolean
                      :date [:re date-regex]
                      :keyword :keyword
                      :single-select (if (seq options)
                                       (into [:enum] options)
                                       :string)
                      :multi-select (if (seq options)
                                      [:set (into [:enum] options)]
                                      [:set :string])
                      :any)]
    (if required
      base-schema
      [:maybe base-schema])))

(defn sub-schema->malli
  "Convert a sub-schema definition to a Malli schema.
   Supports container types: :vector, :map, :set"
  [{:keys [type item-schema fields min-items max-items]}]
  (case type
    :vector
    (let [item-malli (case (:type item-schema)
                       :map (into [:map]
                                  (map (fn [{:keys [key required] :as field}]
                                         (if required
                                           [key (sub-schema-field->malli field)]
                                           [key {:optional true} (sub-schema-field->malli field)]))
                                       (:fields item-schema)))
                       :string :string
                       :number :number
                       :keyword :keyword
                       :any)]
      (cond-> [:vector item-malli]
        (and min-items max-items) (conj {:min min-items :max max-items})
        (and min-items (not max-items)) (conj {:min min-items})
        (and max-items (not min-items)) (conj {:max max-items})))

    :set
    (let [item-malli (case (:type item-schema)
                       :string :string
                       :number :number
                       :keyword :keyword
                       :any)]
      [:set item-malli])

    :map
    (into [:map]
          (map (fn [{:keys [key required] :as field}]
                 (if required
                   [key (sub-schema-field->malli field)]
                   [key {:optional true} (sub-schema-field->malli field)]))
               fields))

    ;; Default: accept anything
    :any))

(defn validate-sub-schema
  "Validate a value against a sub-schema definition.
   Returns nil if valid, or an error map if invalid."
  [field-def field-slug value]
  (when-let [sub-schema (:sub-schema field-def)]
    (let [malli-schema (sub-schema->malli sub-schema)]
      (when-not (m/validate malli-schema value)
        (let [explanation (m/explain malli-schema value)]
          {:field field-slug
           :message (str (or (:name field-def) field-slug) " contains invalid data")
           :details explanation})))))

(defn validate-single-field-type
  "Validate a single field value against its data type.
   Returns nil if valid, or an error description map if invalid."
  [field-def field-slug value]
  (when (some? value)  ;; nil values pass - required check handles presence
    (let [data-type (:data-type field-def)
          field-name (or (:name field-def) field-slug)]
      (case data-type
        :text
        (when-not (string? value)
          {:field field-slug :message (str field-name " must be text")})

        :long_text
        (when-not (string? value)
          {:field field-slug :message (str field-name " must be text")})

        :number
        (when-not (number? value)
          {:field field-slug :message (str field-name " must be a number")})

        :date
        (when-not (and (string? value) (re-matches date-regex value))
          {:field field-slug :message (str field-name " must be a valid date (YYYY-MM-DD)")})

        :boolean
        (when-not (boolean? value)
          {:field field-slug :message (str field-name " must be true or false")})

        :email
        (when-not (and (string? value) (re-matches email-regex value))
          {:field field-slug :message "Please enter a valid email address (e.g., name@example.com)"})

        :phone
        (when-not (and (string? value) (re-matches phone-regex value))
          {:field field-slug :message "Please enter a valid 10-digit phone number"})

        :url
        (when-not (and (string? value) (re-matches url-regex value))
          {:field field-slug :message "Please enter a valid URL starting with http:// or https://"})

        :single_select
        (when-not (string? value)
          {:field field-slug :message (str "Please select a valid option for " field-name)})

        :multi_select
        (cond
          (not (set? value))
          {:field field-slug :message (str "Please select one or more options for " field-name)}

          (not (every? string? value))
          {:field field-slug :message (str "Invalid selection for " field-name)})

        ;; :data type - validates against sub-schema if present, otherwise accepts anything
        :data
        (validate-sub-schema field-def field-slug value)

        ;; :keyword_enum - keywords validated against options
        :keyword_enum
        (let [valid-options (set (:options field-def))]
          (cond
            (not (keyword? value))
            {:field field-slug :message (str "Please select a valid option for " field-name)}

            (and (seq valid-options) (not (valid-options value)))
            {:field field-slug :message (str "Please select a valid option for " field-name)}))

        ;; :encrypted - pre-encrypted map with ciphertext, encrypted-key, iv
        :encrypted
        (cond
          (not (map? value))
          {:field field-slug :message (str field-name " must be encrypted data")}

          (not (and (contains? value :ciphertext)
                    (contains? value :encrypted-key)
                    (contains? value :iv)))
          {:field field-slug :message (str field-name " is missing required encryption fields")}

          (not (and (string? (:ciphertext value))
                    (string? (:encrypted-key value))
                    (string? (:iv value))))
          {:field field-slug :message (str field-name " has invalid encryption data")})

        ;; Unknown data type - skip validation
        nil))))

(defn validate-field-types
  "Validate that field values match their defined data types.
   Returns nil if all valid, or a vector of error descriptions."
  [field-definitions field-values]
  (let [field-def-map (into {} (map (fn [f] [(keyword (:slug f)) f]) field-definitions))
        errors (->> field-values
                    (map (fn [[slug value]]
                           (when-let [field-def (get field-def-map slug)]
                             (validate-single-field-type field-def (name slug) value))))
                    (filter some?)
                    vec)]
    (when (seq errors)
      errors)))

(defn format-field-type-errors
  "Format field type validation errors into a user-friendly message."
  [errors]
  (if (= 1 (count errors))
    (:message (first errors))
    (str/join "; " (map :message errors))))

;; =============================================================================
;; Contact Type Commands
;; =============================================================================

(defcommand :crm create-contact-type
  "Create a new contact type with field definitions."
  [{{:keys [slug name description field-definitions]} :command
    :keys [event-store]}]
  ;; Check if slug already exists
  (if (rm/get-contact-type event-store {:type-slug slug})
    {::anom/category ::anom/conflict
     ::anom/message (str "Contact type with slug '" slug "' already exists")}
    (let [type-id (random-uuid)]
      {:command-result/events
       [(->event
         {:type :crm/contact-type-created
          :tags #{[:contact-type type-id]}
          :body (cond-> {:type-id type-id
                         :slug slug
                         :name name
                         :field-definitions (vec field-definitions)}
                  description (assoc :description description))})]})))

(defcommand :crm update-contact-type
  "Update contact type name/description."
  [{{:keys [type-id name description]} :command
    :keys [event-store]}]
  (if-not (rm/get-contact-type event-store {:type-id type-id})
    {::anom/category ::anom/not-found
     ::anom/message "Contact type not found"}
    (let [changes (cond-> {}
                    name (assoc :name name)
                    description (assoc :description description))]
      (if (empty? changes)
        {:command-result/events []}
        {:command-result/events
         [(->event
           {:type :crm/contact-type-updated
            :tags #{[:contact-type type-id]}
            :body {:type-id type-id
                   :changes changes}})]}))))

(defcommand :crm deactivate-contact-type
  "Deactivate a contact type (prevent new contacts)."
  [{{:keys [type-id]} :command
    :keys [event-store]}]
  (let [contact-type (rm/get-contact-type event-store {:type-id type-id})]
    (cond
      (not contact-type)
      {::anom/category ::anom/not-found
       ::anom/message "Contact type not found"}

      (not (:active contact-type))
      {::anom/category ::anom/conflict
       ::anom/message "Contact type is already inactive"}

      :else
      (let [active-contacts (->> (rm/get-contacts-by-type event-store (:slug contact-type))
                                 (filter #(= :active (:status %))))]
        (if (seq active-contacts)
          {::anom/category ::anom/conflict
           ::anom/message (str "Cannot deactivate contact type with " (count active-contacts) " active contacts")}
          {:command-result/events
           [(->event
             {:type :crm/contact-type-deactivated
              :tags #{[:contact-type type-id]}
              :body {:type-id type-id}})]})))))

(defcommand :crm add-field-definition
  "Add a field definition to a contact type."
  [{{:keys [type-id field position]} :command
    :keys [event-store]}]
  (let [contact-type (rm/get-contact-type event-store {:type-id type-id})]
    (cond
      (not contact-type)
      {::anom/category ::anom/not-found
       ::anom/message "Contact type not found"}

      (some #(= (:slug field) (:slug %)) (:field-definitions contact-type))
      {::anom/category ::anom/conflict
       ::anom/message (str "Field with slug '" (:slug field) "' already exists")}

      :else
      (let [pos (or position (count (:field-definitions contact-type)))]
        {:command-result/events
         [(->event
           {:type :crm/field-definition-added
            :tags #{[:contact-type type-id]}
            :body {:type-id type-id
                   :field field
                   :position pos}})]}))))

(defcommand :crm update-field-definition
  "Update a field definition on a contact type."
  [{{:keys [type-id field-slug updates]} :command
    :keys [event-store]}]
  (let [contact-type (rm/get-contact-type event-store {:type-id type-id})]
    (cond
      (not contact-type)
      {::anom/category ::anom/not-found
       ::anom/message "Contact type not found"}

      (not (some #(= field-slug (:slug %)) (:field-definitions contact-type)))
      {::anom/category ::anom/not-found
       ::anom/message (str "Field with slug '" field-slug "' not found")}

      :else
      {:command-result/events
       [(->event
         {:type :crm/field-definition-updated
          :tags #{[:contact-type type-id]}
          :body {:type-id type-id
                 :field-slug field-slug
                 :updates updates}})]})))

(defcommand :crm remove-field-definition
  "Remove a field definition from a contact type."
  [{{:keys [type-id field-slug]} :command
    :keys [event-store]}]
  (let [contact-type (rm/get-contact-type event-store {:type-id type-id})]
    (cond
      (not contact-type)
      {::anom/category ::anom/not-found
       ::anom/message "Contact type not found"}

      (not (some #(= field-slug (:slug %)) (:field-definitions contact-type)))
      {::anom/category ::anom/not-found
       ::anom/message (str "Field with slug '" field-slug "' not found")}

      :else
      {:command-result/events
       [(->event
         {:type :crm/field-definition-removed
          :tags #{[:contact-type type-id]}
          :body {:type-id type-id
                 :field-slug field-slug}})]})))

;; =============================================================================
;; Contact Commands
;; =============================================================================

(defcommand :crm create-contact
  "Create a new contact of a given type."
  [{{:keys [type-slug field-values tags attribution contact-id]} :command
    :keys [event-store]}]
  (let [contact-type (rm/get-contact-type event-store {:type-slug type-slug})]
    (cond
      (not contact-type)
      {::anom/category ::anom/not-found
       ::anom/message (str "Contact type '" type-slug "' not found")}

      (not (:active contact-type))
      {::anom/category ::anom/conflict
       ::anom/message "Cannot create contact of inactive type"}

      (not (validate-required-fields (:field-definitions contact-type) field-values))
      {::anom/category ::anom/incorrect
       ::anom/message "Required fields are missing or empty"}

      (validate-field-types (:field-definitions contact-type) field-values)
      {::anom/category ::anom/incorrect
       ::anom/message (format-field-type-errors
                       (validate-field-types (:field-definitions contact-type) field-values))}

      :else
      ;; Check unique field constraints
      (let [unique-fields (->> (:field-definitions contact-type)
                               (filter :unique))
            violations (for [f unique-fields
                             :let [slug (:slug f)
                                   value (get field-values (keyword slug))
                                   existing (check-unique-field-violation
                                             event-store type-slug slug value nil)]
                             :when existing]
                         {:field slug :value value :existing-id (:id existing)})]
        (if (seq violations)
          {::anom/category ::anom/conflict
           ::anom/message (str "Unique field constraint violated: "
                               (str/join ", " (map :field violations)))}
          (let [contact-id (or contact-id (random-uuid))
                display-name (derive-display-name field-values)]
            {:command-result/events
             [(->event
               {:type :crm/contact-created
                :tags #{[:contact contact-id]
                        [:contact-type (:id contact-type)]}
                :body {:contact-id contact-id
                       :type-id (:id contact-type)
                       :type-slug type-slug
                       :display-name display-name
                       :field-values field-values
                       :tags (or tags #{})}})]}))))))

(defcommand :crm update-contact
  "Update multiple field values on a contact."
  [{{:keys [contact-id field-values]} :command
    :keys [event-store]}]
  (let [contact (rm/get-contact event-store contact-id)
        contact-type (when contact
                       (rm/get-contact-type event-store {:type-id (:type-id contact)}))
        type-errors (when contact-type
                      (validate-field-types (:field-definitions contact-type) field-values))]
    (cond
      (not contact)
      {::anom/category ::anom/not-found
       ::anom/message "Contact not found"}

      type-errors
      {::anom/category ::anom/incorrect
       ::anom/message (format-field-type-errors type-errors)}

      :else
      {:command-result/events
       [(->event
         {:type :crm/contact-updated
          :tags #{[:contact contact-id]
                  [:contact-type (:type-id contact)]}
          :body {:contact-id contact-id
                 :field-values field-values}})]})))

(defcommand :crm set-contact-field
  "Set a single field on a contact."
  [{{:keys [contact-id field-slug value]} :command
    :keys [event-store]}]
  (let [contact (rm/get-contact event-store contact-id)
        contact-type (when contact
                       (rm/get-contact-type event-store {:type-id (:type-id contact)}))
        field-def (when contact-type
                    (->> (:field-definitions contact-type)
                         (filter #(= field-slug (:slug %)))
                         first))
        type-error (when field-def
                     (validate-single-field-type field-def field-slug value))]
    (cond
      (not contact)
      {::anom/category ::anom/not-found
       ::anom/message "Contact not found"}

      type-error
      {::anom/category ::anom/incorrect
       ::anom/message (:message type-error)}

      :else
      (let [old-value (get-in contact [:field-values (keyword field-slug)])]
        {:command-result/events
         [(->event
           {:type :crm/contact-field-set
            :tags #{[:contact contact-id]
                    [:contact-type (:type-id contact)]
                    [:contact-data contact-id]}   ;; Granular: separate from lifecycle
            :body {:contact-id contact-id
                   :field-slug field-slug
                   :old-value old-value
                   :new-value value}})]}))))

(defcommand :crm tag-contact
  "Add a tag to a contact."
  [{{:keys [contact-id tag]} :command
    :keys [event-store]}]
  (let [contact (rm/get-contact event-store contact-id)]
    (if-not contact
      {::anom/category ::anom/not-found
       ::anom/message "Contact not found"}
      {:command-result/events
       [(->event
         {:type :crm/contact-tagged
          :tags #{[:contact contact-id]
                  [:contact-data contact-id]}   ;; Granular: separate from lifecycle
          :body {:contact-id contact-id
                 :tag tag}})]})))

(defcommand :crm untag-contact
  "Remove a tag from a contact."
  [{{:keys [contact-id tag]} :command
    :keys [event-store]}]
  (let [contact (rm/get-contact event-store contact-id)]
    (if-not contact
      {::anom/category ::anom/not-found
       ::anom/message "Contact not found"}
      {:command-result/events
       [(->event
         {:type :crm/contact-untagged
          :tags #{[:contact contact-id]
                  [:contact-data contact-id]}   ;; Granular: separate from lifecycle
          :body {:contact-id contact-id
                 :tag tag}})]})))

(defcommand :crm change-contact-status
  "Change a contact's status."
  [{{:keys [contact-id new-status reason]} :command
    :keys [event-store]}]
  (let [contact (rm/get-contact event-store contact-id)]
    (if-not contact
      {::anom/category ::anom/not-found
       ::anom/message "Contact not found"}
      {:command-result/events
       [(->event
         {:type :crm/contact-status-changed
          :tags #{[:contact contact-id]
                  [:contact-lifecycle contact-id]}   ;; Granular: separate from data changes
          :body (cond-> {:contact-id contact-id
                         :old-status (:status contact)
                         :new-status new-status}
                  reason (assoc :reason reason))})]})))

(defcommand :crm archive-contact
  "Archive a contact."
  [{{:keys [contact-id reason]} :command
    :keys [event-store]}]
  (let [contact (rm/get-contact event-store contact-id)]
    (cond
      (not contact)
      {::anom/category ::anom/not-found
       ::anom/message "Contact not found"}

      (= :archived (:status contact))
      {::anom/category ::anom/conflict
       ::anom/message "Contact is already archived"}

      :else
      {:command-result/events
       [(->event
         {:type :crm/contact-archived
          :tags #{[:contact contact-id]
                  [:contact-lifecycle contact-id]}   ;; Granular: separate from data changes
          :body (cond-> {:contact-id contact-id}
                  reason (assoc :reason reason))})]})))

(defcommand :crm restore-contact
  "Restore an archived contact."
  [{{:keys [contact-id]} :command
    :keys [event-store]}]
  (let [contact (rm/get-contact event-store contact-id)]
    (cond
      (not contact)
      {::anom/category ::anom/not-found
       ::anom/message "Contact not found"}

      (not= :archived (:status contact))
      {::anom/category ::anom/conflict
       ::anom/message "Contact is not archived"}

      :else
      {:command-result/events
       [(->event
         {:type :crm/contact-restored
          :tags #{[:contact contact-id]
                  [:contact-lifecycle contact-id]}   ;; Granular: separate from data changes
          :body {:contact-id contact-id}})]})))

(defcommand :crm merge-contacts
  "Merge two contacts of the same type."
  [{{:keys [primary-contact-id secondary-contact-id field-resolutions]} :command
    :keys [event-store]}]
  (let [primary (rm/get-contact event-store primary-contact-id)
        secondary (rm/get-contact event-store secondary-contact-id)]
    (cond
      (not primary)
      {::anom/category ::anom/not-found
       ::anom/message "Primary contact not found"}

      (not secondary)
      {::anom/category ::anom/not-found
       ::anom/message "Secondary contact not found"}

      (not= (:type-id primary) (:type-id secondary))
      {::anom/category ::anom/incorrect
       ::anom/message "Cannot merge contacts of different types"}

      (= primary-contact-id secondary-contact-id)
      {::anom/category ::anom/incorrect
       ::anom/message "Cannot merge a contact with itself"}

      :else
      ;; Merge field values based on resolutions (default to primary)
      (let [merged-fields (merge
                           (:field-values secondary)
                           (:field-values primary)
                           (reduce (fn [acc [field-key resolution]]
                                     (case resolution
                                       :secondary (assoc acc field-key
                                                         (get-in secondary [:field-values field-key]))
                                       :combined (assoc acc field-key
                                                        (str (get-in primary [:field-values field-key])
                                                             ", "
                                                             (get-in secondary [:field-values field-key])))
                                       acc))
                                   {}
                                   field-resolutions))
            merged-tags (set/union (:tags primary) (:tags secondary))]
        {:command-result/events
         [(->event
           {:type :crm/contact-merged
            :tags #{[:contact primary-contact-id]
                    [:contact secondary-contact-id]
                    [:contact-type (:type-id primary)]}
            :body {:primary-contact-id primary-contact-id
                   :secondary-contact-id secondary-contact-id
                   :merged-field-values merged-fields
                   :merged-tags merged-tags}})]}))))

(defcommand :crm delete-contact
  "Permanently delete a contact."
  [{{:keys [contact-id]} :command
    :keys [event-store]}]
  (let [contact (rm/get-contact event-store contact-id)]
    (if-not contact
      {::anom/category ::anom/not-found
       ::anom/message "Contact not found"}
      {:command-result/events
       [(->event
         {:type :crm/contact-deleted
          :tags #{[:contact contact-id]}
          :body {:contact-id contact-id}})]})))

;; =============================================================================
;; Entry Point Commands
;; =============================================================================

(defcommand :crm capture-lead-from-form
  "Create a contact from a form submission."
  [{{:keys [form-type form-id contact-type-slug field-values]} :command
    :keys [event-store]}]
  (let [contact-type (rm/get-contact-type event-store {:type-slug contact-type-slug})
        type-errors (when contact-type
                      (validate-field-types (:field-definitions contact-type) field-values))]
    (cond
      (not contact-type)
      {::anom/category ::anom/not-found
       ::anom/message (str "Contact type '" contact-type-slug "' not found")}

      (not (:active contact-type))
      {::anom/category ::anom/conflict
       ::anom/message "Cannot create contact of inactive type"}

      type-errors
      {::anom/category ::anom/incorrect
       ::anom/message (format-field-type-errors type-errors)}

      :else
      (let [contact-id (random-uuid)
            display-name (derive-display-name field-values)]
        {:command-result/events
         [(->event
           {:type :crm/lead-captured
            :tags #{[:contact contact-id]
                    [:contact-type (:id contact-type)]
                    [:form form-id]}
            :body {:contact-id contact-id
                   :form-type form-type
                   :form-id form-id
                   :contact-type-slug contact-type-slug
                   :field-values field-values}})
          (->event
           {:type :crm/contact-created
            :tags #{[:contact contact-id]
                    [:contact-type (:id contact-type)]}
            :body {:contact-id contact-id
                   :type-id (:id contact-type)
                   :type-slug contact-type-slug
                   :display-name display-name
                   :field-values field-values
                   :tags #{}}})]}))))

(defcommand :crm receive-referral
  "Create a contact from a referral."
  [{{:keys [referring-contact-id contact-type-slug field-values referral-notes]} :command
    :keys [event-store]}]
  (let [referring-contact (rm/get-contact event-store referring-contact-id)
        contact-type (rm/get-contact-type event-store {:type-slug contact-type-slug})
        type-errors (when contact-type
                      (validate-field-types (:field-definitions contact-type) field-values))]
    (cond
      (not referring-contact)
      {::anom/category ::anom/not-found
       ::anom/message "Referring contact not found"}

      (not contact-type)
      {::anom/category ::anom/not-found
       ::anom/message (str "Contact type '" contact-type-slug "' not found")}

      (not (:active contact-type))
      {::anom/category ::anom/conflict
       ::anom/message "Cannot create contact of inactive type"}

      type-errors
      {::anom/category ::anom/incorrect
       ::anom/message (format-field-type-errors type-errors)}

      :else
      (let [contact-id (random-uuid)
            display-name (derive-display-name field-values)]
        {:command-result/events
         [(->event
           {:type :crm/referral-received
            :tags #{[:contact contact-id]
                    [:contact referring-contact-id]
                    [:contact-type (:id contact-type)]}
            :body (cond-> {:contact-id contact-id
                           :referring-contact-id referring-contact-id
                           :contact-type-slug contact-type-slug
                           :field-values field-values}
                    referral-notes (assoc :referral-notes referral-notes))})
          (->event
           {:type :crm/contact-created
            :tags #{[:contact contact-id]
                    [:contact-type (:id contact-type)]}
            :body {:contact-id contact-id
                   :type-id (:id contact-type)
                   :type-slug contact-type-slug
                   :display-name display-name
                   :field-values field-values
                   :tags #{}}})]}))))

(defcommand :crm import-contacts
  "Bulk import contacts."
  [{{:keys [import-id contact-type-slug contacts source-description]} :command
    :keys [event-store]}]
  (let [contact-type (rm/get-contact-type event-store {:type-slug contact-type-slug})
        ;; Validate all contacts and track which ones have errors
        validation-results (when contact-type
                             (map-indexed
                              (fn [idx field-values]
                                {:index idx
                                 :errors (validate-field-types (:field-definitions contact-type) field-values)})
                              contacts))
        invalid-contacts (filter #(seq (:errors %)) validation-results)]
    (cond
      (not contact-type)
      {::anom/category ::anom/not-found
       ::anom/message (str "Contact type '" contact-type-slug "' not found")}

      (not (:active contact-type))
      {::anom/category ::anom/conflict
       ::anom/message "Cannot create contacts of inactive type"}

      (seq invalid-contacts)
      {::anom/category ::anom/incorrect
       ::anom/message (str "Import validation failed for " (count invalid-contacts) " contacts. "
                           "First error: Contact " (:index (first invalid-contacts))
                           " - " (-> invalid-contacts first :errors first :field)
                           " expected " (-> invalid-contacts first :errors first :expected))}

      :else
      (let [contact-events (mapv
                            (fn [contact-data]
                              ;; Allow :contact-id in the data to preserve IDs during migration
                              (let [contact-id (or (:contact-id contact-data) (random-uuid))
                                    field-values (dissoc contact-data :contact-id)
                                    display-name (derive-display-name field-values)]
                                (->event
                                 {:type :crm/contact-created
                                  :tags #{[:contact contact-id]
                                          [:contact-type (:id contact-type)]
                                          [:import import-id]}
                                  :body {:contact-id contact-id
                                         :type-id (:id contact-type)
                                         :type-slug contact-type-slug
                                         :display-name display-name
                                         :field-values field-values
                                         :tags #{}}})))
                            contacts)
            contact-ids (mapv :contact-id contact-events)]
        {:command-result/events
         (conj contact-events
               (->event
                {:type :crm/contacts-imported
                 :tags #{[:import import-id]
                         [:contact-type (:id contact-type)]}
                 :body {:import-id import-id
                        :contact-type-slug contact-type-slug
                        :contact-ids contact-ids
                        :source-description source-description
                        :import-count (count contacts)}}))}))))

(defcommand :crm resolve-duplicate
  "Resolve a duplicate detection."
  [{{:keys [duplicate-id resolution merge-config]} :command
    :keys [event-store]}]
  (let [dup (rm/get-duplicate event-store duplicate-id)]
    (cond
      (not dup)
      {::anom/category ::anom/not-found
       ::anom/message "Duplicate record not found"}

      (not= :pending (:status dup))
      {::anom/category ::anom/conflict
       ::anom/message "Duplicate has already been resolved"}

      :else
      (let [events [(->event
                     {:type :crm/duplicate-resolved
                      :tags #{[:duplicate duplicate-id]
                              [:duplicate-primary (:contact-id dup)]
                              [:duplicate-candidate (:potential-duplicate-id dup)]}
                      :body {:duplicate-id duplicate-id
                             :resolution resolution}})]]
        ;; If resolution is merge, also emit merge event
        (if (= :merge resolution)
          {:command-result/events
           (conj events
                 (->event
                  {:type :crm/contact-merged
                   :tags #{[:contact (:contact-id dup)]
                           [:contact (:potential-duplicate-id dup)]}
                   :body {:primary-contact-id (:contact-id dup)
                          :secondary-contact-id (:potential-duplicate-id dup)
                          :merged-field-values (or (:merged-field-values merge-config) {})
                          :merged-tags (or (:merged-tags merge-config) #{})}}))}
          {:command-result/events events})))))

;; =============================================================================
;; Relationship Type Commands
;; =============================================================================

(defcommand :crm create-relationship-type
  "Create a new relationship type."
  [{{:keys [name slug inverse-name source-type-slugs target-type-slugs allowed-properties]} :command
    :keys [event-store]}]
  (if (rm/get-relationship-type event-store {:type-slug slug})
    {::anom/category ::anom/conflict
     ::anom/message (str "Relationship type with slug '" slug "' already exists")}
    (let [type-id (random-uuid)]
      {:command-result/events
       [(->event
         {:type :crm/relationship-type-created
          :tags #{[:relationship-type type-id]}
          :body {:type-id type-id
                 :name name
                 :slug slug
                 :inverse-name inverse-name
                 :source-type-slugs (or source-type-slugs #{})
                 :target-type-slugs (or target-type-slugs #{})
                 :allowed-properties (or allowed-properties [])}})]})))

(defcommand :crm update-relationship-type
  "Update a relationship type."
  [{{:keys [type-id name inverse-name source-type-slugs target-type-slugs]} :command
    :keys [event-store]}]
  (if-not (rm/get-relationship-type event-store {:type-id type-id})
    {::anom/category ::anom/not-found
     ::anom/message "Relationship type not found"}
    (let [changes (cond-> {}
                    name (assoc :name name)
                    inverse-name (assoc :inverse-name inverse-name)
                    source-type-slugs (assoc :source-type-slugs source-type-slugs)
                    target-type-slugs (assoc :target-type-slugs target-type-slugs))]
      (if (empty? changes)
        {:command-result/events []}
        {:command-result/events
         [(->event
           {:type :crm/relationship-type-updated
            :tags #{[:relationship-type type-id]}
            :body {:type-id type-id
                   :changes changes}})]}))))

;; =============================================================================
;; Relationship Commands
;; =============================================================================

(defcommand :crm create-relationship
  "Create a relationship between two contacts."
  [{{:keys [type-slug source-contact-id target-contact-id properties start-date is-primary]} :command
    :keys [event-store]}]
  (let [rel-type (rm/get-relationship-type event-store {:type-slug type-slug})
        source-contact (rm/get-contact event-store source-contact-id)
        target-contact (rm/get-contact event-store target-contact-id)]
    (cond
      (not rel-type)
      {::anom/category ::anom/not-found
       ::anom/message (str "Relationship type '" type-slug "' not found")}

      (not source-contact)
      {::anom/category ::anom/not-found
       ::anom/message "Source contact not found"}

      (not target-contact)
      {::anom/category ::anom/not-found
       ::anom/message "Target contact not found"}

      (= source-contact-id target-contact-id)
      {::anom/category ::anom/incorrect
       ::anom/message "Cannot create self-relationship"}

      ;; Check source type restriction
      (and (seq (:source-type-slugs rel-type))
           (not (contains? (:source-type-slugs rel-type) (:type-slug source-contact))))
      {::anom/category ::anom/incorrect
       ::anom/message (str "Source contact type '" (:type-slug source-contact)
                           "' not allowed for this relationship type")}

      ;; Check target type restriction
      (and (seq (:target-type-slugs rel-type))
           (not (contains? (:target-type-slugs rel-type) (:type-slug target-contact))))
      {::anom/category ::anom/incorrect
       ::anom/message (str "Target contact type '" (:type-slug target-contact)
                           "' not allowed for this relationship type")}

      ;; Check for duplicate active relationship
      (seq (rm/get-relationships-between event-store source-contact-id target-contact-id type-slug))
      {::anom/category ::anom/conflict
       ::anom/message "Active relationship of this type already exists between these contacts"}

      :else
      (let [relationship-id (random-uuid)
            actual-start-date (or start-date (str (java.time.LocalDate/now)))
            events [(->event
                     {:type :crm/relationship-created
                      :tags #{[:relationship relationship-id]
                              [:relationship-source source-contact-id]
                              [:relationship-target target-contact-id]
                              [:relationship-type (:id rel-type)]}
                      :body {:relationship-id relationship-id
                             :type-id (:id rel-type)
                             :type-slug type-slug
                             :source-contact-id source-contact-id
                             :target-contact-id target-contact-id
                             :properties (or properties {})
                             :start-date actual-start-date
                             :is-primary (or is-primary false)}})]]
        ;; If marking as primary, also emit primary-set event
        (if is-primary
          (let [current-primary (rm/get-primary-relationship-for-contact
                                 event-store source-contact-id type-slug)]
            {:command-result/events
             (conj events
                   (->event
                    {:type :crm/primary-relationship-set
                     :tags #{[:relationship relationship-id]
                             [:relationship-source source-contact-id]
                             [:relationship-type (:id rel-type)]}
                     :body (cond-> {:relationship-id relationship-id
                                    :contact-id source-contact-id
                                    :type-id (:id rel-type)}
                             current-primary (assoc :previous-primary-id (:id current-primary)))}))})
          {:command-result/events events})))))

(defcommand :crm update-relationship
  "Update relationship properties or start date."
  [{{:keys [relationship-id properties start-date]} :command
    :keys [event-store]}]
  (let [relationship (rm/get-relationship event-store relationship-id)]
    (if-not relationship
      {::anom/category ::anom/not-found
       ::anom/message "Relationship not found"}
      (let [changes (cond-> {}
                      properties (assoc :properties properties)
                      start-date (assoc :start-date start-date))]
        (if (empty? changes)
          {:command-result/events []}
          {:command-result/events
           [(->event
             {:type :crm/relationship-updated
              :tags #{[:relationship relationship-id]
                      [:relationship-source (:source-contact-id relationship)]
                      [:relationship-target (:target-contact-id relationship)]}
              :body {:relationship-id relationship-id
                     :changes changes}})]})))))

(defcommand :crm end-relationship
  "End a relationship with an optional end date."
  [{{:keys [relationship-id end-date reason]} :command
    :keys [event-store]}]
  (let [relationship (rm/get-relationship event-store relationship-id)]
    (cond
      (not relationship)
      {::anom/category ::anom/not-found
       ::anom/message "Relationship not found"}

      (:end-date relationship)
      {::anom/category ::anom/conflict
       ::anom/message "Relationship has already ended"}

      :else
      {:command-result/events
       [(->event
         {:type :crm/relationship-ended
          :tags #{[:relationship relationship-id]
                  [:relationship-source (:source-contact-id relationship)]
                  [:relationship-target (:target-contact-id relationship)]}
          :body (cond-> {:relationship-id relationship-id
                         :end-date (or end-date (str (java.time.LocalDate/now)))}
                  reason (assoc :reason reason))})]})))

(defcommand :crm delete-relationship
  "Permanently delete a relationship."
  [{{:keys [relationship-id]} :command
    :keys [event-store]}]
  (let [relationship (rm/get-relationship event-store relationship-id)]
    (if-not relationship
      {::anom/category ::anom/not-found
       ::anom/message "Relationship not found"}
      {:command-result/events
       [(->event
         {:type :crm/relationship-deleted
          :tags #{[:relationship relationship-id]
                  [:relationship-source (:source-contact-id relationship)]
                  [:relationship-target (:target-contact-id relationship)]}
          :body {:relationship-id relationship-id}})]})))

(defcommand :crm set-primary-relationship
  "Set a relationship as the primary for a contact."
  [{{:keys [relationship-id contact-id]} :command
    :keys [event-store]}]
  (let [relationship (rm/get-relationship event-store relationship-id)]
    (cond
      (not relationship)
      {::anom/category ::anom/not-found
       ::anom/message "Relationship not found"}

      (and (not= contact-id (:source-contact-id relationship))
           (not= contact-id (:target-contact-id relationship)))
      {::anom/category ::anom/incorrect
       ::anom/message "Contact is not part of this relationship"}

      (:is-primary relationship)
      {::anom/category ::anom/conflict
       ::anom/message "Relationship is already primary"}

      :else
      (let [current-primary (rm/get-primary-relationship-for-contact
                             event-store contact-id (:type-slug relationship))]
        {:command-result/events
         [(->event
           {:type :crm/primary-relationship-set
            :tags #{[:relationship relationship-id]
                    [:relationship-source contact-id]
                    [:relationship-type (:type-id relationship)]}
            :body (cond-> {:relationship-id relationship-id
                           :contact-id contact-id
                           :type-id (:type-id relationship)}
                    current-primary (assoc :previous-primary-id (:id current-primary)))})]}))))

;; =============================================================================
;; Communication Commands
;; =============================================================================

(defcommand :crm log-communication
  "Log a communication (email/SMS) for a contact."
  [{{:keys [contact-id communication-type direction sender recipient
            logged-by-contact-id subject content occurred-at metadata]} :command
    :keys [event-store auth-claims]}]
  (let [;; Use provided logged-by-contact-id or fall back to auth-claims
        logger-id (or logged-by-contact-id
                      (when-let [uid (:user-id auth-claims)]
                        (if (uuid? uid) uid (java.util.UUID/fromString uid))))
        contact (rm/get-contact event-store contact-id)
        logger (when logger-id (rm/get-contact event-store logger-id))]
    (cond
      (not contact)
      {::anom/category ::anom/not-found
       ::anom/message "Contact not found"}

      (not logger-id)
      {::anom/category ::anom/forbidden
       ::anom/message "No logged-by-contact-id provided and no auth-claims available"}

      (not logger)
      {::anom/category ::anom/not-found
       ::anom/message "Logging staff member not found in CRM"}

      :else
      (let [now (str (time/now))]
        {:command-result/events
         [(->event
           {:type :crm/communication-logged
            :tags #{[:contact contact-id]
                    [:communication-logger logger-id]
                    [:communication contact-id]}   ;; Granular: separate from contact data
            :body {:contact-id contact-id
                   :communication-id (random-uuid)
                   :communication-type communication-type
                   :direction direction
                   :sender sender
                   :recipient recipient
                   :logged-by-contact-id logger-id
                   :subject subject
                   :content content
                   :occurred-at (or occurred-at now)
                   :logged-at now
                   :metadata metadata}})]}))))

