#!/usr/bin/env bash
#
# Génère un fichier jacoco.exec par scénario Cucumber dans le projet cible.
# Stratégie : un fork JVM par scénario (simple et isolé).
#
# Deux modes :
#   1. Par défaut : suppose que le projet cible a le plugin jacoco-maven-plugin
#      configuré dans son pom.xml.
#   2. Avec --no-pom-changes : injecte l'agent JaCoCo via -DargLine, sans
#      toucher au pom du projet cible. Utile pour évaluer sur l'historique
#      Git sans polluer les commits.
#
# Usage :
#   ./scripts/run_per_scenario_coverage.sh [--no-pom-changes] \
#       <project-path> [output-dir]
#
# Sortie :
#   <output-dir>/sc_001.exec, sc_002.exec, ...
#   <output-dir>/index.txt   (id|nom-du-scenario)

set -euo pipefail

JACOCO_VERSION="0.8.11"
NO_POM_CHANGES=false

# ── Parsing des options ───────────────────────────────────────────────────
POSITIONAL=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-pom-changes) NO_POM_CHANGES=true; shift ;;
    -h|--help)
      sed -n '2,20p' "$0"; exit 0 ;;
    --) shift; while [[ $# -gt 0 ]]; do POSITIONAL+=("$1"); shift; done ;;
    -*) echo "Option inconnue : $1" >&2; exit 1 ;;
    *)  POSITIONAL+=("$1"); shift ;;
  esac
done

if [[ ${#POSITIONAL[@]} -lt 1 ]]; then
  echo "Usage : $0 [--no-pom-changes] <project-path> [output-dir]" >&2
  exit 1
fi

PROJECT="$(cd "${POSITIONAL[0]}" && pwd)"
OUT_DIR="${POSITIONAL[1]:-$PROJECT/coverage-per-scenario}"

mkdir -p "$OUT_DIR"
INDEX="$OUT_DIR/index.txt"
: > "$INDEX"

# ── Préparation de l'agent JaCoCo (mode --no-pom-changes) ─────────────────
JACOCO_AGENT=""
if $NO_POM_CHANGES; then
  JACOCO_AGENT="$HOME/.m2/repository/org/jacoco/org.jacoco.agent/${JACOCO_VERSION}/org.jacoco.agent-${JACOCO_VERSION}-runtime.jar"
  if [[ ! -f "$JACOCO_AGENT" ]]; then
    echo "→ téléchargement de l'agent JaCoCo $JACOCO_VERSION..."
    mvn -q dependency:get \
        -Dartifact="org.jacoco:org.jacoco.agent:${JACOCO_VERSION}:jar:runtime" \
        -Dtransitive=false
  fi
  if [[ ! -f "$JACOCO_AGENT" ]]; then
    echo "Échec : impossible de récupérer l'agent JaCoCo en $JACOCO_AGENT" >&2
    exit 1
  fi
  echo "→ agent JaCoCo : $JACOCO_AGENT"

  # Sanity check : avertir si le pom utilise déjà <argLine> (conflit possible)
  if grep -q "<argLine>" "$PROJECT/pom.xml" 2>/dev/null; then
    echo "  ⚠ ATTENTION : <argLine> détecté dans pom.xml — conflit possible." >&2
    echo "    Inspecte le pom et combine manuellement si besoin." >&2
  fi
fi

# ── Découverte des scénarios ─────────────────────────────────────────────
SEARCH_DIRS=()
[[ -d "$PROJECT/src/test/resources" ]] && SEARCH_DIRS+=("$PROJECT/src/test/resources")
[[ -d "$PROJECT/src/test" ]] && SEARCH_DIRS+=("$PROJECT/src/test")

if [[ ${#SEARCH_DIRS[@]} -eq 0 ]]; then
  echo "Aucun répertoire src/test trouvé sous $PROJECT" >&2
  exit 1
fi

mapfile -t SCENARIOS < <(
  grep -rhE "^[[:space:]]*Scenario( Outline)?:" "${SEARCH_DIRS[@]}" 2>/dev/null \
    | sed -E 's/^[[:space:]]*Scenario( Outline)?:[[:space:]]*//' \
    | sed -E 's/[[:space:]]+$//'
)

if [[ ${#SCENARIOS[@]} -eq 0 ]]; then
  echo "Aucun scénario trouvé dans les .feature de $PROJECT" >&2
  exit 1
fi

echo "→ ${#SCENARIOS[@]} scénarios détectés dans $PROJECT"
echo "→ couverture écrite dans $OUT_DIR"
echo

# ── Boucle scénario par scénario ─────────────────────────────────────────
i=0
ok=0
ko=0
for sc in "${SCENARIOS[@]}"; do
  i=$((i+1))
  id=$(printf "sc_%03d" "$i")

  # Échappe les méta-caractères regex (cucumber.filter.name est une regex Java)
  escaped=$(printf '%s' "$sc" | sed -E 's/[][\\.*^$()+?{}|]/\\&/g')

  printf '[%s] %s\n' "$id" "$sc"

  # Reset de l'exec global pour repartir d'un état vierge
  rm -f "$PROJECT/target/jacoco.exec"

  # Construction de la commande mvn
  MVN_OPTS=(
    -q test
    -Dcucumber.filter.name="^${escaped}\$"
    -Dsurefire.failIfNoSpecifiedTests=false
    -DfailIfNoTests=false
  )

  if $NO_POM_CHANGES; then
    # Injecte l'agent JaCoCo directement dans la JVM des tests Surefire.
    # destfile pointe vers target/jacoco.exec pour rester compatible avec le mode 1.
    MVN_OPTS+=(
      "-DargLine=-javaagent:${JACOCO_AGENT}=destfile=${PROJECT}/target/jacoco.exec,append=false"
    )
  else
    MVN_OPTS+=(-Djacoco.append=false)
  fi

  pushd "$PROJECT" > /dev/null
  if mvn "${MVN_OPTS[@]}" > "$OUT_DIR/${id}.log" 2>&1; then
    : # ok
  else
    echo "  (test en échec — on garde quand même la couverture)" >&2
  fi
  popd > /dev/null

  if [[ -f "$PROJECT/target/jacoco.exec" ]]; then
    mv "$PROJECT/target/jacoco.exec" "$OUT_DIR/${id}.exec"
    printf '%s|%s\n' "$id" "$sc" >> "$INDEX"
    ok=$((ok+1))
  else
    echo "  (avertissement) pas de jacoco.exec produit, scénario ignoré" >&2
    ko=$((ko+1))
  fi
done

echo
echo "Terminé : $ok réussi(s), $ko sans couverture sur $i scénario(s)."
echo "Index   : $INDEX"
echo "Logs    : $OUT_DIR/sc_*.log"
