package com.rts.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
            // Fallback : extraire les indices/noms depuis le texte libre
            List<Integer> indices = extractIndicesFromText(llmResponse, allScenarios);
            List<String> scenarios = new ArrayList<>();
            for (int idx : indices) {
                scenarios.add(allScenarios.get(idx - 1));
            }
            String note = "Erreur de parsing JSON : " + e.getMessage()
                    + (indices.isEmpty() ? "" : " → fallback texte : " + indices + " scénario(s) extraits")
                    + "\nRéponse brute : " + llmResponse;
            return new SelectionResult(indices, scenarios, note);
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

    // ── Fallback texte ────────────────────────────────

    /**
     * Tente d'extraire des indices de scénarios depuis une réponse en texte libre.
     * Stratégie 1 : cherche ([N]) ou [N] au sens de référence explicite.
     * Stratégie 2 : cherche les noms de scénarios connus dans le texte.
     */
    private static List<Integer> extractIndicesFromText(String response, List<String> allScenarios) {
        Set<Integer> seen = new LinkedHashSet<>();

        // Stratégie 1 : ([N]) ou ([N]) — référence explicite à l'indice
        Pattern pIdx = Pattern.compile("\\(\\[?(\\d+)\\]?\\)");
        Matcher m = pIdx.matcher(response);
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            if (idx >= 1 && idx <= allScenarios.size()) seen.add(idx);
        }
        if (!seen.isEmpty()) return new ArrayList<>(seen);

        // Stratégie 2 : chercher les noms de scénarios dans le texte
        // On ignore les lignes qui contiennent "non impacté", "not impacted", "pas impacté"
        String lower = response.toLowerCase(Locale.FRENCH);
        for (int i = 0; i < allScenarios.size(); i++) {
            String name = allScenarios.get(i).toLowerCase(Locale.FRENCH).trim();
            if (name.isEmpty()) continue;
            int pos = lower.indexOf(name);
            if (pos < 0) continue;
            // Vérifier que la ligne contenant ce nom ne nie pas l'impact
            int lineStart = lower.lastIndexOf('\n', pos) + 1;
            int lineEnd   = lower.indexOf('\n', pos);
            String line   = lower.substring(lineStart, lineEnd < 0 ? lower.length() : lineEnd);
            if (line.contains("non impacté") || line.contains("not impacted")
                    || line.contains("pas impacté") || line.contains("n'est pas")) continue;
            seen.add(i + 1);
        }
        return new ArrayList<>(seen);
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
