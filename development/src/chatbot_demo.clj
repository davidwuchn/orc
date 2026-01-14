(ns chatbot-demo
  "Chatbot with conversational memory using the behavior tree DSL.

   Demonstrates tracing with multiple node types:
   - Sequence (control node)
   - Parallel (control node)
   - Map-each (control node)
   - AI nodes (leaf nodes with generation events)
   - Code node (leaf node with span event)

   Usage:
     (def sheet-id (build-chatbot!))
     (def session (create-session sheet-id :system-prompt \"You are helpful.\"))
     (chat! session \"Hello!\")"
  (:require [ai.obney.workshop.sheet-service.interface :as sheet]
            [repl-stuff :as rs]))

;; =============================================================================
;; Code Executor Functions
;; =============================================================================

(defn append-to-history
  "Appends the current exchange to conversation history."
  [{:keys [inputs]}]
  (let [history (or (get inputs "conversation-history") [])
        user-msg (get inputs "user-message")
        assistant-msg (get inputs "assistant-response")]
    {"conversation-history"
     (conj history
           {:role :user :content user-msg}
           {:role :assistant :content assistant-msg})}))

;; =============================================================================
;; Workflow Definition
;; =============================================================================

(def chatbot-workflow
  (sheet/workflow "conversational-chatbot"
    (sheet/blackboard
      {:system-prompt :string
       :conversation-history [:vector [:map
                                       [:role [:enum :user :assistant]]
                                       [:content :string]]]
       :user-message :string
       :sentiment [:enum "positive" "negative" "neutral"]
       :intent [:enum "question" "statement" "greeting" "farewell" "other"]
       ;; For map-each demo: extract and classify topics
       :topics [:vector :string]
       :current-topic :string
       :topic-category [:enum "technical" "personal" "business" "general"]
       :classified-topics [:vector [:map
                                    [:topic-category :string]]]
       :assistant-response :string})

    (sheet/sequence
      ;; Step 1: Analyze user message in parallel
      (sheet/parallel
        (sheet/ai-node "analyze-sentiment"
          :model "google/gemini-2.0-flash-001"
          :instruction "Classify the emotional tone of the user's message."
          :reads ["user-message"]
          :writes ["sentiment"])

        (sheet/ai-node "classify-intent"
          :model "google/gemini-2.0-flash-001"
          :instruction "Classify the intent of the user's message."
          :reads ["user-message"]
          :writes ["intent"])

        (sheet/ai-node "extract-topics"
          :model "google/gemini-2.0-flash-001"
          :instruction "Extract 1-3 key topics or subjects mentioned in the user's message. Return as a list of short phrases."
          :reads ["user-message"]
          :writes ["topics"]))

      ;; Step 2: Classify each topic using map-each
      (sheet/map-each "classify-topics"
        :source "topics"
        :item "current-topic"
        :output "classified-topics"
        :concurrency 2

        (sheet/ai-node "classify-topic"
          :model "google/gemini-2.0-flash-001"
          :instruction "Classify this topic into a category."
          :reads ["current-topic"]
          :writes ["topic-category"]))

      ;; Step 3: Generate response informed by analysis
      (sheet/ai-node "respond"
        :model "google/gemini-2.0-flash-001"
        :instruction "Generate a conversational response. Adapt tone based on sentiment, intent, and the classified topics."
        :reads ["system-prompt" "conversation-history" "user-message" "sentiment" "intent" "classified-topics"]
        :writes ["assistant-response"])

      ;; Step 4: Update history
      (sheet/code-node "update-history"
        :fn "chatbot-demo/append-to-history"
        :reads ["conversation-history" "user-message" "assistant-response"]
        :writes ["conversation-history"]))))

;; =============================================================================
;; API
;; =============================================================================

(defn build-chatbot!
  "Build the chatbot workflow. Returns sheet-id (visible in UI)."
  []
  (sheet/build-workflow!! rs/context chatbot-workflow))

(defn create-session
  "Create a chat session for a sheet-id."
  [sheet-id & {:keys [system-prompt]
               :or {system-prompt "You are a helpful assistant."}}]
  {:sheet-id sheet-id
   :system-prompt system-prompt
   :history (atom [])})

(defn chat!
  "Send a message and get a response. Updates session history."
  [session message]
  (let [{:keys [sheet-id system-prompt history]} session
        result (sheet/execute rs/context sheet-id
                 {"user-message" message
                  "system-prompt" system-prompt
                  "conversation-history" @history})]
    (if (= :success (:status result))
      (let [outputs (:outputs result)]
        (reset! history (get outputs "conversation-history"))
        (get outputs "assistant-response"))
      (throw (ex-info "Chat failed" {:error (:error result)})))))

(defn get-history [session] @(:history session))
(defn clear-history! [session] (reset! (:history session) []))

(comment
  
  (def sheet-id (build-chatbot!))
  
  (def session (create-session sheet-id))

  (chat! session "Analyze this: Thanks, Lucas! I'm looking forward to working with everyone here and will be in touch soon about January meeting availability.

Warmly,
Elin")
  
  
  
  
  "")