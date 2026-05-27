(ns ai.obney.orc.ontology.consolidator-test
  "Tests for C-2a-3b: consolidator processor + LLM reflection.

   The consolidator subscribes to :ontology/consolidation-requested
   events. For each, it pulls the target's current description, recent
   events, accumulated metrics, and structural context, runs a single
   structured-output LLM reflection call, and emits the matching
   :*-description-updated event with the validated body.

   Tests fake the LLM call via `with-redefs` on dscloj/predict so no
   real network calls happen during dev iteration. The HITL live-verify
   uses a real LLM."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [dscloj.core :as dscloj]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.consolidator]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test context helpers
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/c2a3b-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :event-pubsub ps
                  :dscloj-provider :openrouter
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv
                     (fn [acc proc-name {:keys [handler-fn topics]}]
                       (assoc acc proc-name
                              (tp/start {:event-pubsub ps :topics topics
                                         :handler-fn handler-fn :context base-ctx})))
                     {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

;; =============================================================================
;; Fake LLM response — well-formed description-body
;; =============================================================================

(def fake-description-body
  "A minimal but well-formed description-body that the fake LLM returns.
   Used by tests that don't care about content, only orchestration."
  {:capabilities ["x"]
   :strengths [{:trait "stable structured output"
                :good-when "structured output desired"
                :recommended-pattern "[:llm {:output-schemas {...}}]"
                :confidence 1.0
                :evidence-count 1
                :first-observed-at "2026-05-26T00:00:00Z"
                :last-reinforced-at "2026-05-26T00:00:00Z"}]
   :weaknesses [{:trait "rate-limit risk under unbounded concurrency"
                 :avoid-when "high event rates AND no :max-concurrency cap"
                 :recommended-alternative "bound :max-concurrency to 3 on :map-each"
                 :confidence 1.0
                 :evidence-count 1
                 :first-observed-at "2026-05-26T00:00:00Z"
                 :last-reinforced-at "2026-05-26T00:00:00Z"}]
   :representative-uses ["per-chunk extraction"]
   :avoid-when ["work is deterministic — use :code"]
   :summary "Sample LLM-authored description body."
   :version 99           ;; LLM-authored; consolidator overwrites
   :consolidated-from-event-count 99 ;; same
   })

(defn- with-faked-llm
  "Run body with dscloj/predict stubbed to return a synthetic well-formed
   description body. The fake returns one :outputs entry PER U11 :writes
   key — matching the consolidator's six-key structured-output contract
   (capabilities / strengths / weaknesses / representative-uses /
   avoid-when / summary). The :version + :consolidated-from-event-count
   values in `response` are ignored — the consolidator computes them."
  [response f]
  (with-redefs [dscloj/predict (fn [_provider _module _inputs _options]
                                  {:outputs (select-keys response
                                                          [:capabilities
                                                           :strengths
                                                           :weaknesses
                                                           :representative-uses
                                                           :avoid-when
                                                           :summary])
                                   :usage {:total-tokens 100}
                                   :model "fake-model"})]
    (f)))

;; =============================================================================
;; RED #1 — consolidator processor receives :ontology/consolidation-requested
;; =============================================================================
;;
;; Tracer-bullet: after the on-demand command emits consolidation-requested,
;; the consolidator handler runs end-to-end. The handler effect we observe:
;; a matching :*-description-updated event lands in the store.

(deftest consolidator-receives-consolidation-requested-event
  (testing "After an on-demand consolidation-requested, the consolidator emits a matching :*-description-updated event"
    (with-test-ctx [ctx]
      (with-faked-llm fake-description-body
        (fn []
          (cp/process-command
            (assoc ctx :command
                   {:command/name :ontology/request-consolidation
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :target-type :node-type
                    :target-id :llm
                    :on-demand? true}))
          (Thread/sleep 500)
          (let [body (ontology/get-description ctx :node-type :llm)]
            (is (some? body)
                "Consolidator should have emitted a :ontology/node-type-description-updated event")
            (is (= "Sample LLM-authored description body." (:summary body))
                "Description body should contain the LLM's summary")))))))

;; =============================================================================
;; RED #3 — Version increments correctly (consolidator overwrites LLM value)
;; =============================================================================
;;
;; The LLM may return any :version (the fake returns 99). The consolidator
;; MUST overwrite with the computed value: 1 if no prior description,
;; (inc prior-version) otherwise. This isolates the consolidator from any
;; version-numbering drift in the LLM's output.

(deftest first-consolidation-emits-version-1
  (testing "With no prior description in the store, the consolidator emits :version 1"
    (with-test-ctx [ctx]
      (with-faked-llm fake-description-body
        (fn []
          (cp/process-command
            (assoc ctx :command
                   {:command/name :ontology/request-consolidation
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :target-type :node-type
                    :target-id :code
                    :on-demand? true}))
          (Thread/sleep 500)
          (let [body (ontology/get-description ctx :node-type :code)]
            (is (= 1 (:version body))
                "First consolidation overrides LLM's :version 99 to computed :version 1")))))))

(deftest subsequent-consolidation-increments-version
  (testing "With a prior description at version 3, the consolidator emits :version 4"
    (with-test-ctx [ctx]
      ;; Seed a prior description at version 3
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/record-node-type-description
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :target-id :map-each
                :body (assoc fake-description-body
                              :version 3
                              :summary "Prior body at v3")}))
      (Thread/sleep 200)
      (is (= 3 (:version (ontology/get-description ctx :node-type :map-each)))
          "Sanity: prior body is at v3")
      ;; Now consolidate
      (with-faked-llm fake-description-body
        (fn []
          (cp/process-command
            (assoc ctx :command
                   {:command/name :ontology/request-consolidation
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :target-type :node-type
                    :target-id :map-each
                    :on-demand? true}))
          (Thread/sleep 500)
          (let [body (ontology/get-description ctx :node-type :map-each)]
            (is (= 4 (:version body))
                "Consolidator overrides LLM's :version 99 to computed :version 4 (prior 3 + 1)")
            (is (= "Sample LLM-authored description body." (:summary body))
                "Body otherwise reflects the LLM's output")))))))

;; =============================================================================
;; RED #4 — :consolidated-from-event-count matches the recent-window size
;; =============================================================================
;;
;; The LLM returns :consolidated-from-event-count 99 (fake). The consolidator
;; MUST overwrite with the actual count of events it pulled into the
;; reflection prompt. Drives implementation of gather-recent-events —
;; for this slice, it pulls all :sheet/node-execution-completed events
;; for the target's node-type from the event store.

(defn- complete-node-event! [ctx node-type]
  (cp/process-command
    (assoc ctx :command
           {:command/name :sheet/complete-node-execution
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id (random-uuid)
            :tick-id (random-uuid)
            :node-id (random-uuid)
            :node-type node-type
            :status :success
            :writes {}
            :duration-ms 100})))

(deftest consolidated-from-event-count-matches-recent-events
  (testing "After 3 node-execution-completed events for the target, the consolidator's emitted body has :consolidated-from-event-count 3"
    (with-test-ctx [ctx]
      (dotimes [_ 3] (complete-node-event! ctx :sequence))
      (Thread/sleep 200)
      (with-faked-llm fake-description-body
        (fn []
          (cp/process-command
            (assoc ctx :command
                   {:command/name :ontology/request-consolidation
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :target-type :node-type
                    :target-id :sequence
                    :on-demand? true}))
          (Thread/sleep 2000)
          (let [body (ontology/get-description ctx :node-type :sequence)]
            (is (= 3 (:consolidated-from-event-count body))
                "Consolidator computes count from gathered events, overriding LLM's 99")))))))

;; =============================================================================
;; RED #5 — Malli-validation failure aborts cleanly (no event emitted)
;; =============================================================================
;;
;; When the LLM returns a malformed body (e.g., missing :strengths), U11
;; output-schemas Malli validation rejects it inside the workflow execution.
;; The consolidator must abort cleanly: NO :*-description-updated event
;; emitted, error logged. (Retry-once-then-abort lands in C-2a-3c.)

(def malformed-description-body
  "LLM output that's missing required keys (:strengths). Used to verify
   that the U11 :output-schemas Malli validation catches it and the
   consolidator aborts cleanly."
  {:capabilities ["x"]
   ;; Intentionally missing :strengths, :weaknesses, etc.
   :summary "Malformed body."})

(deftest malformed-llm-output-aborts-without-emitting
  (testing "When the LLM returns a malformed body, no :*-description-updated event is emitted"
    (with-test-ctx [ctx]
      (with-faked-llm malformed-description-body
        (fn []
          (cp/process-command
            (assoc ctx :command
                   {:command/name :ontology/request-consolidation
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :target-type :node-type
                    :target-id :parallel
                    :on-demand? true}))
          (Thread/sleep 1500)
          (is (nil? (ontology/get-description ctx :node-type :parallel))
              "Malformed LLM output should produce NO description event"))))))

;; =============================================================================
;; RED #6 — Fan-out to all three target types
;; =============================================================================
;;
;; The consolidator must produce the matching :*-description-updated event
;; for each of the three target types. Same LLM body used for all three;
;; the consolidator routes via record-description-command's case
;; dispatching.

(deftest fan-out-to-all-three-target-types
  (testing "Consolidator emits the matching :*-description-updated event for each target type"
    (with-test-ctx [ctx]
      (let [node-instance-target [(random-uuid) (random-uuid)]
            tree-fp "fp-consolidator-fan-out"]
        (with-faked-llm fake-description-body
          (fn []
            ;; node-type
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :node-type
                      :target-id :final
                      :on-demand? true}))
            ;; node-instance
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :node-instance
                      :target-id node-instance-target
                      :on-demand? true}))
            ;; tree-fingerprint
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :tree-fingerprint
                      :target-id tree-fp
                      :on-demand? true}))
            (Thread/sleep 2000)
            (is (some? (ontology/get-description ctx :node-type :final))
                "node-type target produces a node-type description event")
            (is (some? (ontology/get-description ctx :node-instance node-instance-target))
                "node-instance target produces a node-instance description event")
            (is (some? (ontology/get-description ctx :tree-fingerprint tree-fp))
                "tree-fingerprint target produces a tree description event")))))))

;; =============================================================================
;; C-2a-3c — Anti-recency safeguards (Layer 2: aggregate + delta in prompt)
;; =============================================================================
;;
;; To verify the consolidator passes real aggregate metrics from C-2a-2's
;; rolling-metrics aggregator into the LLM input, we install a fake LLM
;; that captures its inputs and assert :aggregate-metrics is non-nil after
;; some source events have been emitted.

(defn- complete-node-event-with-status! [ctx node-type status]
  (cp/process-command
    (assoc ctx :command
           {:command/name :sheet/complete-node-execution
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id (random-uuid)
            :tick-id (random-uuid)
            :node-id (random-uuid)
            :node-type node-type
            :status status
            :writes {}
            :duration-ms 100})))

(defn- with-input-capturing-llm
  "Like with-faked-llm but ALSO captures the inputs passed to dscloj/predict
   into the supplied atom. The fake returns the well-formed description-body
   regardless."
  [captured-inputs response f]
  (with-redefs [dscloj/predict
                (fn [_provider _module inputs _options]
                  (swap! captured-inputs conj inputs)
                  {:outputs (select-keys response
                                          [:capabilities :strengths :weaknesses
                                           :representative-uses :avoid-when :summary])
                   :usage {:total-tokens 100}
                   :model "fake-model"})]
    (f)))

;; =============================================================================
;; C-2a-3c — Layer 2: recent-vs-historical delta computation
;; =============================================================================
;;
;; The consolidator computes recent success-rate from recent-events and
;; historical success-rate from aggregate-metrics; passes the delta into
;; the LLM as a separate input so the model can see "recent vs aggregate"
;; without conflating them.

(deftest delta-is-computed-from-recent-vs-historical
  (testing "compute-delta returns recent-success-rate, historical-success-rate, and the delta when aggregate-metrics is non-nil"
    (let [aggregate {:success-count 80 :failure-count 20 :total-duration 0 :executions []}
          recent-events [{:status :success} {:status :success} {:status :failure}
                         {:status :failure} {:status :failure}]
          delta (#'ai.obney.orc.ontology.core.consolidator/compute-delta
                  aggregate recent-events)]
      (is (some? delta) "compute-delta returns a map")
      (is (= 0.4 (:recent-success-rate delta))
          "recent-success-rate = 2 successes / 5 events")
      (is (= 0.8 (:historical-success-rate delta))
          "historical-success-rate = 80 / (80 + 20)")
      (is (< (Math/abs (- -0.4 (:delta delta))) 1e-9)
          "delta = recent - historical = 0.4 - 0.8 = -0.4 (recent is worse)"))))

(deftest delta-nil-when-no-aggregate
  (testing "compute-delta returns nil when aggregate-metrics is nil (first consolidation has no historical to compare against)"
    (is (nil? (#'ai.obney.orc.ontology.core.consolidator/compute-delta
                nil
                [{:status :success}])))))

(deftest delta-passed-to-llm-when-aggregate-exists
  (testing "After source events, the consolidator passes recent-vs-historical-delta to the LLM"
    (with-test-ctx [ctx]
      (dotimes [_ 7] (complete-node-event-with-status! ctx :llm :success))
      (dotimes [_ 3] (complete-node-event-with-status! ctx :llm :failure))
      (Thread/sleep 300)
      (let [captured (atom [])]
        (with-input-capturing-llm captured fake-description-body
          (fn []
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :node-type
                      :target-id :llm
                      :on-demand? true}))
            (Thread/sleep 1500)
            (let [inputs (first @captured)
                  delta (get inputs :recent-vs-historical-delta)
                  delta-text (if (string? delta) delta (pr-str delta))]
              (is (some? delta) ":recent-vs-historical-delta should be present in the LLM input")
              ;; recent-success-rate = 7/10 = 0.7, historical-success-rate = 7/10
              ;; (events ARE the aggregate, so delta is 0.0).
              (is (or (str/includes? delta-text "delta")
                      (str/includes? delta-text "rate"))
                  (str "delta payload mentions delta/rate fields: "
                       (subs delta-text 0 (min 200 (count delta-text))))))))))))

;; =============================================================================
;; C-2a-3c — Layer 4: status-shape forbidden in the prompt (no runtime check)
;; =============================================================================
;;
;; Decision (2026-05-26): the production gate is the PROMPT, not a post-hoc
;; runtime check. Hardcoded phrase matching against status-words is brittle
;; (misses synonyms, false-positives on legitimate uses), so the prompt does
;; the work and the test only verifies the prompt's content.
;;
;; If the live verify shows status-shaped leakage, the fix is prompt tuning
;; (or adding an LLM-as-judge classifier in a future slice), NOT a hardcoded
;; backstop.

(deftest reflection-prompt-forbids-status-shaped-entries
  (testing "Reflection prompt explicitly forbids status-shaped output with concrete examples"
    (let [instr @#'ai.obney.orc.ontology.core.consolidator/reflection-instruction
          lower (str/lower-case instr)]
      (is (str/includes? lower "investigate")
          "Prompt mentions 'investigate' as a forbidden status-word example")
      (is (str/includes? lower "observed")
          "Prompt mentions 'observed' as a forbidden status-word example")
      (is (or (str/includes? lower "never produce")
              (str/includes? lower "must not")
              (str/includes? lower "do not"))
          "Prompt uses imperative language forbidding status-shapes")
      (is (or (str/includes? lower "actionable")
              (str/includes? lower "principle-shape"))
          "Prompt explains that every entry must carry actionable principle-shaped content"))))

;; =============================================================================
;; C-2a-3c — Budget cap
;; =============================================================================
;;
;; Hourly rolling budget per target-type. Default 100/hour. When exceeded,
;; subsequent consolidation-requested events no-op (LLM call skipped, no
;; new description emitted). Verified by setting the budget low and
;; firing N+1 consolidation-requested events.

(deftest budget-cap-blocks-consolidation-after-limit
  (testing "After setting node-type budget to 2, three sequential consolidations produce only 2 description-updated events; the 3rd is blocked"
    (with-test-ctx [ctx]
      ;; Set budget = 2 / hour for :node-type
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-consolidation-budget
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :target-type :node-type
                :budget 2}))
      (Thread/sleep 200)
      (with-faked-llm fake-description-body
        (fn []
          ;; Fire consolidations SEQUENTIALLY with waits between — mirrors
          ;; real-world cadence (threshold-driven, spread over time). The
          ;; test would burst-race the read-model if fired back-to-back;
          ;; in production, threshold-tracking serializes these naturally.
          (dotimes [i 3]
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :node-type
                      :target-id (keyword (str "budget-test-" i))
                      :on-demand? true}))
            ;; Wait for the consolidator handler to complete + the
            ;; description-updated event to project through the
            ;; recent-consolidations read-model before the next budget
            ;; check runs.
            (Thread/sleep 1500))
          (let [t0 (ontology/get-description ctx :node-type :budget-test-0)
                t1 (ontology/get-description ctx :node-type :budget-test-1)
                t2 (ontology/get-description ctx :node-type :budget-test-2)
                emitted-count (count (filter some? [t0 t1 t2]))]
            (is (= 2 emitted-count)
                (str "Only 2 description-updated events should be emitted "
                     "(targets " (if t0 "0✓" "0✗") " " (if t1 "1✓" "1✗") " " (if t2 "2✓" "2✗") ")."
                     " The 3rd is blocked by the budget cap."))))))))

(deftest reflection-prompt-includes-anti-recency-framing
  (testing "The reflection-instruction explicitly frames aggregate as stable baseline and recent as leading indicator"
    (let [instr (str/lower-case @#'ai.obney.orc.ontology.core.consolidator/reflection-instruction)]
      (is (or (str/includes? instr "stable baseline")
              (str/includes? instr "leading indicator"))
          "Prompt must include explicit anti-recency framing — phrase 'stable baseline' or 'leading indicator'")
      (is (str/includes? instr "aggregate")
          "Prompt mentions :aggregate-metrics input")
      (is (or (str/includes? instr "recent-vs-historical")
              (str/includes? instr "delta"))
          "Prompt mentions the :recent-vs-historical-delta input"))))

(deftest aggregate-metrics-passed-to-llm-when-source-events-exist
  (testing "After source events for the target, the consolidator passes non-nil :aggregate-metrics to the LLM. The workflow's serialize-for-llm step JSON-encodes the map before it reaches dscloj — we verify the encoded payload contains the expected success/failure counts."
    (with-test-ctx [ctx]
      ;; Emit 6 source events (4 success + 2 failure) for :llm
      (dotimes [_ 4] (complete-node-event-with-status! ctx :llm :success))
      (dotimes [_ 2] (complete-node-event-with-status! ctx :llm :failure))
      (Thread/sleep 300)
      (let [captured (atom [])]
        (with-input-capturing-llm captured fake-description-body
          (fn []
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :node-type
                      :target-id :llm
                      :on-demand? true}))
            (Thread/sleep 1500)
            (is (= 1 (count @captured))
                "Exactly one LLM call expected (on-demand consolidation)")
            (let [inputs (first @captured)
                  agg (get inputs :aggregate-metrics)
                  agg-text (if (string? agg) agg (pr-str agg))]
              (is (string? agg-text)
                  ":aggregate-metrics should be present in the LLM input (string after serialize-for-llm)")
              (is (or (str/includes? agg-text "\"success-count\":4")
                      (str/includes? agg-text ":success-count 4"))
                  (str ":aggregate-metrics payload should contain success-count 4 — got: "
                       (subs agg-text 0 (min 200 (count agg-text)))))
              (is (or (str/includes? agg-text "\"failure-count\":2")
                      (str/includes? agg-text ":failure-count 2"))
                  (str ":aggregate-metrics payload should contain failure-count 2 — got: "
                       (subs agg-text 0 (min 200 (count agg-text))))))))))))
