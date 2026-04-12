package com.rts.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * Extrait le diff Git entre deux commits ou le dernier commit.
 */
public class GitDiffExtractor {

    private final Path projectRoot;

    public GitDiffExtractor(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * Diff du dernier commit (HEAD~1..HEAD), fichiers Java uniquement.
     */
    public String lastCommitDiff() throws IOException {
        return runCommand("git", "diff", "HEAD~1", "--unified=3", "--", "*.java");
    }

    /**
     * Diff entre deux commits, fichiers Java uniquement.
     */
    public String diffBetween(String fromCommit, String toCommit) throws IOException {
        return runCommand("git", "diff", fromCommit, toCommit, "--unified=3", "--", "*.java");
    }

    /**
     * Diff des modifications non commitées (working directory).
     */
    public String uncommittedDiff() throws IOException {
        return runCommand("git", "diff", "--unified=3", "--", "*.java");
    }

    /**
     * Diff incluant tous les types de fichiers (Java + feature + config).
     */
    public String lastCommitDiffAll() throws IOException {
        return runCommand("git", "diff", "HEAD~1", "--unified=3");
    }

    private String runCommand(String... command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String output = reader.lines().collect(Collectors.joining("\n"));

            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Git command interrupted", e);
            }

            if (exitCode != 0 && output.contains("fatal:")) {
                throw new IOException("Git command failed: " + output);
            }

            return output;
        }
    }
}
