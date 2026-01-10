(ns ai.obney.workshop.crm-service.commands-test
  "Unit tests for CRM service command handlers."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [ai.obney.workshop.crm-service.test-helpers :as h]
            [ai.obney.workshop.crm-service.core.commands :as cmd] ;; Keep for utility functions
            [ai.obney.workshop.crm-service.interface.read-models :as rm]
            [ai.obney.grain.event-store-v2.interface :as es]
            [cognitect.anomalies :as anom]))


;; =============================================================================
;; Helper Function Tests
;; =============================================================================

(deftest normalize-email-test
  (testing "normalizes email to lowercase and trims whitespace"
    (is (= "test@example.com" (cmd/normalize-email "  TEST@Example.COM  "))))

  (testing "returns nil for nil input"
    (is (nil? (cmd/normalize-email nil))))

  (testing "returns empty string for blank input"
    (is (= "" (cmd/normalize-email "   ")))))

(deftest normalize-phone-test
  (testing "extracts digits from phone number"
    (is (= "5551234567" (cmd/normalize-phone "(555) 123-4567"))))

  (testing "returns nil for nil input"
    (is (nil? (cmd/normalize-phone nil))))

  (testing "returns empty string for non-digit input"
    (is (= "" (cmd/normalize-phone "abc")))))

;; =============================================================================
;; Field Type Validation Tests
;; =============================================================================

(deftest validate-single-field-type-test
  (testing "text fields accept strings"
    (is (nil? (cmd/validate-single-field-type {:slug "name" :data-type :text} "name" "John")))
    (is (some? (cmd/validate-single-field-type {:slug "name" :data-type :text} "name" 123))))

  (testing "long_text fields accept strings"
    (is (nil? (cmd/validate-single-field-type {:slug "bio" :data-type :long_text} "bio" "A long bio...")))
    (is (some? (cmd/validate-single-field-type {:slug "bio" :data-type :long_text} "bio" 123))))

  (testing "number fields accept numbers"
    (is (nil? (cmd/validate-single-field-type {:slug "gpa" :data-type :number} "gpa" 3.8)))
    (is (nil? (cmd/validate-single-field-type {:slug "year" :data-type :number} "year" 2025)))
    (is (some? (cmd/validate-single-field-type {:slug "gpa" :data-type :number} "gpa" "3.8"))))

  (testing "date fields accept ISO dates"
    (is (nil? (cmd/validate-single-field-type {:slug "dob" :data-type :date} "dob" "2008-01-25")))
    (is (some? (cmd/validate-single-field-type {:slug "dob" :data-type :date} "dob" "01/25/2008")))
    (is (some? (cmd/validate-single-field-type {:slug "dob" :data-type :date} "dob" "2008-13-01"))))

  (testing "boolean fields accept booleans"
    (is (nil? (cmd/validate-single-field-type {:slug "active" :data-type :boolean} "active" true)))
    (is (nil? (cmd/validate-single-field-type {:slug "active" :data-type :boolean} "active" false)))
    (is (some? (cmd/validate-single-field-type {:slug "active" :data-type :boolean} "active" "true"))))

  (testing "email fields accept valid emails"
    (is (nil? (cmd/validate-single-field-type {:slug "email" :data-type :email} "email" "test@example.com")))
    (is (some? (cmd/validate-single-field-type {:slug "email" :data-type :email} "email" "invalid-email"))))

  (testing "phone fields accept 10-digit numbers"
    (is (nil? (cmd/validate-single-field-type {:slug "phone" :data-type :phone} "phone" "2255551234")))
    (is (some? (cmd/validate-single-field-type {:slug "phone" :data-type :phone} "phone" "225-555-1234"))))

  (testing "url fields accept valid URLs"
    (is (nil? (cmd/validate-single-field-type {:slug "website" :data-type :url} "website" "https://example.com")))
    (is (nil? (cmd/validate-single-field-type {:slug "website" :data-type :url} "website" "http://example.com/path")))
    (is (some? (cmd/validate-single-field-type {:slug "website" :data-type :url} "website" "not-a-url"))))

  (testing "single_select accepts strings (no options validation)"
    (is (nil? (cmd/validate-single-field-type {:slug "status" :data-type :single_select :options ["A" "B"]} "status" "A")))
    (is (nil? (cmd/validate-single-field-type {:slug "status" :data-type :single_select :options ["A" "B"]} "status" "Other value")))
    (is (some? (cmd/validate-single-field-type {:slug "status" :data-type :single_select} "status" 123))))

  (testing "multi_select accepts sets of strings (no options validation)"
    (is (nil? (cmd/validate-single-field-type {:slug "tags" :data-type :multi_select :options ["A" "B"]} "tags" #{"A" "B"})))
    (is (nil? (cmd/validate-single-field-type {:slug "tags" :data-type :multi_select :options ["A" "B"]} "tags" #{"Other"})))
    (is (some? (cmd/validate-single-field-type {:slug "tags" :data-type :multi_select} "tags" ["A" "B"])))
    (is (some? (cmd/validate-single-field-type {:slug "tags" :data-type :multi_select} "tags" #{1 2}))))

  (testing "nil values pass validation"
    (is (nil? (cmd/validate-single-field-type {:slug "email" :data-type :email} "email" nil)))
    (is (nil? (cmd/validate-single-field-type {:slug "gpa" :data-type :number} "gpa" nil))))

  (testing "data fields accept any Clojure data (without sub-schema)"
    (is (nil? (cmd/validate-single-field-type {:slug "activities" :data-type :data} "activities"
                                              [{:activity "Sports" :category "Athletics"}])))
    (is (nil? (cmd/validate-single-field-type {:slug "metadata" :data-type :data} "metadata"
                                              {:key "value" :nested {:a 1}})))
    (is (nil? (cmd/validate-single-field-type {:slug "tags" :data-type :data} "tags"
                                              #{:a :b :c})))
    (is (nil? (cmd/validate-single-field-type {:slug "count" :data-type :data} "count" 42)))
    (is (nil? (cmd/validate-single-field-type {:slug "name" :data-type :data} "name" "string"))))

  (testing "data fields with sub-schema validate structure"
    (let [activity-sub-schema {:type :vector
                               :item-schema {:type :map
                                            :fields [{:key :activity :type :string :required true}
                                                    {:key :role :type :string :required false}
                                                    {:key :grades-involved :type :multi-select
                                                     :options ["9th" "10th" "11th" "12th"]}]}}
          field-def {:slug "activities" :data-type :data :sub-schema activity-sub-schema}]

      ;; Valid data passes
      (is (nil? (cmd/validate-single-field-type field-def "activities"
                                                [{:activity "Band" :role "Member" :grades-involved #{"9th" "10th"}}])))

      ;; Valid data with optional fields omitted
      (is (nil? (cmd/validate-single-field-type field-def "activities"
                                                [{:activity "Sports"}])))

      ;; Empty vector is valid
      (is (nil? (cmd/validate-single-field-type field-def "activities" [])))

      ;; Invalid: missing required field
      (is (some? (cmd/validate-single-field-type field-def "activities"
                                                 [{:role "President"}])))

      ;; Invalid: wrong type for activity (number instead of string)
      (is (some? (cmd/validate-single-field-type field-def "activities"
                                                 [{:activity 123}])))

      ;; Invalid: wrong option for multi-select
      (is (some? (cmd/validate-single-field-type field-def "activities"
                                                 [{:activity "Test" :grades-involved #{"13th"}}])))))

  (testing "sub-schema with single-select validates options"
    (let [category-sub-schema {:type :map
                               :fields [{:key :category :type :single-select
                                        :options ["A" "B" "C"] :required true}]}
          field-def {:slug "data" :data-type :data :sub-schema category-sub-schema}]

      ;; Valid option
      (is (nil? (cmd/validate-single-field-type field-def "data" {:category "A"})))

      ;; Invalid option
      (is (some? (cmd/validate-single-field-type field-def "data" {:category "D"})))))

  (testing "sub-schema with number type validates correctly"
    (let [score-sub-schema {:type :map
                            :fields [{:key :score :type :number :required true}]}
          field-def {:slug "data" :data-type :data :sub-schema score-sub-schema}]

      ;; Valid number
      (is (nil? (cmd/validate-single-field-type field-def "data" {:score 95})))
      (is (nil? (cmd/validate-single-field-type field-def "data" {:score 3.14})))

      ;; Invalid: string instead of number
      (is (some? (cmd/validate-single-field-type field-def "data" {:score "95"})))))

  (testing "keyword_enum fields accept keywords and validate against options"
    (is (nil? (cmd/validate-single-field-type
                {:slug "status" :data-type :keyword_enum :options [:active :inactive :pending]}
                "status" :active)))
    (is (nil? (cmd/validate-single-field-type
                {:slug "status" :data-type :keyword_enum}
                "status" :any-keyword)))
    (is (some? (cmd/validate-single-field-type
                 {:slug "status" :data-type :keyword_enum :options [:active :inactive]}
                 "status" "active")))
    (is (some? (cmd/validate-single-field-type
                 {:slug "status" :data-type :keyword_enum :options [:active :inactive]}
                 "status" :unknown)))))

(deftest validate-field-types-test
  (testing "returns nil when all fields are valid"
    (is (nil? (cmd/validate-field-types
               [{:slug "email" :data-type :email}
                {:slug "age" :data-type :number}]
               {:email "test@example.com" :age 25}))))

  (testing "returns errors when fields are invalid"
    (let [errors (cmd/validate-field-types
                  [{:slug "email" :data-type :email}
                   {:slug "age" :data-type :number}]
                  {:email "invalid" :age "twenty"})]
      (is (= 2 (count errors)))
      (is (some #(= "email" (:field %)) errors))
      (is (some #(= "age" (:field %)) errors))))

  (testing "fields without matching definition are skipped"
    (is (nil? (cmd/validate-field-types
               [{:slug "name" :data-type :text}]
               {:name "John" :unknown 123}))))

  (testing "nil values pass validation"
    (is (nil? (cmd/validate-field-types
               [{:slug "email" :data-type :email}]
               {:email nil}))))

  (testing "empty field-values passes validation"
    (is (nil? (cmd/validate-field-types
               [{:slug "email" :data-type :email :required true}]
               {})))))

(deftest format-field-type-errors-test
  (testing "formats single error using message field"
    (is (= "Please enter a valid email"
           (cmd/format-field-type-errors [{:field "email" :message "Please enter a valid email"}]))))

  (testing "formats multiple errors joining messages with semicolons"
    (is (= "Please enter a valid email; Age must be a number"
           (cmd/format-field-type-errors [{:field "email" :message "Please enter a valid email"}
                                          {:field "age" :message "Age must be a number"}])))))

;; =============================================================================
;; Field Type Validation Integration Tests
;; =============================================================================

(deftest create-contact-validates-field-types-test
  (testing "rejects invalid field types"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}
                                      {:name "Age" :slug "age" :data-type :number}]})))
      (let [result (cmd/crm-create-contact
                     (assoc ctx :command
                            {:type-slug "customer"
                             :field-values {:email "not-an-email" :age "twenty"}}))]
        (is (= ::anom/incorrect (::anom/category result)))
        ;; Error message now contains user-friendly field validation messages
        (is (re-find #"valid email" (::anom/message result)))))))

(deftest update-contact-validates-field-types-test
  (testing "rejects invalid field types on update"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}
                                      {:name "Age" :slug "age" :data-type :number}]})))
      (let [contact-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "customer"
                                     :field-values {:email "valid@example.com" :age 25}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (let [result (cmd/crm-update-contact
                       (assoc ctx :command
                              {:contact-id contact-id
                               :field-values {:age "invalid"}}))]
          (is (= ::anom/incorrect (::anom/category result))))))))

(deftest set-contact-field-validates-field-type-test
  (testing "rejects invalid field type on single field update"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :field-definitions [{:name "Age" :slug "age" :data-type :number}]})))
      (let [contact-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "customer"
                                     :field-values {:age 25}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (let [result (cmd/crm-set-contact-field
                       (assoc ctx :command
                              {:contact-id contact-id
                               :field-slug "age"
                               :value "not-a-number"}))]
          (is (= ::anom/incorrect (::anom/category result)))
          ;; Error message is now user-friendly
          (is (re-find #"must be a number" (::anom/message result))))))))

(deftest capture-lead-validates-field-types-test
  (testing "rejects invalid field types"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "lead"
                  :name "Lead"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}]})))
      (let [result (cmd/crm-capture-lead-from-form
                     (assoc ctx :command
                            {:contact-type-slug "lead"
                             :form-type :intake
                             :form-id (random-uuid)
                             :field-values {:email "not-valid-email"}}))]
        (is (= ::anom/incorrect (::anom/category result)))))))

(deftest receive-referral-validates-field-types-test
  (testing "rejects invalid field types"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :field-definitions [{:name "Phone" :slug "phone" :data-type :phone}]})))
      (let [referrer-result (cmd/crm-create-contact
                              (assoc ctx :command
                                     {:type-slug "customer"
                                      :field-values {:phone "5551234567"}}))
            referrer-id (-> referrer-result :command-result/events first :contact-id)]
        (h/apply-events! ctx referrer-result)
        (let [result (cmd/crm-receive-referral
                       (assoc ctx :command
                              {:contact-type-slug "customer"
                               :referring-contact-id referrer-id
                               :field-values {:phone "bad-phone"}}))]
          (is (= ::anom/incorrect (::anom/category result))))))))

(deftest import-contacts-validates-field-types-test
  (testing "rejects entire batch when any contact has invalid fields"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}
                                      {:name "Age" :slug "age" :data-type :number}]})))
      (let [result (cmd/crm-import-contacts
                     (assoc ctx :command
                            {:import-id (random-uuid)
                             :contact-type-slug "customer"
                             :source-description "csv-upload"
                             :contacts [{:email "valid@example.com" :age 25}
                                        {:email "also-valid@example.com" :age "invalid"}
                                        {:email "third@example.com" :age 30}]}))]
        (is (= ::anom/incorrect (::anom/category result)))
        (is (re-find #"Import validation failed" (::anom/message result)))
        (is (re-find #"Contact 1" (::anom/message result)))))))

;; =============================================================================
;; Contact Type Command Tests
;; =============================================================================

(deftest create-contact-type-test
  (testing "creates contact type successfully"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-create-contact-type
                     (assoc ctx :command
                            {:slug "customer"
                             :name "Customer"
                             :description "A customer contact"
                             :field-definitions [{:name "Email" :slug "email" :data-type :email}]}))]
        (is (contains? result :command-result/events))
        (is (= 1 (count (h/get-result-events result))))
        (is (h/event-of-type? result :crm/contact-type-created)))))

  (testing "rejects duplicate slug"
    (h/with-test-context [ctx]
      (let [result1 (cmd/crm-create-contact-type
                            (assoc ctx :command
                                   {:slug "customer"
                                    :name "Customer"
                                    :description "A customer"
                                    :field-definitions []}))]
        (h/apply-events! ctx result1)
        (let [result2 (cmd/crm-create-contact-type
                        (assoc ctx :command
                               {:slug "customer"
                                :name "Another Customer"
                                :description "Another one"
                                :field-definitions []}))]
          (is (= ::anom/conflict (::anom/category result2))))))))

(deftest update-contact-type-test
  (testing "updates contact type successfully"
    (h/with-test-context [ctx]
      (let [result1 (cmd/crm-create-contact-type
                      (assoc ctx :command
                             {:slug "customer"
                              :name "Customer"
                              :description "A customer"
                              :field-definitions []}))
            type-id (-> result1 :command-result/events first :type-id)]
        (h/apply-events! ctx result1)
        (let [result2 (cmd/crm-update-contact-type
                        (assoc ctx :command
                               {:type-id type-id
                                :name "Updated Customer"}))]
          (is (contains? result2 :command-result/events))
          (is (h/event-of-type? result2 :crm/contact-type-updated))))))

  (testing "returns not-found for unknown type"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-update-contact-type
                     (assoc ctx :command
                            {:type-id (random-uuid)
                             :name "Updated"}))]
        (is (= ::anom/not-found (::anom/category result))))))

  (testing "returns incorrect for empty changes"
    (h/with-test-context [ctx]
      (let [result1 (cmd/crm-create-contact-type
                      (assoc ctx :command
                             {:slug "customer"
                              :name "Customer"
                              :description "A customer"
                              :field-definitions []}))
            type-id (-> result1 :command-result/events first :type-id)]
        (h/apply-events! ctx result1)
        (let [result2 (cmd/crm-update-contact-type
                        (assoc ctx :command {:type-id type-id}))]
          ;; Handler returns empty events for empty changes, not an error
          (is (contains? result2 :command-result/events))
          (is (empty? (:command-result/events result2))))))))

(deftest deactivate-contact-type-test
  (testing "deactivates contact type successfully"
    (h/with-test-context [ctx]
      (let [result1 (cmd/crm-create-contact-type
                      (assoc ctx :command
                             {:slug "customer"
                              :name "Customer"
                              :description "A customer"
                              :field-definitions []}))
            type-id (-> result1 :command-result/events first :type-id)]
        (h/apply-events! ctx result1)
        (let [result2 (cmd/crm-deactivate-contact-type
                        (assoc ctx :command {:type-id type-id}))]
          (is (contains? result2 :command-result/events))
          (is (h/event-of-type? result2 :crm/contact-type-deactivated))))))

  (testing "returns not-found for unknown type"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-deactivate-contact-type
                     (assoc ctx :command {:type-id (random-uuid)}))]
        (is (= ::anom/not-found (::anom/category result))))))

  (testing "returns conflict for already inactive type"
    (h/with-test-context [ctx]
      (let [result1 (cmd/crm-create-contact-type
                      (assoc ctx :command
                             {:slug "customer"
                              :name "Customer"
                              :description "A customer"
                              :field-definitions []}))
            type-id (-> result1 :command-result/events first :type-id)]
        (h/apply-events! ctx result1)
        (let [result2 (cmd/crm-deactivate-contact-type
                        (assoc ctx :command {:type-id type-id}))]
          (h/apply-events! ctx result2)
          (let [result3 (cmd/crm-deactivate-contact-type
                          (assoc ctx :command {:type-id type-id}))]
            (is (= ::anom/conflict (::anom/category result3)))))))))

(deftest add-field-definition-test
  (testing "adds field definition successfully"
    (h/with-test-context [ctx]
      (let [result1 (cmd/crm-create-contact-type
                      (assoc ctx :command
                             {:slug "customer"
                              :name "Customer"
                              :description "A customer"
                              :field-definitions []}))
            type-id (-> result1 :command-result/events first :type-id)]
        (h/apply-events! ctx result1)
        (let [result2 (cmd/crm-add-field-definition
                        (assoc ctx :command
                               {:type-id type-id
                                :field {:name "Phone" :slug "phone" :data-type :phone}}))]
          (is (contains? result2 :command-result/events))
          (is (h/event-of-type? result2 :crm/field-definition-added))))))

  (testing "rejects duplicate field slug"
    (h/with-test-context [ctx]
      (let [result1 (cmd/crm-create-contact-type
                      (assoc ctx :command
                             {:slug "customer"
                              :name "Customer"
                              :description "A customer"
                              :field-definitions [{:name "Email" :slug "email" :data-type :email}]}))
            type-id (-> result1 :command-result/events first :type-id)]
        (h/apply-events! ctx result1)
        (let [result2 (cmd/crm-add-field-definition
                        (assoc ctx :command
                               {:type-id type-id
                                :field {:name "Another Email" :slug "email" :data-type :email}}))]
          (is (= ::anom/conflict (::anom/category result2))))))))

(deftest update-field-definition-test
  (testing "updates field definition successfully"
    (h/with-test-context [ctx]
      (let [result1 (cmd/crm-create-contact-type
                      (assoc ctx :command
                             {:slug "customer"
                              :name "Customer"
                              :description "A customer"
                              :field-definitions [{:name "Email" :slug "email" :data-type :email}]}))
            type-id (-> result1 :command-result/events first :type-id)]
        (h/apply-events! ctx result1)
        (let [result2 (cmd/crm-update-field-definition
                        (assoc ctx :command
                               {:type-id type-id
                                :field-slug "email"
                                :updates {:name "Email Address" :required true}}))]
          (is (contains? result2 :command-result/events))
          (is (h/event-of-type? result2 :crm/field-definition-updated))))))

  (testing "returns not-found for unknown field"
    (h/with-test-context [ctx]
      (let [result1 (cmd/crm-create-contact-type
                      (assoc ctx :command
                             {:slug "customer"
                              :name "Customer"
                              :description "A customer"
                              :field-definitions []}))
            type-id (-> result1 :command-result/events first :type-id)]
        (h/apply-events! ctx result1)
        (let [result2 (cmd/crm-update-field-definition
                        (assoc ctx :command
                               {:type-id type-id
                                :field-slug "email"
                                :updates {:name "Email Address"}}))]
          (is (= ::anom/not-found (::anom/category result2))))))))

(deftest remove-field-definition-test
  (testing "removes field definition successfully"
    (h/with-test-context [ctx]
      (let [result1 (cmd/crm-create-contact-type
                      (assoc ctx :command
                             {:slug "customer"
                              :name "Customer"
                              :description "A customer"
                              :field-definitions [{:name "Email" :slug "email" :data-type :email}]}))
            type-id (-> result1 :command-result/events first :type-id)]
        (h/apply-events! ctx result1)
        (let [result2 (cmd/crm-remove-field-definition
                        (assoc ctx :command
                               {:type-id type-id
                                :field-slug "email"}))]
          (is (contains? result2 :command-result/events))
          (is (h/event-of-type? result2 :crm/field-definition-removed))))))

  (testing "returns not-found for unknown field"
    (h/with-test-context [ctx]
      (let [result1 (cmd/crm-create-contact-type
                      (assoc ctx :command
                             {:slug "customer"
                              :name "Customer"
                              :description "A customer"
                              :field-definitions []}))
            type-id (-> result1 :command-result/events first :type-id)]
        (h/apply-events! ctx result1)
        (let [result2 (cmd/crm-remove-field-definition
                        (assoc ctx :command
                               {:type-id type-id
                                :field-slug "nonexistent"}))]
          (is (= ::anom/not-found (::anom/category result2))))))))

;; =============================================================================
;; Contact Command Tests
;; =============================================================================

(deftest create-contact-test
  (testing "creates contact successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}]})))
      (let [result (cmd/crm-create-contact
                     (assoc ctx :command
                            {:type-slug "customer"
                             :field-values {:email "test@example.com"}}))]
        (is (contains? result :command-result/events))
        (is (h/event-of-type? result :crm/contact-created)))))

  (testing "returns not-found for unknown type"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-create-contact
                     (assoc ctx :command
                            {:type-slug "nonexistent"
                             :field-values {}}))]
        (is (= ::anom/not-found (::anom/category result))))))

  (testing "returns conflict for inactive type"
    (h/with-test-context [ctx]
      (let [result1 (cmd/crm-create-contact-type
                      (assoc ctx :command
                             {:slug "customer"
                              :name "Customer"
                              :description "A customer"
                              :field-definitions []}))
            type-id (-> result1 :command-result/events first :type-id)]
        (h/apply-events! ctx result1)
        (h/apply-events! ctx
          (cmd/crm-deactivate-contact-type
            (assoc ctx :command {:type-id type-id})))
        (let [result (cmd/crm-create-contact
                       (assoc ctx :command
                              {:type-slug "customer"
                               :field-values {}}))]
          (is (= ::anom/conflict (::anom/category result)))))))

  (testing "validates required fields"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email :required true}]})))
      (let [result (cmd/crm-create-contact
                     (assoc ctx :command
                            {:type-slug "customer"
                             :field-values {}}))]
        (is (= ::anom/incorrect (::anom/category result))))))

  (testing "validates unique fields"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email :unique true}]})))
      (h/apply-events! ctx
        (cmd/crm-create-contact
          (assoc ctx :command
                 {:type-slug "customer"
                  :field-values {:email "test@example.com"}})))
      (let [result (cmd/crm-create-contact
                     (assoc ctx :command
                            {:type-slug "customer"
                             :field-values {:email "test@example.com"}}))]
        (is (= ::anom/conflict (::anom/category result)))))))

(deftest update-contact-test
  (testing "updates contact successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}]})))
      (let [contact-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "customer"
                                     :field-values {:email "old@example.com"}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (let [result (cmd/crm-update-contact
                       (assoc ctx :command
                              {:contact-id contact-id
                               :field-values {:email "new@example.com"}}))]
          (is (contains? result :command-result/events))
          (is (h/event-of-type? result :crm/contact-updated))))))

  (testing "returns not-found for unknown contact"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-update-contact
                     (assoc ctx :command
                            {:contact-id (random-uuid)
                             :field-values {:email "test@example.com"}}))]
        (is (= ::anom/not-found (::anom/category result)))))))

(deftest set-contact-field-test
  (testing "sets contact field successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}]})))
      (let [contact-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "customer"
                                     :field-values {:email "old@example.com"}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (let [result (cmd/crm-set-contact-field
                       (assoc ctx :command
                              {:contact-id contact-id
                               :field-slug "email"
                               :value "new@example.com"}))]
          (is (contains? result :command-result/events))
          (is (h/event-of-type? result :crm/contact-field-set))
          (let [event (first (h/get-result-events result))]
            (is (= "old@example.com" (:old-value event))))))))

  (testing "returns not-found for unknown contact"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-set-contact-field
                     (assoc ctx :command
                            {:contact-id (random-uuid)
                             :field-slug "email"
                             :value "test@example.com"}))]
        (is (= ::anom/not-found (::anom/category result)))))))

(deftest tag-contact-test
  (testing "tags contact successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions []})))
      (let [contact-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "customer"
                                     :field-values {}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (let [result (cmd/crm-tag-contact
                       (assoc ctx :command
                              {:contact-id contact-id
                               :tag "vip"}))]
          (is (contains? result :command-result/events))
          (is (h/event-of-type? result :crm/contact-tagged))))))

  (testing "returns not-found for unknown contact"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-tag-contact
                     (assoc ctx :command
                            {:contact-id (random-uuid)
                             :tag "vip"}))]
        (is (= ::anom/not-found (::anom/category result)))))))

(deftest untag-contact-test
  (testing "untags contact successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions []})))
      (let [contact-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "customer"
                                     :field-values {}
                                     :tags #{"vip"}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (let [result (cmd/crm-untag-contact
                       (assoc ctx :command
                              {:contact-id contact-id
                               :tag "vip"}))]
          (is (contains? result :command-result/events))
          (is (h/event-of-type? result :crm/contact-untagged))))))

  (testing "returns not-found for unknown contact"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-untag-contact
                     (assoc ctx :command
                            {:contact-id (random-uuid)
                             :tag "vip"}))]
        (is (= ::anom/not-found (::anom/category result)))))))

(deftest change-contact-status-test
  (testing "changes contact status successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions []})))
      (let [contact-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "customer"
                                     :field-values {}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (let [result (cmd/crm-change-contact-status
                       (assoc ctx :command
                              {:contact-id contact-id
                               :new-status :inactive}))]
          (is (contains? result :command-result/events))
          (is (h/event-of-type? result :crm/contact-status-changed))
          (let [event (first (h/get-result-events result))]
            (is (= :active (:old-status event))))))))

  (testing "returns not-found for unknown contact"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-change-contact-status
                     (assoc ctx :command
                            {:contact-id (random-uuid)
                             :new-status :inactive}))]
        (is (= ::anom/not-found (::anom/category result)))))))

(deftest archive-contact-test
  (testing "archives contact successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions []})))
      (let [contact-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "customer"
                                     :field-values {}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (let [result (cmd/crm-archive-contact
                       (assoc ctx :command {:contact-id contact-id}))]
          (is (contains? result :command-result/events))
          (is (h/event-of-type? result :crm/contact-archived))))))

  (testing "returns not-found for unknown contact"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-archive-contact
                     (assoc ctx :command {:contact-id (random-uuid)}))]
        (is (= ::anom/not-found (::anom/category result))))))

  (testing "returns conflict for already archived contact"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions []})))
      (let [contact-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "customer"
                                     :field-values {}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (h/apply-events! ctx
          (cmd/crm-archive-contact (assoc ctx :command {:contact-id contact-id})))
        (let [result (cmd/crm-archive-contact
                       (assoc ctx :command {:contact-id contact-id}))]
          (is (= ::anom/conflict (::anom/category result))))))))

(deftest restore-contact-test
  (testing "restores archived contact successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions []})))
      (let [contact-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "customer"
                                     :field-values {}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (h/apply-events! ctx
          (cmd/crm-archive-contact (assoc ctx :command {:contact-id contact-id})))
        (let [result (cmd/crm-restore-contact
                       (assoc ctx :command {:contact-id contact-id}))]
          (is (contains? result :command-result/events))
          (is (h/event-of-type? result :crm/contact-restored))))))

  (testing "returns not-found for unknown contact"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-restore-contact
                     (assoc ctx :command {:contact-id (random-uuid)}))]
        (is (= ::anom/not-found (::anom/category result))))))

  (testing "returns conflict for non-archived contact"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions []})))
      (let [contact-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "customer"
                                     :field-values {}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (let [result (cmd/crm-restore-contact
                       (assoc ctx :command {:contact-id contact-id}))]
          (is (= ::anom/conflict (::anom/category result))))))))

(deftest merge-contacts-test
  (testing "merges contacts successfully" 
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}]})))
      (let [primary-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "customer"
                                     :field-values {:email "primary@example.com"}}))
            primary-id (-> primary-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx primary-result)
            secondary-result (cmd/crm-create-contact
                               (assoc ctx :command
                                      {:type-slug "customer"
                                       :field-values {:email "secondary@example.com"}}))
            secondary-id (-> secondary-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx secondary-result)]
        (let [result (cmd/crm-merge-contacts
                       (assoc ctx :command
                              {:primary-contact-id primary-id
                               :secondary-contact-id secondary-id}))]
          (is (contains? result :command-result/events))
          (is (h/event-of-type? result :crm/contact-merged))))))

  (testing "returns not-found for unknown primary contact"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions []})))
      (let [contact-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "customer"
                                     :field-values {}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (let [result (cmd/crm-merge-contacts
                       (assoc ctx :command
                              {:primary-contact-id (random-uuid)
                               :secondary-contact-id contact-id}))]
          (is (= ::anom/not-found (::anom/category result)))))))

  (testing "rejects merge of contacts with different types"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions []})))
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "vendor"
                  :name "Vendor"
                  :description "A vendor"
                  :field-definitions []})))
      (let [customer-result (cmd/crm-create-contact
                              (assoc ctx :command
                                     {:type-slug "customer"
                                      :field-values {}}))
            customer-id (-> customer-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx customer-result)
            vendor-result (cmd/crm-create-contact
                            (assoc ctx :command
                                   {:type-slug "vendor"
                                    :field-values {}}))
            vendor-id (-> vendor-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx vendor-result)]
        (let [result (cmd/crm-merge-contacts
                       (assoc ctx :command
                              {:primary-contact-id customer-id
                               :secondary-contact-id vendor-id}))]
          (is (= ::anom/incorrect (::anom/category result)))))))

  (testing "rejects self-merge"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions []})))
      (let [contact-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "customer"
                                     :field-values {}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (let [result (cmd/crm-merge-contacts
                       (assoc ctx :command
                              {:primary-contact-id contact-id
                               :secondary-contact-id contact-id}))]
          (is (= ::anom/incorrect (::anom/category result))))))))

(deftest delete-contact-test
  (testing "deletes contact successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions []})))
      (let [contact-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "customer"
                                     :field-values {}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (let [result (cmd/crm-delete-contact
                       (assoc ctx :command {:contact-id contact-id}))]
          (is (contains? result :command-result/events))
          (is (h/event-of-type? result :crm/contact-deleted))))))

  (testing "returns not-found for unknown contact"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-delete-contact
                     (assoc ctx :command {:contact-id (random-uuid)}))]
        (is (= ::anom/not-found (::anom/category result)))))))

;; =============================================================================
;; Entry Point Command Tests
;; =============================================================================

(deftest capture-lead-from-form-test
  (testing "captures lead successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "lead"
                  :name "Lead"
                  :description "A lead"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}]})))
      (let [result (cmd/crm-capture-lead-from-form
                     (assoc ctx :command
                            {:contact-type-slug "lead"
                             :form-type :intake
                             :form-id (random-uuid)
                             :field-values {:email "lead@example.com"}}))]
        (is (contains? result :command-result/events))
        (is (h/event-of-type? result :crm/lead-captured))
        (is (h/event-of-type? result :crm/contact-created)))))

  (testing "returns not-found for unknown type"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-capture-lead-from-form
                     (assoc ctx :command
                            {:contact-type-slug "nonexistent"
                             :form-type :intake
                             :form-id (random-uuid)
                             :field-values {}}))]
        (is (= ::anom/not-found (::anom/category result)))))))

(deftest receive-referral-test
  (testing "receives referral successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}]})))
      (let [referrer-result (cmd/crm-create-contact
                              (assoc ctx :command
                                     {:type-slug "customer"
                                      :field-values {:email "referrer@example.com"}}))
            referrer-id (-> referrer-result :command-result/events first :contact-id)]
        (h/apply-events! ctx referrer-result)
        (let [result (cmd/crm-receive-referral
                       (assoc ctx :command
                              {:contact-type-slug "customer"
                               :referring-contact-id referrer-id
                               :field-values {:email "referred@example.com"}}))]
          (is (contains? result :command-result/events))
          (is (h/event-of-type? result :crm/referral-received))
          (is (h/event-of-type? result :crm/contact-created))))))

  (testing "returns not-found for unknown referrer"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions []})))
      (let [result (cmd/crm-receive-referral
                     (assoc ctx :command
                            {:contact-type-slug "customer"
                             :referring-contact-id (random-uuid)
                             :field-values {}}))]
        (is (= ::anom/not-found (::anom/category result)))))))

(deftest import-contacts-test
  (testing "imports multiple contacts successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}]})))
      (let [result (cmd/crm-import-contacts
                     (assoc ctx :command
                            {:import-id (random-uuid)
                             :contact-type-slug "customer"
                             :source-description "csv-upload"
                             :contacts [{:email "import1@example.com"}
                                        {:email "import2@example.com"}]}))]
        (is (contains? result :command-result/events))
        (is (h/event-of-type? result :crm/contacts-imported))
        (is (= 2 (count (filter #(= :crm/contact-created (:event/type %)) (h/get-result-events result)))))))))

(deftest resolve-duplicate-test
  (testing "resolves duplicate with merge"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions []})))
      (let [contact1-result (cmd/crm-create-contact
                              (assoc ctx :command
                                     {:type-slug "customer"
                                      :field-values {}}))
            contact1-id (-> contact1-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx contact1-result)
            contact2-result (cmd/crm-create-contact
                              (assoc ctx :command
                                     {:type-slug "customer"
                                      :field-values {}}))
            contact2-id (-> contact2-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx contact2-result)
            dup-id (random-uuid)]
        (es/append (:event-store ctx)
                   {:events [(es/->event {:type :crm/duplicate-detected
                                          :tags #{[:duplicate dup-id]
                                                  [:duplicate-primary contact1-id]
                                                  [:duplicate-candidate contact2-id]}
                                          :body {:duplicate-id dup-id
                                                 :contact-id contact1-id
                                                 :potential-duplicate-id contact2-id
                                                 :match-type :email
                                                 :match-value "test@example.com"
                                                 :confidence 1.0}})]})
        (let [result (cmd/crm-resolve-duplicate
                       (assoc ctx :command
                              {:duplicate-id dup-id
                               :resolution :merge
                               :primary-contact-id contact1-id}))]
          (is (contains? result :command-result/events))
          (is (h/event-of-type? result :crm/duplicate-resolved))))))

  (testing "resolves duplicate with dismiss"
    (h/with-test-context [ctx]
      (let [dup-id (random-uuid)
            contact-id (random-uuid)
            potential-id (random-uuid)]
        (es/append (:event-store ctx)
                   {:events [(es/->event {:type :crm/duplicate-detected
                                          :tags #{[:duplicate dup-id]
                                                  [:duplicate-primary contact-id]
                                                  [:duplicate-candidate potential-id]}
                                          :body {:duplicate-id dup-id
                                                 :contact-id contact-id
                                                 :potential-duplicate-id potential-id
                                                 :match-type :email
                                                 :match-value "test@example.com"
                                                 :confidence 1.0}})]})
        (let [result (cmd/crm-resolve-duplicate
                       (assoc ctx :command
                              {:duplicate-id dup-id
                               :resolution :dismiss}))]
          (is (contains? result :command-result/events))
          (is (h/event-of-type? result :crm/duplicate-resolved))))))

  (testing "returns not-found for unknown duplicate"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-resolve-duplicate
                     (assoc ctx :command
                            {:duplicate-id (random-uuid)
                             :resolution :dismiss}))]
        (is (= ::anom/not-found (::anom/category result)))))))

;; =============================================================================
;; Relationship Type Command Tests
;; =============================================================================

(deftest create-relationship-type-test
  (testing "creates relationship type successfully"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-create-relationship-type
                     (assoc ctx :command
                            {:slug "friend_of"
                             :name "Friend Of"
                             :inverse-name "Friend Of"
                             :source-type-slugs #{}
                             :target-type-slugs #{}}))]
        (is (contains? result :command-result/events))
        (is (h/event-of-type? result :crm/relationship-type-created)))))

  (testing "rejects duplicate slug"
    (h/with-test-context [ctx]
      (let [result1 (cmd/crm-create-relationship-type
                      (assoc ctx :command
                             {:slug "friend_of"
                              :name "Friend Of"
                              :inverse-name "Friend Of"
                              :source-type-slugs #{}
                              :target-type-slugs #{}}))]
        (h/apply-events! ctx result1)
        (let [result2 (cmd/crm-create-relationship-type
                        (assoc ctx :command
                               {:slug "friend_of"
                                :name "Another Friend Of"
                                :inverse-name "Another Friend Of"
                                :source-type-slugs #{}
                                :target-type-slugs #{}}))]
          (is (= ::anom/conflict (::anom/category result2))))))))

(deftest update-relationship-type-test
  (testing "updates relationship type successfully"
    (h/with-test-context [ctx]
      (let [result1 (cmd/crm-create-relationship-type
                      (assoc ctx :command
                             {:slug "friend_of"
                              :name "Friend Of"
                              :inverse-name "Friend Of"
                              :source-type-slugs #{}
                              :target-type-slugs #{}}))
            type-id (-> result1 :command-result/events first :type-id)]
        (h/apply-events! ctx result1)
        (let [result (cmd/crm-update-relationship-type
                       (assoc ctx :command
                              {:type-id type-id
                               :name "Best Friend Of"}))]
          (is (contains? result :command-result/events))
          (is (h/event-of-type? result :crm/relationship-type-updated))))))

  (testing "returns not-found for unknown type"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-update-relationship-type
                     (assoc ctx :command
                            {:type-id (random-uuid)
                             :name "Updated"}))]
        (is (= ::anom/not-found (::anom/category result)))))))

;; =============================================================================
;; Relationship Command Tests
;; =============================================================================

(deftest create-relationship-test
  (testing "creates relationship successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "person"
                  :name "Person"
                  :description "A person"
                  :field-definitions []})))
      (let [source-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            source-id (-> source-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx source-result)
            target-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            target-id (-> target-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx target-result)]
        (h/apply-events! ctx
          (cmd/crm-create-relationship-type
            (assoc ctx :command
                   {:slug "friend_of"
                    :name "Friend Of"
                    :inverse-name "Friend Of"
                    :source-type-slugs #{}
                    :target-type-slugs #{}})))
        (let [result (cmd/crm-create-relationship
                       (assoc ctx :command
                              {:type-slug "friend_of"
                               :source-contact-id source-id
                               :target-contact-id target-id}))]
          (is (contains? result :command-result/events))
          (is (h/event-of-type? result :crm/relationship-created))))))

  (testing "returns not-found for unknown type"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "person"
                  :name "Person"
                  :description "A person"
                  :field-definitions []})))
      (let [source-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            source-id (-> source-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx source-result)
            target-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            target-id (-> target-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx target-result)]
        (let [result (cmd/crm-create-relationship
                       (assoc ctx :command
                              {:type-slug "nonexistent"
                               :source-contact-id source-id
                               :target-contact-id target-id}))]
          (is (= ::anom/not-found (::anom/category result)))))))

  (testing "rejects self-relationship"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "person"
                  :name "Person"
                  :description "A person"
                  :field-definitions []})))
      (let [contact-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (h/apply-events! ctx
          (cmd/crm-create-relationship-type
            (assoc ctx :command
                   {:slug "friend_of"
                    :name "Friend Of"
                    :inverse-name "Friend Of"
                    :source-type-slugs #{}
                    :target-type-slugs #{}})))
        (let [result (cmd/crm-create-relationship
                       (assoc ctx :command
                              {:type-slug "friend_of"
                               :source-contact-id contact-id
                               :target-contact-id contact-id}))]
          (is (= ::anom/incorrect (::anom/category result))))))))

(deftest update-relationship-test
  (testing "updates relationship successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "person"
                  :name "Person"
                  :description "A person"
                  :field-definitions []})))
      (let [source-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            source-id (-> source-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx source-result)
            target-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            target-id (-> target-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx target-result)]
        (h/apply-events! ctx
          (cmd/crm-create-relationship-type
            (assoc ctx :command
                   {:slug "friend_of"
                    :name "Friend Of"
                    :inverse-name "Friend Of"
                    :source-type-slugs #{}
                    :target-type-slugs #{}})))
        (let [rel-result (cmd/crm-create-relationship
                           (assoc ctx :command
                                  {:type-slug "friend_of"
                                   :source-contact-id source-id
                                   :target-contact-id target-id}))
              rel-id (-> rel-result :command-result/events first :relationship-id)]
          (h/apply-events! ctx rel-result)
          (let [result (cmd/crm-update-relationship
                         (assoc ctx :command
                                {:relationship-id rel-id
                                 :properties {:notes "Best friends"}}))]
            (is (contains? result :command-result/events))
            (is (h/event-of-type? result :crm/relationship-updated)))))))

  (testing "returns not-found for unknown relationship"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-update-relationship
                     (assoc ctx :command
                            {:relationship-id (random-uuid)
                             :properties {:notes "Updated"}}))]
        (is (= ::anom/not-found (::anom/category result)))))))

(deftest end-relationship-test
  (testing "ends relationship successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "person"
                  :name "Person"
                  :description "A person"
                  :field-definitions []})))
      (let [source-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            source-id (-> source-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx source-result)
            target-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            target-id (-> target-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx target-result)]
        (h/apply-events! ctx
          (cmd/crm-create-relationship-type
            (assoc ctx :command
                   {:slug "friend_of"
                    :name "Friend Of"
                    :inverse-name "Friend Of"
                    :source-type-slugs #{}
                    :target-type-slugs #{}})))
        (let [rel-result (cmd/crm-create-relationship
                           (assoc ctx :command
                                  {:type-slug "friend_of"
                                   :source-contact-id source-id
                                   :target-contact-id target-id}))
              rel-id (-> rel-result :command-result/events first :relationship-id)]
          (h/apply-events! ctx rel-result)
          (let [result (cmd/crm-end-relationship
                         (assoc ctx :command
                                {:relationship-id rel-id
                                 :end-date "2024-01-01"
                                 :reason "Moved away"}))]
            (is (contains? result :command-result/events))
            (is (h/event-of-type? result :crm/relationship-ended)))))))

  (testing "returns not-found for unknown relationship"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-end-relationship
                     (assoc ctx :command
                            {:relationship-id (random-uuid)}))]
        (is (= ::anom/not-found (::anom/category result)))))))

(deftest delete-relationship-test
  (testing "deletes relationship successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "person"
                  :name "Person"
                  :description "A person"
                  :field-definitions []})))
      (let [source-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            source-id (-> source-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx source-result)
            target-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            target-id (-> target-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx target-result)]
        (h/apply-events! ctx
          (cmd/crm-create-relationship-type
            (assoc ctx :command
                   {:slug "friend_of"
                    :name "Friend Of"
                    :inverse-name "Friend Of"
                    :source-type-slugs #{}
                    :target-type-slugs #{}})))
        (let [rel-result (cmd/crm-create-relationship
                           (assoc ctx :command
                                  {:type-slug "friend_of"
                                   :source-contact-id source-id
                                   :target-contact-id target-id}))
              rel-id (-> rel-result :command-result/events first :relationship-id)]
          (h/apply-events! ctx rel-result)
          (let [result (cmd/crm-delete-relationship
                         (assoc ctx :command {:relationship-id rel-id}))]
            (is (contains? result :command-result/events))
            (is (h/event-of-type? result :crm/relationship-deleted)))))))

  (testing "returns not-found for unknown relationship"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-delete-relationship
                     (assoc ctx :command {:relationship-id (random-uuid)}))]
        (is (= ::anom/not-found (::anom/category result)))))))

(deftest set-primary-relationship-test
  (testing "sets primary relationship successfully"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "person"
                  :name "Person"
                  :description "A person"
                  :field-definitions []})))
      (let [source-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            source-id (-> source-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx source-result)
            target-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            target-id (-> target-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx target-result)]
        (h/apply-events! ctx
          (cmd/crm-create-relationship-type
            (assoc ctx :command
                   {:slug "friend_of"
                    :name "Friend Of"
                    :inverse-name "Friend Of"
                    :source-type-slugs #{}
                    :target-type-slugs #{}})))
        (let [rel-result (cmd/crm-create-relationship
                           (assoc ctx :command
                                  {:type-slug "friend_of"
                                   :source-contact-id source-id
                                   :target-contact-id target-id}))
              rel-id (-> rel-result :command-result/events first :relationship-id)]
          (h/apply-events! ctx rel-result)
          (let [result (cmd/crm-set-primary-relationship
                         (assoc ctx :command
                                {:relationship-id rel-id
                                 :contact-id source-id}))]
            (is (contains? result :command-result/events))
            (is (h/event-of-type? result :crm/primary-relationship-set)))))))

  (testing "returns not-found for unknown relationship"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-set-primary-relationship
                     (assoc ctx :command
                            {:relationship-id (random-uuid)
                             :contact-id (random-uuid)}))]
        (is (= ::anom/not-found (::anom/category result)))))))

;; =============================================================================
;; Communication Commands
;; =============================================================================

(deftest log-communication-test
  (testing "logs email communication successfully"
    (h/with-test-context [ctx]
      ;; Setup: create contact types and contacts
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command {:slug "student" :name "Student" :field-definitions []})))
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command {:slug "staff" :name "Staff" :field-definitions []})))
      (let [student-result (cmd/crm-create-contact
                             (assoc ctx :command {:type-slug "student" :field-values {}}))
            student-id (-> student-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx student-result)
            staff-result (cmd/crm-create-contact
                           (assoc ctx :command {:type-slug "staff" :field-values {}}))
            staff-id (-> staff-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx staff-result)
            result (cmd/crm-log-communication
                     (assoc ctx :command
                            {:contact-id student-id
                             :communication-type :email
                             :direction :outbound
                             :sender {:contact-id staff-id}
                             :recipient {:contact-id student-id}
                             :logged-by-contact-id staff-id
                             :subject "Welcome to BRYC"
                             :content "Hello, welcome to our program!"}))]
        (is (contains? result :command-result/events))
        (is (= 1 (count (:command-result/events result))))
        (let [event (-> result :command-result/events first)]
          (is (= :crm/communication-logged (:event/type event)))
          (is (= :email (:communication-type event)))
          (is (= :outbound (:direction event)))
          (is (= "Welcome to BRYC" (:subject event)))
          (is (some? (:communication-id event)))))))

  (testing "logs SMS communication with external sender"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command {:slug "student" :name "Student" :field-definitions []})))
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command {:slug "staff" :name "Staff" :field-definitions []})))
      (let [student-result (cmd/crm-create-contact
                             (assoc ctx :command {:type-slug "student" :field-values {}}))
            student-id (-> student-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx student-result)
            staff-result (cmd/crm-create-contact
                           (assoc ctx :command {:type-slug "staff" :field-values {}}))
            staff-id (-> staff-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx staff-result)
            result (cmd/crm-log-communication
                     (assoc ctx :command
                            {:contact-id student-id
                             :communication-type :sms
                             :direction :inbound
                             :sender {:name "Unknown" :phone "5551234567"}
                             :recipient {:contact-id staff-id}
                             :logged-by-contact-id staff-id
                             :content "Question about enrollment"}))]
        (is (contains? result :command-result/events))
        (let [event (-> result :command-result/events first)]
          (is (= :sms (:communication-type event)))
          (is (= :inbound (:direction event)))
          (is (= {:name "Unknown" :phone "5551234567"} (:sender event)))))))

  (testing "returns not-found when contact doesn't exist"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command {:slug "staff" :name "Staff" :field-definitions []})))
      (let [staff-result (cmd/crm-create-contact
                           (assoc ctx :command {:type-slug "staff" :field-values {}}))
            staff-id (-> staff-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx staff-result)
            result (cmd/crm-log-communication
                     (assoc ctx :command
                            {:contact-id (random-uuid)
                             :communication-type :email
                             :direction :outbound
                             :sender {:contact-id staff-id}
                             :recipient {:contact-id (random-uuid)}
                             :logged-by-contact-id staff-id
                             :content "Test"}))]
        (is (= ::anom/not-found (::anom/category result)))
        (is (re-find #"Contact not found" (::anom/message result))))))

  (testing "returns not-found when logger doesn't exist"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command {:slug "student" :name "Student" :field-definitions []})))
      (let [student-result (cmd/crm-create-contact
                             (assoc ctx :command {:type-slug "student" :field-values {}}))
            student-id (-> student-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx student-result)
            result (cmd/crm-log-communication
                     (assoc ctx :command
                            {:contact-id student-id
                             :communication-type :email
                             :direction :outbound
                             :sender {:contact-id (random-uuid)}
                             :recipient {:contact-id student-id}
                             :logged-by-contact-id (random-uuid)
                             :content "Test"}))]
        (is (= ::anom/not-found (::anom/category result)))
        (is (re-find #"Logging staff member not found" (::anom/message result)))))))
