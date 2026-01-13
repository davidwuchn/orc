(ns chatbot-demo
  "Chatbot with conversational memory using the behavior tree DSL.

   Usage:
     (def sheet-id (build-chatbot!))
     (def session (create-session sheet-id :system-prompt \"You are helpful.\"))
     (chat! session \"Hello!\")"
  (:require [ai.obney.workshop.sheet-service.interface :as sheet]
            [repl-stuff :as rs]))

;; =============================================================================
;; Code Executor Function
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
       :assistant-response :string})

    (sheet/sequence
      (sheet/ai-node "respond"
        :model "google/gemini-2.0-flash-001"
        :instruction "Generate a conversational response as the assistant."
        :reads ["system-prompt" "conversation-history" "user-message"]
        :writes ["assistant-response"])

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

  (chat! session "What did I just ask you?")
  
  
  
  "")