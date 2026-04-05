(ns ai.obney.orc.orc-service.running-retick-test
  "Tests for :running status re-tick behavior.
   Verifies that conditions with :on-fail :running cause the tree to
   re-tick from root, enabling agent loops in behavior trees."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test executor: increments a counter on each tick
;; =============================================================================

(def tick-counter (atom 0))

(defn increment-counter
  "Code executor that increments a counter each time it's called."
  [{:keys [_inputs]}]
  (let [n (swap! tick-counter inc)]
    {:counter (str n)}))

;; =============================================================================
;; Tests
;; =============================================================================

(deftest running-condition-causes-retick
  (testing "a condition with :on-fail :running causes the tree to re-tick multiple times"
    (h/with-async-test-context [ctx]
      (reset! tick-counter 0)
      (let [wf (sheet/workflow "retick-test"
                 (sheet/blackboard
                   {:counter [:string {:description "tick count"}]})
                 (sheet/sequence "main"
                   (sheet/code "increment"
                     :fn "ai.obney.orc.orc-service.running-retick-test/increment-counter"
                     :reads []
                     :writes [:counter])
                   (sheet/condition "keep-going"
                     :check {:key :counter :op :equals :value "__never__" :on-fail :running})))
            sheet-id (sheet/build-workflow! ctx wf)
            ;; Execute with a short timeout — the tree will re-tick until max iterations
            _ (alter-var-root #'tp/*max-tick-iterations* (constantly 5))
            result (sheet/execute ctx sheet-id {} :timeout-ms 10000)]
        ;; The tree should have ticked 5 times (max iterations)
        ;; and the final status should be :failure (max iterations exhausted)
        (is (= 5 @tick-counter)
            "Tree should re-tick exactly max-tick-iterations times")
        (is (= :failure (:status result))
            "Should fail after exhausting max iterations")))))

(deftest running-condition-terminates-on-success
  (testing "tree re-ticks via :running until a condition passes, then succeeds"
    (h/with-async-test-context [ctx]
      (reset! tick-counter 0)
      (let [wf (sheet/workflow "retick-terminate-test"
                 (sheet/blackboard
                   {:counter [:string {:description "tick count"}]})
                 ;; Increment, then check: if counter != "3" → :running (re-tick).
                 ;; When counter = "3" → condition fails normally → but we need success...
                 ;; Use a fallback: keep-going (not at 3 yet) vs done (at 3)
                 (sheet/fallback "agent"
                   (sheet/sequence "keep-going"
                     (sheet/condition "not-at-3"
                       :check {:key :counter :op :not-equals :value "3"})
                     (sheet/code "increment"
                       :fn "ai.obney.orc.orc-service.running-retick-test/increment-counter"
                       :reads []
                       :writes [:counter])
                     (sheet/condition "re-tick"
                       :check {:key :counter :op :equals :value "__never__" :on-fail :running}))
                   (sheet/sequence "done"
                     (sheet/condition "at-3"
                       :check {:key :counter :op :equals :value "3"}))))
            sheet-id (sheet/build-workflow! ctx wf)
            _ (alter-var-root #'tp/*max-tick-iterations* (constantly 10))
            result (sheet/execute ctx sheet-id {:counter "0"} :timeout-ms 10000)]
        ;; Tick 1: not-at-3 passes ("0"!="3"), increment → "1", re-tick → :running
        ;; Tick 2: not-at-3 passes ("1"!="3"), increment → "2", re-tick → :running
        ;; Tick 3: not-at-3 passes ("2"!="3"), increment → "3", re-tick → :running
        ;; Tick 4: not-at-3 fails ("3"=="3") → fallback tries done → at-3 passes → :success
        (is (= 3 @tick-counter)
            "Increment should run 3 times")
        (is (= :success (:status result))
            "Should succeed when done skill matches")
        (is (= "3" (get-in result [:outputs :counter]))
            "Output should reflect final counter value")))))

(deftest running-in-fallback-reticks
  (testing "a :running condition inside a fallback child re-ticks properly"
    (h/with-async-test-context [ctx]
      (reset! tick-counter 0)
      (let [wf (sheet/workflow "fallback-retick-test"
                 (sheet/blackboard
                   {:counter [:string {:description "tick count"}]
                    :result [:string {:description "output"}]})
                 (sheet/fallback "agent"
                   ;; Skill 1: runs for first 2 ticks (counter < 3)
                   (sheet/sequence "keep-ticking"
                     (sheet/condition "not-done?"
                       :check {:key :counter :op :not-equals :value "3"})
                     (sheet/code "increment"
                       :fn "ai.obney.orc.orc-service.running-retick-test/increment-counter"
                       :reads []
                       :writes [:counter])
                     ;; Always return :running to re-tick
                     (sheet/condition "re-tick"
                       :check {:key :counter :op :equals :value "__never__" :on-fail :running}))
                   ;; Skill 2: runs when counter = "3" (not-done? fails, fallback tries this)
                   (sheet/sequence "done"
                     (sheet/condition "is-done?"
                       :check {:key :counter :op :equals :value "3"})
                     (sheet/code "finish"
                       :fn "ai.obney.orc.orc-service.running-retick-test/increment-counter"
                       :reads []
                       :writes [:counter]))))
            sheet-id (sheet/build-workflow! ctx wf)
            _ (alter-var-root #'tp/*max-tick-iterations* (constantly 10))
            result (sheet/execute ctx sheet-id {:counter "0"} :timeout-ms 10000)]
        ;; Ticks 1-2: keep-ticking increments counter (1, 2), re-tick fires
        ;; Tick 3: not-done? fails (counter="3"?... wait, counter is "2" after 2 increments,
        ;;         then on tick 3 not-done? checks counter != "3" → "2" != "3" → true,
        ;;         so keep-ticking runs again, counter becomes "3", re-tick fires
        ;; Tick 4: not-done? checks "3" != "3" → false → sequence fails → fallback tries done
        ;;         is-done? checks "3" = "3" → true → finish runs → success
        (is (= :success (:status result))
            "Fallback should eventually succeed via the done skill")
        (is (>= @tick-counter 4)
            "Should take at least 4 ticks")))))

(deftest per-execute-max-ticks-option
  (testing ":max-ticks option on execute overrides the global dynamic var"
    (h/with-async-test-context [ctx]
      (reset! tick-counter 0)
      ;; Set a high global value to prove the per-execute option wins
      (alter-var-root #'tp/*max-tick-iterations* (constantly 50))
      (let [wf (sheet/workflow "per-execute-cap-test"
                 (sheet/blackboard
                   {:counter [:string {:description "tick count"}]})
                 (sheet/sequence "main"
                   (sheet/code "increment"
                     :fn "ai.obney.orc.orc-service.running-retick-test/increment-counter"
                     :reads []
                     :writes [:counter])
                   (sheet/condition "keep-going"
                     :check {:key :counter :op :equals :value "__never__" :on-fail :running})))
            sheet-id (sheet/build-workflow! ctx wf)
            result (sheet/execute ctx sheet-id {} :timeout-ms 10000 :max-ticks 3)]
        (is (= 3 @tick-counter)
            "Tree should re-tick exactly :max-ticks times, not the global 50")
        (is (= :failure (:status result))
            "Should fail after exhausting :max-ticks")))))
