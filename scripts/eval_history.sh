#!/usr/bin/env bash
#
# Évalue la vérité terrain sur N commits historiques d'un projet cible.
#
# Pour chaque commit C de la plage demandée :
#   1. checkout C^ (parent)              — état AVANT le diff
#   2. mvn test-compile                  — peuple target/classes
#   3. run_per_scenario_coverage.sh      — un .exec par scénario (mode --no-pom-changes)
#   4. GroundTruthBuilder build          — coverage.json
#   5. checkout C
#   6. GroundTruthBuilder eval --csv     — calcule G pour le diff C^→C
#   7. agrège dans un CSV
#
# Le projet cible doit avoir un working tree propre. La branche courante est
# restaurée à la fin (même en cas d'interruption).
#
# Usage :
#   ./scripts/eval_history.sh <project-path> [options]
#
# Options :
#   --commits <ref>      Plage Git (ex: HEAD~10..HEAD).  Défaut : HEAD~5..HEAD
#   --workdir <dir>      Dossier de travail.             Défaut : ./eval-workdir
#   --output <file>      CSV de sortie.                  Défaut : ./eval_results.csv
#   --reuse-coverage     Saute la collecte si coverage.json existe déjà pour ce commit
#   --skip-non-java      Ignore les commits sans diff Java (sinon ils donnent G=∅)

set -euo pipefail

# ── Parsing ───────────────────────────────────────────────────────────────
COMMITS_RANGE="HEAD~5..HEAD"
WORKDIR="./eval-workdir"
OUTPUT="./eval_results.csv"
REUSE_COVERAGE=false
SKIP_NON_JAVA=false
PROJECT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --commits) COMMITS_RANGE="$2"; shift 2 ;;
    --workdir) WORKDIR="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    --reuse-coverage) REUSE_COVERAGE=true; shift ;;
    --skip-non-java) SKIP_NON_JAVA=true; shift ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    -*) echo "Option inconnue : $1" >&2; exit 1 ;;
    *) PROJECT="$1"; shift ;;
  esac
done

if [[ -z "$PROJECT" ]]; then
  echo "Usage : $0 <project-path> [options]" >&2
  exit 1
fi

PROJECT="$(cd "$PROJECT" && pwd)"
WORKDIR="$(realpath "$WORKDIR")"
OUTPUT="$(realpath "$OUTPUT")"

RTS_LLM_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RTS_LLM_JAR="$RTS_LLM_DIR/target/rts-llm-1.0-SNAPSHOT.jar"
COVERAGE_SCRIPT="$RTS_LLM_DIR/scripts/run_per_scenario_coverage.sh"

if [[ ! -f "$RTS_LLM_JAR" ]]; then
  echo "Jar manquant : $RTS_LLM_JAR — lance 'mvn package -DskipTests' dans rts-llm." >&2
  exit 1
fi

mkdir -p "$WORKDIR"

# ── Vérification du working tree du projet ───────────────────────────────
cd "$PROJECT"
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "Erreur : working tree non propre dans $PROJECT" >&2
  echo "Commit ou stash tes modifications avant de lancer ce script." >&2
  exit 1
fi
ORIGINAL_REF="$(git rev-parse --abbrev-ref HEAD)"
if [[ "$ORIGINAL_REF" == "HEAD" ]]; then
  ORIGINAL_REF="$(git rev-parse HEAD)"   # detached
fi
echo "→ Branche/commit initial : $ORIGINAL_REF"

# Restauration garantie en cas d'interruption
cleanup() {
  echo
  echo "→ Restauration du working tree sur $ORIGINAL_REF"
  cd "$PROJECT"
  git checkout -q "$ORIGINAL_REF" 2>/dev/null || true
}
trap cleanup EXIT

# ── Liste des commits à évaluer (du plus ancien au plus récent) ──────────
mapfile -t COMMITS < <(git log --reverse --format=%H "$COMMITS_RANGE")

if [[ ${#COMMITS[@]} -eq 0 ]]; then
  echo "Aucun commit dans la plage $COMMITS_RANGE" >&2
  exit 1
fi
echo "→ ${#COMMITS[@]} commit(s) à évaluer (plage : $COMMITS_RANGE)"

# ── En-tête CSV ──────────────────────────────────────────────────────────
if [[ ! -f "$OUTPUT" ]]; then
  echo "commit,modified_methods,gt_size,sel_size,tp,fp,fn,precision,recall,f1" > "$OUTPUT"
fi

# ── Boucle principale ────────────────────────────────────────────────────
total=${#COMMITS[@]}
idx=0
for C in "${COMMITS[@]}"; do
  idx=$((idx+1))
  short=$(git rev-parse --short "$C")
  echo
  echo "════════════════════════════════════════════════════════════"
  echo " Commit $idx/$total : $short"
  echo "════════════════════════════════════════════════════════════"
  git --no-pager log -1 --format='  %s%n  par %an, %ad' --date=short "$C"

  # Le commit a-t-il un parent ?
  if ! PARENT=$(git rev-parse --verify "${C}^" 2>/dev/null); then
    echo "  (sauté — pas de parent, premier commit)"
    continue
  fi

  # Diff Java seulement
  JAVA_DIFF=$(git diff "$PARENT" "$C" --name-only -- '*.java' | wc -l)
  if [[ "$JAVA_DIFF" -eq 0 && "$SKIP_NON_JAVA" == true ]]; then
    echo "  (sauté — aucune modification Java)"
    continue
  fi

  COMMIT_DIR="$WORKDIR/$short"
  COVERAGE_JSON="$COMMIT_DIR/coverage.json"
  COVERAGE_DIR="$COMMIT_DIR/coverage-per-scenario"
  mkdir -p "$COMMIT_DIR"

  if [[ "$REUSE_COVERAGE" == true && -f "$COVERAGE_JSON" ]]; then
    echo "  → couverture réutilisée : $COVERAGE_JSON"
  else
    # 1. Checkout du parent
    echo "  → checkout $PARENT (parent)"
    git checkout -q "$PARENT"

    # 2. Compilation (nécessaire pour target/classes)
    echo "  → mvn test-compile"
    if ! mvn -q test-compile > "$COMMIT_DIR/build.log" 2>&1; then
      echo "    (compilation échouée — commit ignoré, voir $COMMIT_DIR/build.log)"
      continue
    fi

    # 3. Couverture par scénario
    echo "  → collecte de la couverture par scénario..."
    if ! "$COVERAGE_SCRIPT" --no-pom-changes "$PROJECT" "$COVERAGE_DIR" \
          > "$COMMIT_DIR/coverage.log" 2>&1; then
      echo "    (collecte de couverture échouée — voir $COMMIT_DIR/coverage.log)"
      continue
    fi
    nb=$(ls "$COVERAGE_DIR"/sc_*.exec 2>/dev/null | wc -l)
    echo "    $nb scénario(s) couvert(s)"

    # 4. Build de la map JSON
    echo "  → construction de coverage.json"
    CLASSES_DIRS=("$PROJECT/target/classes")
    [[ -d "$PROJECT/target/test-classes" ]] && CLASSES_DIRS+=("$PROJECT/target/test-classes")
    java -cp "$RTS_LLM_JAR" com.rts.llm.GroundTruthBuilder build \
        "$COVERAGE_DIR" "${CLASSES_DIRS[@]}" "$COVERAGE_JSON" \
        > "$COMMIT_DIR/build_json.log" 2>&1
  fi

  # 5. Checkout du commit (pour appliquer le diff parent→C)
  echo "  → checkout $short"
  git checkout -q "$C"

  # 6. Calcul de la vérité terrain en mode CSV
  echo "  → calcul de la vérité terrain"
  java -cp "$RTS_LLM_JAR" com.rts.llm.GroundTruthBuilder eval \
      "$COVERAGE_JSON" "$PROJECT" \
      --diff-from "$PARENT" --diff-to "$C" \
      --csv --commit "$short" \
      | tee -a "$OUTPUT" \
      | tail -1

done

echo
echo "════════════════════════════════════════════════════════════"
echo " Évaluation terminée"
echo " Résultats : $OUTPUT"
echo " Workdir   : $WORKDIR"
echo "════════════════════════════════════════════════════════════"
