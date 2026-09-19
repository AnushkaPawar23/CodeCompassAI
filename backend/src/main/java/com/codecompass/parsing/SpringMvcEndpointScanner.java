package com.codecompass.parsing;

import com.codecompass.ingestion.FileWalkerService;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Stage 7 — scans an ingested repository's Java source tree and returns
 * all Spring MVC REST endpoints it declares.
 *
 * <h2>Detection strategy</h2>
 * <p>Pure AST scan — no symbol resolution or database access:
 * <ol>
 *   <li>Walk all {@code .java} files under the repository root using
 *       {@link FileWalkerService#walkJavaFiles}.</li>
 *   <li>Parse each file with {@link JavaParser} (Java 21, no symbol solver —
 *       same config as {@link JavaAstParserService#PARSER_CONFIG}).</li>
 *   <li>Identify controller classes: any {@link ClassOrInterfaceDeclaration}
 *       annotated with {@code @RestController} or {@code @Controller}.</li>
 *   <li>For each controller class, extract the class-level
 *       {@code @RequestMapping} path prefix (if present).</li>
 *   <li>For each method in the class, check for a Spring mapping annotation:
 *       {@code @GetMapping}, {@code @PostMapping}, {@code @PutMapping},
 *       {@code @DeleteMapping}, {@code @PatchMapping}, or
 *       {@code @RequestMapping}.</li>
 *   <li>Combine the class prefix with the method path and record an
 *       {@link EndpointDescriptor}.</li>
 * </ol>
 *
 * <h2>Annotation forms handled</h2>
 * <ul>
 *   <li>Marker: {@code @GetMapping} → path = {@code ""}</li>
 *   <li>Single-member: {@code @PostMapping("/api/ingest")} → path = {@code "/api/ingest"}</li>
 *   <li>Normal: {@code @RequestMapping(value = "/api", method = RequestMethod.GET)}</li>
 *   <li>Array value (first element taken):
 *       {@code @GetMapping({"/a", "/b"})} → path = {@code "/a"}</li>
 * </ul>
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li>Only the first path value is used for array-valued annotations.</li>
 *   <li>{@code @RequestMapping} on a method without an explicit {@code method}
 *       attribute is recorded as {@code "ANY"}.</li>
 *   <li>Abstract controller base-class patterns (prefix defined in a
 *       superclass) are not resolved — only the declaring class is checked.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpringMvcEndpointScanner {

    private final FileWalkerService fileWalker;

    // ── Annotation name constants ─────────────────────────────────────────────

    private static final String ANN_REST_CONTROLLER  = "RestController";
    private static final String ANN_CONTROLLER       = "Controller";
    private static final String ANN_REQUEST_MAPPING  = "RequestMapping";
    private static final String ANN_GET_MAPPING      = "GetMapping";
    private static final String ANN_POST_MAPPING     = "PostMapping";
    private static final String ANN_PUT_MAPPING      = "PutMapping";
    private static final String ANN_DELETE_MAPPING   = "DeleteMapping";
    private static final String ANN_PATCH_MAPPING    = "PatchMapping";

    /** All method-level mapping annotations. */
    private static final Set<String> METHOD_MAPPING_ANNOTATIONS = Set.of(
            ANN_GET_MAPPING, ANN_POST_MAPPING, ANN_PUT_MAPPING,
            ANN_DELETE_MAPPING, ANN_PATCH_MAPPING, ANN_REQUEST_MAPPING
    );

    /**
     * Immutable parser config — JAVA_21, no symbol resolver (not needed for
     * annotation extraction). Matches {@link JavaAstParserService#PARSER_CONFIG}.
     */
    private static final ParserConfiguration PARSER_CONFIG =
            new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Scans all Java source files under {@code repoRoot} and returns every
     * Spring MVC endpoint declared in the codebase.
     *
     * @param repoRoot root directory of the repository on disk
     * @return list of discovered endpoints; empty if none found or no
     *         controller classes are present
     * @throws IOException if the file tree cannot be walked
     */
    public List<EndpointDescriptor> scan(Path repoRoot) throws IOException {
        List<Path> javaFiles = fileWalker.walkJavaFiles(repoRoot);
        List<EndpointDescriptor> endpoints = new ArrayList<>();
        int filesScanned = 0;
        int parseErrors  = 0;

        for (Path file : javaFiles) {
            Optional<CompilationUnit> cuOpt = parseFile(file);
            if (cuOpt.isEmpty()) {
                parseErrors++;
                continue;
            }
            filesScanned++;
            endpoints.addAll(extractEndpoints(cuOpt.get(), file));
        }

        log.info("Endpoint scan complete — {} file(s) scanned, {} endpoint(s) found, {} parse error(s)",
                filesScanned, endpoints.size(), parseErrors);
        return endpoints;
    }

    // ── Extraction helpers ────────────────────────────────────────────────────

    /**
     * Extracts all endpoint descriptors from a single parsed compilation unit.
     */
    private List<EndpointDescriptor> extractEndpoints(CompilationUnit cu, Path filePath) {
        List<EndpointDescriptor> results = new ArrayList<>();

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            // Only process classes annotated with @RestController or @Controller
            if (!isController(cls)) continue;

            String className   = cls.getNameAsString();
            String classPrefix = extractClassPrefix(cls);
            String absFilePath = filePath.toAbsolutePath().toString();

            for (MethodDeclaration method : cls.getMethods()) {
                for (AnnotationExpr ann : method.getAnnotations()) {
                    String annName = ann.getNameAsString();
                    if (!METHOD_MAPPING_ANNOTATIONS.contains(annName)) continue;

                    String httpMethod = resolveHttpMethod(annName, ann);
                    String methodPath = extractAnnotationPath(ann).orElse("");
                    String fullPath   = joinPaths(classPrefix, methodPath);
                    int    startLine  = method.getRange()
                                              .map(r -> r.begin.line)
                                              .orElse(-1);

                    results.add(new EndpointDescriptor(
                            httpMethod,
                            fullPath,
                            className,
                            method.getNameAsString(),
                            absFilePath,
                            startLine
                    ));

                    log.debug("Endpoint found: {} {} → {}.{}() at line {}",
                            httpMethod, fullPath, className, method.getNameAsString(), startLine);

                    // One method may have only one mapping annotation in practice;
                    // break after the first match to avoid duplicates if somehow
                    // multiple mapping annotations are stacked.
                    break;
                }
            }
        }

        return results;
    }

    // ── Annotation interpretation ─────────────────────────────────────────────

    /**
     * Returns {@code true} if the class has a {@code @RestController} or
     * {@code @Controller} annotation (checked by simple name, without import
     * resolution).
     */
    private static boolean isController(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .anyMatch(n -> ANN_REST_CONTROLLER.equals(n) || ANN_CONTROLLER.equals(n));
    }

    /**
     * Extracts the path prefix from a class-level {@code @RequestMapping}
     * annotation.  Returns an empty string if no such annotation is present.
     */
    private static String extractClassPrefix(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotations().stream()
                .filter(a -> ANN_REQUEST_MAPPING.equals(a.getNameAsString()))
                .findFirst()
                .flatMap(SpringMvcEndpointScanner::extractAnnotationPath)
                .orElse("");
    }

    /**
     * Maps a mapping annotation name (and annotation expression for
     * {@code @RequestMapping}) to its HTTP verb string.
     *
     * <p>For {@code @RequestMapping} the {@code method} attribute is inspected.
     * If absent, {@code "ANY"} is returned (the endpoint matches all verbs).
     */
    private static String resolveHttpMethod(String annName, AnnotationExpr ann) {
        return switch (annName) {
            case ANN_GET_MAPPING    -> "GET";
            case ANN_POST_MAPPING   -> "POST";
            case ANN_PUT_MAPPING    -> "PUT";
            case ANN_DELETE_MAPPING -> "DELETE";
            case ANN_PATCH_MAPPING  -> "PATCH";
            default -> {
                // @RequestMapping — look for method = RequestMethod.XXX
                if (ann instanceof NormalAnnotationExpr na) {
                    yield na.getPairs().stream()
                            .filter(p -> "method".equals(p.getNameAsString()))
                            .findFirst()
                            .map(MemberValuePair::getValue)
                            .map(SpringMvcEndpointScanner::extractRequestMethodVerb)
                            .orElse("ANY");
                }
                yield "ANY";
            }
        };
    }

    /**
     * Extracts the verb name from a {@code RequestMethod.XXX} field-access
     * expression (e.g. {@code RequestMethod.GET} → {@code "GET"}).
     *
     * <p>Also handles plain name expressions (e.g. just {@code GET}) in case
     * the import was star-imported.
     */
    private static String extractRequestMethodVerb(Expression expr) {
        if (expr instanceof FieldAccessExpr fa) {
            // RequestMethod.GET → field name is "GET"
            return fa.getNameAsString().toUpperCase();
        }
        // Fallback: treat the expression string representation as the verb
        return expr.toString().toUpperCase();
    }

    /**
     * Extracts the first path string from a mapping annotation, handling all
     * three annotation syntactic forms:
     * <ul>
     *   <li>{@link SingleMemberAnnotationExpr}: {@code @GetMapping("/path")}</li>
     *   <li>{@link NormalAnnotationExpr}: {@code @RequestMapping(value = "/path")}</li>
     *   <li>Marker annotation: {@code @GetMapping} → empty string</li>
     * </ul>
     *
     * @return the path string, or empty if the annotation carries no path
     */
    private static Optional<String> extractAnnotationPath(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr sma) {
            return extractFirstString(sma.getMemberValue());
        }
        if (ann instanceof NormalAnnotationExpr na) {
            return na.getPairs().stream()
                    .filter(p -> "value".equals(p.getNameAsString())
                              || "path".equals(p.getNameAsString()))
                    .findFirst()
                    .flatMap(p -> extractFirstString(p.getValue()));
        }
        // MarkerAnnotationExpr — e.g. @GetMapping with no arguments
        return Optional.of("");
    }

    /**
     * Pulls the first {@link StringLiteralExpr} from an expression —
     * handles both plain strings and array initialisers.
     */
    private static Optional<String> extractFirstString(Expression expr) {
        if (expr instanceof StringLiteralExpr s) {
            return Optional.of(s.asString());
        }
        if (expr instanceof ArrayInitializerExpr arr) {
            return arr.getValues().stream()
                    .filter(v -> v instanceof StringLiteralExpr)
                    .findFirst()
                    .map(v -> ((StringLiteralExpr) v).asString());
        }
        return Optional.empty();
    }

    // ── Path utilities ────────────────────────────────────────────────────────

    /**
     * Combines a class-level path prefix with a method-level path suffix.
     *
     * <p>Rules:
     * <ul>
     *   <li>Trailing slash on {@code prefix} is removed.</li>
     *   <li>If {@code suffix} does not start with {@code "/"} and is non-empty,
     *       a {@code "/"} separator is prepended.</li>
     *   <li>If both are empty, returns {@code "/"}.</li>
     * </ul>
     *
     * @param prefix class-level path (may be null or empty)
     * @param suffix method-level path (may be null or empty)
     * @return combined path string
     */
    private static String joinPaths(String prefix, String suffix) {
        if (prefix == null) prefix = "";
        if (suffix == null) suffix = "";

        // Strip trailing slash from prefix
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        // Ensure suffix starts with "/" if non-empty
        if (!suffix.isEmpty() && !suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }

        String combined = prefix + suffix;
        return combined.isEmpty() ? "/" : combined;
    }

    // ── File parsing ──────────────────────────────────────────────────────────

    /**
     * Parses a single {@code .java} file, returning empty on failure.
     * A new {@link JavaParser} is created per call (consistent with
     * {@link JavaAstParserService} thread-safety approach).
     */
    private static Optional<CompilationUnit> parseFile(Path filePath) {
        try {
            JavaParser parser = new JavaParser(PARSER_CONFIG);
            ParseResult<CompilationUnit> result = parser.parse(filePath);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                log.debug("Skipping '{}' — parse not successful", filePath.getFileName());
                return Optional.empty();
            }
            return result.getResult();
        } catch (Exception e) {
            log.warn("Cannot parse '{}': {}", filePath.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }
}
