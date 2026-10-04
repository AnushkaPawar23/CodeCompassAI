package com.codecompass.graph;

import com.codecompass.parsing.ParsedClass;
import com.codecompass.parsing.ParsedFile;
import com.codecompass.parsing.ParsedMethod;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.Map;
import java.util.Optional;

/**
 * Stage 5 — builds the intra-project method-level call graph for an ingested repository
 * and persists it as {@link CallGraphEdge} rows in PostgreSQL.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Delete stale edges</b> for {@code repoId} (idempotent re-ingest).</li>
 *   <li><b>Locate Java source root</b> — the directory that is the root of the
 *       package hierarchy (e.g. {@code src/main/java}).  Required by
 *       {@link JavaParserTypeSolver} so it can resolve simple class names to
 *       their declaration files.</li>
 *   <li><b>Configure symbol solver</b> — a {@link CombinedTypeSolver} combining:
 *       <ul>
 *         <li>{@link ReflectionTypeSolver} — resolves JDK types ({@code java.util.*}, etc.)</li>
 *         <li>{@link JavaParserTypeSolver} pointing at the source root — resolves
 *             our own project classes.</li>
 *       </ul>
 *       Maven dependency JARs are intentionally omitted; calls to Spring, LangChain4j,
 *       etc. will fail to resolve and are silently skipped.</li>
 *   <li><b>Re-parse each file with the solver</b> — {@link JavaParser} must have the
 *       solver injected at parse time; we cannot retro-fit it onto the already-parsed
 *       {@link CompilationUnit} objects from Stage 2/3.</li>
 *   <li><b>Extract call edges</b> — for each {@link MethodDeclaration} body, visit all
 *       {@link MethodCallExpr} nodes.  Attempt {@code resolve()} on each; catch any
 *       exception and skip (external/unresolvable call).</li>
 *   <li><b>Filter to intra-project edges only</b> — only store an edge when the
 *       resolved callee class is one of our own parsed classes.</li>
 *   <li><b>Persist in batch</b>.</li>
 * </ol>
 *
 * <h2>Error tolerance</h2>
 * <p>Failures to resolve a single call site are logged at DEBUG and skipped —
 * they never abort the build. This matches the ingestion error-tolerance pattern
 * established in Stage 3.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GraphBuilderService {

    private final CallGraphEdgeRepository edgeRepository;

    /**
     * Builds (or rebuilds) the call graph for one ingested repository.
     *
     * @param parsedFiles list of successfully parsed files from the ingestion pipeline
     * @param repoId      logical repository identifier (local path or Git URL)
     * @param repoRoot    root directory of the repository on disk
     * @return number of intra-project call edges persisted
     */
    @Transactional
    public int build(List<ParsedFile> parsedFiles, String repoId, Path repoRoot) {
        // Fallback for repos whose folder layout doesn't mirror package declarations:
        // mirror those files into a temp dir laid out by package. Always cleaned up.
        Path mirrorRoot = createPackageMirror(parsedFiles);
        try {
            return doBuild(parsedFiles, repoId, repoRoot, mirrorRoot);
        } finally {
            if (mirrorRoot != null) deleteQuietly(mirrorRoot);
        }
    }

    private int doBuild(List<ParsedFile> parsedFiles, String repoId, Path repoRoot, Path mirrorRoot) {
        if (parsedFiles.isEmpty()) {
            log.info("No parsed files for repoId='{}' — skipping graph build", repoId);
            return 0;
        }

        // ── 1. Delete stale edges ─────────────────────────────────────────────
        long existing = edgeRepository.countByRepoId(repoId);
        if (existing > 0) {
            log.info("Removing {} stale graph edge(s) for repoId='{}'", existing, repoId);
            edgeRepository.deleteByRepoId(repoId);
        }

        // ── 2. Build lookup: simple class name → ParsedFile + ParsedMethod ────
        //    Used to check whether a resolved callee is in-project and to look up
        //    its file/line metadata when creating an edge.
        Map<String, ParsedFile>   classToFile   = new HashMap<>();
        Map<String, ParsedMethod> classMethodKey = new HashMap<>(); // "ClassName#methodName" → ParsedMethod

        for (ParsedFile pf : parsedFiles) {
            for (ParsedClass pc : pf.classes()) {
                classToFile.put(pc.name(), pf);
                for (ParsedMethod pm : pc.methods()) {
                    classMethodKey.put(pc.name() + "#" + pm.name(), pm);
                }
            }
        }

        // ── 3. Locate Java source root for JavaParserTypeSolver ───────────────
        List<Path> sourceRoots = new ArrayList<>(findSourceRoots(repoRoot));
        if (mirrorRoot != null) sourceRoots.add(mirrorRoot);
        log.info("Graph build — repoId='{}', sourceRoots={}, files={}", repoId, sourceRoots, parsedFiles.size());

        // ── 4. Configure symbol solver (one shared instance for the whole build) ─
        //    One JavaParserTypeSolver per discovered source root (multi-module support).
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver(false));   // JDK types; false = do NOT restrict to JRE
        for (Path root : sourceRoots) {
            typeSolver.add(new JavaParserTypeSolver(root));
        }
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);

        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(symbolSolver);

        // ── 5. Re-parse files and extract edges ───────────────────────────────
        List<CallGraphEdge> edges = new ArrayList<>();
        int filesProcessed = 0;
        int resolveErrors  = 0;
        int resolvedCalls  = 0;
        int externalCalls  = 0;   // resolved, but callee is not a project class

        for (ParsedFile pf : parsedFiles) {
            Path filePath = Paths.get(pf.filePath());
            Optional<CompilationUnit> cuOpt = reparseWithSolver(filePath, config);
            if (cuOpt.isEmpty()) continue;

            CompilationUnit cu = cuOpt.get();
            filesProcessed++;

            // For each method in the CU ...
            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {

                // Identify caller metadata (class name + method name + location)
                String callerClassName = md.findAncestor(
                        com.github.javaparser.ast.body.TypeDeclaration.class)
                        .map(td -> td.getNameAsString())
                        .orElse(null);
                if (callerClassName == null) continue;

                String callerMethodName = md.getNameAsString();
                ParsedFile callerFile = classToFile.get(callerClassName);
                if (callerFile == null) continue;  // shouldn't happen, but guard

                ParsedMethod callerMethodMeta = classMethodKey.get(callerClassName + "#" + callerMethodName);
                int callerStart = callerMethodMeta != null ? callerMethodMeta.startLine() : -1;
                int callerEnd   = callerMethodMeta != null ? callerMethodMeta.endLine()   : -1;

                // For each method call inside this method's body ...
                for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
                    try {
                        ResolvedMethodDeclaration resolved = call.resolve();
                        resolvedCalls++;
                        String calleeClass  = resolved.getClassName();
                        String calleeMethod = resolved.getName();

                        // Only store intra-project edges
                        ParsedFile calleeFile = classToFile.get(calleeClass);
                        if (calleeFile == null) { externalCalls++; continue; }

                        ParsedMethod calleeMethodMeta = classMethodKey.get(calleeClass + "#" + calleeMethod);
                        int calleeStart = calleeMethodMeta != null ? calleeMethodMeta.startLine() : -1;
                        int calleeEnd   = calleeMethodMeta != null ? calleeMethodMeta.endLine()   : -1;

                        // Avoid self-loops (a method calling itself is not useful for impact analysis)
                        if (callerClassName.equals(calleeClass) && callerMethodName.equals(calleeMethod)) {
                            continue;
                        }

                        edges.add(CallGraphEdge.builder()
                                .repoId(repoId)
                                .callerClass(callerClassName)
                                .callerMethod(callerMethodName)
                                .callerFile(callerFile.filePath())
                                .callerStartLine(callerStart)
                                .callerEndLine(callerEnd)
                                .calleeClass(calleeClass)
                                .calleeMethod(calleeMethod)
                                .calleeFile(calleeFile.filePath())
                                .calleeStartLine(calleeStart)
                                .calleeEndLine(calleeEnd)
                                .build());

                    } catch (UnsolvedSymbolException e) {
                        // External or unresolvable call — expected, skip silently
                        resolveErrors++;
                        log.debug("Unresolved call '{}' in {}.{}: {}",
                                call.getNameAsString(), callerClassName, callerMethodName, e.getName());
                    } catch (Exception e) {
                        // Catch-all for other resolution failures (UnsupportedOperationException etc.)
                        resolveErrors++;
                        log.debug("Could not resolve call '{}' in {}.{}: {}",
                                call.getNameAsString(), callerClassName, callerMethodName, e.getMessage());
                    }
                }
            }
        }

        // ── 6. Deduplicate edges (same caller+callee pair may appear multiple times
        //    if a method calls another method more than once) ───────────────────
        List<CallGraphEdge> unique = edges.stream()
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(
                                e -> e.getCallerClass() + "#" + e.getCallerMethod()
                                   + "->" + e.getCalleeClass() + "#" + e.getCalleeMethod(),
                                e -> e,
                                (existing2, replacement) -> existing2  // keep first
                        ),
                        m -> new ArrayList<>(m.values())
                ));

        // ── 7. Persist ────────────────────────────────────────────────────────
        edgeRepository.saveAll(unique);

        log.info("Graph build complete — repoId='{}': {} edge(s) persisted, {} files processed, "
                        + "calls: {} resolved ({} to project classes, {} external), {} unresolved",
                repoId, unique.size(), filesProcessed, resolvedCalls,
                resolvedCalls - externalCalls, externalCalls, resolveErrors);

        return unique.size();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final java.util.regex.Pattern PACKAGE_DECL =
            java.util.regex.Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", java.util.regex.Pattern.MULTILINE);

    /**
     * For every parsed file whose parent directory does not end with its declared
     * package path (e.g. package {@code com.x.y} but folder {@code x/y}), copies it to
     * {@code <tmp>/com/x/y/File.java}. {@link JavaParserTypeSolver} resolves types by
     * package-to-directory mapping, so this lets malformed layouts resolve.
     *
     * @return the temp root, or {@code null} if no file needed mirroring
     */
    private Path createPackageMirror(List<ParsedFile> parsedFiles) {
        Path tmp = null;
        try {
            for (ParsedFile pf : parsedFiles) {
                Path file = Paths.get(pf.filePath());
                java.util.regex.Matcher m = PACKAGE_DECL.matcher(Files.readString(file));
                if (!m.find()) continue;
                Path pkgPath = Paths.get(m.group(1).replace('.', '/'));
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null && parent.endsWith(pkgPath)) continue;   // layout already correct
                if (tmp == null) {
                    tmp = Files.createTempDirectory("codecompass-pkgmirror-");
                    log.info("Package/folder mismatch detected — mirroring into '{}'", tmp);
                }
                Path dest = tmp.resolve(pkgPath);
                Files.createDirectories(dest);
                Files.copy(file, dest.resolve(file.getFileName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Package mirror creation failed: {}", e.getMessage());
        }
        return tmp;
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    /**
     * Discovers every Java source root within {@code repoRoot}.
     *
     * <p>Scans the whole tree for directories ending in {@code src/main/java}
     * (multi-module and nested layouts), skipping {@code .git}, {@code node_modules}
     * and {@code target}. If {@code repoRoot} itself is {@code .../src} (contains
     * {@code main/java}) that is added too. Falls back to {@code repoRoot} when
     * nothing is found.
     */
    static List<Path> findSourceRoots(Path repoRoot) {
        Set<Path> roots = new LinkedHashSet<>();

        Path direct = repoRoot.resolve("main/java");
        if (Files.isDirectory(direct)) roots.add(direct);

        try (Stream<Path> walk = Files.walk(repoRoot)) {
            walk.filter(Files::isDirectory)
                .filter(p -> {
                    Path rel = repoRoot.relativize(p);
                    for (Path part : rel) {
                        String n = part.toString();
                        if (n.equals(".git") || n.equals("node_modules") || n.equals("target")) return false;
                    }
                    return true;
                })
                .filter(p -> p.endsWith(Paths.get("src", "main", "java")))
                .forEach(roots::add);
        } catch (IOException e) {
            log.warn("Source root scan failed under '{}': {}", repoRoot, e.getMessage());
        }

        if (roots.isEmpty()) {
            log.warn("No src/main/java found under '{}'; using root directly", repoRoot);
            roots.add(repoRoot);
        }
        return new ArrayList<>(roots);
    }

    /**
     * Re-parses a single {@code .java} file using the given {@link ParserConfiguration}
     * (which must have a {@link JavaSymbolSolver} attached).
     *
     * @param filePath absolute path to the source file
     * @param config   parser config with symbol solver
     * @return the parsed {@link CompilationUnit}, or empty on failure
     */
    private Optional<CompilationUnit> reparseWithSolver(Path filePath, ParserConfiguration config) {
        try {
            JavaParser parser = new JavaParser(config);
            ParseResult<CompilationUnit> result = parser.parse(filePath);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                log.warn("Graph build: failed to re-parse '{}' — skipping", filePath.getFileName());
                return Optional.empty();
            }
            return result.getResult();
        } catch (IOException e) {
            log.warn("Graph build: I/O error reading '{}': {}", filePath.getFileName(), e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Graph build: unexpected error re-parsing '{}': {}", filePath.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }
}
