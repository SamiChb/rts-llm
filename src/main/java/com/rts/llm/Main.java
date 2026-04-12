package com.rts.llm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Point d'entrée — RTS basé sur l'analyse LLM du code source.
 *
 * Usage :
 *   java -jar rts-llm.jar <chemin-projet> [options]
 *
 * Options :
 *   --provider openai|anthropic|ollama   (défaut: openai)
 *   --model <nom-du-modèle>             (défaut: gpt-4o-mini)
 *   --api-key <clé>                     (ou variable OPENAI_API_KEY / ANTHROPIC_API_KEY)
 *   --diff-from <commit>                (défaut: HEAD~1)
 *   --diff-to <commit>                  (défaut: HEAD)
 *   --include-stepdefs                  (inclut les step defs dans le prompt)
 *   --show-prompt                       (affiche le prompt envoyé au LLM)
 */
public class Main {

    public static void main(String[] args) {
        try {
            Config config = Config.parse(args);
            run(config);
        } catch (IllegalArgumentException e) {
            System.err.println("Erreur : " + e.getMessage());
            printUsage();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Erreur : " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static SelectionResult run(Config config) throws IOException {
        Path projectPath = config.projectPath;

        // ── 1. Extraire le diff ──
        System.out.println("1. Extraction du diff Git...");
        GitDiffExtractor diffExtractor = new GitDiffExtractor(projectPath);

        String diff;
        if (config.diffFrom != null && config.diffTo != null) {
            diff = diffExtractor.diffBetween(config.diffFrom, config.diffTo);
        } else {
            diff = diffExtractor.lastCommitDiff();
        }

        if (diff.isBlank()) {
            System.out.println("   Aucune modification Java détectée.");
            return new SelectionResult(List.of(), List.of(), "Aucune modification.");
        }

        int diffLines = diff.split("\n").length;
        System.out.println("   " + diffLines + " lignes de diff extraites.");

        // ── 2. Collecter les scénarios ──
        System.out.println("2. Collecte des scénarios BDD...");
        FeatureCollector collector = new FeatureCollector(projectPath);
        List<FeatureCollector.FeatureFile> features = collector.collectFeatures();
        List<FeatureCollector.StepDefFile> stepDefs = collector.collectStepDefs();

        // Construire la liste ordonnée de tous les scénarios
        List<String> allScenarios = new ArrayList<>();
        for (FeatureCollector.FeatureFile f : features) {
            allScenarios.addAll(f.scenarioNames());
        }

        System.out.println("   " + features.size() + " fichiers .feature");
        System.out.println("   " + allScenarios.size() + " scénarios trouvés");
        System.out.println("   " + stepDefs.size() + " step definitions");

        if (allScenarios.isEmpty()) {
            System.out.println("   Aucun scénario trouvé. Arrêt.");
            return new SelectionResult(List.of(), List.of(), "Aucun scénario.");
        }

        // ── 3. Construire le prompt ──
        System.out.println("3. Construction du prompt...");
        String prompt = PromptBuilder.build(diff, features, stepDefs, config.includeStepDefs);
        System.out.println("   Prompt : " + prompt.length() + " caractères");

        if (config.showPrompt) {
            System.out.println("\n── PROMPT ──────────────────────────────────");
            System.out.println(prompt);
            System.out.println("── FIN PROMPT ──────────────────────────────\n");
        }

        // ── 4. Appeler le LLM ──
        System.out.println("4. Appel au LLM (" + config.provider + " / " + config.model + ")...");
        LLMClient client = new LLMClient(config.provider, config.apiKey, config.model);
        String response = client.chat(prompt);

        if (config.showPrompt) {
            System.out.println("\n── RÉPONSE LLM ─────────────────────────────");
            System.out.println(response);
            System.out.println("── FIN RÉPONSE ─────────────────────────────\n");
        }

        // ── 5. Parser et afficher le résultat ──
        SelectionResult result = SelectionResult.parse(response, allScenarios);
        result.afficherRapport();

        return result;
    }

    // ── Configuration ──────────────────────────────────────

    public static class Config {
        Path projectPath;
        LLMClient.Provider provider = LLMClient.Provider.OPENAI;
        String model = "gpt-4o-mini";
        String apiKey;
        String diffFrom;
        String diffTo;
        boolean includeStepDefs = false;
        boolean showPrompt = false;

        public static Config parse(String[] args) {
            Config config = new Config();

            if (args.length == 0) {
                throw new IllegalArgumentException("Chemin du projet requis.");
            }

            config.projectPath = Path.of(args[0]).toAbsolutePath();

            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--provider" -> {
                        String p = args[++i].toUpperCase();
                        config.provider = LLMClient.Provider.valueOf(p);
                        // Ajuster le modèle par défaut selon le provider
                        if (config.model.equals("gpt-4o-mini")) {
                            config.model = switch (config.provider) {
                                case OPENAI -> "gpt-4o-mini";
                                case ANTHROPIC -> "claude-sonnet-4-20250514";
                                case OLLAMA -> "llama3";
                                case GEMINI -> "gemini-2.0-flash";
                            };
                        }
                    }
                    case "--model" -> config.model = args[++i];
                    case "--api-key" -> config.apiKey = args[++i];
                    case "--diff-from" -> config.diffFrom = args[++i];
                    case "--diff-to" -> config.diffTo = args[++i];
                    case "--include-stepdefs" -> config.includeStepDefs = true;
                    case "--show-prompt" -> config.showPrompt = true;
                    default -> throw new IllegalArgumentException(
                            "Option inconnue : " + args[i]);
                }
            }

            // Résoudre la clé API
            if (config.apiKey == null) {
                config.apiKey = switch (config.provider) {
                    case OPENAI -> System.getenv("OPENAI_API_KEY");
                    case ANTHROPIC -> System.getenv("ANTHROPIC_API_KEY");
                    case OLLAMA -> "not-needed";
                    case GEMINI -> System.getenv("GEMINI_API_KEY");
                };
            }

            if (config.apiKey == null && config.provider != LLMClient.Provider.OLLAMA) {
                String envVar = switch (config.provider) {
                    case OPENAI -> "OPENAI_API_KEY";
                    case ANTHROPIC -> "ANTHROPIC_API_KEY";
                    case GEMINI -> "GEMINI_API_KEY";
                    default -> "API_KEY";
                };
                throw new IllegalArgumentException(
                        "Clé API requise. Utilisez --api-key <clé> ou la variable " + envVar);
            }

            return config;
        }
    }

    private static void printUsage() {
        System.err.println("""

                Usage : java -jar rts-llm.jar <chemin-projet> [options]

                Options :
                  --provider openai|anthropic|ollama|gemini  (défaut: openai)
                  --model <nom>                      (défaut: gpt-4o-mini / claude-sonnet-4-20250514 / llama3 / gemini-2.0-flash)
                  --api-key <clé>                    (ou env OPENAI_API_KEY / ANTHROPIC_API_KEY / GEMINI_API_KEY)
                  --diff-from <commit>               (défaut: HEAD~1)
                  --diff-to <commit>                 (défaut: HEAD)
                  --include-stepdefs                 (inclut les step defs dans le prompt)
                  --show-prompt                      (affiche le prompt et la réponse)

                Exemples :
                  # Avec OpenAI, dernier commit
                  java -jar rts-llm.jar /mon/projet --api-key sk-xxx

                  # Avec Gemini (Google AI Studio)
                  java -jar rts-llm.jar /mon/projet --provider gemini --api-key AIza-xxx

                  # Avec Anthropic, entre deux commits
                  java -jar rts-llm.jar /mon/projet --provider anthropic --diff-from abc123 --diff-to def456

                  # Avec Ollama local (gratuit)
                  java -jar rts-llm.jar /mon/projet --provider ollama --model llama3
                """);
    }
}
