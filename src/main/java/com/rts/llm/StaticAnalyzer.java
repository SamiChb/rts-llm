package com.rts.llm;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Analyse statique d'un projet Java/Cucumber : construit le call graph,
 * recense les step definitions et les scénarios, et matche scénarios ↔ step defs.
 *
 * Identifiant de méthode utilisé : "FQN.ClassName.methodName" (sans paramètres).
 * Pour les appels non résolus, un alias "*.methodName" est également indexé afin
 * de conserver un recall conservateur.
 */
public class StaticAnalyzer {

    private static final Set<String> CUCUMBER_ANNOTATIONS =
            Set.of("Given", "When", "Then", "And", "But");

    public record StepDefMethod(String methodId, String annotationType, String pattern) {}

    public record Scenario(
            int index,
            String name,
            String filePath,
            List<String> stepLines,
            List<String> stepDefMethodIds) {}

    public record MethodRange(String methodId, String filePath, int startLine, int endLine) {}

    public record Index(
            Map<String, Set<String>> callGraph,
            Map<String, Set<String>> reverseCallGraph,
            List<StepDefMethod> stepDefs,
            List<Scenario> scenarios,
            Map<String, List<MethodRange>> methodRangesByFile) {}

    public static Index analyze(Path projectRoot) throws IOException {
        JavaParser parser = buildParser(projectRoot);

        Map<String, Set<String>> callGraph = new HashMap<>();
        Map<String, Set<String>> reverseCallGraph = new HashMap<>();
        List<StepDefMethod> stepDefs = new ArrayList<>();
        Map<String, List<MethodRange>> methodRanges = new HashMap<>();

        List<Path> javaFiles = collectJavaFiles(projectRoot);

        for (Path f : javaFiles) {
            ParseResult<CompilationUnit> result;
            try {
                result = parser.parse(f);
            } catch (IOException e) {
                System.err.println("   (avertissement) lecture échouée : " + f);
                continue;
            }
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                continue;
            }
            CompilationUnit cu = result.getResult().get();
            String relativePath = projectRoot.relativize(f).toString();
            List<MethodRange> ranges = new ArrayList<>();

            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                String methodId = buildMethodId(md);
                if (methodId == null) continue;

                md.getRange().ifPresent(r -> ranges.add(
                        new MethodRange(methodId, relativePath, r.begin.line, r.end.line)));

                for (AnnotationExpr ann : md.getAnnotations()) {
                    if (CUCUMBER_ANNOTATIONS.contains(ann.getNameAsString())) {
                        String pat = extractAnnotationString(ann);
                        stepDefs.add(new StepDefMethod(methodId, ann.getNameAsString(), pat));
                    }
                }

                Set<String> callees = callGraph.computeIfAbsent(methodId, k -> new HashSet<>());
                for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
                    recordCall(call, methodId, callees, reverseCallGraph);
                }
            }

            methodRanges.put(relativePath, ranges);
        }

        List<Scenario> scenarios = parseFeatures(projectRoot, stepDefs);

        return new Index(callGraph, reverseCallGraph, stepDefs, scenarios, methodRanges);
    }

    private static JavaParser buildParser(Path projectRoot) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        Path srcMain = projectRoot.resolve("src/main/java");
        Path srcTest = projectRoot.resolve("src/test/java");
        if (Files.isDirectory(srcMain)) typeSolver.add(new JavaParserTypeSolver(srcMain));
        if (Files.isDirectory(srcTest)) typeSolver.add(new JavaParserTypeSolver(srcTest));

        ParserConfiguration conf = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        return new JavaParser(conf);
    }

    private static List<Path> collectJavaFiles(Path projectRoot) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path root : List.of(projectRoot.resolve("src/main/java"),
                                 projectRoot.resolve("src/test/java"))) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
            }
        }
        return out;
    }

    private static String buildMethodId(MethodDeclaration md) {
        return md.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(cls -> cls.getFullyQualifiedName().orElse(cls.getNameAsString())
                        + "." + md.getNameAsString())
                .orElse(null);
    }

    private static void recordCall(MethodCallExpr call, String callerId,
                                   Set<String> callerOutgoing,
                                   Map<String, Set<String>> reverseCallGraph) {
        String simpleName = call.getNameAsString();
        String wildcardId = "*." + simpleName;

        callerOutgoing.add(wildcardId);
        reverseCallGraph.computeIfAbsent(wildcardId, k -> new HashSet<>()).add(callerId);

        try {
            var resolved = call.resolve();
            String calleeId = resolved.declaringType().getQualifiedName()
                    + "." + resolved.getName();
            callerOutgoing.add(calleeId);
            reverseCallGraph.computeIfAbsent(calleeId, k -> new HashSet<>()).add(callerId);
        } catch (Exception ignore) {
            // Appel non résolu : on se contente de l'alias wildcard.
        }
    }

    private static String extractAnnotationString(AnnotationExpr ann) {
        if (ann.isSingleMemberAnnotationExpr()) {
            var expr = ann.asSingleMemberAnnotationExpr().getMemberValue();
            if (expr instanceof StringLiteralExpr s) return s.asString();
        } else if (ann.isNormalAnnotationExpr()) {
            for (var p : ann.asNormalAnnotationExpr().getPairs()) {
                if (p.getNameAsString().equals("value")
                        && p.getValue() instanceof StringLiteralExpr s) {
                    return s.asString();
                }
            }
        }
        return "";
    }

    private static List<Scenario> parseFeatures(Path projectRoot, List<StepDefMethod> stepDefs)
            throws IOException {
        List<Scenario> scenarios = new ArrayList<>();
        Path searchRoot = projectRoot.resolve("src/test");
        if (!Files.isDirectory(searchRoot)) searchRoot = projectRoot;

        List<Path> featureFiles;
        try (Stream<Path> walk = Files.walk(searchRoot)) {
            featureFiles = walk.filter(p -> p.toString().endsWith(".feature")).sorted().toList();
        }

        int globalIndex = 1;
        for (Path f : featureFiles) {
            String content = Files.readString(f);
            String rel = projectRoot.relativize(f).toString();
            String currentName = null;
            List<String> currentSteps = new ArrayList<>();

            for (String rawLine : content.split("\n")) {
                String line = rawLine.trim();
                if (line.startsWith("Scenario:") || line.startsWith("Scenario Outline:")) {
                    if (currentName != null) {
                        scenarios.add(buildScenario(globalIndex++, currentName, rel,
                                currentSteps, stepDefs));
                    }
                    currentName = line.replaceFirst("^Scenario( Outline)?:\\s*", "");
                    currentSteps = new ArrayList<>();
                } else if (line.startsWith("Given ") || line.startsWith("When ")
                        || line.startsWith("Then ") || line.startsWith("And ")
                        || line.startsWith("But ")) {
                    currentSteps.add(line);
                }
            }
            if (currentName != null) {
                scenarios.add(buildScenario(globalIndex++, currentName, rel,
                        currentSteps, stepDefs));
            }
        }
        return scenarios;
    }

    private static Scenario buildScenario(int idx, String name, String file,
                                          List<String> steps, List<StepDefMethod> stepDefs) {
        LinkedHashSet<String> matched = new LinkedHashSet<>();
        for (String step : steps) {
            String body = step.replaceFirst("^(Given|When|Then|And|But)\\s+", "");
            for (StepDefMethod sd : stepDefs) {
                if (stepMatches(body, sd.pattern())) {
                    matched.add(sd.methodId());
                }
            }
        }
        return new Scenario(idx, name, file, List.copyOf(steps), new ArrayList<>(matched));
    }

    /** Match d'un step contre un pattern (regex ou Cucumber Expression). */
    private static boolean stepMatches(String step, String pattern) {
        if (pattern == null || pattern.isEmpty()) return false;
        try {
            if (Pattern.matches(pattern, step)) return true;
        } catch (Exception ignore) {}
        // Cucumber Expression : {int}, {string}, {word}, {float}, {} → .*?
        String converted = pattern.replaceAll("\\{[^}]*\\}", ".*?");
        try {
            if (Pattern.matches(converted, step)) return true;
        } catch (Exception ignore) {}
        return false;
    }
}
