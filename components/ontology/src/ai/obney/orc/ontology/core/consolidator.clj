(ns ai.obney.orc.ontology.core.consolidator
  "C-2a-3b — Living Description consolidator.

   Subscribes to :ontology/consolidation-requested events. For each
   request, gathers the target's current description + recent events +
   accumulated metrics + structural context, runs a single
   structured-output LLM reflection call, and emits the matching
   :*-description-updated event.

   Anti-recency-bias safeguards (confidence-decay/grow math + anomaly
   handling + budget cap + 3-run regression scenario) are SCOPED OUT —
   they land in C-2a-3c. For this slice, the LLM's :version and
   :consolidated-from-event-count fields are overwritten by the
   consolidator's computed values before emission."
  (:require [malli.core :as m]
            [malli.error :as me]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas :as ontology-schemas]
            [ai.obney.grain.command-processor-v2.interface :as command-processor]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; Recent-window size (configurable in C-2a-3c via event-sourced config)
;; =============================================================================

(def ^:private recent-window-size
  "Default window size for the consolidator's recent-events input.
   Decoupled from the threshold (trigger fires after N=10 events; the
   reflection sees up to W=500 historical events for richer context)."
  500)

;; =============================================================================
;; Reflection prompt + structured-output workflow
;; =============================================================================

(def ^:private reflection-instruction
  "Instruction text for the reflection LLM. The model is asked to
   synthesize a principle-shaped description body from the inputs."
  (str "You are consolidating an evolving description of a behavior-tree component "
       "from observed execution evidence. Your job is to produce a principle-shaped "
       "description body that captures the component's strengths, weaknesses, and "
       "actionable usage guidance.\n\n"
       "ANTI-RECENCY DISCIPLINE — read this carefully:\n"
       "  :aggregate-metrics is the STABLE BASELINE accumulated across the target's lifetime.\n"
       "  :recent-events is a LEADING INDICATOR slice (most recent events only).\n"
       "  :recent-vs-historical-delta is the computed gap between recent and historical.\n"
       "  Update strength/weakness confidences GRADUALLY — large recent swings against a strong\n"
       "  historical baseline should produce SMALL confidence changes, not big ones. Erasing a\n"
       "  prior strong principle because of one bad burst is the failure mode to avoid.\n"
       "  A consistent + substantial recent shift (delta of >0.20 sustained, OR aggregate\n"
       "  evidence-count high but recent counter-evidence consistent) warrants larger changes.\n\n"
       "INPUTS PROVIDED:\n"
       "- :target-type — the granularity: :node-type, :node-instance, or :tree-fingerprint\n"
       "- :target-id — the identity within that granularity (keyword, [sheet node] tuple, or string/UUID)\n"
       "- :current-description — the latest existing description body, or nil if this is the first consolidation\n"
       "- :recent-events — the last W events involving this target (status, duration, etc.)\n"
       "- :aggregate-metrics — accumulated rolling metrics across this target's lifetime\n"
       "- :recent-vs-historical-delta — {:recent-success-rate :historical-success-rate :delta}, or nil on first consolidation\n"
       "- :structural-context — for trees: the canonical tree-raw S-expression; "
       "for instances: the node config; for node-types: the keyword alone\n\n"
       "OUTPUT — :description-body is a single map with ALL of these top-level keys:\n"
       "  :capabilities         — vector of short strings, what the component CAN do\n"
       "  :strengths            — vector of principle-entry maps (see below)\n"
       "  :weaknesses           — vector of principle-entry maps (see below)\n"
       "  :representative-uses  — vector of short strings, concrete situations the component is good for\n"
       "  :avoid-when           — vector of short strings, contexts to AVOID using the component\n"
       "  :summary              — one-paragraph free-text synthesis (self-contained — NO file paths, NO internal slice names)\n"
       "  :version              — int (any value; consolidator overwrites with computed)\n"
       "  :consolidated-from-event-count — int (any value; consolidator overwrites with computed)\n\n"
       "EVERY one of those 8 keys MUST be present in your output. Empty vectors are acceptable for\n"
       ":capabilities, :strengths, :weaknesses, :representative-uses, :avoid-when when truly nothing applies,\n"
       "but you must include the key with an empty vector ([]) — DO NOT OMIT THE KEY.\n\n"
       "PRINCIPLE-ENTRY shape — every map in :strengths or :weaknesses must contain:\n"
       "  :trait                   — a concrete observable pattern (NOT a status like 'investigate')\n"
       "  :good-when               — context guard (strengths only)\n"
       "  :avoid-when              — context guard (weaknesses only)\n"
       "  :recommended-pattern     — actionable advice (strengths only)\n"
       "  :recommended-alternative — actionable advice (weaknesses only)\n"
       "  :confidence              — 0.0–1.0 weight signal\n"
       "  :evidence-count          — integer count of supporting observations\n"
       "  :first-observed-at       — ISO timestamp string\n"
       "  :last-reinforced-at      — ISO timestamp string\n\n"
       "NEVER produce status-shaped entries (avoid words like 'investigate', 'observed', "
       "'unclear'). Every entry, even at low confidence, must carry concrete actionable guidance.\n\n"
       "EXAMPLE OUTPUT (illustrative shape — your content will differ):\n"
       "  {:capabilities [\"runs a sub-LLM call\" \"produces structured output\"]\n"
       "   :strengths [{:trait \"...\" :good-when \"...\" :recommended-pattern \"...\"\n"
       "                :confidence 1.0 :evidence-count 8\n"
       "                :first-observed-at \"2026-05-20T00:00:00Z\"\n"
       "                :last-reinforced-at \"2026-05-26T00:00:00Z\"}]\n"
       "   :weaknesses [{:trait \"...\" :avoid-when \"...\" :recommended-alternative \"...\"\n"
       "                 :confidence 1.0 :evidence-count 2\n"
       "                 :first-observed-at \"2026-05-20T00:00:00Z\"\n"
       "                 :last-reinforced-at \"2026-05-26T00:00:00Z\"}]\n"
       "   :representative-uses [\"per-chunk extraction inside :map-each\"]\n"
       "   :avoid-when [\"deterministic work — use :code instead\"]\n"
       "   :summary \"One-paragraph synthesis.\"\n"
       "   :version 1\n"
       "   :consolidated-from-event-count 10}"))

(def ^:private reflection-workflow
  "Single-:llm-node ORC workflow for the consolidator's reflection call.

   The blackboard schemas for each :writes key drive dscloj's structured-
   output spec — that's how the LLM is told the per-field types. Each of
   the six top-level description-body fields is its own :writes slot with
   a precise schema; in particular :strengths/:weaknesses use the rich
   principle-entry schema so the LLM is told to produce maps with
   :trait/:good-when/:recommended-pattern/:confidence/:evidence-count/...
   fields, not just `:map`."
  (orc/workflow "ontology-consolidator-reflection"
    (orc/blackboard
      {:target-type [:enum :node-type :node-instance :tree-fingerprint]
       :target-id :any
       :current-description [:maybe :map]
       :recent-events [:vector :map]
       :aggregate-metrics [:maybe :map]
       :recent-vs-historical-delta [:maybe :map]
       :structural-context :any
       :capabilities [:vector :string]
       :strengths [:vector ontology-schemas/principle-entry]
       :weaknesses [:vector ontology-schemas/principle-entry]
       :representative-uses [:vector :string]
       :avoid-when [:vector :string]
       :summary :string})

    (orc/llm "reflect"
      :instruction reflection-instruction
      :reads [:target-type :target-id :current-description
              :recent-events :aggregate-metrics
              :recent-vs-historical-delta :structural-context]
      :writes [:capabilities :strengths :weaknesses
               :representative-uses :avoid-when :summary]
      ;; Use the existing ORC/dscloj retry primitive — the executor's
      ;; :llm handler already retries on transient errors AND nil
      ;; outputs (executor.clj `outputs-have-nil?`). Lifting the budget
      ;; from the default 1 retry to 3 covers the LLM-flakiness we see
      ;; on first-consolidation runs without reinventing retry.
      :options {:max-retries 3
                :retry-delay-ms [500 1500 3000]})))

;; =============================================================================
;; Input gathering
;; =============================================================================

(defn- source-event-type-for-target
  "Which source event-type carries observations for a given target-type?"
  [target-type]
  (case target-type
    :node-type        :sheet/node-execution-completed
    :node-instance    :sheet/node-execution-completed
    :tree-fingerprint :sheet/rlm-tree-execution-completed))

(defn- event-matches-target?
  "Filter predicate for an event against a (target-type, target-id) target."
  [target-type target-id event]
  (case target-type
    :node-type        (= target-id (:node-type event))
    :node-instance    (and (= (first target-id) (:sheet-id event))
                           (= (second target-id) (:node-id event)))
    :tree-fingerprint (= target-id (:tree-fingerprint event))))

(defn- clean-event-for-llm
  "Strip non-JSON-serializable fields (event-store metadata, timestamps,
   tag sets) from a source event before it's passed to the LLM. Keeps
   only the semantic content the LLM needs to reason about — status,
   duration, target identity, usage. Timestamps are stringified."
  [event]
  (cond-> (-> event
              (dissoc :event/id :event/tags :event/timestamp))
    (some? (:event/timestamp event))
    (assoc :timestamp (str (:event/timestamp event)))))

(defn- gather-recent-events
  "Pull the last W events for this target from the event store.
   Returns a vector of cleaned event maps (in event-store order, most
   recent last). Capped at recent-window-size."
  [ctx target-type target-id]
  (let [event-type (source-event-type-for-target target-type)]
    (->> (es/read (:event-store ctx)
                  {:types #{event-type}
                   :tenant-id (:tenant-id ctx)})
         (into [])
         (filter #(event-matches-target? target-type target-id %))
         (map clean-event-for-llm)
         (take-last recent-window-size)
         vec)))

(defn- success-rate
  "Fraction of events with :status :success. nil for empty input."
  [events]
  (when (seq events)
    (/ (count (filter #(= :success (:status %)) events))
       (double (count events)))))

(defn- compute-delta
  "Compute recent-vs-historical comparison for the LLM. Returns a small
   map with :recent-success-rate, :historical-success-rate, and the
   :delta (recent minus historical). Returns nil when aggregate-metrics
   is unavailable (first consolidation) — the prompt then omits the
   delta section."
  [aggregate-metrics recent-events]
  (when (and aggregate-metrics (seq recent-events))
    (let [succ (:success-count aggregate-metrics)
          fail (:failure-count aggregate-metrics)
          total (+ (or succ 0) (or fail 0))]
      (when (pos? total)
        (let [historical (double (/ succ total))
              recent (success-rate recent-events)]
          {:recent-success-rate recent
           :historical-success-rate historical
           :delta (- recent historical)})))))

(defn- gather-aggregate-metrics
  "Pull accumulated metrics for this target from C-2a-2's cross-sheet
   rolling-metrics aggregators. Returns nil for granularities without
   a cross-sheet aggregator (currently :node-instance) — the LLM still
   gets the recent-events slice, just no aggregate baseline."
  [ctx target-type target-id]
  (case target-type
    :node-type        (orc/get-node-type-metrics ctx target-id)
    :tree-fingerprint (orc/get-tree-fingerprint-metrics ctx target-id)
    ;; :node-instance has no cross-sheet aggregator today (the legacy
    ;; per-(sheet, node-id) rolling-metrics is sheet-scoped). Could be
    ;; added in a follow-up — for now we pass nil and the LLM works
    ;; from recent-events + structural-context.
    :node-instance    nil
    nil))

(defn- gather-structural-context
  "Pull structural context for this target (tree-raw / node config / kw)."
  [_ctx _target-type target-id]
  ;; Minimum impl for RED #1 — return target-id itself for node-types
  ;; (the keyword IS the structural context).
  target-id)

;; =============================================================================
;; Consolidation effect
;; =============================================================================

(defn- parse-vector-of-maps
  "dscloj returns simple-typed vector fields as native Clojure data, but
   vector-of-map fields (like :strengths / :weaknesses with their rich
   per-entry schema) sometimes arrive as EDN-or-JSON-encoded strings.
   Parse those back into native data. Pass already-parsed vectors through."
  [v]
  (cond
    (vector? v) v
    (sequential? v) (vec v)
    (not (string? v)) v
    (str/blank? v) []
    :else
    (try
      ;; Try EDN first — the LLM more often produces Clojure-syntax output
      ;; under our prompt; JSON is the fallback.
      (let [parsed (read-string v)]
        (if (sequential? parsed) (vec parsed) v))
      (catch Exception _
        (try
          (json/parse-string v true)
          (catch Exception _ v))))))

(defn- record-description-command [target-type target-id body]
  (let [cmd-name (case target-type
                   :node-type        :ontology/record-node-type-description
                   :node-instance    :ontology/record-node-instance-description
                   :tree-fingerprint :ontology/record-tree-description)]
    {:command/name cmd-name
     :command/id (random-uuid)
     :command/timestamp (time/now)
     :target-id target-id
     :body body}))

(defn- next-version [current-description]
  (if current-description
    (inc (or (:version current-description) 0))
    1))

(defn- consolidate!-inner
  "The actual consolidation body — split out so the public consolidate!
   can do a clean budget-gate check before kicking off the LLM workflow."
  [context target-type target-id]
  (let [current-description (ontology/get-description context target-type target-id)
        recent-events (gather-recent-events context target-type target-id)
        aggregate-metrics (gather-aggregate-metrics context target-type target-id)
        recent-vs-historical-delta (compute-delta aggregate-metrics recent-events)
        structural-context (gather-structural-context context target-type target-id)
        sheet-id (orc/build-workflow! context reflection-workflow)
        exec-result (orc/execute context sheet-id
                                  {:target-type target-type
                                   :target-id target-id
                                   :current-description current-description
                                   :recent-events recent-events
                                   :aggregate-metrics aggregate-metrics
                                   :recent-vs-historical-delta recent-vs-historical-delta
                                   :structural-context structural-context})
        outputs (:outputs exec-result)
        ;; Assemble the description-body from the six separate :writes
        ;; produced by the LLM. dscloj returns simple-vector fields
        ;; (:capabilities, :representative-uses, :avoid-when) as native
        ;; Clojure data, but complex vector-of-map fields (:strengths,
        ;; :weaknesses) sometimes arrive as EDN/JSON-encoded strings —
        ;; we parse those before assembling.
        body (when (and outputs
                        (every? #(contains? outputs %)
                                [:capabilities :strengths :weaknesses
                                 :representative-uses :avoid-when :summary]))
               {:capabilities                  (:capabilities outputs)
                :strengths                     (parse-vector-of-maps (:strengths outputs))
                :weaknesses                    (parse-vector-of-maps (:weaknesses outputs))
                :representative-uses           (:representative-uses outputs)
                :avoid-when                    (:avoid-when outputs)
                :summary                       (:summary outputs)
                :version                       (next-version current-description)
                :consolidated-from-event-count (count recent-events)})]
    (cond
      (not= :success (:status exec-result))
      (u/log ::consolidate-execution-failed
             :target-type target-type
             :target-id target-id
             :status (:status exec-result)
             :error (:error exec-result))

      (not (m/validate ontology-schemas/description-body body))
      (u/log ::consolidate-validation-failed
             :target-type target-type
             :target-id target-id
             :explain (me/humanize (m/explain ontology-schemas/description-body body)))

      :else
      (command-processor/process-command
        (assoc context :command (record-description-command target-type target-id body))))))

(defn consolidate!
  "Run the consolidation for a single (target-type, target-id) target.

   Budget gate: if the configured hourly consolidation budget for this
   target-type has been exhausted (per the rolling-hour count of
   :*-description-updated events), the consolidation is skipped (no LLM
   call, no event emitted). The hour window rolls naturally; subsequent
   requests succeed once older entries fall out of the window.

   Otherwise: reads inputs, executes the reflection workflow via ORC,
   validates the structured output against the description-body schema,
   overwrites :version + :consolidated-from-event-count with computed
   values, and emits the matching :*-description-updated event via the
   existing C-2a-1 record-*-description command.

   Validation failure: logs and aborts cleanly (no event emitted)."
  [context target-type target-id]
  (u/log ::consolidate-start :target-type target-type :target-id target-id)
  (let [budget (ontology/get-consolidation-budget context target-type)
        recent-count (ontology/get-recent-consolidation-count context target-type)]
    (if (>= recent-count budget)
      (u/log ::consolidate-budget-exceeded
             :target-type target-type
             :target-id target-id
             :budget budget
             :recent-count recent-count)
      (consolidate!-inner context target-type target-id))))

;; =============================================================================
;; Processor registration
;; =============================================================================

(defprocessor :ontology consolidate-on-request
  {:topics #{:ontology/consolidation-requested}}
  "C-2a-3b: handle :ontology/consolidation-requested by running the
   reflection LLM call and emitting the matching :*-description-updated."
  [{:keys [event] :as context}]
  (let [{:keys [target-type target-id]} event]
    (try
      (consolidate! context target-type target-id)
      (catch Exception e
        (u/log ::consolidate-error
               :error (.getMessage e)
               :target-type target-type
               :target-id target-id)))))
