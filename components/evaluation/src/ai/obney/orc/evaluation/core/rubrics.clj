(ns ai.obney.orc.evaluation.core.rubrics
  "Evaluation rubrics for LLM-as-judge evaluation.

   Each rubric defines:
   - What dimension of quality is being evaluated
   - Scoring criteria (0.0 to 1.0)
   - The prompt template for the judge LLM
   - Weight for aggregation with other dimensions

   These rubrics are designed for REFERENCE-FREE evaluation,
   meaning they don't require ground truth labels.")

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
