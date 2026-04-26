package com.rts.llm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifie les méthodes modifiées par un diff Git, en croisant les numéros de
 * ligne des hunks avec les plages de méthodes issues de {@link StaticAnalyzer}.
 */
public class SymbolExtractor {

    private static final Pattern FILE_HEADER = Pattern.compile("^\\+\\+\\+ b/(.+)$");
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    public record ModifiedSymbol(String methodId, String filePath) {}

    public static List<ModifiedSymbol> extract(
            String diff,
            Map<String, List<StaticAnalyzer.MethodRange>> methodRangesByFile) {

        LinkedHashSet<ModifiedSymbol> modified = new LinkedHashSet<>();
        String currentFile = null;
        List<StaticAnalyzer.MethodRange> currentRanges = List.of();

        for (String line : diff.split("\n")) {
            Matcher fileMatch = FILE_HEADER.matcher(line);
            if (fileMatch.find()) {
                currentFile = fileMatch.group(1);
                if (currentFile.equals("/dev/null")) {
                    currentFile = null;
                    currentRanges = List.of();
                } else {
                    currentRanges = methodRangesByFile.getOrDefault(currentFile, List.of());
                }
                continue;
            }

            if (currentFile == null || currentRanges.isEmpty()) continue;

            Matcher hunkMatch = HUNK_HEADER.matcher(line);
            if (hunkMatch.find()) {
                int start = Integer.parseInt(hunkMatch.group(1));
                int count = hunkMatch.group(2) != null
                        ? Integer.parseInt(hunkMatch.group(2))
                        : 1;
                int end = start + Math.max(count - 1, 0);

                for (StaticAnalyzer.MethodRange r : currentRanges) {
                    if (overlaps(start, end, r.startLine(), r.endLine())) {
                        modified.add(new ModifiedSymbol(r.methodId(), currentFile));
                    }
                }
            }
        }

        return new ArrayList<>(modified);
    }

    private static boolean overlaps(int a1, int a2, int b1, int b2) {
        return a1 <= b2 && b1 <= a2;
    }
}
