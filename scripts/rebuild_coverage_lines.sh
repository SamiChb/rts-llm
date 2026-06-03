#!/usr/bin/env bash
#
# Recrée les coverage.json avec données de couverture par lignes.
#
# Pour chaque commit dans eval-workdir :
#   1. Checkout du parent (C^) dans le projet cible
#   2. mvn test-compile
#   3. GroundTruthBuilder build → coverage.json avec champ "lines"
#   4. Restauration du checkout
#
# Usage :
#   ./scripts/rebuild_coverage_lines.sh <project-path> [--workdir <dir>]

set -euo pipefail

PROJECT=""
WORKDIR="./eval-workdir"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --workdir) WORKDIR="$2"; shift 2 ;;
    -*) echo "Option inconnue : $1" >&2; exit 1 ;;
    *) PROJECT="$1"; shift ;;
  esac
done

if [[ -z "$PROJECT" ]]; then
  echo "Usage : $0 <project-path> [--workdir <dir>]" >&2
  exit 1
fi

PROJECT="$(cd "$PROJECT" && pwd)"
WORKDIR="$(realpath "$WORKDIR")"
RTS_LLM_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RTS_LLM_JAR="$RTS_LLM_DIR/target/rts-llm-1.0-SNAPSHOT.jar"

DEFAULT_SKIP_FLAGS="-Drat.skip=true -Dcheckstyle.skip=true -Denforcer.skip=true \
-Dspotbugs.skip=true -Dpmd.skip=true -Dcpd.skip=true \
-Dmaven.javadoc.skip=true -Danimal.sniffer.skip=true \
-Dgit.commit.id.skip=true -Dmaven.gitcommitid.skip=true"

if [[ ! -f "$RTS_LLM_JAR" ]]; then
  echo "Jar manquant : $RTS_LLM_JAR" >&2; exit 1
fi

cd "$PROJECT"
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "Erreur : working tree non propre dans $PROJECT" >&2; exit 1
fi
ORIGINAL_REF="$(git rev-parse --abbrev-ref HEAD)"
[[ "$ORIGINAL_REF" == "HEAD" ]] && ORIGINAL_REF="$(git rev-parse HEAD)"

cleanup() {
  echo; echo "→ Restauration sur $ORIGINAL_REF"
  cd "$PROJECT"; git checkout -q "$ORIGINAL_REF" 2>/dev/null || true
}
trap cleanup EXIT

total=$(ls -d "$WORKDIR"/*/ 2>/dev/null | wc -l)
idx=0

for COMMIT_DIR in "$WORKDIR"/*/; do
  short=$(basename "$COMMIT_DIR")
  idx=$((idx+1))

  COVERAGE_DIR="$COMMIT_DIR/coverage-per-scenario"
  COVERAGE_JSON="$COMMIT_DIR/coverage.json"

  if [[ ! -d "$COVERAGE_DIR" ]] || [[ -z "$(ls "$COVERAGE_DIR"/*.exec 2>/dev/null)" ]]; then
    echo "[$idx/$total] $short — pas de fichiers .exec, ignoré"
    continue
  fi

  # Résoudre le commit complet depuis son short hash
  FULL_COMMIT=$(cd "$PROJECT" && git rev-parse "$short" 2>/dev/null || true)
  if [[ -z "$FULL_COMMIT" ]]; then
    echo "[$idx/$total] $short — commit introuvable dans $PROJECT, ignoré"
    continue
  fi

  PARENT=$(cd "$PROJECT" && git rev-parse "${FULL_COMMIT}^" 2>/dev/null || true)
  if [[ -z "$PARENT" ]]; then
    echo "[$idx/$total] $short — pas de parent (premier commit), ignoré"
    continue
  fi

  echo
  echo "════════ [$idx/$total] $short (parent: ${PARENT:0:7}) ════════"

  # 1. Checkout du parent
  git checkout -q "$PARENT"
  git clean -fdq -e "*.csv" 2>/dev/null || true

  # 2. Compilation
  echo "  → mvn test-compile"
  if ! mvn -q test-compile $DEFAULT_SKIP_FLAGS \
        > "$COMMIT_DIR/rebuild_build.log" 2>&1; then
    echo "    (compilation échouée — voir $COMMIT_DIR/rebuild_build.log)"
    continue
  fi

  # 3. GroundTruthBuilder build (remplace coverage.json)
  echo "  → GroundTruthBuilder build"
  CLASSES_DIRS=("$PROJECT/target/classes")
  [[ -d "$PROJECT/target/test-classes" ]] && CLASSES_DIRS+=("$PROJECT/target/test-classes")

  if java -cp "$RTS_LLM_JAR" com.rts.llm.GroundTruthBuilder build \
      "$COVERAGE_DIR" "${CLASSES_DIRS[@]}" "$COVERAGE_JSON" \
      > "$COMMIT_DIR/rebuild_json.log" 2>&1; then
    echo "  → coverage.json mis à jour avec données lignes"
  else
    echo "  (échec build JSON — voir $COMMIT_DIR/rebuild_json.log)"
  fi
done

echo
echo "════════════════════════════════════════════════════════════"
echo " Reconstruction terminée"
echo "════════════════════════════════════════════════════════════"
