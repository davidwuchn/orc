(ns ai.obney.orc.evaluation.core.rubrics
  "Evaluation rubrics for LLM-as-judge evaluation.

   Each rubric defines:
   - What dimension of quality is being evaluated
   - Scoring criteria (0.0 to 1.0)
   - The prompt template for the judge LLM
   - Weight for aggregation with other dimensions

   These rubrics are designed for REFERENCE-FREE evaluation,
   meaning they don't require ground truth labels.

   ---------------------------------------------------------------------------
   PA-3 (2026-06): the GROUNDING judge has been redesigned to the tier-1
   shape (ADR 0011 / doc/judge-framework-verdict-notes.md):
     - a first-class DISCRETE 1-5 `Scale` with explicit per-level bands,
       DECOUPLED from the criteria/instruction (see core/scale.clj), mapped
       deterministically to [0,1] for storage;
     - an ADVERSARIAL, SOURCE-GROUNDED, reason-BEFORE-score stance;
     - structured output via the typed blackboard (DSCloj output fields with
       {:description ...}) — NO json-in-prompt, NO permissive output schema.
   The old soft-0.0-1.0 `GROUNDING_RUBRIC` below is retained only for the
   legacy retrospective code paths; the live grounding judge uses
   GROUNDING_CRITERIA + GROUNDING_SCALE. The OTHER three rubrics
   (instruction / reasoning / completeness) are migrated in PA-4 (demand-pull).
   ---------------------------------------------------------------------------"
  (:require [ai.obney.orc.evaluation.core.scale :as scale]))

;; =============================================================================
;; Grounding/Hallucination Detection
;; =============================================================================

(def GROUNDING_RUBRIC
  {:name "Source Grounding"
   :weight 0.35
   :description "Evaluates whether the response is grounded in the provided context/inputs"
   :prompt
   "You are evaluating whether an LLM response is grounded in the provided context.

## Task
Check if every factual claim in the response can be traced to the input context.
Identify any hallucinations (claims not supported by the inputs).

## Input Context
The LLM was given these inputs:
```json
{inputs}
```

## LLM Response
```
{response}
```

## Instruction Given to LLM
```
{instruction}
```

## Scoring Rubric
- 1.0 (Excellent): Every claim is traceable to inputs, no hallucinations
- 0.8 (Good): Almost all claims grounded, very minor extrapolations only
- 0.6 (Adequate): Most claims grounded, some unsupported but plausible inferences
- 0.4 (Poor): Significant ungrounded claims that could mislead
- 0.2 (Very Poor): Most information not from inputs
- 0.0 (Failed): Contains clear hallucinations or contradicts inputs

## Required Output (JSON)
{
  \"score\": <float 0.0-1.0>,
  \"grounded-claims\": [\"list of claims that ARE supported by inputs\"],
  \"ungrounded-claims\": [\"list of claims that are NOT supported\"],
  \"feedback\": \"Specific actionable feedback explaining the score. Include suggestions for improvement.\"
}"})

;; =============================================================================
;; PA-3 — Grounding judge: decoupled criteria + discrete-band Scale
;; =============================================================================
;;
;; These three artifacts are kept SEPARATE on purpose (the tier-1 decoupling):
;;   GROUNDING_CRITERIA — WHAT to evaluate (the dimension's definition)
;;   GROUNDING_STANCE   — HOW to behave (adversarial, source-grounded persona)
;;   GROUNDING_SCALE    — HOW to score (a first-class discrete 1-5 Scale)
;; The judge composes them; none of them embeds the others.

(def GROUNDING_CRITERIA
  "WHAT the grounding judge evaluates. Decoupled from the scale + stance."
  (str "Grounding measures whether every factual claim in the RESPONSE can be "
       "traced to the SOURCE (the inputs/context the producer was given). "
       "An ungrounded claim is any statement of fact, number, name, date, or "
       "entity that is NOT present in or directly entailed by the source — "
       "even if it sounds plausible or is generally true in the world. "
       "Contradicting the source is worse than merely adding unsupported "
       "detail. Generic framing, hedging, and restating the task are neither "
       "grounded nor ungrounded claims — judge the substantive factual "
       "content."))

(def GROUNDING_STANCE
  "HOW the grounding judge behaves: an adversarial, source-grounded reviewer.
   This is the cheap, high-value calibration move (ADR 0011): the judge
   defends the null that the output is NOT grounded and hunts for flaws."
  (str "You are a skeptical, adversarial reviewer. Your default assumption is "
       "that the response is NOT fully grounded until the source proves "
       "otherwise. Actively hunt for claims the source does not support. Check "
       "every specific fact, number, name, date, and entity against the "
       "source — not against your own world knowledge, and never against the "
       "producer's self-report. A confident, fluent response that invents one "
       "specific fact is a grounding failure, not a success. First reason "
       "through the evidence, THEN choose a band."))

(def GROUNDING_SCALE
  "PA-3 grounding Scale — REVIEWED & FINALIZED 2026-06-16 (keep-strict).

   A first-class discrete 1-5 scale with explicit per-level bands, decoupled
   from the criteria/stance. Mapped deterministically to [0,1] via
   scale/level->unit-score (1→0.0, 2→0.25, 3→0.5, 4→0.75, 5→1.0).

   These band descriptions were calibrated against REAL bench-document
   traces (employment_agreement.txt, yyj_rfp-derived) scored live on
   OpenRouter — see development/prototype_grounding_calibration.clj and the
   PA-3 report. Strictness policy (human-reviewed): ANY inference presented
   as fact — even hedged ('likely', 'probably') — caps at band 3. Grounding
   is deliberately unforgiving; balanced by the other dimension judges, the
   satisfaction judge, and human-gated GEPA acceptance."
  (scale/discrete-scale
    {:min 1 :max 5
     :bands
     {1 (str "Ungrounded / fabricated. The response contradicts the source on "
             "central facts, or is about a different subject entirely, or "
             "nearly all of its specific claims are inventions. A reader "
             "relying on it would be badly misled.")
      2 (str "Largely ungrounded. Multiple specific claims (facts, numbers, "
             "names, dates, entities) are unsupported or wrong, OR one central "
             "fact is fabricated/contradicted, even if some surrounding detail "
             "is correct. More wrong than right on substance.")
      3 (str "Mixed grounding. Most of the substance is supported, but there "
             "are one or more unsupported claims or inferences presented as "
             "fact — INCLUDING hedged ones ('likely', 'probably'). A reader "
             "should verify before trusting it.")
      4 (str "Well grounded. Every substantive claim traces to the source; "
             "the only deviation is a minor imprecise phrasing or omission "
             "that does not mislead about a fact. NO inference or extrapolation "
             "presented as fact, even hedged — any such claim caps at band 3.")
      5 (str "Fully grounded. Every substantive factual claim is directly "
             "supported by the source. No fabrication, no contradiction, and "
             "no inference presented as fact — hedged or otherwise. Strictly "
             "faithful to what the source says.")}}))

;; =============================================================================
;; Instruction Following
;; =============================================================================

(def INSTRUCTION_FOLLOWING_RUBRIC
  {:name "Instruction Following"
   :weight 0.25
   :description "Evaluates whether the LLM followed the given instruction"
   :prompt
   "You are evaluating whether an LLM correctly followed its instruction.

## Instruction Given
```
{instruction}
```

## LLM Response
```
{response}
```

## LLM Inputs
```json
{inputs}
```

## Task
Evaluate how well the response follows the instruction:
1. Did it address the main task described in the instruction?
2. Did it follow any specific formatting requirements?
3. Did it include all required output components?
4. Did it avoid doing things the instruction forbade?

## Scoring Rubric
- 1.0 (Excellent): Instruction followed completely, all requirements met
- 0.8 (Good): Minor deviations but main task accomplished correctly
- 0.6 (Adequate): Main task done but missing some requirements
- 0.4 (Poor): Partial compliance, significant deviations
- 0.2 (Very Poor): Barely followed the instruction
- 0.0 (Failed): Did not follow the instruction at all

## Required Output (JSON)
{
  \"score\": <float 0.0-1.0>,
  \"requirements-met\": [\"list of instruction requirements that were satisfied\"],
  \"requirements-missed\": [\"list of instruction requirements that were NOT satisfied\"],
  \"feedback\": \"Specific actionable feedback explaining the score and how to better follow the instruction.\"
}"})

;; =============================================================================
;; Reasoning Quality
;; =============================================================================

(def REASONING_QUALITY_RUBRIC
  {:name "Reasoning Quality"
   :weight 0.20
   :description "Evaluates the coherence and quality of reasoning"
   :prompt
   "You are evaluating the quality of reasoning in an LLM response.

## Instruction Given
```
{instruction}
```

## LLM Response
```
{response}
```

## LLM Inputs
```json
{inputs}
```

## Task
Evaluate the reasoning quality:
1. Is the reasoning clear and easy to follow?
2. Are the logical steps connected properly?
3. Does the reasoning lead naturally to the conclusion?
4. Are there any logical fallacies or gaps?

## Scoring Rubric
- 1.0 (Excellent): Crystal clear reasoning, logical flow, well-justified conclusions
- 0.8 (Good): Clear reasoning with minor gaps or unstated assumptions
- 0.6 (Adequate): Understandable but could be clearer or more rigorous
- 0.4 (Poor): Confusing or contains logical gaps
- 0.2 (Very Poor): Hard to follow, unclear how conclusions were reached
- 0.0 (Failed): No apparent reasoning or completely incoherent

## Required Output (JSON)
{
  \"score\": <float 0.0-1.0>,
  \"reasoning-strengths\": [\"aspects of reasoning that were good\"],
  \"reasoning-weaknesses\": [\"logical gaps or unclear elements\"],
  \"feedback\": \"Specific actionable feedback for improving reasoning clarity and rigor.\"
}"})

;; =============================================================================
;; Completeness
;; =============================================================================

(def COMPLETENESS_RUBRIC
  {:name "Completeness"
   :weight 0.20
   :description "Evaluates whether all aspects of the task were addressed"
   :prompt
   "You are evaluating whether an LLM response is complete.

## Instruction Given
```
{instruction}
```

## LLM Response
```
{response}
```

## LLM Inputs
```json
{inputs}
```

## Task
Evaluate completeness:
1. Does the response address all parts of the instruction?
2. Does it utilize all relevant information from the inputs?
3. Is anything missing that should have been included?
4. Does it provide sufficient detail?

## Scoring Rubric
- 1.0 (Excellent): Complete response, all aspects addressed with appropriate detail
- 0.8 (Good): Nearly complete, only minor details missing
- 0.6 (Adequate): Mostly complete but missing some useful information
- 0.4 (Poor): Incomplete, missing significant aspects
- 0.2 (Very Poor): Very incomplete, barely addresses the task
- 0.0 (Failed): Response is a stub or doesn't address the task

## Required Output (JSON)
{
  \"score\": <float 0.0-1.0>,
  \"aspects-covered\": [\"aspects of the task that were addressed\"],
  \"aspects-missing\": [\"aspects that should have been included but weren't\"],
  \"feedback\": \"Specific actionable feedback for improving completeness.\"
}"})

;; =============================================================================
;; Default Rubric Set
;; =============================================================================

(def DEFAULT_RUBRICS
  "Default set of rubrics for reference-free evaluation.
   Weights sum to 1.0."
  {:grounding GROUNDING_RUBRIC
   :instruction-following INSTRUCTION_FOLLOWING_RUBRIC
   :reasoning REASONING_QUALITY_RUBRIC
   :completeness COMPLETENESS_RUBRIC})

(defn get-rubric
  "Get a rubric by key."
  [key]
  (get DEFAULT_RUBRICS key))

(defn get-rubrics
  "Get multiple rubrics by keys. Returns all if keys is nil."
  [keys]
  (if keys
    (select-keys DEFAULT_RUBRICS keys)
    DEFAULT_RUBRICS))

;; =============================================================================
;; PA-3 — tier-1 grounding rubric accessor (criteria × stance × scale)
;; =============================================================================

(def GROUNDING_TIER1
  "The redesigned grounding judge's decoupled pieces, bundled for the judge fn
   to compose at call time. The Scale is first-class and separable."
  {:name "Source Grounding"
   :weight 0.35
   :criteria GROUNDING_CRITERIA
   :stance GROUNDING_STANCE
   :scale GROUNDING_SCALE})

(defn get-tier1-rubric
  "Get a redesigned (tier-1) rubric by key. Currently only :grounding (PA-3);
   the rest migrate in PA-4."
  [key]
  (case key
    :grounding GROUNDING_TIER1
    nil))
