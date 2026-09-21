#!/usr/bin/env bash
#
# Renders every docs/*.puml diagram to a sibling PNG.
#
# Downloads a pinned PlantUML release into a cache directory if it isn't already
# there — nothing is installed globally. Graphviz (`dot`) must be on the PATH.
#
# Usage:  ./docs/render-diagrams.sh

set -euo pipefail

PLANTUML_VERSION="1.2026.0"
DOCS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE_DIR="${TMPDIR:-/tmp}"
PLANTUML_JAR="${CACHE_DIR%/}/plantuml-${PLANTUML_VERSION}.jar"
PLANTUML_URL="https://github.com/plantuml/plantuml/releases/download/v${PLANTUML_VERSION}/plantuml-${PLANTUML_VERSION}.jar"

command -v java > /dev/null || { echo "java not found on the PATH" >&2; exit 1; }
command -v dot > /dev/null || { echo "graphviz 'dot' not found on the PATH (brew install graphviz)" >&2; exit 1; }

if [[ ! -f "$PLANTUML_JAR" ]]; then
  echo "Downloading PlantUML ${PLANTUML_VERSION} to ${PLANTUML_JAR}"
  curl -sSLf -o "$PLANTUML_JAR" "$PLANTUML_URL"
else
  echo "Using cached PlantUML ${PLANTUML_VERSION} at ${PLANTUML_JAR}"
fi

shopt -s nullglob
PUML_FILES=("$DOCS_DIR"/*.puml)
shopt -u nullglob

if [[ ${#PUML_FILES[@]} -eq 0 ]]; then
  echo "No .puml files found in ${DOCS_DIR}" >&2
  exit 1
fi

echo "Rendering ${#PUML_FILES[@]} diagram(s)..."
java -jar "$PLANTUML_JAR" -tpng -failfast2 "${PUML_FILES[@]}"

echo
echo "Produced:"
for puml in "${PUML_FILES[@]}"; do
  png="${puml%.puml}.png"
  if [[ -f "$png" ]]; then
    echo "  $(basename "$png") — $(file -b "$png")"
  else
    echo "  $(basename "$png") — MISSING" >&2
    exit 1
  fi
done