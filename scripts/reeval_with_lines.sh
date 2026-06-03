#!/usr/bin/env bash
#
# Ré-évalue les commits de l'eval-workdir contre la GT par lignes.
# Par défaut, réutilise les selection.txt existants (pas de LLM).
# Avec --run-tool, relance RTS-LLM (optionnellement avec --static-prune).
#
# Usage :
#   ./scripts/reeval_with_lines.sh <project-path> --output <file.csv> [options]
#
# Options :
#   --workdir <dir>      Défaut : ./eval-workdir
#   --output <file>      Défaut : ./eval_lines_reeval.csv
#   --run-tool           Relance RTS-LLM pour chaque commit
#   --provider <p>       ollama|openai|...
#   --model <m>          Modèle LLM
#   --static-prune       Active le pipeline hybride

set -euo pipefail

PROJECT=""
WORKDIR="./eval-workdir"
OUTPUT="./eval_lines_reeval.csv"
RUN_TOOL=false
PROVIDER=""
MODEL=""
STATIC_PRUNE=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --workdir)     WORKDIR="$2";   shift 2 ;;
    --output)      OUTPUT="$2";    shift 2 ;;
    --run-tool)    RUN_TOOL=true;  shift ;;
    --provider)    PROVIDER="$2";  shift 2 ;;
    --model)       MODEL="$2";     shift 2 ;;
    --static-prune) STATIC_PRUNE=true; shift ;;
    -*) echo "Option inconnue : $1" >&2; exit 1 ;;
    *) PROJECT="$1"; shift ;;
  esac
done

if [[ -z "$PROJECT" ]]; then
  echo "Usage : $0 <project-path> [--output file.csv] [--workdir dir]" >&2; exit 1
fi

PROJECT="$(cd "$PROJECT" && pwd)"
WORKDIR="$(realpath "$WORKDIR")"
OUTPUT="$(realpath "$OUTPUT")"
RTS_LLM_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RTS_LLM_JAR="$RTS_LLM_DIR/target/rts-llm-1.0-SNAPSHOT.jar"

if [[ ! -f "$RTS_LLM_JAR" ]]; then
  echo "Jar manquant : $RTS_LLM_JAR" >&2; exit 1
fi

echo "commit,modified_lines,gt_size,sel_size,tp,fp,fn,precision,recall,f1" > "$OUTPUT"

total=$(ls -d "$WORKDIR"/*/ 2>/dev/null | wc -l)
idx=0

for COMMIT_DIR in "$WORKDIR"/*/; do
  short=$(basename "$COMMIT_DIR")
  idx=$((idx+1))

  COVERAGE_JSON="$COMMIT_DIR/coverage.json"
  SELECTION_FILE="$COMMIT_DIR/selection.txt"

  if [[ ! -f "$COVERAGE_JSON" ]]; then
    echo "[$idx/$total] $short — pas de coverage.json, ignoré"
    continue
  fi

  # Vérifier que le coverage.json a des données lignes
  if ! python3 -c "
import json, sys
d = json.load(open('$COVERAGE_JSON'))
has_lines = any(v.get('lines') for v in d.values())
sys.exit(0 if has_lines else 1)
" 2>/dev/null; then
    echo "[$idx/$total] $short — coverage.json sans données lignes, ignoré"
    continue
  fi

  # Résoudre le commit complet et son parent
  FULL_COMMIT=$(cd "$PROJECT" && git rev-parse "$short" 2>/dev/null || true)
  if [[ -z "$FULL_COMMIT" ]]; then
    echo "[$idx/$total] $short — commit introuvable, ignoré"
    continue
  fi
  PARENT=$(cd "$PROJECT" && git rev-parse "${FULL_COMMIT}^" 2>/dev/null || true)
  if [[ -z "$PARENT" ]]; then
    echo "[$idx/$total] $short — pas de parent, ignoré"
    continue
  fi

  # Optionnel : relancer RTS-LLM
  if [[ "$RUN_TOOL" == true ]]; then
    # Checkout du commit pour que le diff soit accessible
    (cd "$PROJECT" && git checkout -q "$FULL_COMMIT" 2>/dev/null)
    TOOL_OPTS=(--diff-from "$PARENT" --diff-to "$FULL_COMMIT" --selection-only)
    [[ -n "$PROVIDER" ]] && TOOL_OPTS+=(--provider "$PROVIDER")
    [[ -n "$MODEL"    ]] && TOOL_OPTS+=(--model "$MODEL")
    [[ "$STATIC_PRUNE" == true ]] && TOOL_OPTS+=(--static-prune)
    java -jar "$RTS_LLM_JAR" "$PROJECT" "${TOOL_OPTS[@]}" \
        > "$SELECTION_FILE" 2> "$COMMIT_DIR/tool_lines.log" || true
    (cd "$PROJECT" && git checkout -q master 2>/dev/null || true)
  fi

  # Construire l'argument --selection depuis selection.txt
  # Toujours passer --selection (même vide) pour que fn=gt_size quand sel=0
  if [[ -f "$SELECTION_FILE" && -s "$SELECTION_FILE" ]]; then
    sel=$(paste -sd ';' "$SELECTION_FILE")
  else
    sel=""
  fi
  SELECTION_ARG=(--selection "$sel")

  # Calcul GT lignes + métriques
  printf "[$idx/$total] %-10s → " "$short"
  result=$(java -cp "$RTS_LLM_JAR" com.rts.llm.GroundTruthBuilder eval \
      "$COVERAGE_JSON" "$PROJECT" \
      --diff-from "$PARENT" --diff-to "$FULL_COMMIT" \
      --csv --commit "$short" \
      "${SELECTION_ARG[@]}" 2>/dev/null)

  echo "$result" | tee -a "$OUTPUT"
done

echo
echo "════════════════════════════════════════════════════════════"
echo " Ré-évaluation terminée → $OUTPUT"
echo "════════════════════════════════════════════════════════════"
