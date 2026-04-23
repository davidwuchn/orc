# Hermes-Agent vs ORC Self-Learning: Comprehensive Analysis

## Status: Analysis Complete - Ready for Review

---

## Executive Summary

After thorough analysis of the [hermes-agent](https://github.com/NousResearch/hermes-agent) repository (~474KB main file, 65KB trajectory compressor, 52KB state management), I've compared its self-learning architecture against ORC's ontology-based system.

**Key Finding**: ORC and hermes-agent solve similar problems with fundamentally different philosophies. ORC uses **structured event sourcing with typed ontologies**, while hermes-agent uses **flat markdown files with periodic nudges**. Both approaches have merit, and several hermes-agent patterns could enhance ORC's conversational capabilities.

---

## Architecture Comparison

### Knowledge Storage

| Aspect | Hermes-Agent | ORC Ontology |
|--------|--------------|--------------|
| **Primary Store** | Flat markdown files (MEMORY.md, USER.md) | Event-sourced ontology with typed concepts |
| **Structure** | Free-form text with LLM interpretation | 61 typed concepts with explicit relationships |
| **Relationships** | Implicit (LLM infers from text) | Explicit graph edges (`:related-to`, `:is-a`, `:mitigates`) |
| **Query Mechanism** | Full-text search (FTS5) + LLM reasoning | Hybrid search (Graph BFS + Embeddings + ColBERT RRF) |
| **Persistence** | SQLite + filesystem | PostgreSQL event store + read model projections |

**Cross-Check**: Verified in `tools/memory_tool.py` lines 50-120 (MEMORY.md write), `ontology/core/graph.clj` (BFS traversal).

### Learning Triggers

| Aspect | Hermes-Agent | ORC Ontology |
|--------|--------------|--------------|
| **When** | Periodic nudges (every N turns/iterations) | On evaluation events (score thresholds) |
| **How** | Agent-initiated tool calls to memory/skill tools | Event-driven todo processors |
| **Control** | Configurable intervals (`--memory-nudge-interval`) | Threshold-based (< 0.8 weakness, ≥ 0.85 strength) |
| **Scope** | Full conversation context available | Specific trace/execution context |

**Cross-Check**: Verified nudge trigger at `run_agent.py:7005-7011`:
```python
if (self._memory_nudge_interval > 0 and "memory" in valid_tools):
    self._turns_since_memory += 1
    if self._turns_since_memory >= self._memory_nudge_interval:
        _should_review_memory = True
```

### Reflection Mechanism

| Aspect | Hermes-Agent | ORC Ontology |
|--------|--------------|--------------|
| **Process** | Background thread spawns forked agent | Synchronous classification + recording |
| **Isolation** | Separate agent instance for reflection | Same execution context |
| **Output** | Free-form memory updates + skill files | Typed patterns with context-conditions/action-taken |
| **Feedback Loop** | Memory injected via system prompt prefix | Context injected via `:context` node parameter |

**Cross-Check**: Background review in `run_agent.py:1814-1900`, ORC injection in `todo_processors.clj:apply-ontology-context`.

---

## What ORC Already Has (Hermes-Agent Equivalents)

### 1. Memory/Knowledge Storage
- **Hermes**: MEMORY.md flat file
- **ORC**: Three-layer ontology (Failure/Success/Problem) with 61 typed concepts
- **ORC Advantage**: Typed relationships enable graph traversal, not just text search

### 2. User/Domain Profiles
- **Hermes**: USER.md for user preferences
- **ORC**: Tree profiles read model with per-tree strengths/weaknesses
- **ORC Advantage**: Automatically populated from evaluation feedback

### 3. Skill System
- **Hermes**: SKILL.md files with templates/scripts
- **ORC**: Behavior tree nodes with typed parameters, executors, tools
- **ORC Advantage**: Version-controlled, publishable, testable via GEPA

### 4. Session Management
- **Hermes**: SQLite with FTS5 search
- **ORC**: Event store with full audit trail + read model projections
- **ORC Advantage**: Time-travel debugging, cross-run analysis

### 5. Training Data Generation
- **Hermes**: Trajectory compression for fine-tuning data
- **ORC**: ColBERT training data extraction from execution traces
- **Comparable**: Both extract high-quality pairs from successful executions

### 6. Hybrid Search
- **Hermes**: FTS5 + LLM reasoning
- **ORC**: Three-signal RRF (Graph BFS + MiniLM embeddings + ColBERT late-interaction)
- **ORC Advantage**: More principled fusion, domain-aware graph structure

---

## What Hermes-Agent Does That Could Improve ORC

### 1. Periodic Nudge System (HIGH VALUE)

**What it is**: Hermes triggers self-reflection at configurable intervals regardless of success/failure.

**Why valuable**: ORC only learns from explicit evaluation events. A nudge system would enable:
- Proactive knowledge consolidation during long workflows
- User preference learning without explicit feedback
- Pattern discovery across multiple executions

**Implementation sketch**:
```clojure
;; New field in tick context
:nudge-config {:memory-interval 5   ;; every 5 nodes
               :skill-interval 10}  ;; every 10 nodes

;; In todo_processors.clj, track node count
(when (zero? (mod node-count (:memory-interval nudge-config)))
  (dispatch-command ctx {:command/name :ontology/self-reflect
                         :tree-id tree-id
                         :context current-blackboard}))
```

### 2. Background Reflection Thread (MEDIUM VALUE)

**What it is**: Hermes spawns a forked agent instance for reflection, isolated from main execution.

**Why valuable**:
- Main workflow continues unblocked
- Reflection has full conversation context
- Failures in reflection don't affect main execution

**ORC equivalent**: Could use `core.async` go-blocks or separate thread pool for ontology operations.

### 3. Frozen Memory Snapshots for Prefix Caching (MEDIUM VALUE)

**What it is**: Hermes creates immutable memory snapshots that enable LLM prefix caching.

```python
# From memory_tool.py
self._frozen_memory = self._memory  # Snapshot for caching
```

**Why valuable**: Reduces LLM API costs by enabling prompt prefix reuse.

**ORC opportunity**: Store context injection prefix as immutable snapshot, mark as cacheable in Langfuse.

### 4. Conversational Memory Updates (HIGH VALUE)

**What it is**: Hermes lets the agent naturally update memory via tool calls during conversation.

**Current ORC gap**: Ontology updates require explicit evaluation events or commands.

**Implementation sketch**:
```clojure
;; New MCP tool for conversational ontology updates
(defn update-ontology-from-conversation
  [{:keys [insight pattern-type domain context]}]
  (cmd/process-command ctx
    {:command/name :ontology/record-insight
     :insight insight
     :pattern-type pattern-type  ;; :strength, :weakness, :observation
     :domain domain
     :context context}))
```

### 5. Trajectory Compression for Long Conversations (LOW-MEDIUM VALUE)

**What it is**: Hermes compresses long conversations into training-friendly format with protected head/tail.

**ORC context**: Less critical because behavior trees have natural boundaries (sheet executions), but could help for long-running multi-step workflows.

---

## What ORC Has That Hermes-Agent Lacks

### 1. Typed Ontology Structure
Hermes stores everything as free-form text. ORC's typed concepts with explicit relationships enable:
- Programmatic traversal (graph BFS)
- Relationship-aware retrieval
- Domain isolation (concepts scoped to domains)

### 2. Event Sourcing Foundation
ORC's Grain framework provides:
- Full audit trail of all learning events
- Time-travel debugging
- Cross-run analysis (GEPA inherits frontiers)
- Reproducibility guarantees

### 3. Multi-Signal Hybrid Search
ORC combines three retrieval signals via RRF:
- Graph BFS (structural relationships)
- MiniLM embeddings (semantic similarity)
- ColBERT (token-level precision)

Hermes uses only FTS5 + LLM reasoning.

### 4. Prompt Optimization (GEPA)
ORC has sophisticated prompt optimization that hermes-agent completely lacks:
- Reflective mutation from failure feedback
- Pareto-based diversity maintenance
- Cross-run persistence of optimized instructions

### 5. Structured Evaluation Pipeline
ORC's judge system (Grounding, IF, Reasoning, Completeness) provides:
- Quantified feedback for learning triggers
- Rolling metrics for trend detection
- GEPA-compatible ScoreWithFeedback format

---

## Actionable Recommendations

### Phase 1: Add Conversational Ontology Interface (Effort: Medium)

**Goal**: Enable natural language ontology updates during app conversations.

**Implementation**:
1. Create MCP tool `ontology-insight` for recording observations
2. Create MCP tool `ontology-query` for retrieving relevant patterns
3. Wire tools into ORC MCP server
4. Enable LLM nodes to call these tools during execution

**Files to modify**:
- `components/ontology-mcp-server/` - Add new tools
- `components/orc-service/core/todo_processors.clj` - Optional auto-enable

### Phase 2: Add Periodic Nudge System (Effort: Medium)

**Goal**: Trigger self-reflection at configurable intervals.

**Implementation**:
1. Add `:nudge-config` to execution options
2. Track node execution count in tick state
3. Dispatch `:ontology/self-reflect` command at intervals
4. Create reflection prompt that summarizes recent execution

**Files to modify**:
- `components/orc-service/core/runtime.clj` - Add nudge config
- `components/orc-service/core/todo_processors.clj` - Track count, dispatch
- `components/ontology/src/.../core/commands.clj` - Add self-reflect command

### Phase 3: Add Background Reflection (Effort: High)

**Goal**: Non-blocking ontology operations.

**Implementation**:
1. Create async ontology processor pool
2. Queue reflection tasks instead of synchronous dispatch
3. Results flow back via event stream

**Consideration**: May not be necessary if ontology operations are fast enough.

### Phase 4: Implement Prefix Caching for Context Injection (Effort: Low)

**Goal**: Reduce LLM costs for repeated context.

**Implementation**:
1. Hash context injection prefix
2. Store hash → prefix mapping
3. Mark cached prefixes in Langfuse traces
4. Use cache-aware API calls where supported

---

## Architecture Synthesis: "Hermes-style ORC"

The ideal architecture combines:

```
┌─────────────────────────────────────────────────────────────┐
│                    Conversational Layer                      │
│  (App/Chat Interface → MCP Tools → Natural Language)        │
├─────────────────────────────────────────────────────────────┤
│                     ORC Behavior Trees                       │
│  (Structured workflows with typed nodes and blackboards)    │
├─────────────────────────────────────────────────────────────┤
│                    Ontology Integration                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Nudge System │  │ MCP Tools    │  │ Eval Triggers│      │
│  │ (periodic)   │  │ (conversational)│ (threshold) │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
├─────────────────────────────────────────────────────────────┤
│                  Typed Ontology Store                        │
│  (Event-sourced, graph-structured, three-signal retrieval)  │
├─────────────────────────────────────────────────────────────┤
│                     GEPA Optimization                        │
│  (Continuous improvement of prompts from learned patterns)  │
└─────────────────────────────────────────────────────────────┘
```

---

## Verification Checklist

- [x] Cross-checked hermes nudge trigger location (run_agent.py:7005-7011)
- [x] Cross-checked hermes memory storage (tools/memory_tool.py)
- [x] Cross-checked hermes skill system (tools/skill_manager_tool.py)
- [x] Cross-checked ORC ontology structure (docs/ONTOLOGY.md)
- [x] Cross-checked ORC context injection (todo_processors.clj, dsl.clj)
- [x] Cross-checked ORC hybrid search (retrieval.clj three-signal RRF)
- [x] Verified ORC already has: typed storage, event sourcing, GEPA, judges
- [x] Verified hermes-agent lacks: typed ontology, GEPA, multi-signal search

---

## Conclusion

ORC's architecture is more sophisticated than hermes-agent in terms of:
- **Structure**: Typed ontologies vs flat markdown
- **Persistence**: Event sourcing vs SQLite
- **Retrieval**: Three-signal RRF vs FTS5
- **Optimization**: GEPA vs none

However, hermes-agent excels at:
- **Conversational interface**: Natural tool-based updates
- **Proactive learning**: Periodic nudges regardless of evaluation
- **Background processing**: Isolated reflection threads

**Recommendation**: Implement Phases 1-2 (Conversational Interface + Nudge System) to gain hermes-agent's strengths while retaining ORC's architectural advantages. This enables the "talking through an app to the orc/behavior trees which can self reflect and learn" vision.

---

## Files to Create/Modify

| File | Change |
|------|--------|
| `components/ontology-mcp-server/src/.../tools.clj` | Add `ontology-insight`, `ontology-query` tools |
| `components/orc-service/core/runtime.clj` | Add `:nudge-config` to execution options |
| `components/orc-service/core/todo_processors.clj` | Track node count, dispatch nudge reflections |
| `components/ontology/src/.../core/commands.clj` | Add `:ontology/self-reflect` command |
| `components/ontology/src/.../core/reflection.clj` | New file: reflection prompt generation |

---

# Part 2: Framework vs Application Separation

## The Core Question

**ORC** = Framework providing self-learning behavior tree primitives
**Hermes-style Assistant** = Application built using those primitives

The goal: ORC should provide all building blocks needed so that someone could build a Hermes-like self-evolving assistant on top of it.

---

## ORC Core Framework Primitives (What ORC Provides)

### Tier 1: Execution Primitives (Already Complete)

| Primitive | Status | Description |
|-----------|--------|-------------|
| **Node Types** | ✅ Complete | `llm`, `code`, `sequence`, `parallel`, `fallback`, `map-each`, `condition`, `llm-condition`, `repl-researcher` |
| **Blackboard** | ✅ Complete | Malli-typed shared state with `:reads`/`:writes` scoping |
| **Tick Lifecycle** | ✅ Complete | Synchronous tree traversal, status propagation |
| **MCP Integration** | ✅ Complete | Dynamic tool discovery, executor generation |
| **Code Executors** | ✅ Complete | User-defined functions resolved at runtime |

### Tier 2: Learning Primitives (Already Complete)

| Primitive | Status | Description |
|-----------|--------|-------------|
| **Ontology Graph** | ✅ Complete | 61 typed concepts, SKOS relationships, domain scoping |
| **Context Injection** | ✅ Complete | `:context` parameter → LLM prompt prepending |
| **Hybrid Search** | ✅ Complete | Graph BFS + MiniLM + ColBERT via RRF |
| **Judge System** | ✅ Complete | Grounding, IF, Reasoning, Completeness evaluators |
| **Rolling Metrics** | ✅ Complete | Per-node success rates, trend detection |
| **Tree Profiles** | ✅ Complete | Strengths/weaknesses per workflow |
| **Rule Extraction** | ✅ Complete | Domain-agnostic pattern mining |

### Tier 3: Optimization Primitives (Already Complete)

| Primitive | Status | Description |
|-----------|--------|-------------|
| **GEPA Core** | ✅ Complete | Reflective mutation, Pareto selection, cross-run persistence |
| **Training Data Extraction** | ✅ Complete | ColBERT triplet generation from traces |
| **Metric Functions** | ✅ Complete | Pluggable evaluation (exact match, contains, judge-based) |

### Tier 4: Missing Framework Primitives (To Add to ORC)

| Primitive | Status | Why Framework-Level |
|-----------|--------|---------------------|
| **Execution Hooks** | ❌ Missing | Generic callback system for pre/post node execution |
| **Periodic Triggers** | ❌ Missing | Configurable interval-based events (not conversation-specific) |
| **Async Processors** | ⚠️ Partial | Background task queue for non-blocking operations |
| **Prefix Cache Hints** | ❌ Missing | Mark context sections as cacheable for LLM providers |
| **Iteration Budgets** | ❌ Missing | Cost control limits (max nodes, max LLM calls) |
| **Subagent Delegation** | ⚠️ Partial | `repl-researcher` exists, but no generic child-workflow spawning |

---

## Application-Level Concerns (Built ON ORC, Not IN ORC)

### What Hermes Has That Should NOT Be in ORC Core

| Component | Why Application-Level |
|-----------|----------------------|
| **CLI/Chat UI** | Platform-specific user interface |
| **MEMORY.md / USER.md** | Hermes-specific personalization format |
| **Skill Files** | Hermes-specific procedural memory encoding |
| **Session SQLite** | Application state management (ORC uses Grain events) |
| **Turn-Based Nudges** | Conversation-specific triggers |
| **Trajectory Compression** | Long-conversation UX optimization |
| **Tool Implementations** | Terminal, web, vision are capabilities, not orchestration |

### What an "ORC Assistant" Application Would Provide

```
┌─────────────────────────────────────────────────────────────┐
│                 ASSISTANT APPLICATION LAYER                  │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐           │
│  │ Chat UI     │ │ Memory Tool │ │ Skill Tool  │           │
│  │ (Web/CLI)   │ │ (Record)    │ │ (Create)    │           │
│  └─────────────┘ └─────────────┘ └─────────────┘           │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐           │
│  │ Session Mgmt│ │ User Prefs  │ │ Nudge Logic │           │
│  │ (History)   │ │ (Profile)   │ │ (Reminders) │           │
│  └─────────────┘ └─────────────┘ └─────────────┘           │
├─────────────────────────────────────────────────────────────┤
│                    ORC FRAMEWORK LAYER                       │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ Behavior Trees: sequence, parallel, llm, code, ...  │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ Self-Learning: Ontology, Judges, GEPA, Profiles     │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ Retrieval: Graph BFS, Hybrid Search, ColBERT, Embed │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ Infrastructure: Grain Events, MCP Tools, DSCloj     │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```
Daryls notes: I think its super important that we do some additional digging into the ontology element in orc as there should also be the evolving ontology element that can serve as more than just succeess/failure/etc we can use it to find an infinite number of ontologies and use rrf for our graph bfs, colbert, and embeddings if needed. It should also be able to identify what fields should be embedded/indexed so we can search any ontology. I think the success/failure ontology is a version of the evlolutionary ontology just focused on describing and evolving nodes/trees. But the onotlogy system should be general for all search needs. Please do a deep dive and confirm.

### RESEARCH FINDING 1: Ontology Generalization - CONFIRMED ✅

**Your intuition is correct.** The ORC ontology is already designed to be general-purpose:

**Evidence from code:**

1. **Scope-Based Multi-Ontology** (`interface/schemas.clj:15-17`):
   ```clojure
   (def ontology-scope
     [:enum :failure :success :problem :node-type :custom])
   ```
   The `:custom` scope allows **arbitrary domain-specific ontologies**. Success/failure/problem are just bootstrap scopes.

2. **Domain-Agnostic Event Schemas** (`interface/schemas.clj:92-119`):
   - Strength/weakness events include flexible `context-conditions`, `action-taken`, `domain-type`
   - These are `[:map-of :keyword :any]` - completely flexible, not hardcoded
   - Tests prove: legal, sales, drone, construction domains all work identically

3. **Evolutionary Ontology Builder** (`evolutionary.clj`):
   ```clojure
   (evolutionary/build-from-sources ctx
     {:sources [{:path "data.csv" :type "csv"}
                {:path "database.sql" :type "sql"}
                {:content "RDF text" :type "rdf"}]
      :config {:base-uri "http://mycompany.org/#"}})
   ```
   Builds ontologies from ANY sources, not domain-specific.

4. **Embeddable Field Detection** (`interface.clj:589-606`):
   - **Heuristic detection**: Analyzes Malli schemas for embeddable fields
   - **LLM-driven analysis**: Uses ORC workflow to recommend which fields to index
   - Works on ANY schema - not domain-specific

5. **Hybrid Search Works on ANY Ontology** (`retrieval.clj:45-69`):
   - Graph BFS, embedding search, ColBERT all operate on structure, not content
   - Reads from `:ontology/concepts` read model (ALL concepts regardless of scope)

**Conclusion**: The success/failure ontology IS a specialized application of the general evolutionary ontology system, exactly as you suspected. The architecture supports infinite custom ontologies with automatic field detection for indexing.

---

## Framework Enhancements: What to Add to ORC Core

### 1. Execution Hooks (Framework Primitive)

**Purpose**: Generic callback system for workflow instrumentation

```clojure
;; Framework provides hook registration
(orc/register-execution-hook!
  {:on-node-start (fn [ctx node] ...)
   :on-node-complete (fn [ctx node result] ...)
   :on-tree-complete (fn [ctx tree outputs] ...)})

;; Application uses hooks for nudge logic
(orc/register-execution-hook!
  {:on-node-complete
   (fn [ctx node result]
     (when (should-reflect? ctx)
       (trigger-reflection! ctx)))})
```

**Why framework**: Any application might want instrumentation.
Daryls notes: This seems to make sense. Lets just make sure it is consistent with grain's architecture

### RESEARCH FINDING 2: Execution Hooks vs Grain Architecture - IMPORTANT CONSTRAINT ⚠️

**Mutable callback registration BREAKS Grain's guarantees.** Here's the proper pattern:

**Problem with mutable hooks:**
- Non-deterministic replay (hooks don't exist during replay)
- Audit trail gap (hook execution not captured in events)
- GEPA can't optimize workflows whose behavior depends on runtime hooks
- Concurrency races between registration and execution

**~~Grain-Idiomatic Pattern: Event-Based Hooks~~** (SUPERSEDED)

> **UPDATE 2026-04**: After deeper Grain research, this pattern is **redundant**.
> Grain already provides `defperiodic` for scheduled triggers and `defprocessor` with
> checkpointing for reliable side-effect handling. ORC already emits execution events
> (`:sheet/tick-completed`, `:sheet/node-execution-completed`) that can be subscribed to.
>
> **Use Grain's native patterns instead:**
> - `defperiodic` for cron/interval triggers (daily reflection, hourly extraction)
> - `defprocessor` with `:result/checkpoint :after` for at-least-once handling
> - Subscribe to existing ORC events instead of creating custom lifecycle events

~~Instead of `register-execution-hook!`, use event emission + todo processors:~~

```clojure
;; REMOVED - Use defperiodic + defprocessor instead
;; See "Files Updated (2026-04)" section for the correct Grain-native pattern
```

**Correct Pattern (Grain-Native):**
```clojure
;; 1. Periodic task triggers learning on schedule
(defperiodic :learning daily-reflection
  {:schedule {:cron "0 0 * * *"}}
  [tenant-id time]
  {:result/events [(es/->event {:type :learning/reflection-triggered ...})]})

;; 2. Todo processor handles with checkpointing (retry on failure)
(defprocessor :learning process-reflection
  {:topics #{:learning/reflection-triggered}}
  [context]
  {:result/effect (fn [] (ontology/update-from-traces! context))
   :result/checkpoint :after})
```

### 2. Execution Budgets (Framework Primitive)

**Purpose**: Cost control for runaway workflows

```clojure
(orc/execute ctx sheet-id inputs
  :budget {:max-llm-calls 50
           :max-nodes 100
           :max-duration-ms 300000})
```

**Why framework**: All applications need cost control.
Daryls notes: I'm not sure i understand the max-llm-calls and max-nodes. If our behavior trees already have a set number of llm calls and nodes inside the tree. Unless we mean by on specific nodes that can loop, in that case i believe we already have a budget right? Can we take a deeper look at this to see if its viable/makes sense with sheet services?

### RESEARCH FINDING 3: Execution Budgets - PARTIALLY REDUNDANT ✅

**Your intuition is correct.** Per-node budgets already exist for looping nodes:

| Node Type | Budget Parameter | Default | Location |
|-----------|-----------------|---------|----------|
| **repl-researcher** | `:max-iterations` | 10 | `executor.clj:746` |
| **map-each** | `:max-concurrency` | 1 | `todo_processors.clj:836` |
| **llm nodes** | `:retry {:max-attempts n}` | 1 | `executor.clj:910` |

**Code evidence:**
```clojure
;; repl-researcher (executor.clj:765-839)
(loop [iteration 0]
  (if (>= iteration max-iterations)
    {:status :failure :error "Max iterations reached"}
    ...))

;; map-each (todo_processors.clj)
(let [batch-size (min max-concurrency total-items)]
  ;; Processes ALL items, just controls parallelism
  )
```

**What's MISSING (when global budget would matter):**
1. **Nested iterations** - repl-researcher calling code with retries
2. **Large map-each** - 1000 items × 2 LLM calls each = 2000 calls
3. **Cost tracking** - No total token/cost tracking across all nodes

**Recommendation**:
- **Don't add `max-nodes`** - Trees have fixed structure, redundant
- **Consider `max-llm-calls`** - As safety net for nested iteration scenarios
- **Consider `max-cost-usd`** - Actual cost tracking during execution

The existing per-node budgets cover 90% of use cases. A global budget would be a safety net, not primary control.

### 3. Subworkflow Delegation (Framework Primitive)

**Purpose**: Spawn child workflows with isolated context

```clojure
;; New node type or code executor pattern
(orc/delegate "subtask"
  :sheet-id child-sheet-id
  :inputs {:from-parent "value"}
  :isolated? true  ;; No parent blackboard access
  :budget {:max-llm-calls 10})
```

**Why framework**: Composable workflows are fundamental.
Daryls notes: In our future vision if you review it, we do talk about being able to have builders (similar to the mcp tool builder tree) that can build trees and use the ontologies of other nodes/trees to help inform how to build trees. Our orc system should be able to "build" or use existing sheets using the ontology that then can be delegated with their own blackboard, signatures, etc etc. Please review the future vision document and mcp tool building tree to confirm but this should have already been thought of and planned for. Please confirm.

### RESEARCH FINDING 4: Subworkflow Delegation - NOT EXPLICITLY PLANNED ⚠️

**Important clarification:** The FUTURE-VISION.md "hierarchy" refers to **library organization**, not **runtime tree-calling-tree execution**.

**What FUTURE-VISION.md actually says:**

1. **Theme 6 (Lines 158-186)** describes tree **organizational hierarchy**:
   > "research → research Wikipedia → research marvels wiki → research Spider-Man wiki"

   This is **specialization of task focus** (library structure), NOT **nested execution**.

2. **Line 163-167**:
   > "Trees form hierarchies (parent → child specializations)"

   Specialization in **capability scope**, not in **execution flow**.

**What's IMPLEMENTED:**
- ✅ Dynamic executor generation (MCP Sheet Builder) - Trees can call code/tools
- ✅ Tree organization concepts - Metadata structure exists in vision
- ❌ Tree-to-tree execution delegation - NOT implemented or explicitly planned

**What's ACTUALLY Planned:**
- Trees can **reference** few-shot examples from other trees (via ontology)
- Tree builders can **query** successful trees for patterns
- Trees can call **code executors** and **MCP tools**
- But execution is **within a single tree's blackboard**

**Gap Identified:**
The `:delegate` node type for spawning child workflows with independent blackboards is a **NEW requirement** that should be added to the roadmap.

**Recommended Addition to FUTURE-VISION.md:**
```clojure
;; New node type needed
(orc/delegate "child-task"
  :sheet-id child-sheet-id     ;; Reference existing sheet
  :inputs {:from-parent val}   ;; Pass inputs
  :isolated? true              ;; Independent blackboard
  :inherit-ontology? true)     ;; Share ontology context
```

### 4. Cache Hints (Framework Primitive)

**Purpose**: Mark content as prefix-cacheable for LLM providers

```clojure
(orc/llm "node"
  :instruction "..."
  :context {:content "..." :cacheable? true}
  :reads [:input])
```

**Why framework**: Cost optimization applies to all applications.
Daryls notes: We are using dscloj -> litellm i believe please confirm but i'm not sure if this will be possible or not, it seems like this caching would be at that layer and if that allows then maybe we do need to plan for this.

### RESEARCH FINDING 5: DSCloj → LiteLLM Caching - POSSIBLE BUT NEEDS UPSTREAM WORK ⚠️

**Confirmed routing chain:**
```
ORC Sheet Service → DSCloj → litellm-router → LLM Providers
```

**Evidence from code:**
- `executor.clj:19-20`: Requires `dscloj.core` and `litellm.router`
- `executor.clj:525`: `(dscloj/predict effective-provider dscloj-module inputs dscloj-options)`
- GEPA uses direct `litellm-router/completion` calls

**LiteLLM DOES support prefix caching:**
- **Anthropic**: `cache_control: {type: "ephemeral"}` annotation
- **OpenAI**: `prompt_cache_key` and `prompt_cache_retention` parameters
- **Response includes**: `usage.prompt_tokens_details.cached_tokens`

**HOWEVER: DSCloj doesn't currently expose these parameters.**

Current options flow:
```clojure
dscloj-options (assoc options :validate? false)  ;; Only :validate? used
```

**Implementation path:**

1. **Short-term (if DSCloj passes through options):**
   ```clojure
   (execute-ai node blackboard provider
     :options (merge options (:cache-options node)))
   ```

2. **Long-term (proper integration):**
   - Submit PR to DSCloj for caching parameter support
   - Add `:cache` field to ORC node schema
   - Build cache metrics tracking in read models

**Worth pursuing?**
- **YES for GEPA** - Repeated evaluations with same base prompts benefit significantly
- **MAYBE for general workflows** - Need 1024+ tokens to benefit (many ORC nodes are smaller)
- **NO for Ollama/Bedrock** - Those providers don't support prefix caching

**Recommendation**: Defer until after higher-priority features. If GEPA cost is a concern, investigate as a targeted optimization.

---

## Application Patterns: How to Build a Hermes-like Assistant

### Pattern 1: Memory as Ontology Queries

Instead of MEMORY.md flat file, use ontology:

```clojure
;; Application defines memory-recording workflow
(def record-memory-workflow
  (orc/workflow "record-memory"
    (orc/blackboard {:insight :string :domain :string})
    (orc/code "record"
      :fn "assistant.memory/record-to-ontology"
      :reads [:insight :domain])))

;; Executor uses ontology commands
(defn record-to-ontology [{:keys [inputs context]}]
  (ontology/record-insight! context
    {:insight (get inputs "insight")
     :domain (get inputs "domain")
     :pattern-type :observation}))
```

### Pattern 2: Skills as Published Workflows

Instead of SKILL.md files, use versioned sheets:

```clojure
;; Application creates skill as workflow
(def skill-workflow
  (orc/workflow "user-skill:summarize-emails"
    (orc/blackboard {:emails [:vector :map] :summary :string})
    (orc/llm "summarize" ...)))

;; Build and publish
(orc/build-workflow! ctx skill-workflow)
(orc/publish-version! ctx sheet-id)

;; Skill discovery via ontology
(ontology/search ctx "summarize" :scope :skills)
```

### Pattern 3: Nudges as Execution Hooks

Instead of turn counters, use node-count hooks:

```clojure
;; Application registers nudge hook
(defn nudge-hook [ctx node result]
  (let [count (get-in ctx [:execution :node-count])
        interval (get-in ctx [:config :nudge-interval] 10)]
    (when (zero? (mod count interval))
      (orc/execute ctx reflection-workflow-id
        {:recent-outputs (get-recent-outputs ctx 5)}))))

(orc/register-execution-hook! {:on-node-complete nudge-hook})
```

### Pattern 4: Session History as Event Stream

Instead of SQLite, use Grain events:

```clojure
;; Application queries conversation history
(defn get-session-history [ctx session-id]
  (grain/query ctx
    {:query/name :assistant/get-session-messages
     :session-id session-id
     :limit 50}))

;; Events already capture full execution trace
;; :sheet/execution-traced has inputs, outputs, timing
```

---

## Verification: Does ORC Have All the Blocks?

| Hermes Capability | ORC Equivalent | Status |
|-------------------|----------------|--------|
| Memory storage | Ontology concepts + tree profiles | ✅ Ready |
| Memory retrieval | Hybrid search (3-signal RRF) | ✅ Ready |
| Skill creation | Workflow DSL + publish | ✅ Ready |
| Skill discovery | Ontology search by scope | ✅ Ready |
| Self-reflection | Rule extraction + context injection | ✅ Ready |
| Learning from success | Tree strength recording | ✅ Ready |
| Learning from failure | Weakness classification + GEPA | ✅ Ready |
| Tool orchestration | MCP integration + code nodes | ✅ Ready |
| Subagent delegation | `repl-researcher` / nested execute | ⚠️ Enhance |
| Periodic nudges | Execution hooks | ❌ Add |
| Cost control | Execution budgets | ❌ Add |
| Prefix caching | Cache hints | ❌ Add |

---

## Recommended Implementation Order

### Phase 1: Add Framework Primitives (ORC Core)

1. **Execution Hooks** - Generic callback registration
2. **Execution Budgets** - Cost control limits
3. **Cache Hints** - Prefix caching markers

### Phase 2: Build Example Assistant (Application Layer)

1. **Memory Tool** - Uses ontology for storage/retrieval
2. **Skill Tool** - Uses workflow DSL for skill creation
3. **Reflection Workflow** - Periodic self-assessment
4. **Chat Interface** - Simple CLI or web UI

### Phase 3: Demonstrate Full Loop

1. User chats with assistant
2. Assistant executes workflows
3. Low scores trigger ontology learning
4. High scores trigger strength recording
5. Nudges trigger periodic reflection
6. Next execution benefits from learned patterns

---

## Summary: The Separation

| Layer | Responsibility | Examples |
|-------|----------------|----------|
| **ORC Core** | Execution primitives, learning primitives, retrieval primitives | Nodes, ontology, GEPA, hybrid search |
| **ORC Extensions** | Framework enhancements for common patterns | Hooks, budgets, cache hints |
| **Application** | User-facing features, UX, platform-specific logic | Chat UI, memory tool, session mgmt |

**Key Insight**: ORC already has 90%+ of the primitives needed. The main gaps are:
1. **Execution hooks** for instrumentation
2. **Budgets** for cost control
3. **Cache hints** for optimization

These are framework-level concerns that any application would benefit from. Everything else (memory format, skill storage, nudge triggers) is application-level customization that should be built ON ORC, not IN ORC.

---

# Part 3: Research Findings Summary

## Consolidated Research Results

Based on deep investigation of the ORC codebase, FUTURE-VISION.md, and hermes-agent comparison, here are the validated conclusions:

### Finding 1: Ontology Generalization ✅ CONFIRMED
**Status**: Already implemented and general-purpose
- `:custom` scope supports infinite domain-specific ontologies
- Evolutionary ontology builder works on any data source
- Hybrid search (Graph BFS + Embeddings + ColBERT) operates on structure, not domain
- Automatic embeddable field detection via heuristics + LLM analysis
- **No changes needed** - success/failure ontology IS a specialization of the general system

### Finding 2: Execution Hooks ❌ REDUNDANT (Updated 2026-04)
**Status**: After deeper Grain research, lifecycle events are **redundant**
- Grain already provides `defperiodic` for cron/interval triggers
- Grain's `defprocessor` with checkpointing provides at-least-once/at-most-once semantics
- ORC already emits `:sheet/tick-completed`, `:sheet/node-execution-completed` which can be subscribed to
- **Use Grain's periodic tasks + todo processors instead of custom lifecycle events**
- Lifecycle event schema and emission code have been **removed** from ORC

### Finding 3: Execution Budgets ✅ PARTIALLY REDUNDANT
**Status**: Per-node budgets already exist
- `repl-researcher` has `:max-iterations` (default 10)
- `map-each` has `:max-concurrency`
- Leaf nodes have `:retry {:max-attempts n}`
- **Global budget** useful only as safety net for nested iteration scenarios
- Don't add `max-nodes` (redundant), consider `max-llm-calls` as safety net

### Finding 4: Subworkflow Delegation ⚠️ NEW REQUIREMENT
**Status**: NOT explicitly planned in FUTURE-VISION.md
- Theme 6 "hierarchy" refers to library organization, not runtime delegation
- MCP Sheet Builder provides dynamic executor generation (code, not trees)
- Tree-to-tree execution with independent blackboards is a **gap**
- **Recommendation**: Add `:delegate` node type to roadmap

### Finding 5: Cache Hints ⚠️ POSSIBLE BUT DEFERRED
**Status**: LiteLLM supports it, DSCloj doesn't expose parameters
- Confirmed chain: ORC → DSCloj → litellm-router → providers
- Anthropic/OpenAI support prefix caching natively
- DSCloj doesn't pass through caching parameters currently
- **Recommendation**: Defer until GEPA cost optimization becomes priority

---

## Updated Verification Table

| Original Recommendation | Research Status | Action |
|------------------------|-----------------|--------|
| Ontology generalization | ✅ Already works | Document capability |
| Execution hooks | ❌ **REDUNDANT** | Use Grain's `defperiodic` + `defprocessor` |
| Execution budgets | ✅ Mostly redundant | Add only `max-llm-calls` safety net |
| Subworkflow delegation | ✅ **IMPLEMENTED** | `:delegate` node type added |
| Cache hints | ⚠️ Needs upstream | Defer to Phase 2+ |

---

## Revised Implementation Phases (Updated 2026-04)

### Phase 1: Completed ✅

1. ~~**Event-Based Execution Hooks**~~ - **REMOVED** as redundant with Grain's `defperiodic` + `defprocessor`
   - Grain already provides periodic task triggers
   - Grain's todo-processor-v2 provides checkpointing for at-least-once/at-most-once semantics
   - ORC already emits `:sheet/tick-completed`, `:sheet/node-execution-completed` events

2. **Subworkflow Delegation Node** ✅ **IMPLEMENTED**
   - `:delegate` node type added to DSL
   - Isolated blackboard spawning works
   - Uses `:reads`/`:writes` for input/output mapping

### Phase 2: Low Priority

1. **Global LLM Call Budget** (Low effort - safety net only)
   - Track LLM call count in tick state
   - Add `:max-llm-calls` to execute options
   - Fail fast when limit exceeded

### Phase 3: Deferred Enhancements

1. **Prefix Caching** - Wait for DSCloj upstream support
2. **Cost Tracking** - Track actual $ cost during execution
3. **Additional Budgets** - `max-cost-usd`, `max-tokens`

### Phase 4: Application Layer (Hermes-style Assistant)

1. Memory tool using ontology
2. Skill tool using published workflows
3. Self-learning via Grain's `defperiodic` + ontology integration
4. Chat interface

---

## Files Updated (2026-04)

| File | Change | Status |
|------|--------|--------|
| **FUTURE-VISION.md** | Add `:delegate` node to roadmap | ✅ Done |
| ~~**schemas.clj**~~ | ~~Add lifecycle event schema~~ | ❌ Removed (redundant) |
| ~~**todo_processors.clj**~~ | ~~Emit lifecycle events~~ | ❌ Removed (redundant) |
| **dsl.clj** | Add `delegate` node type | ✅ Done |
| **interface.clj** | Export `delegate` function | ✅ Done |
| **runtime.clj** | Add `max-llm-calls` tracking | ⏳ Low priority |
| **DSCloj (upstream)** | Add cache parameter pass-through | ⏳ Deferred |

### Grain-Native Self-Learning (NEW)

Instead of custom lifecycle events, use Grain's built-in capabilities:

```clojure
;; 1. Define periodic task for self-reflection
(defperiodic :learning daily-reflection
  {:schedule {:cron "0 0 * * *"}}
  [tenant-id time]
  {:result/events [(es/->event {:type :learning/reflection-triggered ...})]})

;; 2. Todo processor handles the trigger with checkpointing
(defprocessor :learning process-reflection
  {:topics #{:learning/reflection-triggered}}
  [context]
  {:result/effect (fn [] (run-ontology-update! context))
   :result/checkpoint :after
   :result/on-success [(es/->event {:type :learning/reflection-completed ...})]})
```
