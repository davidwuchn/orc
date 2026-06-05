(ns ai.obney.orc.evaluation.judge-runtime-test
  "Gap-1 — per-event evaluator runtime. Tests progress in RED→GREEN cycles
   per `docs/issues/c2d-followups/Gap-1-per-event-evaluator-runtime.md`."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.judge-runtime]
            [ai.obney.orc.evaluation.core.judges :as judges]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test context helpers (mirror the pattern from consolidation_trigger_test)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/judge-runtime-test-" (random-uuid))
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

(defn- count-score-emitted-events [ctx]
  (count (into [] (es/read (:event-store ctx)
                           {:types #{:judge/score-emitted}
                            :tenant-id (:tenant-id ctx)}))))

(defn- set-living-description-enabled! [ctx enabled?]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/set-living-description-enabled
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :enabled? enabled?}))
  (Thread/sleep 100))

;; =============================================================================
;; RED #2 — :judge/score-emitted schema registration + validation
;; =============================================================================
;;
;; The unified evaluator protocol's per-event evaluator emission. Every
;; auto-running judge under Gap-1 emits this event shape. Consolidator
;; will consume them under Gap-3.

(def well-formed-body
  "A minimal but fully-conforming :judge/score-emitted body."
  {:sheet-id (random-uuid)
   :tick-id (random-uuid)
   :node-id (random-uuid)
   :judge-name "grounding"
   :judge-config {:type :grounding}
   :score 0.85
   :feedback "Output is well-grounded in the source document."
   :dimensions [{:name "Source Grounding" :weight 0.5 :score 0.9 :feedback "All claims trace to source"}
                {:name "Citation Quality" :weight 0.5 :score 0.8 :feedback "Most citations are direct quotes"}]
   :emitted-at "2026-06-03T12:00:00Z"})

(deftest judge-score-emitted-schema-validates-well-formed-body
  (testing "A well-formed :judge/score-emitted body passes schema validation"
    (let [schema (get @schema-util/registry* :judge/score-emitted)]
      (is (some? schema)
          ":judge/score-emitted schema should be registered in the global registry")
      (is (m/validate schema well-formed-body)
          (str "Well-formed body should validate. Explanation: "
               (when schema (pr-str (m/explain schema well-formed-body))))))))

(deftest judge-score-emitted-schema-rejects-missing-score
  (testing "A body missing the required :score field fails validation"
    (let [schema (get @schema-util/registry* :judge/score-emitted)
          malformed (dissoc well-formed-body :score)]
      (is (some? schema))
      (is (not (m/validate schema malformed))
          "Missing :score must fail validation"))))

(deftest judge-score-emitted-schema-rejects-missing-judge-name
  (testing "A body missing :judge-name fails validation"
    (let [schema (get @schema-util/registry* :judge/score-emitted)
          malformed (dissoc well-formed-body :judge-name)]
      (is (not (m/validate schema malformed))
          "Missing :judge-name must fail validation"))))

(deftest judge-score-emitted-schema-rejects-out-of-range-score
  (testing "A score outside [0.0, 1.0] fails validation"
    (let [schema (get @schema-util/registry* :judge/score-emitted)
          malformed (assoc well-formed-body :score 1.5)]
      (is (not (m/validate schema malformed))
          "Score > 1.0 must fail validation"))))

(deftest judge-score-emitted-schema-accepts-empty-dimensions
  (testing "An empty :dimensions vector is acceptable (some judges produce no dimensions)"
    (let [schema (get @schema-util/registry* :judge/score-emitted)
          minimal (assoc well-formed-body :dimensions [])]
      (is (m/validate schema minimal)
          "Empty :dimensions should validate — judges may produce no breakdown"))))

;; =============================================================================
;; RED #3 — Processor gated by opt-in flag (tracer bullet)
;; =============================================================================
;;
;; New defprocessor in evaluation/core/judge-runtime subscribed to
;; :sheet/node-execution-completed. With the opt-in flag OFF (default),
;; the processor must NOT emit any :judge/score-emitted events, even if
;; the host node has judges attached.
;;
;; This is the tracer-bullet test — proves the processor is registered,
;; subscribed to the right event, and respects the flag gate. Doesn't
;; yet test the dispatch logic (that's RED#5+).

(defn- emit-node-execution-completed!
  "Helper: emit a synthetic :sheet/node-execution-completed event so
   the new processor reacts to it. We dispatch a real command rather
   than poking the event store directly, to exercise the full path."
  [ctx sheet-id tick-id node-id]
  (cp/process-command
    (assoc ctx :command
           {:command/name :sheet/complete-node-execution
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id sheet-id
            :tick-id tick-id
            :node-id node-id
            :node-type :llm
            :status :success
            :writes {}
            :duration-ms 100})))

(defn- emit-node-execution-with-tree-output!
  "Emit a node-execution-completed event whose :writes carries the
   model's :generated-tree-raw — what a real repl-researcher Phase 1
   emit-tree! would write into the blackboard."
  [ctx sheet-id tick-id node-id tree-dsl]
  (cp/process-command
    (assoc ctx :command
           {:command/name :sheet/complete-node-execution
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id sheet-id
            :tick-id tick-id
            :node-id node-id
            :node-type :llm
            :status :success
            :writes {:generated-tree-raw tree-dsl}
            :duration-ms 100})))

(deftest processor-no-events-when-opt-in-off
  (testing "Tracer bullet: with opt-in flag OFF, no :judge/score-emitted events fire even after node-execution-completed"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)]
        ;; Flag is default OFF — no explicit set call
        (is (false? (ontology/get-living-description-enabled? ctx))
            "Pre-condition: opt-in flag is off")
        (emit-node-execution-completed! ctx sheet-id tick-id node-id)
        (Thread/sleep 400)
        (is (= 0 (count-score-emitted-events ctx))
            "No judge events should fire when opt-in is off")))))

;; =============================================================================
;; RED #4 — Early-return when no judges attached to the completing node
;; =============================================================================
;;
;; Opt-in flag ON, but the node has no attached judges. Processor must
;; not emit any :judge/score-emitted events. This isolates the no-judges
;; short-circuit from the judge-dispatch logic (which lands in RED#5+).

(deftest processor-no-events-when-no-judges-attached
  (testing "With opt-in flag ON but the node has no :judges attached, processor emits zero events"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (is (true? (ontology/get-living-description-enabled? ctx))
          "Pre-condition: opt-in flag is on")
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)]
        ;; Node has no attached judges (it doesn't even exist in any sheet,
        ;; so get-node returns nil and the read-model's :judges field is nil)
        (emit-node-execution-completed! ctx sheet-id tick-id node-id)
        (Thread/sleep 400)
        (is (= 0 (count-score-emitted-events ctx))
            "No judge events when no judges attached")))))

;; =============================================================================
;; RED #5 — One LLM judge dispatches and emits :judge/score-emitted
;; =============================================================================
;;
;; First real dispatch slice. Test setup builds a real sheet with a leaf
;; node that has a `:grounding` judge attached. After node-execution-
;; completed fires, the processor must look up the judge, invoke
;; evaluation/grounding-judge (mock mode bound so no real LLM call), and
;; emit exactly one :judge/score-emitted event with judge-name "grounding"
;; and a structured score/feedback.
;;
;; Uses evaluation/judges/*use-mock-llm* dynamic var to skip real LLM —
;; the judge function returns the mock score map.

(defn- setup-sheet-with-judges!
  "Helper: create a sheet, declare a judge, create a leaf node, attach
   the judge. Returns {:sheet-id :node-id}."
  [ctx judge-name judge-config]
  (let [sheet-result (cp/process-command
                       (assoc ctx :command
                              {:command/name :sheet/create-sheet
                               :command/id (random-uuid)
                               :command/timestamp (time/now)
                               :name (str "Test sheet — " (random-uuid))}))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)
        _ (cp/process-command
            (assoc ctx :command
                   {:command/name :sheet/declare-judge
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :sheet-id sheet-id
                    :judge-name judge-name
                    :judge-config judge-config}))
        node-result (cp/process-command
                      (assoc ctx :command
                             {:command/name :sheet/create-node
                              :command/id (random-uuid)
                              :command/timestamp (time/now)
                              :sheet-id sheet-id
                              :type :leaf}))
        node-id (-> node-result :command-result/events first :node-id)
        _ (cp/process-command
            (assoc ctx :command
                   {:command/name :sheet/set-node-judges
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :sheet-id sheet-id
                    :node-id node-id
                    :judges [judge-name]}))]
    (Thread/sleep 100)
    {:sheet-id sheet-id :node-id node-id}))

(defn- setup-sheet-with-multiple-judges!
  "Helper: create a sheet + leaf node + attach a list of [name config] judges."
  [ctx judge-pairs]
  (let [sheet-result (cp/process-command
                       (assoc ctx :command
                              {:command/name :sheet/create-sheet
                               :command/id (random-uuid)
                               :command/timestamp (time/now)
                               :name (str "Test sheet — " (random-uuid))}))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)
        _ (doseq [[judge-name judge-config] judge-pairs]
            (cp/process-command
              (assoc ctx :command
                     {:command/name :sheet/declare-judge
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :sheet-id sheet-id
                      :judge-name judge-name
                      :judge-config judge-config})))
        node-result (cp/process-command
                      (assoc ctx :command
                             {:command/name :sheet/create-node
                              :command/id (random-uuid)
                              :command/timestamp (time/now)
                              :sheet-id sheet-id
                              :type :leaf}))
        node-id (-> node-result :command-result/events first :node-id)
        _ (cp/process-command
            (assoc ctx :command
                   {:command/name :sheet/set-node-judges
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :sheet-id sheet-id
                    :node-id node-id
                    :judges (mapv first judge-pairs)}))]
    (Thread/sleep 150)
    {:sheet-id sheet-id :node-id node-id}))

(deftest one-grounding-judge-dispatches-and-emits-event
  (testing "Opt-in ON + node with [:grounding] attached + mock LLM mode → exactly one :judge/score-emitted event lands"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      ;; *use-mock-llm* is a dynamic var; `binding` doesn't propagate across
      ;; the processor's async/thread boundary. with-redefs mutates the var's
      ;; root value globally for the body's lifetime, which DOES propagate.
      (with-redefs [judges/*use-mock-llm* true]
        (let [{:keys [sheet-id node-id]} (setup-sheet-with-judges!
                                            ctx "my-grounding"
                                            {:type :grounding})
              tick-id (random-uuid)]
          (emit-node-execution-completed! ctx sheet-id tick-id node-id)
          (Thread/sleep 500)
          (let [events (into [] (es/read (:event-store ctx)
                                         {:types #{:judge/score-emitted}
                                          :tenant-id (:tenant-id ctx)}))]
            (is (= 1 (count events))
                "Exactly one :judge/score-emitted event should land")
            (when (seq events)
              (let [e (first events)]
                (is (= "my-grounding" (:judge-name e))
                    "Event carries the configured judge-name")
                (is (number? (:score e))
                    "Event has a numeric score")
                (is (string? (:feedback e))
                    "Event has feedback text")
                (is (= node-id (:node-id e))
                    "Event references the host node")))))))))

;; =============================================================================
;; RED #6 — All 4 LLM judges dispatch in one node-execution
;; =============================================================================
;;
;; Attaching all four prebuilt LLM judges to a single leaf node should
;; produce one :judge/score-emitted event per judge. Verifies the
;; dispatch table covers grounding / reasoning / completeness /
;; instruction-following uniformly and that one node-execution-completed
;; event drives multiple judge runs.

(deftest four-llm-judges-dispatch-in-parallel
  (testing "All 4 LLM judge types attached → 4 :judge/score-emitted events with correct judge-names"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (with-redefs [judges/*use-mock-llm* true]
        (let [{:keys [sheet-id node-id]}
              (setup-sheet-with-multiple-judges!
                ctx
                [["g" {:type :grounding}]
                 ["r" {:type :reasoning}]
                 ["c" {:type :completeness}]
                 ["i" {:type :instruction-following}]])
              tick-id (random-uuid)]
          (emit-node-execution-completed! ctx sheet-id tick-id node-id)
          (Thread/sleep 600)
          (let [events (into [] (es/read (:event-store ctx)
                                         {:types #{:judge/score-emitted}
                                          :tenant-id (:tenant-id ctx)}))]
            (is (= 4 (count events))
                "All four judges should emit exactly one event each")
            (is (= #{"g" "r" "c" "i"}
                   (set (map :judge-name events)))
                "Events should cover all four configured judge-names")
            (is (every? #(and (number? (:score %))
                              (string? (:feedback %)))
                        events)
                "Every event has a numeric score and string feedback")))))))

;; =============================================================================
;; RED #7 — Per-judge failure isolation
;; =============================================================================
;;
;; A single judge that throws inside its invocation must NOT block other
;; attached judges. Try/catch per judge + mulog log. Only the surviving
;; judges emit events.

(deftest one-judge-throws-others-still-fire
  (testing "Two judges attached, one throws → exactly one event lands (the surviving judge)"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (with-redefs [judges/*use-mock-llm* true
                    ;; Force grounding-judge to throw; reasoning-judge passes through
                    judges/grounding-judge (fn [_ctx]
                                             (throw (ex-info "synthetic grounding failure" {})))]
        (let [{:keys [sheet-id node-id]}
              (setup-sheet-with-multiple-judges!
                ctx
                [["g" {:type :grounding}]
                 ["r" {:type :reasoning}]])
              tick-id (random-uuid)]
          (emit-node-execution-completed! ctx sheet-id tick-id node-id)
          (Thread/sleep 500)
          (let [events (into [] (es/read (:event-store ctx)
                                         {:types #{:judge/score-emitted}
                                          :tenant-id (:tenant-id ctx)}))]
            (is (= 1 (count events))
                "Only the surviving (reasoning) judge should have emitted")
            (when (seq events)
              (is (= "r" (:judge-name (first events)))
                  "The surviving event is the reasoning judge"))))))))

;; =============================================================================
;; RED #8 — :heuristic-structural judge stub emits sentinel value
;; =============================================================================
;;
;; Gap-2 replaces this with the real heuristic structural evaluator
;; (re-extracted from the retiring RLM Rolling Judge). For Gap-1, the
;; type is recognized and emits a sentinel score so downstream
;; consumers can be written against the unified event shape now.

(deftest heuristic-structural-judge-recognized-and-emits
  (testing ":heuristic-structural judge type is recognized and emits when a tree is present in :writes"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [{:keys [sheet-id node-id]}
            (setup-sheet-with-judges! ctx "structure" {:type :heuristic-structural})
            tick-id (random-uuid)
            tree-dsl [:sequence [:llm {}] [:final {}]]]
        (emit-node-execution-with-tree-output! ctx sheet-id tick-id node-id tree-dsl)
        (Thread/sleep 400)
        (let [events (into [] (es/read (:event-store ctx)
                                       {:types #{:judge/score-emitted}
                                        :tenant-id (:tenant-id ctx)}))]
          (is (= 1 (count events))
              "Heuristic-structural judge emits when tree is present in :writes")
          (when (seq events)
            (let [e (first events)]
              (is (= "structure" (:judge-name e)))
              (is (number? (:score e))
                  "Score is numeric (Gap-2 real evaluator output)"))))))))

;; =============================================================================
;; RED #9 — :custom judge type gracefully no-ops (Gap-4 implements)
;; =============================================================================
;;
;; The :custom judge type accepts a :sheet-id reference to a consumer-
;; defined evaluation sheet. Gap-4 ships the sub-execution mechanics.
;; For Gap-1, the type must be RECOGNIZED but invoke-judge returns nil
;; (graceful no-op). No event emitted, no crash, no other judges
;; affected.

(deftest custom-judge-type-gracefully-no-ops
  (testing ":custom judge type: no event emitted, no crash, sibling judges still fire"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (with-redefs [judges/*use-mock-llm* true]
        ;; Attach a :custom judge alongside an LLM judge. The custom one
        ;; should silently no-op; the LLM one should emit normally.
        (let [{:keys [sheet-id node-id]}
              (setup-sheet-with-multiple-judges!
                ctx
                [["g" {:type :grounding}]
                 ["mycustom" {:type :custom :sheet-id (random-uuid)}]])
              tick-id (random-uuid)]
          (emit-node-execution-completed! ctx sheet-id tick-id node-id)
          (Thread/sleep 500)
          (let [events (into [] (es/read (:event-store ctx)
                                         {:types #{:judge/score-emitted}
                                          :tenant-id (:tenant-id ctx)}))]
            (is (= 1 (count events))
                "Exactly one event — the LLM judge emits; the :custom judge no-ops in Gap-1")
            (when (seq events)
              (is (= "g" (:judge-name (first events)))
                  "The surviving event is the LLM judge, not the custom"))))))))

;; =============================================================================
;; RED #10 — get-judge-scores read-model and public query
;; =============================================================================
;;
;; After N :judge/score-emitted events fire for a (sheet-id, tick-id,
;; node-id) tuple, the public query (evaluation/get-judge-scores ctx
;; sheet-id node-id tick-id) returns the vector of judge result bodies
;; in emission order.

(deftest get-judge-scores-returns-emitted-events
  (testing "After 2 judges fire on a node, get-judge-scores returns both"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (with-redefs [judges/*use-mock-llm* true]
        (let [{:keys [sheet-id node-id]}
              (setup-sheet-with-multiple-judges!
                ctx
                [["g" {:type :grounding}]
                 ["r" {:type :reasoning}]])
              tick-id (random-uuid)]
          (emit-node-execution-completed! ctx sheet-id tick-id node-id)
          (Thread/sleep 500)
          (let [scores ((requiring-resolve
                          'ai.obney.orc.evaluation.interface/get-judge-scores)
                        ctx sheet-id node-id tick-id)]
            (is (= 2 (count scores))
                "Read-model returns both emitted scores")
            (is (= #{"g" "r"} (set (map :judge-name scores)))
                "Both judge-names present in the result vector")
            (is (every? #(number? (:score %)) scores)
                "Every score entry has a numeric :score field")))))))

;; =============================================================================
;; Gap-5 — Default judge attachment for repl-researcher nodes
;; =============================================================================
;;
;; When the Living Description opt-in flag is on AND a repl-researcher
;; node has no explicit :judges attached, the resolver applies a default
;; set: heuristic-structural + grounding + reasoning + completeness +
;; instruction-following. Consumer's explicit set-node-judges (even with
;; an empty list) ALWAYS wins over defaults. Defaults DO NOT apply to
;; other node types — only :repl-researcher.

(defn- create-repl-researcher-node!
  "Create a repl-researcher node in the given sheet. Returns node-id."
  [ctx sheet-id]
  (let [result (cp/process-command
                 (assoc ctx :command
                        {:command/name :sheet/create-node
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :sheet-id sheet-id
                         :type :repl-researcher}))]
    (Thread/sleep 100)
    (-> result :command-result/events first :node-id)))

(defn- create-bare-sheet!
  "Create a fresh sheet, return sheet-id."
  [ctx]
  (let [result (cp/process-command
                 (assoc ctx :command
                        {:command/name :sheet/create-sheet
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :name (str "gap5 — " (random-uuid))}))]
    (-> result :command-result/events first :sheet-id)))

(deftest get-effective-judges-defaults-on-repl-researcher
  (testing "When opt-in is ON and a repl-researcher node has no explicit :judges, the resolver returns the 5 default judges"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [sheet-id (create-bare-sheet! ctx)
            node-id (create-repl-researcher-node! ctx sheet-id)
            get-eff (requiring-resolve
                      'ai.obney.orc.evaluation.interface/get-effective-judges-for-node)
            effective (get-eff ctx sheet-id node-id)]
        (is (= 5 (count effective))
            "Resolver returns 5 default judges for repl-researcher with no explicit set-node-judges")
        (is (= #{:heuristic-structural :grounding :reasoning :completeness :instruction-following}
               (set (map #(get-in % [:judge-config :type]) effective)))
            "Defaults include all four LLM judges plus heuristic-structural")))))

(deftest get-effective-judges-non-repl-researcher-no-defaults
  (testing ":leaf node (not :repl-researcher) gets no default judges"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [sheet-id (create-bare-sheet! ctx)
            node-result (cp/process-command
                          (assoc ctx :command
                                 {:command/name :sheet/create-node
                                  :command/id (random-uuid)
                                  :command/timestamp (time/now)
                                  :sheet-id sheet-id
                                  :type :leaf}))
            node-id (-> node-result :command-result/events first :node-id)
            _ (Thread/sleep 100)
            get-eff (requiring-resolve
                      'ai.obney.orc.evaluation.interface/get-effective-judges-for-node)]
        (is (= [] (get-eff ctx sheet-id node-id))
            ":leaf node gets no defaults — only :repl-researcher does")))))

(deftest get-effective-judges-opt-in-off-no-defaults
  (testing "Opt-in flag OFF → no defaults applied even for repl-researcher"
    (with-test-ctx [ctx]
      ;; Flag stays default OFF
      (is (false? (ontology/get-living-description-enabled? ctx)))
      (let [sheet-id (create-bare-sheet! ctx)
            node-id (create-repl-researcher-node! ctx sheet-id)
            get-eff (requiring-resolve
                      'ai.obney.orc.evaluation.interface/get-effective-judges-for-node)]
        (is (= [] (get-eff ctx sheet-id node-id))
            "Opt-in flag still gates defaults")))))

(deftest get-effective-judges-explicit-empty-disables-defaults
  (testing "Consumer set-node-judges with [] explicitly → zero judges (consumer override wins over defaults)"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [sheet-id (create-bare-sheet! ctx)
            node-id (create-repl-researcher-node! ctx sheet-id)
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/set-node-judges
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id sheet-id
                        :node-id node-id
                        :judges []}))
            _ (Thread/sleep 100)
            get-eff (requiring-resolve
                      'ai.obney.orc.evaluation.interface/get-effective-judges-for-node)]
        (is (= [] (get-eff ctx sheet-id node-id))
            "Explicit empty consumer override returns []")))))

;; =============================================================================
;; Gap-5 RED#3-#6 — Integration: defaults dispatch through the runtime
;; =============================================================================

(deftest gap5-defaults-fire-for-repl-researcher
  (testing "repl-researcher node + opt-in ON + no explicit judges → all 5 defaults fire"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (with-redefs [judges/*use-mock-llm* true]
        (let [sheet-id (create-bare-sheet! ctx)
              node-id (create-repl-researcher-node! ctx sheet-id)
              tick-id (random-uuid)
              ;; Use a tree-bearing event so heuristic-structural can score
              tree-dsl [:sequence [:llm {}] [:final {}]]]
          (emit-node-execution-with-tree-output! ctx sheet-id tick-id node-id tree-dsl)
          (Thread/sleep 800)
          (let [events (into [] (es/read (:event-store ctx)
                                         {:types #{:judge/score-emitted}
                                          :tenant-id (:tenant-id ctx)}))]
            (is (= 5 (count events))
                "All 5 default judges (heuristic-structural + 4 LLM) fired")
            (is (= #{"heuristic-structural" "grounding" "reasoning"
                     "completeness" "instruction-following"}
                   (set (map :judge-name events)))
                "Default judge names match")))))))

(deftest gap5-consumer-empty-override-fires-nothing
  (testing "repl-researcher + opt-in ON + consumer set-node-judges [] → zero events"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [sheet-id (create-bare-sheet! ctx)
            node-id (create-repl-researcher-node! ctx sheet-id)
            tick-id (random-uuid)
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/set-node-judges
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id sheet-id
                        :node-id node-id
                        :judges []}))
            _ (Thread/sleep 100)
            tree-dsl [:sequence [:llm {}] [:final {}]]]
        (emit-node-execution-with-tree-output! ctx sheet-id tick-id node-id tree-dsl)
        (Thread/sleep 400)
        (let [events (into [] (es/read (:event-store ctx)
                                       {:types #{:judge/score-emitted}
                                        :tenant-id (:tenant-id ctx)}))]
          (is (= 0 (count events))
              "Explicit empty override silences defaults"))))))

(deftest gap5-non-repl-researcher-leaf-no-defaults
  (testing ":leaf node (not :repl-researcher) + opt-in ON + no explicit judges → zero events"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (with-redefs [judges/*use-mock-llm* true]
        (let [sheet-id (create-bare-sheet! ctx)
              node-result (cp/process-command
                            (assoc ctx :command
                                   {:command/name :sheet/create-node
                                    :command/id (random-uuid)
                                    :command/timestamp (time/now)
                                    :sheet-id sheet-id
                                    :type :leaf}))
              node-id (-> node-result :command-result/events first :node-id)
              _ (Thread/sleep 100)
              tick-id (random-uuid)
              tree-dsl [:sequence [:llm {}] [:final {}]]]
          (emit-node-execution-with-tree-output! ctx sheet-id tick-id node-id tree-dsl)
          (Thread/sleep 400)
          (let [events (into [] (es/read (:event-store ctx)
                                         {:types #{:judge/score-emitted}
                                          :tenant-id (:tenant-id ctx)}))]
            (is (= 0 (count events))
                "Defaults only apply to :repl-researcher; :leaf gets nothing")))))))

(deftest gap5-defaults-do-not-fire-when-opt-in-off
  (testing "repl-researcher node + opt-in OFF + no explicit judges → zero events"
    (with-test-ctx [ctx]
      ;; Flag default OFF — no explicit set call
      (with-redefs [judges/*use-mock-llm* true]
        (let [sheet-id (create-bare-sheet! ctx)
              node-id (create-repl-researcher-node! ctx sheet-id)
              tick-id (random-uuid)
              tree-dsl [:sequence [:llm {}] [:final {}]]]
          (emit-node-execution-with-tree-output! ctx sheet-id tick-id node-id tree-dsl)
          (Thread/sleep 400)
          (let [events (into [] (es/read (:event-store ctx)
                                         {:types #{:judge/score-emitted}
                                          :tenant-id (:tenant-id ctx)}))]
            (is (= 0 (count events))
                "Opt-in flag still gates default attachment")))))))

;; =============================================================================
;; Gap-2 RED #4 — standalone Rolling Judge processor retired
;; =============================================================================
;;
;; The :rlm evaluate-rlm-tree processor at orc-service todo_processors.clj
;; subscribed to :rlm/tree-generated and emitted :rlm/tree-evaluated.
;; Under the unified protocol it's superseded by the :heuristic-structural
;; judge attached via the per-event evaluator runtime. After retirement:
;;   - The processor key is no longer registered in tp/processor-registry*
;;   - :rlm/tree-evaluated event TYPE schema stays (backward compat for any
;;     external consumer) but nothing in our codebase emits it.

(deftest standalone-rlm-rolling-judge-processor-not-registered
  (testing "Gap-2: :rlm evaluate-rlm-tree processor is no longer in the global registry"
    (is (not (contains? @tp/processor-registry* :rlm/evaluate-rlm-tree))
        "After Gap-2 retirement, the standalone RLM Rolling Judge processor must be gone — its functionality moved under the unified evaluator protocol via the :heuristic-structural judge type.")))

;; =============================================================================
;; Gap-2 RED #2 — heuristic-structural judge uses real evaluator
;; =============================================================================
;;
;; Replaces Gap-1's hardcoded sentinel (score 0.5, "stub" feedback)
;; with the real structural heuristic extracted in Gap-2 RED#1. When
;; the node-execution-completed event carries a :generated-tree-raw in
;; its :writes, the heuristic-structural judge scores the actual tree
;; shape — exposing whether the model used :sequence / :map-each /
;; :final patterns.

(deftest heuristic-structural-uses-real-evaluator-not-stub
  (testing "When the host node has :generated-tree-raw in outputs, the heuristic-structural judge scores the actual tree shape (not the Gap-1 sentinel)"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [{:keys [sheet-id node-id]}
            (setup-sheet-with-judges! ctx "structure" {:type :heuristic-structural})
            tick-id (random-uuid)
            ;; An EXCELLENT tree shape per the heuristic: :sequence + :map-each + :final
            excellent-tree [:sequence
                            [:chunk-document {:from :doc :into :chunks}]
                            [:map-each {:from :chunks :as :chunk :into :results}
                             [:llm {:reads [:chunk] :writes [:result]}]]
                            [:aggregate {:from :results :writes [:out]}]
                            [:final {:keys [:out]}]]]
        (emit-node-execution-with-tree-output! ctx sheet-id tick-id node-id excellent-tree)
        (Thread/sleep 400)
        (let [events (into [] (es/read (:event-store ctx)
                                       {:types #{:judge/score-emitted}
                                        :tenant-id (:tenant-id ctx)}))]
          (is (= 1 (count events))
              "Heuristic judge emitted once")
          (when (seq events)
            (let [e (first events)]
              (is (>= (:score e) 0.5)
                  (str "Excellent tree shape (seq + map-each + final) should score >= 0.5 — "
                       "Gap-1 sentinel returned 0.5 hardcoded; if we still see 0.5 the "
                       "real evaluator may not be wired. Got: " (:score e)))
              (is (not (.contains (str (:feedback e)) "stub"))
                  "Feedback should NOT contain the Gap-1 'stub' text — that means the sentinel is still in place")
              ;; Substantive check: real evaluator produces 2 dimensions
              (is (= 2 (count (:dimensions e)))
                  "Real evaluator emits 2 dimensions (Structure + Decomposition); Gap-1 sentinel emitted []"))))))))

;; =============================================================================
;; Gap-2 RED #3 — graceful default when host node didn't emit a tree
;; =============================================================================

(deftest heuristic-structural-no-tree-no-event
  (testing "When the node-execution-completed event has no :generated-tree-raw in :writes, the heuristic judge gracefully no-ops"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [{:keys [sheet-id node-id]}
            (setup-sheet-with-judges! ctx "structure" {:type :heuristic-structural})
            tick-id (random-uuid)]
        ;; emit a node-completed event with EMPTY writes (no tree output)
        (emit-node-execution-completed! ctx sheet-id tick-id node-id)
        (Thread/sleep 400)
        (let [events (into [] (es/read (:event-store ctx)
                                       {:types #{:judge/score-emitted}
                                        :tenant-id (:tenant-id ctx)}))]
          (is (= 0 (count events))
              "No tree output → heuristic judge doesn't emit (avoids scoring nothing)"))))))

;; =============================================================================
;; RED #11 — Integration tracer: mixed judges, end-to-end queryable
;; =============================================================================
;;
;; Final synthetic check before LIVE verify. Combines LLM + heuristic
;; judges on one node, exercises the full chain: opt-in flag → judges
;; attached → node-execution-completed → processor → multiple judges
;; invoked → events emitted → queryable via public API. Mirrors what
;; the bench's repl-researcher path will do, just at this slice's
;; synthetic level.

(deftest integration-tracer-mixed-judges-end-to-end
  (testing "Mixed judges (2 LLM + heuristic-structural) attached → all emit + queryable"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (with-redefs [judges/*use-mock-llm* true]
        (let [{:keys [sheet-id node-id]}
              (setup-sheet-with-multiple-judges!
                ctx
                [["g" {:type :grounding}]
                 ["c" {:type :completeness}]
                 ["structure" {:type :heuristic-structural}]])
              tick-id (random-uuid)
              tree-dsl [:sequence [:llm {}] [:final {}]]
              get-scores (requiring-resolve
                           'ai.obney.orc.evaluation.interface/get-judge-scores)]
          ;; Pre-condition: no scores yet
          (is (= [] (get-scores ctx sheet-id node-id tick-id))
              "Before execution, no scores exist for this tick/node")
          (emit-node-execution-with-tree-output! ctx sheet-id tick-id node-id tree-dsl)
          (Thread/sleep 600)
          ;; Post-condition: 3 scores, all queryable
          (let [scores (get-scores ctx sheet-id node-id tick-id)]
            (is (= 3 (count scores))
                "All three judges emitted")
            (is (= #{"g" "c" "structure"} (set (map :judge-name scores)))
                "All three judge-names present")
            (is (every? #(and (number? (:score %))
                              (string? (:feedback %))
                              (= sheet-id (:sheet-id %))
                              (= node-id (:node-id %))
                              (= tick-id (:tick-id %)))
                        scores)
                "All entries have canonical shape + correct identifiers"))
          ;; Independent isolation: a different tick on the same node sees 0 scores
          (is (= [] (get-scores ctx sheet-id node-id (random-uuid)))
              "Different tick-id returns empty (scope isolation)"))))))

;; =============================================================================
;; Observability — all-judges-returned-nil silent-skip
;; =============================================================================
;;
;; Diagnostic surfaced via gap3_anomaly_diagnose (2026-06-03): a real
;; bench run's terminal repl-researcher completion lacks
;; `:generated-tree-raw` in `:writes`, so heuristic-structural returns
;; nil. In that diagnostic, the 4 LLM judges ALSO returned nil for the
;; terminal completion (root cause deferred to follow-up issue
;; `Gap-7-judge-routing-by-completion-kind.md`). The combined effect
;; was a silent skip: the processor returned nil with no events, no
;; logs, no signal.
;;
;; This test locks the post-fix contract: when effective-judges is
;; non-empty but every judge returns nil, the processor still returns
;; nil (no events emitted, no crash) — the observability work happens
;; via mulog in the production path and is not asserted here. The
;; behavior guarantee is: no spurious judge events, no exception.

(deftest processor-no-events-when-effective-judges-all-return-nil
  (testing "When effective judges are non-empty but every judge returns nil (e.g., heuristic-structural with no tree in :writes), the processor returns nil — no judge events, no crash"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [;; Attach ONLY heuristic-structural so the only path is the
            ;; one that nils when :generated-tree-raw is absent.
            {:keys [sheet-id node-id]}
            (setup-sheet-with-judges! ctx "structure" {:type :heuristic-structural})
            tick-id (random-uuid)]
        ;; Emit a completion with empty :writes — no :generated-tree-raw.
        ;; This mirrors the recursive-RLM terminal-(final!)-completion
        ;; shape that the anomaly diagnostic surfaced.
        (emit-node-execution-completed! ctx sheet-id tick-id node-id)
        (Thread/sleep 400)
        (is (= 0 (count-score-emitted-events ctx))
            "No :judge/score-emitted events when all effective judges return nil — processor must not fabricate events")))))

;; =============================================================================
;; Gap-7 RED#1 — resolver filters by completion-kind
;; =============================================================================
;;
;; Recursive RLM fires :sheet/node-execution-completed multiple times
;; per "run" with different :writes payloads. Intermediate ticks have
;; :generated-tree-raw; terminal (final!) ticks have final outputs.
;; Different judges grade different completion kinds. The resolver
;; filters effective judges by checking each judge's
;; :applies-to-completion-kinds set against the event's completion-
;; kind. Judges whose set doesn't include the event's kind are
;; filtered out.

(deftest gap7-resolver-filters-by-applies-to-completion-kinds
  (testing "Gap-7 RED#1: a judge whose :applies-to-completion-kinds excludes the event's :completion-kind is filtered out of the effective list when the resolver is called with the kind"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [{:keys [sheet-id node-id]}
            (setup-sheet-with-judges! ctx "structure"
                                       {:type :heuristic-structural
                                        :applies-to-completion-kinds #{:tree-iteration}})
            resolver @#'ai.obney.orc.evaluation.core.judge-runtime/get-effective-judges-for-node]
        ;; Sanity: the 3-arg arity still returns the judge (no filter)
        (let [unfiltered (resolver ctx sheet-id node-id)]
          (is (= 1 (count unfiltered))
              "3-arg arity returns the explicitly-attached judge")
          (is (= "structure" (-> unfiltered first :judge-name))
              "3-arg arity returns the judge by name"))
        ;; The new arity with completion-kind :terminal — judge excluded
        (let [filtered-terminal (resolver ctx sheet-id node-id :terminal)]
          (is (= 0 (count filtered-terminal))
              (str "4-arg arity with :terminal filters out a judge configured "
                   "for :tree-iteration. Got: " (pr-str filtered-terminal))))
        ;; The new arity with completion-kind :tree-iteration — judge included
        (let [filtered-iter (resolver ctx sheet-id node-id :tree-iteration)]
          (is (= 1 (count filtered-iter))
              "4-arg arity with :tree-iteration retains the matching judge")
          (is (= "structure" (-> filtered-iter first :judge-name))
              "Retained judge's name preserved"))))))

;; =============================================================================
;; Gap-7 RED#3 — :sheet/node-execution-completed event schema accepts :completion-kind
;; =============================================================================

(deftest gap7-node-execution-completed-schema-accepts-completion-kind
  (testing "Gap-7 RED#3: the :sheet/complete-node-execution command + :sheet/node-execution-completed event accept an optional :completion-kind field of value :tree-iteration or :terminal"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [{:keys [sheet-id node-id]}
            (setup-sheet-with-judges! ctx "structure" {:type :heuristic-structural})
            tick-id (random-uuid)
            ;; Dispatch a complete-node-execution command WITH :completion-kind :terminal.
            ;; If the schema doesn't accept the field, this dispatch returns an
            ;; anomaly and no event lands.
            result (cp/process-command
                     (assoc ctx :command
                            {:command/name :sheet/complete-node-execution
                             :command/id (random-uuid)
                             :command/timestamp (time/now)
                             :sheet-id sheet-id
                             :tick-id tick-id
                             :node-id node-id
                             :node-type :leaf
                             :status :success
                             :writes {}
                             :completion-kind :terminal
                             :duration-ms 100}))]
        (Thread/sleep 200)
        (is (not (:cognitect.anomalies/category result))
            (str "Command must accept :completion-kind without anomaly. Got: " (pr-str result)))
        (let [events (into [] (es/read (:event-store ctx)
                                       {:types #{:sheet/node-execution-completed}
                                        :tenant-id (:tenant-id ctx)}))
              matching (filter #(and (= sheet-id (:sheet-id %))
                                      (= tick-id (:tick-id %))
                                      (= node-id (:node-id %)))
                               events)]
          (is (= 1 (count matching))
              "The completion event landed")
          (is (= :terminal (:completion-kind (first matching)))
              (str "Event must carry :completion-kind :terminal verbatim. Got: "
                   (pr-str (first matching)))))))))

;; =============================================================================
;; Gap-7 RED#4 — processor honors event :completion-kind during resolution
;; =============================================================================

(deftest gap7-processor-routes-judge-by-event-completion-kind
  (testing "Gap-7 RED#4: when a node's attached judge declares :applies-to-completion-kinds #{:tree-iteration} and the event arrives with :completion-kind :terminal, the processor does NOT fire the judge. Inverse: when the event's :completion-kind is :tree-iteration, the judge fires."
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [{:keys [sheet-id node-id]}
            (setup-sheet-with-judges! ctx "structure"
                                       {:type :heuristic-structural
                                        :applies-to-completion-kinds #{:tree-iteration}})
            tree-dsl [:sequence [:llm {}] [:final {}]]
            tick-terminal (random-uuid)
            tick-iter (random-uuid)]
        ;; Dispatch a TERMINAL completion — judge should NOT fire.
        (cp/process-command
          (assoc ctx :command
                 {:command/name :sheet/complete-node-execution
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :sheet-id sheet-id
                  :tick-id tick-terminal
                  :node-id node-id
                  :node-type :leaf
                  :status :success
                  :writes {:generated-tree-raw tree-dsl}  ;; tree present, BUT...
                  :completion-kind :terminal              ;; ...event marked terminal
                  :duration-ms 100}))
        (Thread/sleep 400)
        (is (= 0 (count-score-emitted-events ctx))
            "No judge events should fire when event's :completion-kind doesn't match judge's :applies-to-completion-kinds")
        ;; Dispatch a TREE-ITERATION completion — judge SHOULD fire.
        (cp/process-command
          (assoc ctx :command
                 {:command/name :sheet/complete-node-execution
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :sheet-id sheet-id
                  :tick-id tick-iter
                  :node-id node-id
                  :node-type :leaf
                  :status :success
                  :writes {:generated-tree-raw tree-dsl}
                  :completion-kind :tree-iteration
                  :duration-ms 100}))
        (Thread/sleep 400)
        (is (= 1 (count-score-emitted-events ctx))
            "Judge fires for matching :completion-kind")))))

;; =============================================================================
;; Gap-7 RED#5 — default judges routed by completion kind
;; =============================================================================
;;
;; Per the Gap-7 design: heuristic-structural grades the intermediate
;; tree shape; the 4 LLM judges grade the terminal synthesized output.
;; The default-judges set carries :applies-to-completion-kinds so the
;; resolver routes them correctly when opt-in is on.

(deftest gap7-default-judges-have-no-completion-kind-filter
  (testing "Gap-7 (post-revert 2026-06-05): default-judges have NO :applies-to-completion-kinds, so all 5 judges resolve regardless of the event's :completion-kind. The infrastructure (4-arg resolver arity, schema field, command-handler derivation) stays in place for Gap-7b. See default-judges docstring for the full retrospective. If a future change adds :applies-to-completion-kinds back to these defaults without Gap-7b shipping first, that's a silent regression."
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [sheet-id (create-bare-sheet! ctx)
            node-id (create-repl-researcher-node! ctx sheet-id)
            resolver @#'ai.obney.orc.evaluation.core.judge-runtime/get-effective-judges-for-node
            ;; All five expected default judge names.
            all-defaults #{"heuristic-structural" "grounding" "reasoning"
                           "completeness" "instruction-following"}]
        (let [no-kind (resolver ctx sheet-id node-id)
              iter-kind (resolver ctx sheet-id node-id :tree-iteration)
              terminal-kind (resolver ctx sheet-id node-id :terminal)]
          (is (= all-defaults (set (map :judge-name no-kind)))
              "3-arg arity (no completion-kind) returns all 5 default judges")
          (is (= all-defaults (set (map :judge-name iter-kind)))
              (str "With :tree-iteration: all 5 still return because defaults have "
                   "no :applies-to-completion-kinds field. "
                   "Got: " (pr-str (mapv :judge-name iter-kind))))
          (is (= all-defaults (set (map :judge-name terminal-kind)))
              (str "With :terminal: same — all 5 return. "
                   "Got: " (pr-str (mapv :judge-name terminal-kind))))
          ;; Lock in the infrastructure: a user attaching a CUSTOM judge
          ;; with :applies-to-completion-kinds STILL gets filtered (this
          ;; is what gap7-resolver-filters-by-applies-to-completion-kinds
          ;; tests). The defaults just don't USE the filter today.
          (doseq [j no-kind]
            (is (nil? (:applies-to-completion-kinds (:judge-config j)))
                (str "Default judge " (:judge-name j)
                     " must NOT carry :applies-to-completion-kinds — "
                     "see default-judges docstring for why."))))))))

;; =============================================================================
;; Gap-7 RED#6 — RLM repl-researcher dispatch populates :completion-kind by status
;; =============================================================================
;;
;; The orc-service todo processor that executes a repl-researcher
;; dispatches :sheet/complete-node-execution at the end of each Phase 1
;; iteration. In recursive RLM mode, status is :tree-generated for
;; intermediate iterations (where the model emit-tree!'d) and :success
;; for the terminal (final!) call. The dispatch must tag those
;; completion events with the appropriate :completion-kind so the judge
;; runtime routes correctly.
;;
;; This test isn't a full RLM execution (that needs the real executor +
;; an LLM). Instead it dispatches the command directly with both
;; status flavors and asserts the events carry the right
;; :completion-kind. The dispatch-site change (which the GREEN
;; implementation makes) is to populate the field at every callsite
;; based on the status.
;;
;; Note: this test ONLY locks the wiring between status and
;; completion-kind at the orc-service command level. The LIVE verify
;; exercises the full recursive-RLM path.

(deftest gap7-repl-researcher-completion-kind-derived-from-status
  (testing "Gap-7 RED#6: a completion command dispatched WITHOUT explicit :completion-kind but with status :tree-generated lands as :completion-kind :tree-iteration; status :success lands as :terminal. (Either the caller supplies kind or the command handler derives it from status — both interfaces are acceptable; this test verifies the END STATE on the event.)"
    (with-test-ctx [ctx]
      (let [sheet-id (create-bare-sheet! ctx)
            node-id (create-repl-researcher-node! ctx sheet-id)
            tick-iter (random-uuid)
            tick-term (random-uuid)]
        ;; Intermediate dispatch — status :tree-generated, NO explicit completion-kind.
        ;; Handler must derive :tree-iteration.
        (cp/process-command
          (assoc ctx :command
                 {:command/name :sheet/complete-node-execution
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :sheet-id sheet-id
                  :tick-id tick-iter
                  :node-id node-id
                  :node-type :repl-researcher
                  :status :tree-generated
                  :writes {:generated-tree-raw [:sequence [:llm {}]]}
                  :duration-ms 100}))
        ;; Terminal dispatch — status :success, NO explicit completion-kind.
        ;; Handler must derive :terminal.
        (cp/process-command
          (assoc ctx :command
                 {:command/name :sheet/complete-node-execution
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :sheet-id sheet-id
                  :tick-id tick-term
                  :node-id node-id
                  :node-type :repl-researcher
                  :status :success
                  :writes {:issues "final synthesis output"}
                  :duration-ms 100}))
        (Thread/sleep 300)
        (let [events (into [] (es/read (:event-store ctx)
                                       {:types #{:sheet/node-execution-completed}
                                        :tenant-id (:tenant-id ctx)}))
              by-tick (into {} (map (fn [e] [(:tick-id e) e])) events)
              iter-e (get by-tick tick-iter)
              term-e (get by-tick tick-term)]
          (is (= :tree-iteration (:completion-kind iter-e))
              (str "Intermediate completion (status :tree-generated) lands as :tree-iteration. Got: "
                   (pr-str iter-e)))
          (is (= :terminal (:completion-kind term-e))
              (str "Terminal completion (status :success) lands as :terminal. Got: "
                   (pr-str term-e))))))))

;; =============================================================================
;; Gap-7 fix#1 — build-trace-data reaches back for :inputs on terminal events
;; =============================================================================
;;
;; LIVE verify of Gap-7 routing surfaced that terminal repl-researcher
;; completions don't carry :inputs (the original task inputs flow
;; through :sheet/node-execution-started separately). The LLM judges'
;; build-trace-data then sees empty inputs → renders an empty
;; {inputs} JSON in the rubric prompt → OpenRouter responds without a
;; valid :score → invoke-llm-judge silently returns nil. Fix: when the
;; completion event lacks :inputs, build-trace-data queries the event
;; store for the matching (sheet, tick, node) :sheet/node-execution-
;; started event and uses its :inputs instead.

(deftest gap7-build-trace-data-reaches-back-for-inputs
  (testing "Gap-7 fix#1: build-trace-data, given a completion event with NO :inputs but a matching :sheet/node-execution-started event in the store with :inputs, returns trace-data whose :inputs is the started event's"
    (with-test-ctx [ctx]
      (let [build-trace-data @#'ai.obney.orc.evaluation.core.judge-runtime/build-trace-data
            sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            started-inputs {:document "The original task input text"
                            :max-length 1000}
            ;; Append a synthetic started event with the inputs we want
            ;; build-trace-data to find.
            _ (es/append (:event-store ctx)
                         {:tenant-id (:tenant-id ctx)
                          :events [(es/->event
                                     {:type :sheet/node-execution-started
                                      :tags #{[:sheet sheet-id]
                                              [:node node-id]
                                              [:tick tick-id]}
                                      :body {:sheet-id sheet-id
                                             :tick-id tick-id
                                             :node-id node-id
                                             :inputs started-inputs}})]})
            ;; The completion event has rich :writes but NO :inputs —
            ;; the shape we observed on real bench terminal completions.
            completion-event {:sheet-id sheet-id
                              :tick-id tick-id
                              :node-id node-id
                              :status :success
                              :writes {:issues "Found some legal issues"
                                       :ambiguities "Found some ambiguities"}}
            trace-data (build-trace-data ctx completion-event)]
        (is (= started-inputs (:inputs trace-data))
            (str "When the completion event lacks :inputs, build-trace-data must reach back "
                 "to the matching started event. Got: " (pr-str (:inputs trace-data))))
        (is (= {:issues "Found some legal issues"
                :ambiguities "Found some ambiguities"}
               (:outputs trace-data))
            ":outputs still comes from the completion event's :writes")))))

(deftest gap7-build-trace-data-prefers-direct-inputs-when-present
  (testing "Gap-7 fix#1: when the completion event ALREADY has :inputs, build-trace-data uses them directly and doesn't reach back. Avoids unnecessary event-store queries on the happy path."
    (with-test-ctx [ctx]
      (let [build-trace-data @#'ai.obney.orc.evaluation.core.judge-runtime/build-trace-data
            sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            ;; Started event with DIFFERENT inputs — should be ignored.
            _ (es/append (:event-store ctx)
                         {:tenant-id (:tenant-id ctx)
                          :events [(es/->event
                                     {:type :sheet/node-execution-started
                                      :tags #{[:sheet sheet-id]
                                              [:node node-id]
                                              [:tick tick-id]}
                                      :body {:sheet-id sheet-id
                                             :tick-id tick-id
                                             :node-id node-id
                                             :inputs {:stale "started inputs"}}})]})
            ;; Completion carries its own inputs — the started lookup should NOT clobber them.
            completion-event {:sheet-id sheet-id
                              :tick-id tick-id
                              :node-id node-id
                              :status :success
                              :inputs {:fresh "completion inputs"}
                              :writes {:final "output"}}
            trace-data (build-trace-data ctx completion-event)]
        (is (= {:fresh "completion inputs"} (:inputs trace-data))
            (str ":inputs must be the completion event's direct value, NOT the started event's. "
                 "Got: " (pr-str (:inputs trace-data))))))))
