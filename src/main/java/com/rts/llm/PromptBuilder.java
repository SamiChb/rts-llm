package com.rts.llm;

import java.util.List;

/**
 * Construit le prompt envoyé au LLM à partir du diff et des scénarios.
 */
public class PromptBuilder {

    /**
     * Construit le prompt complet.
     *
     * @param diff       le diff Git (code modifié)
     * @param features   les fichiers .feature du projet
     * @param stepDefs   les step definitions (optionnel, peut être vide)
     * @param includeStepDefs  si true, inclut le code des step defs dans le prompt
     */
    public static String build(String diff,
                               List<FeatureCollector.FeatureFile> features,
                               List<FeatureCollector.StepDefFile> stepDefs,
                               boolean includeStepDefs) {

        StringBuilder sb = new StringBuilder();

        // ── Rôle du LLM ──
        sb.append("""
                Tu es un expert en sélection de tests de régression pour les projets BDD (Cucumber/Gherkin).
                
                Ton rôle : analyser les modifications de code source et déterminer quels scénarios BDD
                existants sont impactés et doivent être ré-exécutés.
                
                Règles :
                - Sélectionne UNIQUEMENT les scénarios dont le comportement pourrait être affecté par les changements.
                - Un scénario est impacté si le code modifié est utilisé (directement ou indirectement) par ce scénario.
                - Ne sélectionne PAS les scénarios qui n'ont aucun lien avec le code modifié.
                - Si aucun scénario n'est impacté, retourne une liste vide.
                
                """);

        // ── Diff ──
        sb.append("═══ MODIFICATIONS DE CODE ═══\n\n");
        if (diff.isBlank()) {
            sb.append("(aucune modification détectée)\n\n");
        } else {
            // Tronquer si trop long (garder les 4000 premiers caractères)
            if (diff.length() > 4000) {
                sb.append(diff, 0, 4000);
                sb.append("\n... (diff tronqué, ").append(diff.length()).append(" caractères au total)\n\n");
            } else {
                sb.append(diff).append("\n\n");
            }
        }

        // ── Scénarios ──
        sb.append("═══ SCÉNARIOS BDD EXISTANTS ═══\n");
        sb.append("La numérotation commence à [1]. Utilise UNIQUEMENT les indices listés ci-dessous.\n\n");
        int index = 1;
        for (FeatureCollector.FeatureFile f : features) {
            sb.append("--- Fichier : ").append(f.path()).append(" ---\n");
            for (String line : f.content().split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Scenario:") || trimmed.startsWith("Scenario Outline:")) {
                    sb.append("[").append(index).append("] ").append(trimmed).append("\n");
                    index++;
                } else if (trimmed.startsWith("Given ") || trimmed.startsWith("When ")
                        || trimmed.startsWith("Then ") || trimmed.startsWith("And ")
                        || trimmed.startsWith("But ")) {
                    sb.append("    ").append(trimmed).append("\n");
                }
            }
            sb.append("\n");
        }

        // Récapitulatif des indices valides
        int totalScenarios = index - 1;
        sb.append("Indices valides : [1] à [").append(totalScenarios).append("]. ")
          .append("Tout indice en dehors de cette plage est INVALIDE.\n\n");

        // ── Step definitions (optionnel) ──
        if (includeStepDefs && !stepDefs.isEmpty()) {
            sb.append("═══ STEP DEFINITIONS (lien code ↔ scénario) ═══\n\n");
            for (FeatureCollector.StepDefFile sd : stepDefs) {
                sb.append("--- ").append(sd.path()).append(" ---\n");
                // Inclure seulement les annotations et signatures de méthodes
                for (String line : sd.content().split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("@Given") || trimmed.startsWith("@When")
                            || trimmed.startsWith("@Then") || trimmed.startsWith("@And")
                            || trimmed.startsWith("@But")
                            || trimmed.startsWith("import ")
                            || trimmed.startsWith("public ") || trimmed.startsWith("private ")) {
                        sb.append(trimmed).append("\n");
                    }
                }
                sb.append("\n");
            }
        }

        // ── Format de réponse ──
        sb.append("═══ FORMAT DE RÉPONSE ═══\n\n");
        sb.append("IMPORTANT :\n");
        sb.append("- Ta réponse doit commencer IMMÉDIATEMENT par { et se terminer par }.\n");
        sb.append("- N'écris aucun texte avant ou après le JSON.\n");
        sb.append("- N'utilise pas de blocs ```json```.\n");
        sb.append("- Utilise UNIQUEMENT des indices entre [1] et [").append(totalScenarios).append("].\n");
        sb.append("- Un seul objet JSON, rien d'autre.\n\n");
        sb.append("Exemple si des scénarios sont impactés :\n");
        sb.append("{\"selected\": [1, 3], \"reasoning\": \"Explication courte.\"}\n\n");
        sb.append("Exemple si aucun scénario n'est impacté :\n");
        sb.append("{\"selected\": [], \"reasoning\": \"Aucun scénario impacté par ces modifications.\"}\n");

        return sb.toString();
    }

    /**
     * Version simplifiée sans step definitions.
     */
    public static String build(String diff, List<FeatureCollector.FeatureFile> features) {
        return build(diff, features, List.of(), false);
    }

    /**
     * Prompt en mode élagueur : les candidats ont déjà été filtrés par l'analyse
     * statique. Le LLM a pour seul rôle de retirer les faux positifs.
     */
    public static String buildPruning(String diff,
                                      List<CandidateFinder.Candidate> candidates,
                                      int totalScenarios) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                TÂCHE : Sélection de tests BDD impactés par une modification de code.

                Une analyse statique a présélectionné des scénarios candidats.
                Tu dois décider lesquels sont réellement impactés (comportement observable change)
                et lesquels sont des faux positifs (lien statique mais pas d'impact réel).

                RÈGLES :
                - Réponds UNIQUEMENT avec un objet JSON. Rien d'autre.
                - Format obligatoire : {\"selected\": [indices], \"reasoning\": \"explication\"}
                - Si aucun candidat n'est impacté : {\"selected\": [], \"reasoning\": \"...\"}

                """);

        sb.append("═══ MODIFICATIONS DE CODE ═══\n\n");
        if (diff.isBlank()) {
            sb.append("(aucune modification détectée)\n\n");
        } else if (diff.length() > 4000) {
            sb.append(diff, 0, 4000);
            sb.append("\n... (diff tronqué, ").append(diff.length()).append(" caractères au total)\n\n");
        } else {
            sb.append(diff).append("\n\n");
        }

        sb.append("═══ SCÉNARIOS CANDIDATS (présélection statique) ═══\n");
        sb.append("Chaque candidat est listé avec son indice global [n], son nom, ses steps,\n");
        sb.append("et la chaîne d'appel qui le relie au code modifié (step def → … → méthode modifiée).\n\n");

        if (candidates.isEmpty()) {
            sb.append("(aucun candidat — l'analyse statique n'a trouvé aucun lien)\n\n");
        }

        for (CandidateFinder.Candidate c : candidates) {
            StaticAnalyzer.Scenario sc = c.scenario();
            sb.append("[").append(sc.index()).append("] ").append(sc.name())
              .append("   (").append(sc.filePath()).append(")\n");
            for (String step : sc.stepLines()) {
                sb.append("    ").append(step).append("\n");
            }
            sb.append("  Chaîne(s) d'appel vers le code modifié :\n");
            for (CandidateFinder.CandidateChain ch : c.chains()) {
                sb.append("    ").append(String.join(" → ", ch.chain())).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Indices valides : parmi les candidats listés ci-dessus uniquement. ")
          .append("La numérotation globale va de [1] à [").append(totalScenarios).append("] ; ")
          .append("tout indice en dehors des candidats listés est INVALIDE.\n\n");

        sb.append("═══ RÉPONSE ATTENDUE ═══\n\n");
        sb.append("Réponds MAINTENANT avec UNIQUEMENT ce JSON (commence par { et termine par }) :\n");
        sb.append("{\"selected\": [indices des candidats réellement impactés], \"reasoning\": \"explication\"}\n\n");
        sb.append("Exemples :\n");
        sb.append("{\"selected\": [2, 5], \"reasoning\": \"Ces scénarios testent la méthode modifiée.\"}\n");
        sb.append("{\"selected\": [], \"reasoning\": \"Le changement est cosmétique, pas d'impact observable.\"}\n\n");
        sb.append("RAPPEL : ta réponse doit commencer immédiatement par { sans aucun texte avant.\n");

        return sb.toString();
    }
}
