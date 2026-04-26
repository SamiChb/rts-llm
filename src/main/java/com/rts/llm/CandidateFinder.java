package com.rts.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * À partir des méthodes modifiées et de l'index statique, calcule l'ensemble
 * des scénarios BDD candidats (potentiellement impactés) et, pour chacun, la
 * chaîne d'appel qui relie une step def à une méthode modifiée.
 */
public class CandidateFinder {

    public record CandidateChain(String stepDefMethodId, List<String> chain) {}

    public record Candidate(StaticAnalyzer.Scenario scenario, List<CandidateChain> chains) {}

    public static List<Candidate> find(
            List<SymbolExtractor.ModifiedSymbol> modified,
            StaticAnalyzer.Index index) {

        // BFS inverse depuis les méthodes modifiées (+ leur alias wildcard) dans
        // le reverse call graph. `parent` permet de reconstruire la chaîne.
        Map<String, String> parent = new HashMap<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();

        for (SymbolExtractor.ModifiedSymbol m : modified) {
            seed(m.methodId(), visited, parent, queue);
            seed(toWildcard(m.methodId()), visited, parent, queue);
        }

        while (!queue.isEmpty()) {
            String node = queue.poll();
            Set<String> callers = index.reverseCallGraph().getOrDefault(node, Set.of());
            for (String caller : callers) {
                if (visited.add(caller)) {
                    parent.put(caller, node);
                    queue.add(caller);
                }
            }
        }

        // Pour chaque scénario, vérifier si au moins une de ses step defs est dans l'ensemble impacté.
        List<Candidate> candidates = new ArrayList<>();
        for (StaticAnalyzer.Scenario sc : index.scenarios()) {
            List<CandidateChain> chains = new ArrayList<>();
            for (String sdId : sc.stepDefMethodIds()) {
                if (visited.contains(sdId)) {
                    chains.add(new CandidateChain(sdId, buildChain(sdId, parent)));
                }
            }
            if (!chains.isEmpty()) {
                candidates.add(new Candidate(sc, chains));
            }
        }
        return candidates;
    }

    private static void seed(String id, Set<String> visited,
                             Map<String, String> parent, Deque<String> queue) {
        if (id == null) return;
        if (visited.add(id)) {
            parent.put(id, null);
            queue.add(id);
        }
    }

    private static List<String> buildChain(String start, Map<String, String> parent) {
        List<String> chain = new ArrayList<>();
        String cur = start;
        int safety = 0;
        while (cur != null && safety++ < 100) {
            chain.add(cur);
            cur = parent.get(cur);
        }
        return chain;
    }

    private static String toWildcard(String methodId) {
        if (methodId == null) return null;
        int dot = methodId.lastIndexOf('.');
        if (dot < 0) return methodId;
        return "*" + methodId.substring(dot);
    }
}
