# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Clojure/ClojureScript full-stack application using the **Polylith** architecture with the **Grain** framework for event sourcing. The top-level namespace is `ai.obney.workshop`.

## Build & Development Commands

### Backend (Clojure)

```bash
# Start nREPL server (port 7888 default)
./scripts/nrepl.sh
./scripts/nrepl.sh 8888   # custom port

# Start services via Docker (PostgreSQL + LocalStack)
docker-compose up -d

# Start the backend service
# 1. Connect to REPL
# 2. Evaluate development/src/repl_stuff.clj
# 3. Evaluate the `do` form on line 10 to start

# Create a new Grain service component
bb scripts/create_component.bb <component-name>

# Run Polylith commands
clojure -M:poly <command>
```

### Frontend (ClojureScript)

```bash
cd ui/web-app

npm install              # Install dependencies
npm run dev              # Development server (port 8080)
npm run build:dev        # Build for local backend
npm run build:staging    # Build for staging
npm run build:prod       # Build for production
```

## Architecture

### Polylith Structure

- **bases/**: Entry points (web-api)
- **components/**: Business logic modules with interface/core separation
- **projects/**: Deployable artifacts
- **development/**: REPL development environment
- **ui/web-app/**: ClojureScript frontend

### Grain Framework (Event Sourcing)

The backend uses Grain, an event-sourcing framework. Key concepts:

- **Commands**: Validate and emit events (`defcommand`)
- **Queries**: Read from event-sourced read models (`defquery`)
- **Events**: Immutable facts created with `->event`
- **Read Models**: Reduce events into queryable state (multimethod + reducer)
- **Todo Processors**: React to events and emit follow-up events
- **Schemas**: Malli schemas registered with `defschemas`

### Component Pattern

Each service component follows this structure:
```
components/service-name/
├── src/ai/obney/workshop/service_name/
│   ├── interface.clj           # Public API
│   ├── interface/schemas.clj   # Malli schemas
│   └── core/
│       ├── commands.clj        # Command handlers
│       ├── queries.clj         # Query handlers
│       ├── read_models.clj     # Event projections
│       └── todo_processors.clj # Event reactions
```

### Frontend Architecture

- **UIx**: React wrapper for ClojureScript
- **si-frame/re-frame**: State management
- **ShadCN**: UI components (compiled from TypeScript to `/gen/`)
- **Reitit**: Client-side routing

Frontend store structure:
```
ui/web-app/src/store/feature/
├── events.cljs   # Event handlers (reg-event-db, reg-event-fx)
├── subs.cljs     # Subscriptions (reg-sub)
└── effects.cljs  # Side effects (reg-fx, API calls)
```

## Key Patterns

### Command Handler
```clojure
(defcommand :namespace command-name
  [{{:keys [field]} :command :keys [event-store]}]
  (if (valid?)
    {:command-result/events [(->event {:type :ns/event-type :tags #{[:entity id]} :body {...}})]}
    {::anom/category ::anom/not-found ::anom/message "..."}))
```

### Query Handler
```clojure
(defquery :namespace query-name
  [{{:keys [id]} :query :keys [event-store]}]
  {:query/result (rm/get-entity event-store id)})
```

### Read Model
```clojure
(defmulti entities* (fn [_state event] (:event/type event)))
(defmethod entities* :ns/created [state event] (assoc state (:id event) {...}))
(defmethod entities* :default [state _] state)
(defn entities [init events] (reduce entities* init events))
```

### Frontend Component
```clojure
(defui component [{:keys [current-match]}]
  (let [data (use-subscribe [::subs/data])
        ctx (context/use-context)]
    (use-effect (fn [] (rf/dispatch [::events/load (:api/client ctx)]) js/undefined) [])
    ($ :div {:class "..."} ...)))
```

## Environment

LocalStack emulates AWS services (KMS, S3) locally. Configure in `config.edn`:
- `:localstack/enabled true` - Use LocalStack
- `:localstack/endpoint "http://localhost:4566"` - LocalStack URL

## Available Claude Code Skills

Use `/skill-name` to invoke:
- `/grain-command-handler` - Create command handlers
- `/grain-query-handler` - Create query handlers
- `/grain-read-model` - Create read models
- `/grain-schema` - Define Malli schemas
- `/grain-todo-processor` - Create event reactors
- `/grain-ui-component` - Create UIx components
- `/grain-reframe` - Create re-frame events/subs/effects
- `/nrepl-connect` - Connect to running nREPL on port 7888
