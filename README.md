# RTS-LLM — Sélection de tests de régression par analyse LLM du code source

Outil de sélection automatique de tests BDD à ré-exécuter, basé sur l'analyse des modifications de code par un LLM.

## Principe

Quand un développeur fait un commit, l'outil :

1. Extrait le **diff Git** (lignes de code modifiées)
2. Collecte tous les **scénarios BDD** (.feature) du projet
3. Envoie le tout à un **LLM** qui raisonne sur l'impact
4. Retourne la liste des **scénarios à retester** avec une justification

```
git diff HEAD~1 ──► ┌─────────────┐     ┌──────────────────────┐
                    │  Prompt     │────►│  LLM (GPT/Claude/    │
.feature files ──►  │  Builder    │     │  Ollama)             │
                    └─────────────┘     └──────────┬───────────┘
*Steps.java ───►                                   │
  (optionnel)                                      ▼
                                        {"selected": [1,3],
                                         "reasoning": "..."}
```

## Prérequis

- Java 17+
- Maven 3.8+
- Un LLM : clé API OpenAI/Anthropic **ou** Ollama installé localement

## Compilation

```bash
mvn clean package -DskipTests
```

## Utilisation

### Avec OpenAI (le plus simple)

```bash
export OPENAI_API_KEY="sk-votre-clé"
java -jar target/rts-llm-1.0-SNAPSHOT.jar /chemin/vers/projet-bdd
```

### Avec Anthropic (Claude)

```bash
export ANTHROPIC_API_KEY="sk-ant-votre-clé"
java -jar target/rts-llm-1.0-SNAPSHOT.jar /chemin/vers/projet-bdd --provider anthropic
```

### Avec Ollama (gratuit, local)

```bash
# D'abord lancer Ollama
ollama run llama3

# Puis
java -jar target/rts-llm-1.0-SNAPSHOT.jar /chemin/vers/projet-bdd --provider ollama
```

### Options

| Option | Description | Défaut |
|--------|-------------|--------|
| `--provider` | openai, anthropic, ollama | openai |
| `--model` | Nom du modèle | gpt-4o-mini / claude-sonnet-4-20250514 / llama3 |
| `--api-key` | Clé API | Variable d'environnement |
| `--diff-from` | Commit de départ | HEAD~1 |
| `--diff-to` | Commit d'arrivée | HEAD |
| `--include-stepdefs` | Inclut les step defs dans le prompt | false |
| `--show-prompt` | Affiche le prompt et la réponse LLM | false |

### Exemples

```bash
# Analyser les 3 derniers commits
java -jar target/rts-llm-1.0-SNAPSHOT.jar . --diff-from HEAD~3

# Voir le prompt envoyé au LLM (debug)
java -jar target/rts-llm-1.0-SNAPSHOT.jar . --show-prompt

# Inclure les step definitions pour plus de contexte
java -jar target/rts-llm-1.0-SNAPSHOT.jar . --include-stepdefs

# Comparer deux branches
java -jar target/rts-llm-1.0-SNAPSHOT.jar . --diff-from main --diff-to feature/import
```

## Sortie

```
1. Extraction du diff Git...
   42 lignes de diff extraites.
2. Collecte des scénarios BDD...
   3 fichiers .feature
   8 scénarios trouvés
   3 step definitions
3. Construction du prompt...
   Prompt : 2847 caractères
4. Appel au LLM (OPENAI / gpt-4o-mini)...

═══════════════════════════════════════════════════
  SÉLECTION RTS (analyse LLM du code source)
═══════════════════════════════════════════════════

  Scénarios à retester : 2
    [1] Import task configurations from file
    [2] Import proxy servers from file

  Justification LLM :
    La méthode importFrom() de FileImporter a été modifiée
    pour filtrer les lignes vides et trimmer les URLs. Cela
    impacte les scénarios 1 et 2 qui utilisent l'import de
    fichiers. Le scénario de crawl n'est pas affecté.

═══════════════════════════════════════════════════
```

## Architecture

```
com.rts.llm/
├── Main.java               Point d'entrée + CLI
├── GitDiffExtractor.java    Exécute git diff
├── FeatureCollector.java    Lit les .feature et *Steps.java
├── PromptBuilder.java       Construit le prompt pour le LLM
├── LLMClient.java           Appelle l'API (OpenAI/Anthropic/Ollama)
└── SelectionResult.java     Parse la réponse JSON du LLM
```

## Différence avec l'approche TF-IDF classique

| Aspect | TF-IDF (papier Xu 2021) | LLM (ce projet) |
|--------|------------------------|------------------|
| Entrée | Nouveau scénario | Diff de code réel |
| Analyse | Similarité lexicale | Raisonnement sémantique |
| Comprend les synonymes | Non | Oui |
| Comprend la logique métier | Non | Oui |
| Déterministe | Oui | Quasi (temperature=0.1) |
| Coût | Gratuit | API payante ou GPU local |
| Offline | Oui | Ollama uniquement |
