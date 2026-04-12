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
}
