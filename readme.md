# Workshop Template

ObneyAI Application Template using Polylith architecture.

## Documentation

- [Polylith high-level documentation](https://polylith.gitbook.io/polylith)
- [poly tool documentation](https://cljdoc.org/d/polylith/clj-poly/CURRENT)
- [RealWorld example app](https://github.com/furkan3ayraktar/clojure-polylith-realworld-example-app)
- [Polylith Slack](https://clojurians.slack.com/archives/C013B7MQHJQ)

## Dependencies

- Clojure
- Babashka (optional)
- NPM, Node
- Python 3.13+ (with UV)

## Setup

### Python

#### Install the venv

```bash
uv venv --python 3.13 .venv
```

#### Activate venv

```bash
source .venv/bin/activate
```

#### Install deps

```bash
uv pip install -r requirements.txt
```

### JavaScript

```bash
cd ui/web-app && npm install
```

## Running the Application

### Frontend

```bash
cd ui/web-app && npm run dev
```

### Backend

1. `docker-compose up -d`
2. Start REPL with dev alias
3. Eval `development/src/repl_stuff.clj`
4. Eval the `do` form on line 10

### Start nREPL

Run the nREPL script with dev and test aliases:

```bash
./scripts/nrepl.sh        # starts on port 7888 (default)
./scripts/nrepl.sh 8888   # custom port
```

Includes cider-nrepl and refactor-nrepl middleware for IDE integration.

## Development

### Create a new Grain Service Component

1. `bb scripts/create_component.bb <your-component-name>`
2. Include your component in the root level `deps.edn` file
