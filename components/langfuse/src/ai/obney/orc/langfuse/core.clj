(ns ai.obney.orc.langfuse.core
  "Langfuse client for sending trace, span, and generation events."
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]))

(defn ingestion
  "Send events to Langfuse ingestion API.

   client - Map with :host, :public-key, :secret-key
   events - Vector of event maps with :id, :timestamp, :type, :body

   Event types:
   - trace-create: Create or update a trace
   - span-create: Create a span within a trace
   - generation-create: Create an AI generation event"
  [{:keys [host public-key secret-key] :as _client}
   events]
  (when (and host public-key secret-key (seq events))
    (let [resp (http/post
                (str host "/api/public/ingestion")
                {:basic-auth [public-key secret-key]
                 :content-type :json
                 :body (json/write-str {:batch events})
                 :throw-exceptions false})]
      (if (< (:status resp) 300)
        {:success true :body (:body resp)}
        {:success false :status (:status resp) :body (:body resp)}))))

(defn create-client
  "Create a Langfuse client from config or environment variables.

   Config keys:
   - :host - Langfuse host (default: https://cloud.langfuse.com)
   - :public-key - Public API key (or LANGFUSE_PUBLIC_KEY env var)
   - :secret-key - Secret API key (or LANGFUSE_SECRET_KEY env var)"
  ([]
   (create-client {}))
  ([{:keys [host public-key secret-key]}]
   {:host (or host (System/getenv "LANGFUSE_HOST") "https://cloud.langfuse.com")
    :public-key (or public-key (System/getenv "LANGFUSE_PUBLIC_KEY"))
    :secret-key (or secret-key (System/getenv "LANGFUSE_SECRET_KEY"))}))
