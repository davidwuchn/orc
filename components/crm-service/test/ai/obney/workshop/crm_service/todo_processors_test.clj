(ns ai.obney.workshop.crm-service.todo-processors-test
  "Unit tests for CRM service todo processors (event-driven side effects)."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [ai.obney.workshop.crm-service.test-helpers :as h]
            [ai.obney.workshop.crm-service.interface.read-models :as rm]
            [ai.obney.workshop.crm-service.core.todo-processors :as tp]
            [ai.obney.grain.event-store-v2.interface :as es]))

;; =============================================================================
;; Helper Function Tests
;; =============================================================================

(deftest normalize-email-processor-test
  (testing "normalizes email correctly"
    (is (= "test@example.com" (tp/normalize-email "  TEST@Example.COM  "))))

  (testing "returns nil for nil input"
    (is (nil? (tp/normalize-email nil))))

  (testing "returns nil for blank input"
    (is (nil? (tp/normalize-email "   ")))))

(deftest normalize-phone-processor-test
  (testing "extracts digits from phone"
    (is (= "5551234567" (tp/normalize-phone "(555) 123-4567"))))

  (testing "returns nil for nil input"
    (is (nil? (tp/normalize-phone nil))))

  (testing "returns nil for blank input"
    (is (nil? (tp/normalize-phone "   ")))))

;; =============================================================================
;; Attribution Processor Tests
;; =============================================================================

(deftest ensure-attribution-test
  (testing "records default attribution when contact has none"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (h/run-command ctx {:command/name :crm/create-contact-type
                            :slug "customer"
                            :name "Customer"
                            :description "Test type"
                            :field-definitions []}))
      (let [result (h/run-command ctx {:command/name :crm/create-contact
                                       :type-slug "customer"
                                       :field-values {}})
            contact-id (-> result :command-result/events first :contact-id)]
        (h/apply-events! ctx result)
        (let [event {:contact-id contact-id}
              result (tp/ensure-attribution {:event event
                                             :event-store (:event-store ctx)})]
          (is (contains? result :result/events))
          (is (= 1 (count (:result/events result))))
          (is (= :crm/attribution-recorded (:event/type (first (:result/events result)))))
          (is (= :manual_entry (get-in (first (:result/events result)) [:attribution :source])))))))

  (testing "skips attribution when contact already has one"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (h/run-command ctx {:command/name :crm/create-contact-type
                            :slug "customer"
                            :name "Customer"
                            :description "Test type"
                            :field-definitions []}))
      (let [result (h/run-command ctx {:command/name :crm/create-contact
                                       :type-slug "customer"
                                       :field-values {}})
            contact-id (-> result :command-result/events first :contact-id)]
        (h/apply-events! ctx result)
        ;; Apply attribution event
        (es/append (:event-store ctx)
                   {:events [(es/->event {:type :crm/attribution-recorded
                                          :tags #{[:contact contact-id]}
                                          :body {:contact-id contact-id
                                                 :attribution {:source :referral
                                                               :recorded-at "2025-01-01T00:00:00Z"}}})]})
        (let [event {:contact-id contact-id}
              result (tp/ensure-attribution {:event event
                                             :event-store (:event-store ctx)})]
          (is (= {} result)))))))

(deftest record-lead-attribution-test
  (testing "records form-based attribution for intake form"
    (let [contact-id (random-uuid)
          form-id (random-uuid)
          event {:contact-id contact-id
                 :form-type :intake
                 :form-id form-id}
          result (tp/record-lead-attribution {:event event})]
      (is (contains? result :result/events))
      (is (= :crm/attribution-recorded (:event/type (first (:result/events result)))))
      (is (= :intake_form (get-in (first (:result/events result)) [:attribution :source])))))

  (testing "records form-based attribution for application form"
    (let [contact-id (random-uuid)
          form-id (random-uuid)
          event {:contact-id contact-id
                 :form-type :application
                 :form-id form-id}
          result (tp/record-lead-attribution {:event event})]
      (is (= :application_form (get-in (first (:result/events result)) [:attribution :source]))))))

(deftest record-referral-attribution-test
  (testing "records referral attribution with referring contact"
    (let [contact-id (random-uuid)
          referrer-id (random-uuid)
          event {:contact-id contact-id
                 :referring-contact-id referrer-id
                 :referral-notes "Friend recommendation"}
          result (tp/record-referral-attribution {:event event})]
      (is (contains? result :result/events))
      (is (= :crm/attribution-recorded (:event/type (first (:result/events result)))))
      (is (= :referral (get-in (first (:result/events result)) [:attribution :source])))
      (is (= referrer-id (get-in (first (:result/events result)) [:attribution :referring-contact-id]))))))

;; =============================================================================
;; Duplicate Detection Processor Tests
;; =============================================================================

(deftest check-for-duplicates-test
  (testing "detects duplicate by email"
    (h/with-test-context [ctx]
      (let [new-id (random-uuid)]
        (h/apply-events! ctx
          (h/run-command ctx {:command/name :crm/create-contact-type
                              :slug "customer"
                              :name "Customer"
                              :description "Test type"
                              :field-definitions [{:name "Email" :slug "email" :data-type :email}]}))
        (h/apply-events! ctx
          (h/run-command ctx {:command/name :crm/create-contact
                              :type-slug "customer"
                              :field-values {:email "test@example.com"}}))
        (let [event {:contact-id new-id
                     :type-slug "customer"
                     :field-values {:email "TEST@Example.COM"}}
              result (tp/check-for-duplicates {:event event
                                               :event-store (:event-store ctx)})]
          (is (contains? result :result/events))
          (is (= 1 (count (:result/events result))))
          (is (= :crm/duplicate-detected (:event/type (first (:result/events result)))))
          (is (= :email (:match-type (first (:result/events result)))))))))

  (testing "detects duplicate by phone"
    (h/with-test-context [ctx]
      (let [new-id (random-uuid)]
        (h/apply-events! ctx
          (h/run-command ctx {:command/name :crm/create-contact-type
                              :slug "customer"
                              :name "Customer"
                              :description "Test type"
                              :field-definitions [{:name "Phone" :slug "phone" :data-type :phone}]}))
        (h/apply-events! ctx
          (h/run-command ctx {:command/name :crm/create-contact
                              :type-slug "customer"
                              :field-values {:phone "5551234567"}}))
        (let [event {:contact-id new-id
                     :type-slug "customer"
                     :field-values {:phone "5551234567"}}
              result (tp/check-for-duplicates {:event event
                                               :event-store (:event-store ctx)})]
          (is (contains? result :result/events))
          (is (= :phone (:match-type (first (:result/events result)))))))))

  (testing "returns empty when no duplicates found"
    (h/with-test-context [ctx]
      (let [new-id (random-uuid)]
        (h/apply-events! ctx
          (h/run-command ctx {:command/name :crm/create-contact-type
                              :slug "customer"
                              :name "Customer"
                              :description "Test type"
                              :field-definitions [{:name "Email" :slug "email" :data-type :email}]}))
        (h/apply-events! ctx
          (h/run-command ctx {:command/name :crm/create-contact
                              :type-slug "customer"
                              :field-values {:email "existing@example.com"}}))
        (let [event {:contact-id new-id
                     :type-slug "customer"
                     :field-values {:email "different@example.com"}}
              result (tp/check-for-duplicates {:event event
                                               :event-store (:event-store ctx)})]
          (is (= {} result)))))))

;; =============================================================================
;; Merge Processor Tests
;; =============================================================================

(deftest transfer-relationships-on-merge-test
  (testing "ends all secondary contact relationships"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (h/run-command ctx {:command/name :crm/create-contact-type
                            :slug "person"
                            :name "Person"
                            :description "Test type"
                            :field-definitions []}))
      (let [primary-result (h/run-command ctx {:command/name :crm/create-contact
                                               :type-slug "person"
                                               :field-values {}})
            primary-id (-> primary-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx primary-result)
            secondary-result (h/run-command ctx {:command/name :crm/create-contact
                                                 :type-slug "person"
                                                 :field-values {}})
            secondary-id (-> secondary-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx secondary-result)
            other-result (h/run-command ctx {:command/name :crm/create-contact
                                             :type-slug "person"
                                             :field-values {}})
            other-id (-> other-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx other-result)]
        (h/apply-events! ctx
          (h/run-command ctx {:command/name :crm/create-relationship-type
                              :slug "friend_of"
                              :name "Friend Of"
                              :inverse-name "Friend Of"
                              :source-type-slugs #{}
                              :target-type-slugs #{}}))
        (h/apply-events! ctx
          (h/run-command ctx {:command/name :crm/create-relationship
                              :type-slug "friend_of"
                              :source-contact-id secondary-id
                              :target-contact-id other-id}))
        (let [event {:primary-contact-id primary-id
                     :secondary-contact-id secondary-id}
              result (tp/transfer-relationships-on-merge {:event event
                                                          :event-store (:event-store ctx)})]
          (is (contains? result :result/events))
          (is (= 1 (count (:result/events result))))
          (is (= :crm/relationship-ended (:event/type (first (:result/events result)))))))))

  (testing "returns empty when secondary has no relationships"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (h/run-command ctx {:command/name :crm/create-contact-type
                            :slug "person"
                            :name "Person"
                            :description "Test type"
                            :field-definitions []}))
      (let [primary-result (h/run-command ctx {:command/name :crm/create-contact
                                               :type-slug "person"
                                               :field-values {}})
            primary-id (-> primary-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx primary-result)
            secondary-result (h/run-command ctx {:command/name :crm/create-contact
                                                 :type-slug "person"
                                                 :field-values {}})
            secondary-id (-> secondary-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx secondary-result)]
        (let [event {:primary-contact-id primary-id
                     :secondary-contact-id secondary-id}
              result (tp/transfer-relationships-on-merge {:event event
                                                          :event-store (:event-store ctx)})]
          (is (= {} result)))))))

;; =============================================================================
;; Archive Processor Tests
;; =============================================================================

(deftest end-relationships-on-archive-test
  (testing "ends all active relationships when contact archived"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (h/run-command ctx {:command/name :crm/create-contact-type
                            :slug "person"
                            :name "Person"
                            :description "Test type"
                            :field-definitions []}))
      (let [contact-result (h/run-command ctx {:command/name :crm/create-contact
                                               :type-slug "person"
                                               :field-values {}})
            contact-id (-> contact-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx contact-result)
            other1-result (h/run-command ctx {:command/name :crm/create-contact
                                              :type-slug "person"
                                              :field-values {}})
            other1-id (-> other1-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx other1-result)
            other2-result (h/run-command ctx {:command/name :crm/create-contact
                                              :type-slug "person"
                                              :field-values {}})
            other2-id (-> other2-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx other2-result)]
        (h/apply-events! ctx
          (h/run-command ctx {:command/name :crm/create-relationship-type
                              :slug "friend_of"
                              :name "Friend Of"
                              :inverse-name "Friend Of"
                              :source-type-slugs #{}
                              :target-type-slugs #{}}))
        (h/apply-events! ctx
          (h/run-command ctx {:command/name :crm/create-relationship
                              :type-slug "friend_of"
                              :source-contact-id contact-id
                              :target-contact-id other1-id}))
        (h/apply-events! ctx
          (h/run-command ctx {:command/name :crm/create-relationship
                              :type-slug "friend_of"
                              :source-contact-id other2-id
                              :target-contact-id contact-id}))
        (let [event {:contact-id contact-id}
              result (tp/end-relationships-on-archive {:event event
                                                       :event-store (:event-store ctx)})]
          (is (contains? result :result/events))
          (is (= 2 (count (:result/events result))))
          (is (every? #(= :crm/relationship-ended (:event/type %)) (:result/events result)))))))

  (testing "returns empty when contact has no active relationships"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (h/run-command ctx {:command/name :crm/create-contact-type
                            :slug "person"
                            :name "Person"
                            :description "Test type"
                            :field-definitions []}))
      (let [contact-result (h/run-command ctx {:command/name :crm/create-contact
                                               :type-slug "person"
                                               :field-values {}})
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (let [event {:contact-id contact-id}
              result (tp/end-relationships-on-archive {:event event
                                                       :event-store (:event-store ctx)})]
          (is (= {} result)))))))
