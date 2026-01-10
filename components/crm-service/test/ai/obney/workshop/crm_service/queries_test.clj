(ns ai.obney.workshop.crm-service.queries-test
  "Unit tests for CRM service query handlers."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [ai.obney.workshop.crm-service.test-helpers :as h]
            [ai.obney.workshop.crm-service.core.commands :as cmd]
            [ai.obney.workshop.crm-service.core.queries :as qry]
            [ai.obney.workshop.crm-service.interface.read-models :as rm]
            [ai.obney.grain.event-store-v2.interface :as es]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Contact Type Query Tests
;; =============================================================================

(deftest list-contact-types-test
  (testing "lists all active contact types"
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
      (let [result (qry/crm-list-contact-types
                     (assoc ctx :query {}))]
        (is (contains? result :query/result))
        (is (= 2 (count (get-in result [:query/result :contact-types])))))))

  (testing "includes inactive types when requested"
    (h/with-test-context [ctx]
      (let [create-result (cmd/crm-create-contact-type
                            (assoc ctx :command
                                   {:slug "customer"
                                    :name "Customer"
                                    :description "A customer"
                                    :field-definitions []}))
            type-id (-> create-result :command-result/events first :type-id)]
        (h/apply-events! ctx create-result)
        (h/apply-events! ctx
          (cmd/crm-deactivate-contact-type
            (assoc ctx :command {:type-id type-id})))
        (let [result-active (qry/crm-list-contact-types
                              (assoc ctx :query {:include-inactive false}))
              result-all (qry/crm-list-contact-types
                           (assoc ctx :query {:include-inactive true}))]
          (is (= 0 (count (get-in result-active [:query/result :contact-types]))))
          (is (= 1 (count (get-in result-all [:query/result :contact-types])))))))))

(deftest get-contact-type-test
  (testing "gets contact type by id"
    (h/with-test-context [ctx]
      (let [create-result (cmd/crm-create-contact-type
                            (assoc ctx :command
                                   {:slug "customer"
                                    :name "Customer"
                                    :description "A customer"
                                    :field-definitions []}))
            type-id (-> create-result :command-result/events first :type-id)]
        (h/apply-events! ctx create-result)
        (let [result (qry/crm-get-contact-type
                       (assoc ctx :query {:type-id type-id}))]
          (is (contains? result :query/result))
          (is (= "customer" (get-in result [:query/result :contact-type :slug])))))))

  (testing "gets contact type by slug"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions []})))
      (let [result (qry/crm-get-contact-type
                     (assoc ctx :query {:type-slug "customer"}))]
        (is (contains? result :query/result))
        (is (= "Customer" (get-in result [:query/result :contact-type :name]))))))

  (testing "returns not-found for unknown type"
    (h/with-test-context [ctx]
      (let [result (qry/crm-get-contact-type
                     (assoc ctx :query {:type-id (random-uuid)}))]
        (is (= ::anom/not-found (::anom/category result)))))))

;; =============================================================================
;; Contact Query Tests
;; =============================================================================

(deftest list-contacts-test
  (testing "lists all contacts"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}]})))
      (h/apply-events! ctx
        (cmd/crm-create-contact
          (assoc ctx :command
                 {:type-slug "customer"
                  :field-values {:email "test1@example.com"}})))
      (h/apply-events! ctx
        (cmd/crm-create-contact
          (assoc ctx :command
                 {:type-slug "customer"
                  :field-values {:email "test2@example.com"}})))
      (let [result (qry/crm-list-contacts (assoc ctx :query {}))]
        (is (contains? result :query/result))
        (is (= 2 (get-in result [:query/result :total]))))))

  (testing "filters by type-slug"
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
      (h/apply-events! ctx
        (cmd/crm-create-contact
          (assoc ctx :command
                 {:type-slug "customer"
                  :field-values {}})))
      (h/apply-events! ctx
        (cmd/crm-create-contact
          (assoc ctx :command
                 {:type-slug "vendor"
                  :field-values {}})))
      (let [result (qry/crm-list-contacts
                     (assoc ctx :query {:type-slug "customer"}))]
        (is (= 1 (get-in result [:query/result :total]))))))

  (testing "supports pagination"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "A customer"
                  :field-definitions []})))
      (dotimes [_ 5]
        (h/apply-events! ctx
          (cmd/crm-create-contact
            (assoc ctx :command
                   {:type-slug "customer"
                    :field-values {}}))))
      (let [result (qry/crm-list-contacts
                     (assoc ctx :query {:limit 2 :offset 1}))]
        (is (= 5 (get-in result [:query/result :total])))
        (is (= 2 (count (get-in result [:query/result :contacts]))))))))

(deftest get-contact-test
  (testing "gets contact with relationships"
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
                                     :field-values {:email "test@example.com"}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (let [result (qry/crm-get-contact
                       (assoc ctx :query {:contact-id contact-id}))]
          (is (contains? result :query/result))
          (is (= contact-id (get-in result [:query/result :contact :id])))
          (is (contains? (get-in result [:query/result :contact]) :relationships))))))

  (testing "returns not-found for unknown contact"
    (h/with-test-context [ctx]
      (let [result (qry/crm-get-contact
                     (assoc ctx :query {:contact-id (random-uuid)}))]
        (is (= ::anom/not-found (::anom/category result)))))))

(deftest get-contact-relationships-test
  (testing "gets all relationships for contact"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "person"
                  :name "Person"
                  :description "A person"
                  :field-definitions []})))
      (let [contact1-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            contact1-id (-> contact1-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx contact1-result)
            contact2-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            contact2-id (-> contact2-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx contact2-result)]
        (h/apply-events! ctx
          (cmd/crm-create-relationship-type
            (assoc ctx :command
                   {:slug "friend_of"
                    :name "Friend Of"
                    :inverse-name "Friend Of"
                    :source-type-slugs #{}
                    :target-type-slugs #{}})))
        (h/apply-events! ctx
          (cmd/crm-create-relationship
            (assoc ctx :command
                   {:type-slug "friend_of"
                    :source-contact-id contact1-id
                    :target-contact-id contact2-id})))
        (let [result (qry/crm-get-contact-relationships
                       (assoc ctx :query {:contact-id contact1-id}))]
          (is (contains? result :query/result))
          (is (= 1 (count (get-in result [:query/result :relationships]))))))))

  (testing "filters by relationship type"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "person"
                  :name "Person"
                  :description "A person"
                  :field-definitions []})))
      (let [contact1-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            contact1-id (-> contact1-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx contact1-result)
            contact2-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            contact2-id (-> contact2-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx contact2-result)
            contact3-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            contact3-id (-> contact3-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx contact3-result)]
        (h/apply-events! ctx
          (cmd/crm-create-relationship-type
            (assoc ctx :command
                   {:slug "friend_of"
                    :name "Friend Of"
                    :inverse-name "Friend Of"
                    :source-type-slugs #{}
                    :target-type-slugs #{}})))
        (h/apply-events! ctx
          (cmd/crm-create-relationship-type
            (assoc ctx :command
                   {:slug "colleague_of"
                    :name "Colleague Of"
                    :inverse-name "Colleague Of"
                    :source-type-slugs #{}
                    :target-type-slugs #{}})))
        (h/apply-events! ctx
          (cmd/crm-create-relationship
            (assoc ctx :command
                   {:type-slug "friend_of"
                    :source-contact-id contact1-id
                    :target-contact-id contact2-id})))
        (h/apply-events! ctx
          (cmd/crm-create-relationship
            (assoc ctx :command
                   {:type-slug "colleague_of"
                    :source-contact-id contact1-id
                    :target-contact-id contact3-id})))
        (let [result (qry/crm-get-contact-relationships
                       (assoc ctx :query {:contact-id contact1-id
                                          :type-slug "friend_of"}))]
          (is (= 1 (count (get-in result [:query/result :relationships])))))))))

(deftest get-contact-graph-test
  (testing "returns contact graph with BFS traversal"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "person"
                  :name "Person"
                  :description "A person"
                  :field-definitions []})))
      (let [contact1-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            contact1-id (-> contact1-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx contact1-result)
            contact2-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            contact2-id (-> contact2-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx contact2-result)
            contact3-result (cmd/crm-create-contact (assoc ctx :command {:type-slug "person" :field-values {}}))
            contact3-id (-> contact3-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx contact3-result)]
        (h/apply-events! ctx
          (cmd/crm-create-relationship-type
            (assoc ctx :command
                   {:slug "knows"
                    :name "Knows"
                    :inverse-name "Known By"
                    :source-type-slugs #{}
                    :target-type-slugs #{}})))
        ;; 1 -> 2 -> 3
        (h/apply-events! ctx
          (cmd/crm-create-relationship
            (assoc ctx :command
                   {:type-slug "knows"
                    :source-contact-id contact1-id
                    :target-contact-id contact2-id})))
        (h/apply-events! ctx
          (cmd/crm-create-relationship
            (assoc ctx :command
                   {:type-slug "knows"
                    :source-contact-id contact2-id
                    :target-contact-id contact3-id})))
        (let [result (qry/crm-get-contact-graph
                       (assoc ctx :query {:contact-id contact1-id :depth 2}))]
          (is (contains? result :query/result))
          (is (= 3 (count (get-in result [:query/result :nodes]))))
          ;; At least 2 edges (may include duplicates from BFS traversal)
          (is (>= (count (get-in result [:query/result :edges])) 2)))))))

;; =============================================================================
;; Relationship Type Query Tests
;; =============================================================================

(deftest list-relationship-types-test
  (testing "lists all active relationship types"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-relationship-type
          (assoc ctx :command
                 {:slug "friend_of"
                  :name "Friend Of"
                  :inverse-name "Friend Of"
                  :source-type-slugs #{}
                  :target-type-slugs #{}})))
      (h/apply-events! ctx
        (cmd/crm-create-relationship-type
          (assoc ctx :command
                 {:slug "parent_of"
                  :name "Parent Of"
                  :inverse-name "Child Of"
                  :source-type-slugs #{}
                  :target-type-slugs #{}})))
      (let [result (qry/crm-list-relationship-types
                     (assoc ctx :query {}))]
        (is (contains? result :query/result))
        (is (= 2 (count (get-in result [:query/result :relationship-types]))))))))

(deftest get-relationship-type-test
  (testing "gets relationship type by id"
    (h/with-test-context [ctx]
      (let [create-result (cmd/crm-create-relationship-type
                            (assoc ctx :command
                                   {:slug "friend_of"
                                    :name "Friend Of"
                                    :inverse-name "Friend Of"
                                    :source-type-slugs #{}
                                    :target-type-slugs #{}}))
            type-id (-> create-result :command-result/events first :type-id)]
        (h/apply-events! ctx create-result)
        (let [result (qry/crm-get-relationship-type
                       (assoc ctx :query {:type-id type-id}))]
          (is (contains? result :query/result))
          (is (= "friend_of" (get-in result [:query/result :relationship-type :slug])))))))

  (testing "gets relationship type by slug"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-relationship-type
          (assoc ctx :command
                 {:slug "friend_of"
                  :name "Friend Of"
                  :inverse-name "Friend Of"
                  :source-type-slugs #{}
                  :target-type-slugs #{}})))
      (let [result (qry/crm-get-relationship-type
                     (assoc ctx :query {:type-slug "friend_of"}))]
        (is (contains? result :query/result))
        (is (= "Friend Of" (get-in result [:query/result :relationship-type :name]))))))

  (testing "returns not-found for unknown type"
    (h/with-test-context [ctx]
      (let [result (qry/crm-get-relationship-type
                     (assoc ctx :query {:type-id (random-uuid)}))]
        (is (= ::anom/not-found (::anom/category result)))))))

;; =============================================================================
;; Duplicate Query Tests
;; =============================================================================

(deftest list-duplicate-candidates-test
  (testing "lists all duplicate candidates"
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
        (let [result (qry/crm-list-duplicate-candidates
                       (assoc ctx :query {}))]
          (is (contains? result :query/result))
          (is (= 1 (count (get-in result [:query/result :duplicates]))))))))

  (testing "filters by status"
    (h/with-test-context [ctx]
      (let [dup1-id (random-uuid)
            dup2-id (random-uuid)
            contact1-id (random-uuid)
            contact2-id (random-uuid)
            potential1-id (random-uuid)
            potential2-id (random-uuid)]
        (es/append (:event-store ctx)
                   {:events [(es/->event {:type :crm/duplicate-detected
                                          :tags #{[:duplicate dup1-id]
                                                  [:duplicate-primary contact1-id]
                                                  [:duplicate-candidate potential1-id]}
                                          :body {:duplicate-id dup1-id
                                                 :contact-id contact1-id
                                                 :potential-duplicate-id potential1-id
                                                 :match-type :email
                                                 :match-value "test1@example.com"
                                                 :confidence 1.0}})]})
        (es/append (:event-store ctx)
                   {:events [(es/->event {:type :crm/duplicate-detected
                                          :tags #{[:duplicate dup2-id]
                                                  [:duplicate-primary contact2-id]
                                                  [:duplicate-candidate potential2-id]}
                                          :body {:duplicate-id dup2-id
                                                 :contact-id contact2-id
                                                 :potential-duplicate-id potential2-id
                                                 :match-type :email
                                                 :match-value "test2@example.com"
                                                 :confidence 1.0}})]})
        (es/append (:event-store ctx)
                   {:events [(es/->event {:type :crm/duplicate-resolved
                                          :tags #{[:duplicate dup2-id]
                                                  [:duplicate-primary contact2-id]
                                                  [:duplicate-candidate potential2-id]}
                                          :body {:duplicate-id dup2-id
                                                 :resolution :dismiss}})]})
        (let [result (qry/crm-list-duplicate-candidates
                       (assoc ctx :query {:status :pending}))]
          (is (= 1 (count (get-in result [:query/result :duplicates])))))))))

;; =============================================================================
;; Cross-Type Query Tests
;; =============================================================================

(deftest search-contacts-test
  (testing "searches contacts by single field filter"
    (h/with-test-context [ctx]
      ;; Create two contact types with same field slugs
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "student"
                  :name "Student"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}
                                      {:name "Name" :slug "name" :data-type :text}]})))
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "guardian"
                  :name "Guardian"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}
                                      {:name "Name" :slug "name" :data-type :text}]})))
      ;; Create contacts
      (h/apply-events! ctx
        (cmd/crm-create-contact
          (assoc ctx :command
                 {:type-slug "student"
                  :field-values {:email "shared@example.com" :name "Student A"}})))
      (h/apply-events! ctx
        (cmd/crm-create-contact
          (assoc ctx :command
                 {:type-slug "guardian"
                  :field-values {:email "shared@example.com" :name "Guardian B"}})))
      (h/apply-events! ctx
        (cmd/crm-create-contact
          (assoc ctx :command
                 {:type-slug "student"
                  :field-values {:email "other@example.com" :name "Student C"}})))
      ;; Search by email (single field)
      (let [result (qry/crm-search-contacts
                     (assoc ctx :query {:filters {:email "shared@example.com"}}))]
        (is (contains? result :query/result))
        (is (= 2 (get-in result [:query/result :total])))
        (is (= #{"student" "guardian"}
               (set (map :type-slug (get-in result [:query/result :contacts]))))))))

  (testing "searches contacts by multiple fields (AND logic)"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "person"
                  :name "Person"
                  :field-definitions [{:name "First Name" :slug "first-name" :data-type :text}
                                      {:name "Last Name" :slug "last-name" :data-type :text}
                                      {:name "City" :slug "city" :data-type :text}]})))
      ;; Create contacts
      (h/apply-events! ctx
        (cmd/crm-create-contact
          (assoc ctx :command
                 {:type-slug "person"
                  :field-values {:first-name "John" :last-name "Smith" :city "NYC"}})))
      (h/apply-events! ctx
        (cmd/crm-create-contact
          (assoc ctx :command
                 {:type-slug "person"
                  :field-values {:first-name "John" :last-name "Doe" :city "LA"}})))
      (h/apply-events! ctx
        (cmd/crm-create-contact
          (assoc ctx :command
                 {:type-slug "person"
                  :field-values {:first-name "Jane" :last-name "Smith" :city "NYC"}})))
      ;; Search for John Smith (both first-name AND last-name must match)
      (let [result (qry/crm-search-contacts
                     (assoc ctx :query {:filters {:first-name "John" :last-name "Smith"}}))]
        (is (= 1 (get-in result [:query/result :total])))
        (is (= "NYC" (get-in result [:query/result :contacts 0 :field-values :city]))))
      ;; Search for all Johns
      (let [result (qry/crm-search-contacts
                     (assoc ctx :query {:filters {:first-name "John"}}))]
        (is (= 2 (get-in result [:query/result :total]))))
      ;; Search with three filters
      (let [result (qry/crm-search-contacts
                     (assoc ctx :query {:filters {:first-name "John" :last-name "Smith" :city "NYC"}}))]
        (is (= 1 (get-in result [:query/result :total]))))))

  (testing "filters by type-slugs"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "student"
                  :name "Student"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}]})))
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "guardian"
                  :name "Guardian"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}]})))
      (h/apply-events! ctx
        (cmd/crm-create-contact
          (assoc ctx :command
                 {:type-slug "student"
                  :field-values {:email "shared@example.com"}})))
      (h/apply-events! ctx
        (cmd/crm-create-contact
          (assoc ctx :command
                 {:type-slug "guardian"
                  :field-values {:email "shared@example.com"}})))
      ;; Search only in student type
      (let [result (qry/crm-search-contacts
                     (assoc ctx :query {:filters {:email "shared@example.com"}
                                        :type-slugs #{"student"}}))]
        (is (= 1 (get-in result [:query/result :total])))
        (is (= "student" (-> result :query/result :contacts first :type-slug))))))

  (testing "supports pagination"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :field-definitions [{:name "Status" :slug "status" :data-type :text}]})))
      ;; Create 5 active customers
      (dotimes [_ 5]
        (h/apply-events! ctx
          (cmd/crm-create-contact
            (assoc ctx :command
                   {:type-slug "customer"
                    :field-values {:status "active"}}))))
      ;; Get first page
      (let [result (qry/crm-search-contacts
                     (assoc ctx :query {:filters {:status "active"}
                                        :limit 2}))]
        (is (= 5 (get-in result [:query/result :total])))
        (is (= 2 (count (get-in result [:query/result :contacts])))))
      ;; Get second page
      (let [result (qry/crm-search-contacts
                     (assoc ctx :query {:filters {:status "active"}
                                        :limit 2
                                        :offset 2}))]
        (is (= 5 (get-in result [:query/result :total])))
        (is (= 2 (count (get-in result [:query/result :contacts])))))))

  (testing "returns empty for no matches"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}]})))
      (h/apply-events! ctx
        (cmd/crm-create-contact
          (assoc ctx :command
                 {:type-slug "customer"
                  :field-values {:email "exists@example.com"}})))
      (let [result (qry/crm-search-contacts
                     (assoc ctx :query {:filters {:email "notfound@example.com"}}))]
        (is (= 0 (get-in result [:query/result :total])))
        (is (empty? (get-in result [:query/result :contacts]))))))

  (testing "empty filters returns all contacts"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :field-definitions [{:name "Name" :slug "name" :data-type :text}]})))
      (dotimes [i 3]
        (h/apply-events! ctx
          (cmd/crm-create-contact
            (assoc ctx :command
                   {:type-slug "customer"
                    :field-values {:name (str "Customer " i)}}))))
      (let [result (qry/crm-search-contacts
                     (assoc ctx :query {:filters {}}))]
        (is (= 3 (get-in result [:query/result :total])))))))

(deftest list-all-contacts-test
  (testing "lists all contacts across types with by-type breakdown"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "student"
                  :name "Student"
                  :field-definitions []})))
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "guardian"
                  :name "Guardian"
                  :field-definitions []})))
      ;; Create 3 students and 2 guardians
      (dotimes [_ 3]
        (h/apply-events! ctx
          (cmd/crm-create-contact
            (assoc ctx :command {:type-slug "student" :field-values {}}))))
      (dotimes [_ 2]
        (h/apply-events! ctx
          (cmd/crm-create-contact
            (assoc ctx :command {:type-slug "guardian" :field-values {}}))))
      (let [result (qry/crm-list-all-contacts (assoc ctx :query {}))]
        (is (contains? result :query/result))
        (is (= 5 (get-in result [:query/result :total])))
        (is (= {"student" 3 "guardian" 2}
               (get-in result [:query/result :by-type]))))))

  (testing "filters by type-slugs"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "student"
                  :name "Student"
                  :field-definitions []})))
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "guardian"
                  :name "Guardian"
                  :field-definitions []})))
      (dotimes [_ 3]
        (h/apply-events! ctx
          (cmd/crm-create-contact
            (assoc ctx :command {:type-slug "student" :field-values {}}))))
      (dotimes [_ 2]
        (h/apply-events! ctx
          (cmd/crm-create-contact
            (assoc ctx :command {:type-slug "guardian" :field-values {}}))))
      (let [result (qry/crm-list-all-contacts
                     (assoc ctx :query {:type-slugs #{"student"}}))]
        (is (= 3 (get-in result [:query/result :total])))
        (is (= {"student" 3} (get-in result [:query/result :by-type]))))))

  (testing "filters by status"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :field-definitions []})))
      (let [contact-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "customer" :field-values {}}))
            contact-id (-> contact-result :command-result/events first :contact-id)]
        (h/apply-events! ctx contact-result)
        (h/apply-events! ctx
          (cmd/crm-create-contact
            (assoc ctx :command {:type-slug "customer" :field-values {}})))
        ;; Archive one contact
        (h/apply-events! ctx
          (cmd/crm-archive-contact
            (assoc ctx :command {:contact-id contact-id})))
        (let [active-result (qry/crm-list-all-contacts
                              (assoc ctx :query {:status :active}))
              archived-result (qry/crm-list-all-contacts
                                (assoc ctx :query {:status :archived}))]
          (is (= 1 (get-in active-result [:query/result :total])))
          (is (= 1 (get-in archived-result [:query/result :total])))))))

  (testing "supports pagination"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :field-definitions []})))
      (dotimes [_ 10]
        (h/apply-events! ctx
          (cmd/crm-create-contact
            (assoc ctx :command {:type-slug "customer" :field-values {}}))))
      (let [result (qry/crm-list-all-contacts
                     (assoc ctx :query {:limit 3 :offset 2}))]
        (is (= 10 (get-in result [:query/result :total])))
        (is (= 3 (count (get-in result [:query/result :contacts]))))))))

;; =============================================================================
;; Communication Query Tests
;; =============================================================================

(deftest get-contact-communications-test
  (testing "returns empty list when no communications"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command {:slug "student" :name "Student" :field-definitions []})))
      (let [student-result (cmd/crm-create-contact
                             (assoc ctx :command {:type-slug "student" :field-values {}}))
            student-id (-> student-result :command-result/events first :contact-id)]
        (h/apply-events! ctx student-result)
        (let [result (qry/crm-get-contact-communications
                       (assoc ctx :query {:contact-id student-id}))]
          (is (contains? result :query/result))
          (is (= [] (get-in result [:query/result :communications])))
          (is (= 0 (get-in result [:query/result :total])))))))

  (testing "returns communications for contact"
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
            _ (h/apply-events! ctx staff-result)]
        (h/apply-events! ctx
          (cmd/crm-log-communication
            (assoc ctx :command
                   {:contact-id student-id
                    :communication-type :email
                    :direction :outbound
                    :sender {:contact-id staff-id}
                    :recipient {:contact-id student-id}
                    :logged-by-contact-id staff-id
                    :subject "Email 1"
                    :content "Content 1"})))
        (let [result (qry/crm-get-contact-communications
                       (assoc ctx :query {:contact-id student-id}))]
          (is (= 1 (get-in result [:query/result :total])))
          (is (= "Email 1" (-> result :query/result :communications first :subject)))))))

  (testing "supports pagination with limit and offset"
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
            _ (h/apply-events! ctx staff-result)]
        ;; Log 3 communications
        (dotimes [i 3]
          (h/apply-events! ctx
            (cmd/crm-log-communication
              (assoc ctx :command
                     {:contact-id student-id
                      :communication-type :email
                      :direction :outbound
                      :sender {:contact-id staff-id}
                      :recipient {:contact-id student-id}
                      :logged-by-contact-id staff-id
                      :content (str "Content " i)}))))
        (let [result (qry/crm-get-contact-communications
                       (assoc ctx :query {:contact-id student-id :limit 2 :offset 1}))]
          (is (= 3 (get-in result [:query/result :total])))
          (is (= 2 (count (get-in result [:query/result :communications]))))))))

  (testing "returns not-found for unknown contact"
    (h/with-test-context [ctx]
      (let [result (qry/crm-get-contact-communications
                     (assoc ctx :query {:contact-id (random-uuid)}))]
        (is (= ::anom/not-found (::anom/category result)))))))
