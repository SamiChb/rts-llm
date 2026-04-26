package com.rts.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Construit la vérité terrain pour l'évaluation de RTS-LLM à partir de
 * la couverture JaCoCo collectée par scénario.
 *
 * <p>Hypothèses :</p>
 * <ul>
 *   <li>Un répertoire {@code coverage/} contenant un fichier {@code sc_NNN.exec}
 *       par scénario, plus un {@code index.txt} mappant {@code sc_NNN|nom-du-scenario}
 *       (généré par {@code scripts/run_per_scenario_coverage.sh}).</li>
 *   <li>Le répertoire {@code target/classes} (et optionnellement
 *       {@code target/test-classes}) du projet cible existe.</li>
 * </ul>
 *
 * <p>Format du methodId : {@code "FQN.ClassName.methodName"}, identique à
 * {@link StaticAnalyzer#analyze(Path)}, pour permettre l'intersection avec
 * {@link SymbolExtractor.ModifiedSymbol}.</p>
 *
 * <h3>Sous-commandes</h3>
 * <pre>
 *   build &lt;coverage-dir&gt; &lt;classes-dir&gt; [classes-dir2 ...] &lt;output.json&gt;
 *       Lit chaque .exec, calcule les méthodes couvertes, écrit la map JSON.
 *
 *   eval  &lt;coverage.json&gt; &lt;project-path&gt;
 *         [--diff-from X] [--diff-to Y]
 *         [--selection nom1;nom2;...]
 *       Calcule la vérité terrain pour le diff donné, et si --selection est
 *       fourni, affiche précision / rappel / F1.
 * </pre>
 */
public class GroundTruthBuilder {

    /** Couverture d'un scénario : son nom + les méthodes qu'il exécute. */
    public static class ScenarioCoverage {
        public String name;
        public List<String> methods;

        public ScenarioCoverage() {}
        public ScenarioCoverage(String name, List<String> methods) {
            this.name = name;
            this.methods = methods;
        }
    }

    /** Métriques d'évaluation d'une sélection contre une vérité terrain. */
    public record Metrics(int truePositives, int falsePositives,
                          int falseNegatives, int groundTruthSize,
                          int selectionSize) {

        public double precision() {
            int sel = truePositives + falsePositives;
            return sel == 0 ? 1.0 : (double) truePositives / sel;
        }

        public double recall() {
            int g = truePositives + falseNegatives;
            return g == 0 ? 1.0 : (double) truePositives / g;
        }

        public double f1() {
            double p = precision(), r = recall();
            return (p + r) == 0 ? 0.0 : 2 * p * r / (p + r);
        }
    }

    // ───────────────────────────── Build ─────────────────────────────

    /**
     * Lit chaque .exec du répertoire et produit la map scénario → méthodes couvertes.
     *
     * @param coverageDir répertoire contenant {@code sc_*.exec} et {@code index.txt}
     * @param classesDirs répertoires de bytecode à analyser (target/classes, etc.)
     */
    public static Map<String, ScenarioCoverage> buildCoverageMap(
            Path coverageDir, List<Path> classesDirs) throws IOException {

        Map<String, String> nameById = readIndex(coverageDir.resolve("index.txt"));
        Map<String, ScenarioCoverage> result = new TreeMap<>();

        try (Stream<Path> stream = Files.list(coverageDir)) {
            List<Path> execs = stream
                    .filter(p -> p.getFileName().toString().endsWith(".exec"))
                    .sorted()
                    .toList();

            for (Path exec : execs) {
                String id = exec.getFileName().toString().replace(".exec", "");
                Set<String> methods = coveredMethods(exec, classesDirs);
                String name = nameById.getOrDefault(id, id);
                result.put(id, new ScenarioCoverage(name, new ArrayList<>(methods)));
                System.out.println(id + " (" + name + ") → "
                        + methods.size() + " méthodes couvertes");
            }
        }
        return result;
    }

    /** Méthodes couvertes par un .exec, au format {@code FQN.Class.method}. */
    public static Set<String> coveredMethods(Path execFile, List<Path> classesDirs)
            throws IOException {

        ExecFileLoader loader = new ExecFileLoader();
        loader.load(execFile.toFile());

        CoverageBuilder cb = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), cb);
        for (Path dir : classesDirs) {
            if (Files.isDirectory(dir)) {
                analyzer.analyzeAll(dir.toFile());
            }
        }

        Set<String> covered = new TreeSet<>();
        for (IClassCoverage cc : cb.getClasses()) {
            String className = normalizeClassName(cc.getName());
            for (IMethodCoverage mc : cc.getMethods()) {
                if (mc.getInstructionCounter().getCoveredCount() == 0) continue;
                String methodName = normalizeMethodName(mc.getName());
                if (methodName == null) continue;        // <init>, <clinit>
                covered.add(className + "." + methodName);
            }
        }
        return covered;
    }

    /** {@code com/example/Outer$Inner} → {@code com.example.Outer.Inner}. */
    private static String normalizeClassName(String internalName) {
        return internalName.replace('/', '.').replace('$', '.');
    }

    /**
     * Replie les lambdas et accesseurs synthétiques sur leur méthode englobante.
     * Retourne {@code null} pour les constructeurs et init statiques (non
     * trackés par {@link StaticAnalyzer}).
     */
    private static String normalizeMethodName(String mname) {
        if (mname.equals("<init>") || mname.equals("<clinit>")) return null;
        if (mname.startsWith("lambda$")) {
            String[] parts = mname.split("\\$");
            if (parts.length >= 2) return parts[1];
        }
        if (mname.startsWith("access$")) return null;
        return mname;
    }

    private static Map<String, String> readIndex(Path indexFile) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(indexFile)) return out;
        for (String line : Files.readAllLines(indexFile)) {
            int sep = line.indexOf('|');
            if (sep > 0) out.put(line.substring(0, sep), line.substring(sep + 1));
        }
        return out;
    }

    // ───────────────────────────── Eval ─────────────────────────────

    /**
     * Vérité terrain : scénarios dont la couverture intersecte les méthodes modifiées.
     */
    public static List<String> groundTruth(Set<String> modifiedMethods,
                                           Map<String, ScenarioCoverage> coverageMap) {
        List<String> g = new ArrayList<>();
        for (var e : coverageMap.entrySet()) {
            Set<String> methods = new TreeSet<>(e.getValue().methods);
            if (!Collections.disjoint(methods, modifiedMethods)) {
                g.add(e.getKey());
            }
        }
        return g;
    }

    /** Compare une sélection (par nom de scénario) à la vérité terrain (par id). */
    public static Metrics evaluate(List<String> selectedNames,
                                   List<String> groundTruthIds,
                                   Map<String, ScenarioCoverage> coverageMap) {

        // Convertir la sélection (noms) en ids via la map
        Map<String, String> idByName = coverageMap.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getValue().name, Map.Entry::getKey,
                        (a, b) -> a));

        Set<String> sel = selectedNames.stream()
                .map(idByName::get)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> g = new TreeSet<>(groundTruthIds);

        Set<String> tp = new TreeSet<>(sel);
        tp.retainAll(g);
        Set<String> fp = new TreeSet<>(sel);
        fp.removeAll(g);
        Set<String> fn = new TreeSet<>(g);
        fn.removeAll(sel);

        return new Metrics(tp.size(), fp.size(), fn.size(), g.size(), sel.size());
    }

    // ───────────────────────────── CLI ─────────────────────────────

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }
        switch (args[0]) {
            case "build" -> cmdBuild(slice(args, 1));
            case "eval"  -> cmdEval(slice(args, 1));
            default -> {
                printUsage();
                System.exit(1);
            }
        }
    }

    private static void cmdBuild(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage : build <coverage-dir> <classes-dir>"
                    + " [classes-dir2 ...] <output.json>");
            System.exit(1);
        }
        Path coverageDir = Path.of(args[0]);
        Path output = Path.of(args[args.length - 1]);
        List<Path> classesDirs = new ArrayList<>();
        for (int i = 1; i < args.length - 1; i++) {
            classesDirs.add(Path.of(args[i]));
        }

        System.out.println("Lecture de " + coverageDir);
        System.out.println("Bytecode  : " + classesDirs);
        Map<String, ScenarioCoverage> map = buildCoverageMap(coverageDir, classesDirs);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(output, gson.toJson(map));
        System.out.println("→ Vérité terrain de couverture écrite dans " + output);
        System.out.println("  " + map.size() + " scénario(s) indexés");
    }

    private static void cmdEval(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage : eval <coverage.json> <project-path>"
                    + " [--diff-from X] [--diff-to Y]"
                    + " [--selection nom1;nom2;...]"
                    + " [--csv]   [--commit SHA]");
            System.exit(1);
        }
        Path coverageJson = Path.of(args[0]);
        Path projectPath = Path.of(args[1]).toAbsolutePath();
        String diffFrom = null, diffTo = null, commitTag = "";
        List<String> selection = null;
        boolean csv = false;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--diff-from" -> diffFrom = args[++i];
                case "--diff-to"   -> diffTo   = args[++i];
                case "--selection" -> selection = List.of(args[++i].split(";"));
                case "--csv"       -> csv = true;
                case "--commit"    -> commitTag = args[++i];
                default -> throw new IllegalArgumentException("Option inconnue : " + args[i]);
            }
        }

        // 1. Charger la map de couverture
        Type t = new TypeToken<Map<String, ScenarioCoverage>>() {}.getType();
        Map<String, ScenarioCoverage> coverageMap =
                new Gson().fromJson(Files.readString(coverageJson), t);
        if (!csv) System.out.println("Couverture : " + coverageMap.size() + " scénarios chargés");

        // 2. Diff
        GitDiffExtractor extractor = new GitDiffExtractor(projectPath);
        String diff = (diffFrom != null && diffTo != null)
                ? extractor.diffBetween(diffFrom, diffTo)
                : extractor.lastCommitDiff();

        // 3. Méthodes modifiées
        Set<String> modifiedIds = new TreeSet<>();
        if (!diff.isBlank()) {
            StaticAnalyzer.Index index = StaticAnalyzer.analyze(projectPath);
            List<SymbolExtractor.ModifiedSymbol> modified =
                    SymbolExtractor.extract(diff, index.methodRangesByFile());
            modified.stream()
                    .map(SymbolExtractor.ModifiedSymbol::methodId)
                    .forEach(modifiedIds::add);
        }

        // 4. Vérité terrain
        List<String> g = groundTruth(modifiedIds, coverageMap);

        // 5. Comparaison avec une sélection (optionnelle)
        Metrics metrics = (selection != null)
                ? evaluate(selection, g, coverageMap)
                : null;

        // 6. Sortie
        if (csv) {
            // Header géré par le script appelant
            // commit,modified_methods,gt_size,sel_size,tp,fp,fn,precision,recall,f1
            int sel = metrics != null ? metrics.selectionSize() : 0;
            int tp = metrics != null ? metrics.truePositives() : 0;
            int fp = metrics != null ? metrics.falsePositives() : 0;
            int fn = metrics != null ? metrics.falseNegatives() : 0;
            double p = metrics != null ? metrics.precision() : Double.NaN;
            double r = metrics != null ? metrics.recall()    : Double.NaN;
            double f = metrics != null ? metrics.f1()        : Double.NaN;
            System.out.printf(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%.4f,%.4f,%.4f%n",
                    commitTag, modifiedIds.size(), g.size(), sel, tp, fp, fn, p, r, f);
            return;
        }

        if (diff.isBlank()) {
            System.out.println("Diff vide — vérité terrain = ∅");
            return;
        }
        System.out.println("Méthodes modifiées : " + modifiedIds.size());
        modifiedIds.forEach(m -> System.out.println("  - " + m));
        System.out.println();
        System.out.println("Vérité terrain : " + g.size() + " scénario(s)");
        for (String id : g) {
            System.out.println("  [" + id + "] " + coverageMap.get(id).name);
        }

        if (metrics != null) {
            System.out.println();
            System.out.println("─── Évaluation ───");
            System.out.printf("  TP=%d  FP=%d  FN=%d%n",
                    metrics.truePositives(), metrics.falsePositives(), metrics.falseNegatives());
            System.out.printf("  Précision : %.3f%n", metrics.precision());
            System.out.printf("  Rappel    : %.3f%n", metrics.recall());
            System.out.printf("  F1        : %.3f%n", metrics.f1());
        }
    }

    private static String[] slice(String[] arr, int from) {
        String[] out = new String[arr.length - from];
        System.arraycopy(arr, from, out, 0, out.length);
        return out;
    }

    private static void printUsage() {
        System.err.println("""
                Usage :
                  build <coverage-dir> <classes-dir> [classes-dir2 ...] <output.json>
                  eval  <coverage.json> <project-path>
                        [--diff-from X] [--diff-to Y]
                        [--selection "nom1;nom2;..."]
                """);
    }
}
