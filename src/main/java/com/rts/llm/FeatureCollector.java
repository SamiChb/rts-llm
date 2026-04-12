package com.rts.llm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Collecte tous les fichiers .feature et les step definitions d'un projet.
 */
public class FeatureCollector {

    private final Path projectRoot;

    public FeatureCollector(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * Lit tous les fichiers .feature et retourne leur contenu numéroté.
     */
    public List<FeatureFile> collectFeatures() throws IOException {
        List<FeatureFile> features = new ArrayList<>();
        Path searchRoot = projectRoot.resolve("src/test");

        if (!Files.isDirectory(searchRoot)) {
            searchRoot = projectRoot; // fallback : chercher depuis la racine
        }

        try (Stream<Path> walk = Files.walk(searchRoot)) {
            List<Path> featureFiles = walk
                    .filter(p -> p.toString().endsWith(".feature"))
                    .sorted()
                    .toList();

            for (Path f : featureFiles) {
                String content = Files.readString(f);
                String relativePath = projectRoot.relativize(f).toString();
                features.add(new FeatureFile(relativePath, content));
            }
        }

        return features;
    }

    /**
     * Lit tous les fichiers *Steps.java pour donner du contexte au LLM.
     */
    public List<StepDefFile> collectStepDefs() throws IOException {
        List<StepDefFile> stepDefs = new ArrayList<>();
        Path searchRoot = projectRoot.resolve("src/test/java");

        if (!Files.isDirectory(searchRoot)) {
            return stepDefs;
        }

        try (Stream<Path> walk = Files.walk(searchRoot)) {
            List<Path> stepFiles = walk
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith("Steps.java")
                                || name.endsWith("StepDefs.java")
                                || name.endsWith("StepDefinitions.java");
                    })
                    .sorted()
                    .toList();

            for (Path f : stepFiles) {
                String content = Files.readString(f);
                String relativePath = projectRoot.relativize(f).toString();
                stepDefs.add(new StepDefFile(relativePath, content));
            }
        }

        return stepDefs;
    }

    // ── Records ──────────────────────────────────────────

    public record FeatureFile(String path, String content) {

        /**
         * Extrait les noms de scénarios du fichier.
         */
        public List<String> scenarioNames() {
            List<String> names = new ArrayList<>();
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Scenario:") || trimmed.startsWith("Scenario Outline:")) {
                    names.add(trimmed
                            .replace("Scenario Outline:", "")
                            .replace("Scenario:", "")
                            .trim());
                }
            }
            return names;
        }
    }

    public record StepDefFile(String path, String content) {}
}
