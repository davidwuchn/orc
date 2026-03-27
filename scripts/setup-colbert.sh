#!/bin/bash
# Setup ColBERT Python environment for semantic search
#
# This script creates a dedicated Python virtual environment with RAGatouille
# and ColBERT dependencies for neural late-interaction retrieval.
#
# Usage:
#   ./scripts/setup-colbert.sh
#
# After setup, the ColBERT bridge can be started via the Clojure interface:
#   (require '[ai.obney.orc.colbert.interface :as colbert])
#   (colbert/ping)  ;; => {:status "ok"}

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
VENV_DIR="$PROJECT_ROOT/.venv-colbert"

echo "=== ColBERT Environment Setup ==="
echo ""

# Check Python version
if ! command -v python3 &> /dev/null; then
    echo "Error: python3 is required but not found"
    exit 1
fi

PYTHON_VERSION=$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')
echo "Found Python $PYTHON_VERSION"

# Create virtual environment
if [ -d "$VENV_DIR" ]; then
    echo "Virtual environment already exists at $VENV_DIR"
    read -p "Recreate? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        rm -rf "$VENV_DIR"
    else
        echo "Using existing environment"
    fi
fi

if [ ! -d "$VENV_DIR" ]; then
    echo "Creating virtual environment..."
    python3 -m venv "$VENV_DIR"
fi

# Activate and install dependencies
echo "Installing dependencies..."
source "$VENV_DIR/bin/activate"

pip install --upgrade pip

# Core dependencies for ColBERT/RAGatouille
pip install \
    "ragatouille>=0.0.9" \
    "torch>=2.0" \
    "sentence-transformers>=2.2.0"

echo ""
echo "=== Setup Complete ==="
echo ""
echo "ColBERT environment ready at: $VENV_DIR"
echo ""
echo "Test with:"
echo "  echo '{\"id\":1,\"method\":\"ping\",\"params\":{}}' | $VENV_DIR/bin/python $PROJECT_ROOT/scripts/colbert_bridge.py"
echo ""
echo "Or from Clojure REPL:"
echo "  (require '[ai.obney.orc.colbert.interface :as colbert])"
echo "  (colbert/ping)"
