package com.rts.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Résultat de la sélection de tests par le LLM.
 */
public class SelectionResult {

    private final List<Integer> selectedIndices;
    private final List<String> selectedScenarios;
    private final String reasoning;

    public SelectionResult(List<Integer> selectedIndices,
                           List<String> selectedScenarios,
                           String reasoning) {
        this.selectedIndices = selectedIndices;
        this.selectedScenarios = selectedScenarios;
        this.reasoning = reasoning;
    }

    /**
     * Parse la réponse JSON du LLM.
     *
     * @param llmResponse  la réponse brute du LLM
     * @param allScenarios liste ordonnée de tous les noms de scénarios (index 1-based)
     */
    public static SelectionResult parse(String llmResponse, List<String> allScenarios) {
        // Extraire le dernier objet JSON valide de la réponse
        String cleaned = extractLastJson(llmResponse);

        try {
            Gson gson = new Gson();
            JsonObject json = gson.fromJson(cleaned, JsonObject.class);

            // Extraire les indices sélectionnés
            List<Integer> indices = new ArrayList<>();
            if (json.has("selected") && json.get("selected").isJsonArray()) {
                json.getAsJsonArray("selected").forEach(e ->
                        indices.add(e.getAsInt()));
            }

            // Mapper les indices vers les noms de scénarios
            List<String> scenarios = new ArrayList<>();
            for (int idx : indices) {
                if (idx >= 1 && idx <= allScenarios.size()) {
                    scenarios.add(allScenarios.get(idx - 1)); // 1-based → 0-based
                }
            }

            // Extraire le raisonnement
            String reasoning = json.has("reasoning")
                    ? json.get("reasoning").getAsString()
                    : "(pas de justification fournie)";

            return new SelectionResult(indices, scenarios, reasoning);

        } catch (JsonSyntaxException e) {
            return new SelectionResult(
                    List.of(), List.of(),
                    "Erreur de parsing JSON : " + e.getMessage()
                            + "\nRéponse brute : " + llmResponse);
        }
    }

    // ── Extraction JSON ────────────────────────────────

    /**
     * Extrait le dernier bloc JSON valide { ... } de la réponse du LLM.
     * Gère les cas où le modèle ajoute du texte ou plusieurs blocs JSON.
     */
    private static String extractLastJson(String response) {
        // Chercher tous les blocs {...} et retourner le dernier valide
        Pattern pattern = Pattern.compile("\\{[^{}]*\\}", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(response);
        String last = null;
        while (matcher.find()) {
            last = matcher.group();
        }
        return last != null ? last : response.trim();
    }

    // ── Affichage ──────────────────────────────────────

    public void afficherRapport() {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════");
        System.out.println("  SÉLECTION RTS (analyse LLM du code source)");
        System.out.println("═══════════════════════════════════════════════════");
        System.out.println();

        if (selectedScenarios.isEmpty()) {
            System.out.println("  Aucun scénario impacté.");
        } else {
            System.out.println("  Scénarios à retester : " + selectedScenarios.size());
            for (int i = 0; i < selectedScenarios.size(); i++) {
                System.out.println("    [" + selectedIndices.get(i) + "] "
                        + selectedScenarios.get(i));
            }
        }

        System.out.println();
        System.out.println("  Justification LLM :");
        System.out.println("    " + reasoning.replace("\n", "\n    "));
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════");
    }

    // ── Getters ──────────────────────────────────────

    public List<Integer> getSelectedIndices() { return selectedIndices; }
    public List<String> getSelectedScenarios() { return selectedScenarios; }
    public String getReasoning() { return reasoning; }
    public boolean hasSelection() { return !selectedScenarios.isEmpty(); }
}
