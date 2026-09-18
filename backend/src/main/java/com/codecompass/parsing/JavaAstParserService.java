package com.codecompass.parsing;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithRange;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stage 2 / 3 — AST parsing service.
 *
 * <p>Parses a single {@code .java} source file into a {@link CompilationUnit}
 * and extracts structured metadata into the
 * {@link ParsedFile} / {@link ParsedClass} / {@link ParsedMethod} record
 * hierarchy.
 *
 * <h2>Thread-safety design</h2>
 * <p>The previous implementation called {@link com.github.javaparser.StaticJavaParser}
 * and mutated its singleton {@link ParserConfiguration} from a
 * {@code @PostConstruct} method.  This pattern is <em>not thread-safe</em>:
 * if two threads call {@code parse()} concurrently one may read the old
 * Java-8 defaults while the other is writing the Java-21 config, causing
 * the record / text-block parse failures observed in Stage 3.
 *
 * <p>The fix: {@link #PARSER_CONFIG} is a <em>static, immutable</em>
 * {@link ParserConfiguration} built once at class-load time.  Each call to
 * {@link #parse} constructs a <em>new</em> {@link JavaParser} instance
 * wrapping that config — {@link JavaParser} itself is <em>not</em> thread-safe,
 * but creating one per call is cheap and eliminates all shared mutable state.
 *
 * <h2>Language level</h2>
 * <p>{@link ParserConfiguration.LanguageLevel#JAVA_21} enables records,
 * text blocks, sealed classes, switch expressions, and pattern {@code instanceof}
 * matching.
 *
 * <h2>Error handling</h2>
 * <p>Parse failures are handled gracefully: the offending file is logged at
 * WARN level and {@link Optional#empty()} is returned so callers can skip it
 * without crashing the whole ingestion run.
 */
@Service
@Slf4j
public class JavaAstParserService {

    /**
     * Sentinel value used when JavaParser cannot determine a line number
     * (e.g. synthetic or incomplete AST nodes).
     */
    private static final int UNKNOWN_LINE = -1;

    /**
     * Immutable parser configuration shared across all parse calls.
     *
     * <p>{@link ParserConfiguration} is safe to share as long as it is never
     * mutated after construction — we only call setters here in the static
     * initialiser and never again.  Each {@link JavaParser} instance created
     * in {@link #parse} receives a reference to this config but does not
     * modify it.
     */
    private static final ParserConfiguration PARSER_CONFIG =
            new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    /**
     * Parse one .java file and return structured metadata, or empty on failure.
     *
     * <p>A new {@link JavaParser} instance is created for each invocation —
     * this is the correct way to use the JavaParser API in a multi-threaded
     * environment.  The cost is negligible (a single object allocation).
     *
     * @param filePath   absolute path to the source file
     * @param isTestFile whether the file lives under {@code src/test/java}
     * @return populated {@link ParsedFile}, or {@link Optional#empty()} if
     *         parsing failed
     */
    public Optional<ParsedFile> parse(Path filePath, boolean isTestFile) {
        try {
            // New JavaParser per call — no shared mutable state
            JavaParser parser = new JavaParser(PARSER_CONFIG);
            ParseResult<CompilationUnit> result = parser.parse(filePath);

            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                String problems = result.getProblems().stream()
                        .map(p -> p.getVerboseMessage())
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("unknown parse error");
                log.warn("Failed to parse '{}': {}", filePath, problems);
                return Optional.empty();
            }

            CompilationUnit cu = result.getResult().get();

            // ── Package ──────────────────────────────────────────────────
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            // ── Imports ──────────────────────────────────────────────────
            List<String> imports = cu.getImports().stream()
                    .map(id -> id.getNameAsString() + (id.isAsterisk() ? ".*" : ""))
                    .toList();

            // ── Type declarations ─────────────────────────────────────────
            List<ParsedClass> classes = new ArrayList<>();
            for (TypeDeclaration<?> type : cu.getTypes()) {
                classes.add(extractClass(type));
            }

            return Optional.of(new ParsedFile(
                    filePath.toAbsolutePath().toString(),
                    packageName,
                    imports,
                    classes,
                    isTestFile
            ));

        } catch (Exception e) {
            log.warn("Failed to parse '{}': {}", filePath, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ParsedClass extractClass(TypeDeclaration<?> type) {
        String classType = resolveClassType(type);

        List<ParsedMethod> methods = new ArrayList<>();
        for (MethodDeclaration method : type.getMethods()) {
            methods.add(extractMethod(method));
        }

        String bodyText = type.toString();
        int startLine = startLine(type);
        int endLine   = endLine(type);

        return new ParsedClass(type.getNameAsString(), classType, methods, bodyText, startLine, endLine);
    }

    private ParsedMethod extractMethod(MethodDeclaration method) {
        List<String> paramTypes = method.getParameters().stream()
                .map(p -> p.getType().asString())
                .toList();

        List<String> modifiers = method.getModifiers().stream()
                .map(m -> m.getKeyword().asString().toLowerCase())
                .toList();

        String bodyText = method.toString();
        int startLine   = startLine(method);
        int endLine     = endLine(method);

        return new ParsedMethod(
                method.getNameAsString(),
                method.getType().asString(),
                paramTypes,
                modifiers,
                bodyText,
                startLine,
                endLine
        );
    }

    /**
     * Maps a JavaParser {@link TypeDeclaration} to a display string.
     *
     * <p>Handles all Java type kinds including {@link RecordDeclaration}
     * (Java 16+) which previously fell through to {@code "OTHER"}.
     */
    private static String resolveClassType(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration coid) {
            return coid.isInterface() ? "INTERFACE" : "CLASS";
        }
        if (type instanceof RecordDeclaration) {
            return "RECORD";
        }
        if (type instanceof EnumDeclaration) {
            return "ENUM";
        }
        if (type instanceof AnnotationDeclaration) {
            return "ANNOTATION";
        }
        return "OTHER";
    }

    /** Returns the 1-based start line of a node, or {@link #UNKNOWN_LINE} if absent. */
    private static int startLine(NodeWithRange<?> node) {
        return node.getRange().map(r -> r.begin.line).orElse(UNKNOWN_LINE);
    }

    /** Returns the 1-based end line of a node, or {@link #UNKNOWN_LINE} if absent. */
    private static int endLine(NodeWithRange<?> node) {
        return node.getRange().map(r -> r.end.line).orElse(UNKNOWN_LINE);
    }
}
