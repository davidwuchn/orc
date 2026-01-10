(ns ai.obney.workshop.crm-service.read-models-test
  "Unit tests for CRM service read model projections."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.workshop.crm-service.test-helpers :as h]
            [ai.obney.workshop.crm-service.core.commands :as cmd]
            [ai.obney.workshop.crm-service.interface.read-models :as rm]
            [ai.obney.grain.event-store-v2.interface :as es]))

;; =============================================================================
;; Contact Type Projection Tests
;; =============================================================================

(deftest contact-type-projections-test
  (testing "projects contact-type-created event"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-create-contact-type
                     (assoc ctx :command
                            {:slug "customer"
                             :name "Customer"
                             :description "A customer"
                             :field-definitions [{:name "Email" :slug "email" :data-type :email}]}))
            type-id (-> result :command-result/events first :type-id)]
        (h/apply-events! ctx result)
        (let [ct (rm/get-contact-type (:event-store ctx) {:type-id type-id})]
          (is (some? ct))
          (is (= "customer" (:slug ct)))
          (is (= "Customer" (:name ct)))
          (is (true? (:active ct)))
          (is (= 1 (count (:field-definitions ct))))))))

  (testing "projects contact-type-updated event"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-create-contact-type
                     (assoc ctx :command
                            {:slug "customer"
                             :name "Customer"
                             :description "Initial"
                             :field-definitions []}))
            type-id (-> result :command-result/events first :type-id)]
        (h/apply-events! ctx result)
        (h/apply-events! ctx
          (cmd/crm-update-contact-type
            (assoc ctx :command
                   {:type-id type-id
                    :name "Updated Customer"
                    :description "Updated description"})))
        (let [ct (rm/get-contact-type (:event-store ctx) {:type-id type-id})]
          (is (= "Updated Customer" (:name ct)))
          (is (= "Updated description" (:description ct)))))))

  (testing "projects contact-type-deactivated event"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-create-contact-type
                     (assoc ctx :command
                            {:slug "customer"
                             :name "Customer"
                             :description "Test"
                             :field-definitions []}))
            type-id (-> result :command-result/events first :type-id)]
        (h/apply-events! ctx result)
        (h/apply-events! ctx
          (cmd/crm-deactivate-contact-type
            (assoc ctx :command {:type-id type-id})))
        (let [ct (rm/get-contact-type (:event-store ctx) {:type-id type-id})]
          (is (false? (:active ct)))))))

  (testing "projects field-definition-added event"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-create-contact-type
                     (assoc ctx :command
                            {:slug "customer"
                             :name "Customer"
                             :description "Test"
                             :field-definitions []}))
            type-id (-> result :command-result/events first :type-id)]
        (h/apply-events! ctx result)
        (h/apply-events! ctx
          (cmd/crm-add-field-definition
            (assoc ctx :command
                   {:type-id type-id
                    :field {:name "Phone" :slug "phone" :data-type :phone}})))
        (let [ct (rm/get-contact-type (:event-store ctx) {:type-id type-id})]
          (is (= 1 (count (:field-definitions ct))))
          (is (= "phone" (:slug (first (:field-definitions ct)))))))))

  (testing "projects field-definition-updated event"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-create-contact-type
                     (assoc ctx :command
                            {:slug "customer"
                             :name "Customer"
                             :description "Test"
                             :field-definitions [{:name "Email" :slug "email" :data-type :email}]}))
            type-id (-> result :command-result/events first :type-id)]
        (h/apply-events! ctx result)
        (h/apply-events! ctx
          (cmd/crm-update-field-definition
            (assoc ctx :command
                   {:type-id type-id
                    :field-slug "email"
                    :updates {:name "Email Address" :required true}})))
        (let [ct (rm/get-contact-type (:event-store ctx) {:type-id type-id})
              field (first (:field-definitions ct))]
          (is (= "Email Address" (:name field)))
          (is (true? (:required field)))))))

  (testing "projects field-definition-removed event"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-create-contact-type
                     (assoc ctx :command
                            {:slug "customer"
                             :name "Customer"
                             :description "Test"
                             :field-definitions [{:name "Email" :slug "email" :data-type :email}
                                                 {:name "Phone" :slug "phone" :data-type :phone}]}))
            type-id (-> result :command-result/events first :type-id)]
        (h/apply-events! ctx result)
        (h/apply-events! ctx
          (cmd/crm-remove-field-definition
            (assoc ctx :command
                   {:type-id type-id
                    :field-slug "email"})))
        (let [ct (rm/get-contact-type (:event-store ctx) {:type-id type-id})]
          (is (= 1 (count (:field-definitions ct))))
          (is (= "phone" (:slug (first (:field-definitions ct))))))))))

;; =============================================================================
;; Contact Projection Tests
;; =============================================================================

(deftest contact-projections-test
  (testing "projects contact-created event"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "Test"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}]})))
      (let [result (cmd/crm-create-contact
                     (assoc ctx :command
                            {:type-slug "customer"
                             :field-values {:email "test@example.com"}
                             :tags #{"vip"}}))
            contact-id (-> result :command-result/events first :contact-id)]
        (h/apply-events! ctx result)
        (let [contact (rm/get-contact (:event-store ctx) contact-id)]
          (is (some? contact))
          (is (= contact-id (:id contact)))
          (is (= "test@example.com" (get-in contact [:field-values :email])))
          (is (= :active (:status contact)))
          (is (contains? (:tags contact) "vip"))))))

  (testing "projects contact-updated event"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "Test"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}]})))
      (let [result (cmd/crm-create-contact
                     (assoc ctx :command
                            {:type-slug "customer"
                             :field-values {:email "old@example.com"}}))
            contact-id (-> result :command-result/events first :contact-id)]
        (h/apply-events! ctx result)
        (h/apply-events! ctx
          (cmd/crm-update-contact
            (assoc ctx :command
                   {:contact-id contact-id
                    :field-values {:email "new@example.com"}})))
        (let [contact (rm/get-contact (:event-store ctx) contact-id)]
          (is (= "new@example.com" (get-in contact [:field-values :email])))))))

  (testing "projects contact-field-set event"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "Test"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}
                                      {:name "Phone" :slug "phone" :data-type :phone}]})))
      (let [result (cmd/crm-create-contact
                     (assoc ctx :command
                            {:type-slug "customer"
                             :field-values {:email "test@example.com"}}))
            contact-id (-> result :command-result/events first :contact-id)]
        (h/apply-events! ctx result)
        (h/apply-events! ctx
          (cmd/crm-set-contact-field
            (assoc ctx :command
                   {:contact-id contact-id
                    :field-slug "phone"
                    :value "5551234567"})))
        (let [contact (rm/get-contact (:event-store ctx) contact-id)]
          (is (= "5551234567" (get-in contact [:field-values :phone])))))))

  (testing "projects contact-tagged event"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "Test"
                  :field-definitions []})))
      (let [result (cmd/crm-create-contact
                     (assoc ctx :command
                            {:type-slug "customer"
                             :field-values {}}))
            contact-id (-> result :command-result/events first :contact-id)]
        (h/apply-events! ctx result)
        (h/apply-events! ctx
          (cmd/crm-tag-contact
            (assoc ctx :command
                   {:contact-id contact-id
                    :tag "premium"})))
        (let [contact (rm/get-contact (:event-store ctx) contact-id)]
          (is (contains? (:tags contact) "premium"))))))

  (testing "projects contact-untagged event"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "Test"
                  :field-definitions []})))
      (let [result (cmd/crm-create-contact
                     (assoc ctx :command
                            {:type-slug "customer"
                             :field-values {}
                             :tags #{"vip" "premium"}}))
            contact-id (-> result :command-result/events first :contact-id)]
        (h/apply-events! ctx result)
        (h/apply-events! ctx
          (cmd/crm-untag-contact
            (assoc ctx :command
                   {:contact-id contact-id
                    :tag "vip"})))
        (let [contact (rm/get-contact (:event-store ctx) contact-id)]
          (is (not (contains? (:tags contact) "vip")))
          (is (contains? (:tags contact) "premium"))))))

  (testing "projects contact-status-changed event"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "Test"
                  :field-definitions []})))
      (let [result (cmd/crm-create-contact
                     (assoc ctx :command
                            {:type-slug "customer"
                             :field-values {}}))
            contact-id (-> result :command-result/events first :contact-id)]
        (h/apply-events! ctx result)
        (h/apply-events! ctx
          (cmd/crm-change-contact-status
            (assoc ctx :command
                   {:contact-id contact-id
                    :new-status :inactive})))
        (let [contact (rm/get-contact (:event-store ctx) contact-id)]
          (is (= :inactive (:status contact)))))))

  (testing "projects contact-archived event"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "Test"
                  :field-definitions []})))
      (let [result (cmd/crm-create-contact
                     (assoc ctx :command
                            {:type-slug "customer"
                             :field-values {}}))
            contact-id (-> result :command-result/events first :contact-id)]
        (h/apply-events! ctx result)
        (h/apply-events! ctx
          (cmd/crm-archive-contact
            (assoc ctx :command {:contact-id contact-id})))
        (let [contact (rm/get-contact (:event-store ctx) contact-id)]
          (is (= :archived (:status contact)))))))

  (testing "projects contact-restored event"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "Test"
                  :field-definitions []})))
      (let [result (cmd/crm-create-contact
                     (assoc ctx :command
                            {:type-slug "customer"
                             :field-values {}}))
            contact-id (-> result :command-result/events first :contact-id)]
        (h/apply-events! ctx result)
        (h/apply-events! ctx
          (cmd/crm-archive-contact
            (assoc ctx :command {:contact-id contact-id})))
        (h/apply-events! ctx
          (cmd/crm-restore-contact
            (assoc ctx :command {:contact-id contact-id})))
        (let [contact (rm/get-contact (:event-store ctx) contact-id)]
          (is (= :active (:status contact)))))))

  (testing "projects contact-deleted event"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "Test"
                  :field-definitions []})))
      (let [result (cmd/crm-create-contact
                     (assoc ctx :command
                            {:type-slug "customer"
                             :field-values {}}))
            contact-id (-> result :command-result/events first :contact-id)]
        (h/apply-events! ctx result)
        (h/apply-events! ctx
          (cmd/crm-delete-contact
            (assoc ctx :command {:contact-id contact-id})))
        (let [contact (rm/get-contact (:event-store ctx) contact-id)]
          (is (nil? contact)))))))

;; =============================================================================
;; Relationship Type Projection Tests
;; =============================================================================

(deftest relationship-type-projections-test
  (testing "projects relationship-type-created event"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-create-relationship-type
                     (assoc ctx :command
                            {:slug "parent_of"
                             :name "Parent Of"
                             :inverse-name "Child Of"
                             :source-type-slugs #{"adult"}
                             :target-type-slugs #{"child"}}))
            type-id (-> result :command-result/events first :type-id)]
        (h/apply-events! ctx result)
        (let [rt (rm/get-relationship-type (:event-store ctx) {:type-id type-id})]
          (is (some? rt))
          (is (= "parent_of" (:slug rt)))
          (is (= "Parent Of" (:name rt)))
          (is (= "Child Of" (:inverse-name rt)))))))

  (testing "projects relationship-type-updated event"
    (h/with-test-context [ctx]
      (let [result (cmd/crm-create-relationship-type
                     (assoc ctx :command
                            {:slug "parent_of"
                             :name "Parent Of"
                             :inverse-name "Child Of"
                             :source-type-slugs #{}
                             :target-type-slugs #{}}))
            type-id (-> result :command-result/events first :type-id)]
        (h/apply-events! ctx result)
        (h/apply-events! ctx
          (cmd/crm-update-relationship-type
            (assoc ctx :command
                   {:type-id type-id
                    :name "Legal Parent Of"
                    :inverse-name "Legal Child Of"})))
        (let [rt (rm/get-relationship-type (:event-store ctx) {:type-id type-id})]
          (is (= "Legal Parent Of" (:name rt)))
          (is (= "Legal Child Of" (:inverse-name rt))))))))

;; =============================================================================
;; Relationship Projection Tests
;; =============================================================================

(deftest relationship-projections-test
  (testing "projects relationship-created event"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "person"
                  :name "Person"
                  :description "Test"
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
                                   :target-contact-id target-id
                                   :properties {:notes "Best friends"}}))
              rel-id (-> rel-result :command-result/events first :relationship-id)]
          (h/apply-events! ctx rel-result)
          (let [rels (rm/get-relationships-for-contact (:event-store ctx) source-id)]
            (is (= 1 (count rels)))
            (is (= rel-id (:id (first rels))))
            (is (= source-id (:source-contact-id (first rels))))
            (is (= target-id (:target-contact-id (first rels)))))))))

  (testing "projects relationship-updated event"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "person"
                  :name "Person"
                  :description "Test"
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
                    :target-type-slugs #{}
                    :allowed-properties [{:name "Notes" :slug "notes" :data-type :text}]})))
        (let [rel-result (cmd/crm-create-relationship
                           (assoc ctx :command
                                  {:type-slug "friend_of"
                                   :source-contact-id source-id
                                   :target-contact-id target-id}))
              rel-id (-> rel-result :command-result/events first :relationship-id)]
          (h/apply-events! ctx rel-result)
          (h/apply-events! ctx
            (cmd/crm-update-relationship
              (assoc ctx :command
                     {:relationship-id rel-id
                      :properties {:notes "Updated notes"}})))
          (let [rels (rm/get-relationships-for-contact (:event-store ctx) source-id)]
            (is (= "Updated notes" (get-in (first rels) [:properties :notes]))))))))

  (testing "projects relationship-ended event"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "person"
                  :name "Person"
                  :description "Test"
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
          (h/apply-events! ctx
            (cmd/crm-end-relationship
              (assoc ctx :command
                     {:relationship-id rel-id
                      :end-date "2024-01-01"
                      :reason "Moved away"})))
          (let [rels (rm/get-relationships-for-contact (:event-store ctx) source-id)]
            (is (= "2024-01-01" (:end-date (first rels))))
            (is (= "Moved away" (:reason (first rels)))))))))

  (testing "projects relationship-deleted event"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "person"
                  :name "Person"
                  :description "Test"
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
          (h/apply-events! ctx
            (cmd/crm-delete-relationship
              (assoc ctx :command {:relationship-id rel-id})))
          (let [rels (rm/get-relationships-for-contact (:event-store ctx) source-id)]
            (is (= 0 (count rels)))))))))

;; =============================================================================
;; Duplicate Projection Tests
;; =============================================================================

(deftest duplicate-projections-test
  (testing "projects duplicate-detected event"
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
        (let [dups (rm/get-duplicates-all (:event-store ctx))]
          (is (= 1 (count dups)))
          (is (= dup-id (:id (first dups))))
          (is (= :email (:match-type (first dups))))))))

  (testing "projects duplicate-resolved event"
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
        (es/append (:event-store ctx)
                   {:events [(es/->event {:type :crm/duplicate-resolved
                                          :tags #{[:duplicate dup-id]
                                                  [:duplicate-primary contact-id]
                                                  [:duplicate-candidate potential-id]}
                                          :body {:duplicate-id dup-id
                                                 :resolution :dismiss}})]})
        (let [pending (rm/get-duplicates-all (:event-store ctx) :status :pending)]
          (is (= 0 (count pending))))))))

;; =============================================================================
;; Attribution Projection Tests
;; =============================================================================

(deftest attribution-projections-test
  (testing "projects attribution-recorded event"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "customer"
                  :name "Customer"
                  :description "Test"
                  :field-definitions []})))
      (let [result (cmd/crm-create-contact
                     (assoc ctx :command
                            {:type-slug "customer"
                             :field-values {}}))
            contact-id (-> result :command-result/events first :contact-id)]
        (h/apply-events! ctx result)
        (es/append (:event-store ctx)
                   {:events [(es/->event {:type :crm/attribution-recorded
                                          :tags #{[:contact contact-id]}
                                          :body {:contact-id contact-id
                                                 :attribution {:source :referral
                                                               :referring-contact-id (random-uuid)
                                                               :recorded-at "2025-01-01T00:00:00Z"}}})]})
        (let [contact (rm/get-contact (:event-store ctx) contact-id)]
          (is (some? (:attribution contact)))
          (is (= :referral (get-in contact [:attribution :source]))))))))

;; =============================================================================
;; Communications Projection Tests
;; =============================================================================

(deftest communications-projection-test
  (testing "projects communication-logged event"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "student"
                  :name "Student"
                  :description "Student contact"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}]})))
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "staff"
                  :name "Staff"
                  :description "Staff member"
                  :field-definitions [{:name "Email" :slug "email" :data-type :email}]})))
      (let [student-result (cmd/crm-create-contact
                             (assoc ctx :command
                                    {:type-slug "student"
                                     :field-values {:email "student@example.com"}}))
            student-id (-> student-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx student-result)
            staff-result (cmd/crm-create-contact
                           (assoc ctx :command
                                  {:type-slug "staff"
                                   :field-values {:email "staff@example.com"}}))
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
                    :subject "Test Subject"
                    :content "Test Content"})))
        (let [comms (rm/get-communications-for-contact (:event-store ctx) student-id)]
          (is (= 1 (count comms)))
          (is (= :email (:communication-type (first comms))))
          (is (= :outbound (:direction (first comms))))
          (is (= "Test Subject" (:subject (first comms))))
          (is (= "Test Content" (:content (first comms))))
          (is (= staff-id (:logged-by-contact-id (first comms))))))))

  (testing "projects multiple communications for same contact"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "student"
                  :name "Student"
                  :description "Student contact"
                  :field-definitions []})))
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "staff"
                  :name "Staff"
                  :description "Staff member"
                  :field-definitions []})))
      (let [student-result (cmd/crm-create-contact
                             (assoc ctx :command {:type-slug "student" :field-values {}}))
            student-id (-> student-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx student-result)
            staff-result (cmd/crm-create-contact
                           (assoc ctx :command {:type-slug "staff" :field-values {}}))
            staff-id (-> staff-result :command-result/events first :contact-id)
            _ (h/apply-events! ctx staff-result)]
        ;; Log 3 communications
        (doseq [i (range 3)]
          (h/apply-events! ctx
            (cmd/crm-log-communication
              (assoc ctx :command
                     {:contact-id student-id
                      :communication-type (if (even? i) :email :sms)
                      :direction :outbound
                      :sender {:contact-id staff-id}
                      :recipient {:contact-id student-id}
                      :logged-by-contact-id staff-id
                      :content (str "Message " i)}))))
        (let [comms (rm/get-communications-for-contact (:event-store ctx) student-id)]
          (is (= 3 (count comms)))))))

  (testing "returns empty list when no communications"
    (h/with-test-context [ctx]
      (h/apply-events! ctx
        (cmd/crm-create-contact-type
          (assoc ctx :command
                 {:slug "student"
                  :name "Student"
                  :description "Test"
                  :field-definitions []})))
      (let [result (cmd/crm-create-contact
                     (assoc ctx :command {:type-slug "student" :field-values {}}))
            contact-id (-> result :command-result/events first :contact-id)]
        (h/apply-events! ctx result)
        (let [comms (rm/get-communications-for-contact (:event-store ctx) contact-id)]
          (is (= 0 (count comms)))
          (is (empty? comms)))))))
