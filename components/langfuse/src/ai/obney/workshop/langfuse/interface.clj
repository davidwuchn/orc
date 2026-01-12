(ns ai.obney.workshop.langfuse.interface
  "Langfuse tracing interface for observability."
  (:require [ai.obney.workshop.langfuse.core :as core]))

(def create-client
  "Create a Langfuse client from config or environment variables."
  core/create-client)

(def ingestion
  "Send events to Langfuse ingestion API."
  core/ingestion)
