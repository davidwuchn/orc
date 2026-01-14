(ns chatbot-demo
  "Comprehensive DSL demo - demonstrates ALL behavior tree node types in one workflow.

   Node types demonstrated:
   - sequence (control node)
   - parallel (control node)
   - fallback (control node)
   - map-each (control node with :from :as :into)
   - condition (static check)
   - llm-condition (LLM-based yes/no decision)
   - llm (leaf node with generation)
   - code (leaf node with custom function)

   Usage:
     (def sheet-id (build-demo!))
     (run-demo sheet-id \"Hello, this is a short message.\")
     (run-demo sheet-id \"URGENT: The server is down and we need help immediately!\")"
  (:require [ai.obney.workshop.sheet-service.interface :as sheet]
            [repl-stuff :as rs]))

;; =============================================================================
;; Code Executor Functions
;; =============================================================================

(defn count-words
  "Count words in text."
  [{:keys [inputs]}]
  (let [text (get inputs "input-text" "")]
    {"word-count" (count (clojure.string/split text #"\s+"))}))

;; =============================================================================
;; Unified Demo Workflow - All DSL Features
;; =============================================================================

(def demo-workflow
  "Demonstrates ALL DSL node types in one workflow:
   - SEQUENCE: orchestrates the main flow
   - CODE: counts words in input
   - FALLBACK + CONDITION: branches based on word count (>50 = summarize)
   - PARALLEL: extracts keywords AND classifies theme concurrently
   - MAP-EACH: classifies each keyword individually
   - FALLBACK + LLM-CONDITION: detects urgency → urgent vs normal response
   - LLM: multiple generation nodes throughout"
  (sheet/workflow "comprehensive-demo"
    (sheet/blackboard
      {:input-text :string
       :word-count :int
       :summary :string
       :text-theme [:enum "technical" "personal" "business" "creative" "general"]
       :keywords [:vector :string]
       :current-keyword :string
       :keyword-type [:enum "noun" "verb" "adjective" "other"]
       :analyzed-keywords [:vector [:map [:keyword-type :string]]]
       :final-response :string})

    ;; SEQUENCE: Main orchestration
    (sheet/sequence

      ;; Step 1: CODE - count words
      (sheet/code "count-words"
        :fn "chatbot-demo/count-words"
        :reads ["input-text"]
        :writes ["word-count"])

      ;; Step 2: FALLBACK + CONDITION - branch based on text length
      (sheet/fallback
        ;; Try: If long text (>50 words), summarize it
        (sheet/sequence
          (sheet/condition "check-long"
            :check {:key "word-count" :op :gt :value 50})
          (sheet/llm "summarize"
            :model "google/gemini-2.0-flash-001"
            :instruction "Summarize this long text in 2-3 sentences."
            :reads ["input-text"]
            :writes ["summary"]))

        ;; Else: Short text, just acknowledge
        (sheet/llm "acknowledge-short"
          :model "google/gemini-2.0-flash-001"
          :instruction "This is a short text. Simply acknowledge what the user said in one sentence."
          :reads ["input-text"]
          :writes ["summary"]))

      ;; Step 3: PARALLEL - extract keywords AND classify theme concurrently
      (sheet/parallel
        (sheet/llm "extract-keywords"
          :model "google/gemini-2.0-flash-001"
          :instruction "Extract 2-4 important keywords from the text."
          :reads ["input-text"]
          :writes ["keywords"])

        (sheet/llm "classify-theme"
          :model "google/gemini-2.0-flash-001"
          :instruction "Classify the overall theme of this text."
          :reads ["input-text"]
          :writes ["text-theme"]))

      ;; Step 4: MAP-EACH - classify each keyword
      (sheet/map-each "analyze-keywords"
        :from "keywords"
        :as "current-keyword"
        :into "analyzed-keywords"
        (sheet/llm "classify-keyword"
          :model "google/gemini-2.0-flash-001"
          :instruction "Classify this keyword as noun, verb, adjective, or other."
          :reads ["current-keyword"]
          :writes ["keyword-type"]))

      ;; Step 5: FALLBACK + LLM-CONDITION - urgent vs normal response
      (sheet/fallback
        ;; Try: If urgent, respond with high priority
        (sheet/sequence
          (sheet/llm-condition "is-urgent?"
            :model "google/gemini-2.0-flash-001"
            :instruction "Is this message urgent, time-sensitive, or requiring immediate attention?"
            :reads ["input-text"])
          (sheet/llm "urgent-response"
            :model "google/gemini-2.0-flash-001"
            :instruction "This is URGENT. Generate a high-priority response acknowledging urgency, using the summary, theme, and keywords for context."
            :reads ["summary" "text-theme" "analyzed-keywords"]
            :writes ["final-response"]))

        ;; Else: Normal response
        (sheet/llm "normal-response"
          :model "google/gemini-2.0-flash-001"
          :instruction "Generate a helpful response based on the summary, theme, and analyzed keywords."
          :reads ["summary" "text-theme" "analyzed-keywords"]
          :writes ["final-response"])))))

;; =============================================================================
;; API
;; =============================================================================

(defn build-demo!
  "Build the demo workflow. Returns sheet-id."
  []
  (sheet/build-workflow!! rs/context demo-workflow))

(defn run-demo
  "Run the demo with input text. Returns analysis results."
  [sheet-id text]
  (let [result (sheet/execute rs/context sheet-id {"input-text" text})]
    (if (= :success (:status result))
      {:word-count (get-in result [:outputs "word-count"])
       :summary (get-in result [:outputs "summary"])
       :text-theme (get-in result [:outputs "text-theme"])
       :keywords (get-in result [:outputs "keywords"])
       :analyzed-keywords (get-in result [:outputs "analyzed-keywords"])
       :response (get-in result [:outputs "final-response"])}
      (throw (ex-info "Demo failed" {:error (:error result)})))))

(comment
  ;; ==========================================================================
  ;; Demo - All DSL Features in One Workflow
  ;; ==========================================================================
  (def sheet-id (build-demo!))

  ;; Short, non-urgent text
  ;; - CODE counts words (<50)
  ;; - CONDITION fails → FALLBACK to acknowledge-short
  ;; - PARALLEL extracts keywords + theme
  ;; - MAP-EACH classifies keywords
  ;; - LLM-CONDITION returns false → FALLBACK to normal-response
  (run-demo sheet-id "Hello, this is a short message about programming.")

  ;; Short, urgent text
  ;; - CODE counts words (<50)
  ;; - CONDITION fails → FALLBACK to acknowledge-short
  ;; - PARALLEL extracts keywords + theme
  ;; - MAP-EACH classifies keywords
  ;; - LLM-CONDITION returns true → urgent-response
  (run-demo sheet-id "URGENT: Server is down! Need immediate help!")

  ;; Long, non-urgent text (>50 words)
  ;; - CODE counts words (>50)
  ;; - CONDITION passes → summarize
  ;; - PARALLEL extracts keywords + theme
  ;; - MAP-EACH classifies keywords
  ;; - LLM-CONDITION returns false → FALLBACK to normal-response
  (run-demo sheet-id
    "This is a much longer piece of text that contains more than fifty words.
     It discusses various topics including technology, programming, and software development.
     We need to implement the new feature by Friday, test it thoroughly, and deploy to production.
     The team should also review the documentation and update the API endpoints.
     Additionally, please schedule a meeting with the stakeholders to discuss progress.")

  ;; ==========================================================================
  ;; Versioning Demo
  ;; ==========================================================================

  ;; After building, the sheet is in draft mode with no published version
  (sheet/get-sheet (:event-store rs/context) sheet-id)
  ;; => {:published-version nil, :draft-dirty? false, :execution-mode nil, ...}

  ;; --- Publish v1 ---
  ;; Lock in the current state as version 1
  (require '[ai.obney.workshop.sheet-service.test-helpers :as h])
  (h/run-and-apply! rs/context
    (h/make-publish-version-command sheet-id :description "Initial release"))

  ;; Check sheet state after publish
  (sheet/get-sheet (:event-store rs/context) sheet-id)
  ;; => {:published-version 1, :draft-dirty? false, ...}

  ;; --- Make Changes (Draft Becomes Dirty) ---
  ;; Modify a node - changes the draft but not the published version
  (let [nodes (sheet/get-nodes-for-sheet (:event-store rs/context) sheet-id)
        summarize-node (->> nodes (filter #(= "summarize" (:name %))) first)]
    (h/run-and-apply! rs/context
      (h/make-set-node-instruction-command sheet-id (:id summarize-node)
        "Provide a concise 1-sentence summary.")))

  ;; Now draft-dirty? is true
  (sheet/get-sheet (:event-store rs/context) sheet-id)
  ;; => {:published-version 1, :draft-dirty? true, ...}

  ;; --- Execution Mode ---
  ;; By default, execute runs the draft
  (run-demo sheet-id "Test message")  ; Uses draft (with new instruction)

  ;; Switch to published mode - executes the v1 snapshot
  (h/run-and-apply! rs/context
    (h/make-set-execution-mode-command sheet-id :published))

  (run-demo sheet-id "Test message")  ; Uses published v1 (old instruction)

  ;; Or explicitly run a specific version
  (sheet/execute rs/context sheet-id {"input-text" "Test"} :use-version 1)

  ;; Switch back to draft mode for testing
  (h/run-and-apply! rs/context
    (h/make-set-execution-mode-command sheet-id :draft))

  ;; --- Publish v2 ---
  ;; Happy with changes? Publish a new version
  (h/run-and-apply! rs/context
    (h/make-publish-version-command sheet-id :description "Improved summarization"))

  ;; --- Version History ---
  ;; Query all versions
  (h/run-query rs/context (h/make-version-history-query sheet-id))
  ;; => {:versions [{:version-number 1, :description "Initial release", ...}
  ;;                {:version-number 2, :description "Improved summarization", ...}]
  ;;     :current-published-version 2
  ;;     :draft-dirty? false}

  ;; Get a specific version's snapshot
  (h/run-query rs/context (h/make-get-version-query sheet-id 1))
  ;; => {:version {:snapshot {:nodes {...}, :blackboard-schema {...}}, ...}}

  ;; --- Revert to Previous Version ---
  ;; Make more changes
  (let [nodes (sheet/get-nodes-for-sheet (:event-store rs/context) sheet-id)
        node (->> nodes (filter #(= "summarize" (:name %))) first)]
    (h/run-and-apply! rs/context
      (h/make-set-node-instruction-command sheet-id (:id node) "Bad instruction")))

  ;; Revert to v1 - automatically stashes dirty draft first
  (h/run-and-apply! rs/context
    (h/make-revert-to-version-command sheet-id 1))

  ;; Check that stash exists
  (sheet/get-sheet (:event-store rs/context) sheet-id)
  ;; => {:has-stash? true, ...}

  ;; View the stash
  (h/run-query rs/context (h/make-get-stash-query sheet-id))
  ;; => {:stash {:snapshot {...with "Bad instruction"...}}}

  ;; Restore from stash if needed
  (h/run-and-apply! rs/context (h/make-restore-stash-command sheet-id))

  "")
