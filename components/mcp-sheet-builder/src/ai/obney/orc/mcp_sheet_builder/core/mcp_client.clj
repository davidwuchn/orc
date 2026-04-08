(ns ai.obney.orc.mcp-sheet-builder.core.mcp-client
  "HTTP/SSE client for MCP servers.

   Supports multiple connection types:
   - :http - HTTP/SSE streamable MCP servers (Langfuse, Exa, Tavily)
   - :claude-mcp - Uses Claude Code's MCP infrastructure directly
   - :nrepl - Uses the nREPL MCP bridge"
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u]))

;; ============================================================================
;; Protocol
;; ============================================================================

(defprotocol MCPClient
  "Protocol for MCP client implementations."
  (list-tools* [this] "List available tools.")
  (call-tool* [this tool-name args] "Call a tool with arguments.")
  (close* [this] "Close the connection."))

;; ============================================================================
;; Streamable HTTP MCP Client (MCP 2025-03-26 spec)
;; ============================================================================

(defn- parse-sse-response
  "Parse an SSE (Server-Sent Events) response body.
   Returns the JSON-RPC result from the data lines."
  [body]
  (let [lines (str/split-lines body)
        data-lines (->> lines
                        (filter #(str/starts-with? % "data: "))
                        (map #(subs % 6)))]
    (when (seq data-lines)
      (json/parse-string (first data-lines) true))))

(defn- parse-response-body
  "Parse the response body, handling both JSON and SSE formats."
  [response]
  (let [content-type (or (get-in response [:headers "content-type"])
                         (get-in response [:headers "Content-Type"])
                         "")
        body (:body response)]
    (cond
      ;; SSE response - parse the event stream
      (str/includes? content-type "text/event-stream")
      (parse-sse-response (if (string? body) body (slurp body)))

      ;; JSON response - body may already be parsed or be a string
      (map? body)
      body

      (string? body)
      (json/parse-string body true)

      :else
      body)))

;; Forward declaration for session initialization
(declare initialize-mcp-session!)

(defn- reinitialize-session!
  "Reinitialize the MCP session when it expires (404 error)."
  [url headers session-id-atom]
  (u/log ::session-expired :url url)
  (let [new-session-id (initialize-mcp-session! url headers)]
    (reset! session-id-atom new-session-id)
    new-session-id))

(defn- mcp-request-with-retry
  "Make an MCP request with automatic session refresh on 404.
   request-fn takes session-id and returns response."
  [url headers session-id-atom request-fn]
  (try
    (request-fn @session-id-atom)
    (catch Exception e
      ;; clj-http with slingshot wraps status in ex-data
      (let [data (ex-data e)
            status (or (:status data)
                       ;; Check if error message contains 404
                       (when (and (instance? clojure.lang.ExceptionInfo e)
                                  (re-find #"status 404" (str (.getMessage e))))
                         404))]
        (if (= 404 status)
          ;; Session expired - reinitialize and retry
          (do
            (reinitialize-session! url headers session-id-atom)
            (request-fn @session-id-atom))
          (throw e))))))

(defrecord StreamableHTTPMCPClient [url session-id-atom headers]
  MCPClient
  (list-tools* [_]
    (u/trace ::list-tools {:url url}
      (mcp-request-with-retry url headers session-id-atom
        (fn [session-id]
          (let [response (http/post url
                                    {:headers (cond-> {"Content-Type" "application/json"
                                                       "Accept" "application/json, text/event-stream"}
                                                session-id (assoc "Mcp-Session-Id" session-id))
                                     :body (json/generate-string {:jsonrpc "2.0"
                                                                   :method "tools/list"
                                                                   :id 1})
                                     :as :auto
                                     :throw-exceptions true})
                parsed (parse-response-body response)]
            (get-in parsed [:result :tools] []))))))

  (call-tool* [_ tool-name args]
    (u/trace ::call-tool {:url url :tool tool-name}
      (mcp-request-with-retry url headers session-id-atom
        (fn [session-id]
          (let [response (http/post url
                                    {:headers (cond-> {"Content-Type" "application/json"
                                                       "Accept" "application/json, text/event-stream"}
                                                session-id (assoc "Mcp-Session-Id" session-id))
                                     :body (json/generate-string {:jsonrpc "2.0"
                                                                   :method "tools/call"
                                                                   :params {:name tool-name
                                                                            :arguments args}
                                                                   :id 1})
                                     :as :auto
                                     :throw-exceptions true})
                parsed (parse-response-body response)]
            (get parsed :result))))))

  (close* [this]
    ;; Send DELETE to close session if we have a session ID
    (when-let [session-id @session-id-atom]
      (try
        (http/delete url {:headers {"Mcp-Session-Id" session-id}})
        (catch Exception _)))
    (reset! session-id-atom nil)))

(defn- initialize-mcp-session!
  "Initialize an MCP session with the server.
   Returns the session ID if one is assigned."
  [url headers]
  (u/trace ::initialize-session {:url url}
    (try
      ;; Step 1: Send InitializeRequest
      (let [init-response (http/post url
                                     {:headers (merge {"Content-Type" "application/json"
                                                       "Accept" "application/json, text/event-stream"}
                                                      headers)
                                      :body (json/generate-string
                                              {:jsonrpc "2.0"
                                               :method "initialize"
                                               :params {:protocolVersion "2025-03-26"
                                                        :capabilities {}
                                                        :clientInfo {:name "orc-mcp-client"
                                                                     :version "1.0.0"}}
                                               :id 1})
                                      :as :auto})
            session-id (get-in init-response [:headers "mcp-session-id"])]

        ;; Step 2: Send InitializedNotification
        (http/post url
                   {:headers (cond-> {"Content-Type" "application/json"
                                      "Accept" "application/json, text/event-stream"}
                               session-id (assoc "Mcp-Session-Id" session-id))
                    :body (json/generate-string
                            {:jsonrpc "2.0"
                             :method "notifications/initialized"})})

        session-id)
      (catch Exception e
        (u/log ::initialize-failed :error (.getMessage e))
        nil))))

(defn- connect-http
  "Connect to an HTTP MCP server using Streamable HTTP transport."
  [{:keys [url headers api-key]}]
  (let [auth-headers (when api-key
                       {"Authorization" (str "Bearer " api-key)})
        all-headers (merge headers auth-headers)
        session-id (initialize-mcp-session! url all-headers)]
    (->StreamableHTTPMCPClient url (atom session-id) all-headers)))

;; ============================================================================
;; Claude MCP Bridge (Uses Claude Code's infrastructure)
;; ============================================================================

(defrecord ClaudeMCPClient [server-name tools-cache]
  MCPClient
  (list-tools* [_]
    ;; Tools are pre-loaded from Claude Code's MCP configuration
    @tools-cache)

  (call-tool* [_ tool-name args]
    ;; This will be called via code executor in ORC context
    ;; The actual call happens through Claude Code's MCP infrastructure
    (throw (ex-info "ClaudeMCPClient.call-tool should be invoked via ORC code executor"
                    {:tool tool-name :args args})))

  (close* [_]
    nil))

(defn- connect-claude-mcp
  "Connect to Claude Code's MCP infrastructure.
   This requires tools to be pre-loaded from the MCP configuration."
  [{:keys [server-name tools]}]
  (->ClaudeMCPClient server-name (atom (or tools []))))

;; ============================================================================
;; Static Tool Definitions (for POC/testing)
;; ============================================================================

(defrecord StaticMCPClient [name tools call-tool-handler]
  MCPClient
  (list-tools* [_]
    tools)

  (call-tool* [_ tool-name args]
    (if call-tool-handler
      (call-tool-handler tool-name args)
      (do (u/log ::static-call-tool :tool tool-name :args args)
          {:result "Static MCP client - tool call not executed"})))

  (close* [_]
    nil))

(defn- connect-static
  "Create a static MCP client with pre-defined tools.
   Useful for testing and POC without an actual MCP server.
   Optionally accepts :call-tool-handler (fn [tool-name args] -> result)
   for deterministic tool responses in tests."
  [{:keys [name tools call-tool-handler]}]
  (->StaticMCPClient name tools call-tool-handler))

;; ============================================================================
;; Tool Definitions for Known MCP Servers
;; ============================================================================

(def langfuse-tools
  "Langfuse MCP tool definitions."
  [{:name "searchLangfuseDocs"
    :description "Semantic search (RAG) over the Langfuse documentation."
    :inputSchema {:type "object"
                  :properties {"query" {:type "string"
                                        :description "The user's question in natural language."}}
                  :required ["query"]}}
   {:name "getLangfuseDocsPage"
    :description "Fetch the raw Markdown for a single Langfuse docs page."
    :inputSchema {:type "object"
                  :properties {"pathOrUrl" {:type "string"
                                            :description "Docs path or full URL."}}
                  :required ["pathOrUrl"]}}
   {:name "getLangfuseOverview"
    :description "Get a high-level, machine-readable index by downloading llms.txt."
    :inputSchema {:type "object"
                  :properties {}}}])

(def nrepl-tools
  "nREPL MCP tool definitions."
  [{:name "connect"
    :description "Connect to an nREPL server."
    :inputSchema {:type "object"
                  :properties {"host" {:type "string"
                                       :description "nREPL server host"}
                               "port" {:type "number"
                                       :description "nREPL server port"}}
                  :required ["host" "port"]}}
   {:name "eval_form"
    :description "Evaluate Clojure code in a specific namespace or the current one."
    :inputSchema {:type "object"
                  :properties {"code" {:type "string"
                                       :description "Clojure code to evaluate"}
                               "ns" {:type "string"
                                     :description "Optional namespace to evaluate in"}}
                  :required ["code"]}}
   {:name "get_ns_vars"
    :description "Get all public vars in a namespace with their metadata and values."
    :inputSchema {:type "object"
                  :properties {"ns" {:type "string"
                                     :description "Namespace to inspect"}}
                  :required ["ns"]}}])

(def exa-tools
  "Exa MCP tool definitions.
   Based on https://github.com/exa-labs/exa-mcp-server"
  [{:name "web_search_exa"
    :description "Search the web with Exa's neural search. Returns relevant results with content snippets."
    :inputSchema {:type "object"
                  :properties {"query" {:type "string"
                                        :description "Search query"}
                               "numResults" {:type "integer"
                                             :description "Number of results (default 10)"}
                               "type" {:type "string"
                                       :enum ["neural" "keyword" "auto"]
                                       :description "Search type (default auto)"}}
                  :required ["query"]}}
   {:name "get_code_context_exa"
    :description "Search code repositories, documentation, and Stack Overflow for code examples and technical content."
    :inputSchema {:type "object"
                  :properties {"query" {:type "string"
                                        :description "Code-related search query"}
                               "language" {:type "string"
                                           :description "Programming language filter (optional)"}}
                  :required ["query"]}}
   {:name "crawling_exa"
    :description "Crawl a URL and extract its main content as clean text."
    :inputSchema {:type "object"
                  :properties {"url" {:type "string"
                                      :description "URL to crawl and extract content from"}}
                  :required ["url"]}}
   {:name "company_research_exa"
    :description "Research a company - find information about funding, team, products, and recent news."
    :inputSchema {:type "object"
                  :properties {"company" {:type "string"
                                          :description "Company name to research"}}
                  :required ["company"]}}])

(def tavily-tools
  "Tavily MCP tool definitions.
   Based on https://github.com/tavily-ai/tavily-mcp"
  [{:name "tavily_search"
    :description "Search the web with Tavily. Returns comprehensive results with AI-optimized content extraction."
    :inputSchema {:type "object"
                  :properties {"query" {:type "string"
                                        :description "Search query"}
                               "search_depth" {:type "string"
                                               :enum ["basic" "advanced"]
                                               :description "Search depth - basic is faster, advanced is more thorough"}
                               "include_answer" {:type "boolean"
                                                 :description "Include AI-generated answer summary"}
                               "max_results" {:type "integer"
                                              :description "Maximum results to return (default 5)"}}
                  :required ["query"]}}
   {:name "tavily_extract"
    :description "Extract and parse content from one or more URLs. Returns clean, structured content."
    :inputSchema {:type "object"
                  :properties {"urls" {:type "array"
                                       :items {:type "string"}
                                       :description "URLs to extract content from"}}
                  :required ["urls"]}}
   {:name "tavily_qna"
    :description "Get a direct answer to a question using Tavily's QnA search. Returns a concise, factual answer."
    :inputSchema {:type "object"
                  :properties {"query" {:type "string"
                                        :description "Question to answer"}}
                  :required ["query"]}}])

(def playwright-tools
  "Playwright MCP tool definitions.
   Based on https://github.com/microsoft/playwright-mcp"
  [{:name "browser_navigate"
    :description "Navigate to a URL in the browser."
    :inputSchema {:type "object"
                  :properties {"url" {:type "string"
                                      :description "URL to navigate to"}}
                  :required ["url"]}}
   {:name "browser_click"
    :description "Click on an element on the page."
    :inputSchema {:type "object"
                  :properties {"element" {:type "string"
                                          :description "Human-readable element description"}
                               "ref" {:type "string"
                                      :description "Exact target element reference from snapshot"}
                               "selector" {:type "string"
                                           :description "CSS selector for the element"}}}}
   {:name "browser_type"
    :description "Type text into an editable element on the page."
    :inputSchema {:type "object"
                  :properties {"element" {:type "string"
                                          :description "Human-readable element description"}
                               "ref" {:type "string"
                                      :description "Exact target element reference from snapshot"}
                               "selector" {:type "string"
                                           :description "CSS selector for the element"}
                               "text" {:type "string"
                                       :description "Text to type into the element"}
                               "submit" {:type "boolean"
                                         :description "Press Enter after typing"}}
                  :required ["text"]}}
   {:name "browser_fill_form"
    :description "Fill multiple form fields at once."
    :inputSchema {:type "object"
                  :properties {"fields" {:type "array"
                                         :items {:type "object"
                                                 :properties {"selector" {:type "string"}
                                                              "value" {:type "string"}}}
                                         :description "Array of {selector, value} pairs"}}
                  :required ["fields"]}}
   {:name "browser_snapshot"
    :description "Capture an accessibility snapshot of the current page, returning structured text content."
    :inputSchema {:type "object"
                  :properties {"filename" {:type "string"
                                           :description "Optional filename to save snapshot"}
                               "selector" {:type "string"
                                           :description "CSS selector to scope the snapshot"}
                               "depth" {:type "integer"
                                        :description "Maximum depth of the snapshot tree"}}}}
   {:name "browser_take_screenshot"
    :description "Take a screenshot of the current page or element."
    :inputSchema {:type "object"
                  :properties {"type" {:type "string"
                                       :enum ["png" "jpeg"]
                                       :description "Image format"}
                               "filename" {:type "string"
                                           :description "Filename to save screenshot"}
                               "element" {:type "string"
                                          :description "Element description to screenshot"}
                               "selector" {:type "string"
                                           :description "CSS selector for element"}
                               "fullPage" {:type "boolean"
                                           :description "Capture full scrollable page"}}}}
   {:name "browser_wait_for"
    :description "Wait for text to appear/disappear or for a specified time."
    :inputSchema {:type "object"
                  :properties {"time" {:type "integer"
                                       :description "Time to wait in milliseconds"}
                               "text" {:type "string"
                                       :description "Text to wait for"}
                               "textGone" {:type "string"
                                           :description "Text to wait to disappear"}}}}
   {:name "browser_evaluate"
    :description "Evaluate JavaScript code on the page and return the result."
    :inputSchema {:type "object"
                  :properties {"function" {:type "string"
                                           :description "JavaScript code to evaluate"}
                               "element" {:type "string"
                                          :description "Element to evaluate on"}
                               "selector" {:type "string"
                                           :description "CSS selector for context"}}
                  :required ["function"]}}
   {:name "browser_press_key"
    :description "Press a key on the keyboard."
    :inputSchema {:type "object"
                  :properties {"key" {:type "string"
                                      :description "Key to press (e.g. Enter, Escape, ArrowDown)"}}
                  :required ["key"]}}
   {:name "browser_select_option"
    :description "Select an option in a dropdown element."
    :inputSchema {:type "object"
                  :properties {"element" {:type "string"
                                          :description "Element description"}
                               "selector" {:type "string"
                                           :description "CSS selector for dropdown"}
                               "values" {:type "array"
                                         :items {:type "string"}
                                         :description "Values to select"}}
                  :required ["values"]}}
   {:name "browser_hover"
    :description "Hover over an element on the page."
    :inputSchema {:type "object"
                  :properties {"element" {:type "string"
                                          :description "Element description"}
                               "selector" {:type "string"
                                           :description "CSS selector"}}}}
   {:name "browser_close"
    :description "Close the browser page."
    :inputSchema {:type "object"
                  :properties {}}}
   {:name "browser_tabs"
    :description "List, create, close, or select a browser tab."
    :inputSchema {:type "object"
                  :properties {"action" {:type "string"
                                         :enum ["list" "create" "close" "select"]
                                         :description "Tab action to perform"}
                               "index" {:type "integer"
                                        :description "Tab index for select/close"}
                               "url" {:type "string"
                                      :description "URL for create action"}}}}
   {:name "browser_console_messages"
    :description "Return all console messages from the page."
    :inputSchema {:type "object"
                  :properties {"level" {:type "string"
                                        :enum ["log" "info" "warn" "error"]
                                        :description "Filter by log level"}
                               "all" {:type "boolean"
                                      :description "Include all levels"}}}}
   {:name "browser_network_requests"
    :description "Return all network requests made since page load."
    :inputSchema {:type "object"
                  :properties {"filter" {:type "string"
                                         :description "Filter requests by URL pattern"}
                               "requestHeaders" {:type "boolean"
                                                 :description "Include request headers"}
                               "requestBody" {:type "boolean"
                                              :description "Include request body"}}}}])

(def known-mcp-servers
  "Known MCP server definitions."
  {:langfuse {:tools langfuse-tools}
   :nrepl {:tools nrepl-tools}
   :exa {:tools exa-tools}
   :tavily {:tools tavily-tools}
   :playwright {:tools playwright-tools}})

;; ============================================================================
;; Public API
;; ============================================================================

(defn connect
  "Connect to an MCP server.

   Options:
   - :type - Connection type (:http, :claude-mcp, :static)
   - :url - Server URL (for :http type)
   - :server-name - Server name (for :claude-mcp type)
   - :name - Client name (for :static type)
   - :tools - Pre-defined tools (for :static or :claude-mcp types)
   - :preset - Use known server preset (:langfuse, :nrepl)"
  [{:keys [type preset] :as opts}]
  (let [preset-opts (when preset
                      (get known-mcp-servers preset))
        merged-opts (merge preset-opts opts)]
    (case type
      :http (connect-http merged-opts)
      :claude-mcp (connect-claude-mcp merged-opts)
      :static (connect-static merged-opts)
      ;; Default to static with preset tools
      (connect-static (assoc merged-opts :type :static)))))

(defn list-tools
  "List available tools from an MCP connection."
  [mcp-conn]
  (list-tools* mcp-conn))

(defn call-tool
  "Call a tool on an MCP connection."
  [mcp-conn tool-name args]
  (call-tool* mcp-conn tool-name args))

(defn close
  "Close an MCP connection."
  [mcp-conn]
  (close* mcp-conn))

;; ============================================================================
;; MCP Registry (Multi-Server)
;; ============================================================================

(defn- validate-server-name
  "Validate that a server name is a valid identifier."
  [server-name]
  (when-not (re-matches #"[a-zA-Z][a-zA-Z0-9_-]*" server-name)
    (throw (ex-info (str "Invalid server name: '" server-name
                         "'. Must start with a letter and contain only alphanumeric, _, -")
                    {:server-name server-name}))))

(defn create-registry
  "Create a registry of named MCP connections with auto-discovered tools.

   server-map is {\"server-name\" mcp-connection, ...}

   Auto-discovers tools from each connection. Validates server names
   are valid identifiers. Detects duplicate tool names across servers.

   Returns:
   {:connections {\"linear\" conn, \"github\" conn, ...}
    :catalog {\"linear/list_issues\" {:server \"linear\" :name \"list_issues\" :description ... :schema ...}
              \"github/list_pulls\"  {:server \"github\" :name \"list_pulls\"  :description ... :schema ...}}}"
  [server-map]
  (doseq [server-name (keys server-map)]
    (validate-server-name server-name))
  (let [catalog (reduce-kv
                  (fn [acc server-name conn]
                    (let [tools (list-tools conn)]
                      (reduce
                        (fn [cat tool]
                          (let [prefixed (str server-name "/" (:name tool))]
                            (assoc cat prefixed
                                   {:server server-name
                                    :name (:name tool)
                                    :prefixed-name prefixed
                                    :description (:description tool)
                                    :schema (:inputSchema tool)})))
                        acc
                        tools)))
                  {}
                  server-map)]
    {:connections server-map
     :catalog catalog}))

(defn registry->call-tool-fn
  "Create a multiplexing call-tool-fn from a registry.

   For 'server/tool' names: parses prefix, routes to correct connection,
   strips prefix before calling the MCP server.

   For unprefixed names: searches all connections. Errors if ambiguous."
  [registry]
  (let [catalog (:catalog registry)
        connections (:connections registry)]
    (fn [tool-name args]
      (u/trace ::registry-call-tool {:tool tool-name}
        (if-let [slash-idx (str/index-of tool-name "/")]
          ;; Namespaced: route to specific server
          (let [server (subs tool-name 0 slash-idx)
                bare-name (subs tool-name (inc slash-idx))
                conn (get connections server)]
            (if conn
              (call-tool conn bare-name args)
              (throw (ex-info (str "Unknown MCP server: " server)
                              {:server server :tool tool-name}))))
          ;; Unprefixed: find across all servers
          (let [matches (->> (vals catalog)
                             (filter #(= (:name %) tool-name)))]
            (case (count matches)
              0 (throw (ex-info (str "Unknown tool: " tool-name)
                                {:tool tool-name}))
              1 (call-tool (get connections (:server (first matches)))
                           tool-name args)
              (throw (ex-info (str "Ambiguous tool '" tool-name "' found on servers: "
                                   (str/join ", " (map :server matches))
                                   ". Use server/tool format.")
                              {:tool tool-name
                               :servers (mapv :server matches)})))))))))

(defn list-all-tools
  "List all tools in the registry with their prefixed names."
  [registry]
  (vals (:catalog registry)))

(defn close-all
  "Close all connections in a registry."
  [registry]
  (doseq [[_ conn] (:connections registry)]
    (close conn)))

(comment
  ;; Example: Create a static client with Langfuse tools
  (def conn (connect {:preset :langfuse}))
  (list-tools conn)

  ;; Example: Create an HTTP client
  (def http-conn (connect {:type :http
                           :url "https://langfuse-mcp.example.com"
                           :api-key "..."}))
  (list-tools http-conn)

  ;; Example: Multi-server registry
  (def registry (create-registry {"langfuse" (connect {:preset :langfuse})
                                   "exa" (connect {:preset :exa})}))
  (list-all-tools registry)
  (def multi-call (registry->call-tool-fn registry))
  ;; (multi-call "langfuse/searchLangfuseDocs" {:query "tracing"})
  ;; (multi-call "exa/web_search_exa" {:query "MCP protocol"})
  )
