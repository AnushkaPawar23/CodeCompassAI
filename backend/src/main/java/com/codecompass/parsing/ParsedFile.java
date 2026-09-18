package com.codecompass.parsing;

import java.util.List;

/**
 * Immutable snapshot of everything extracted from a single .java source file
 * by {@link JavaAstParserService}.
 *
 * <p>Produced during Stage 2 (ingestion + parse). Used as input to Stage 3
 * (chunking + embedding) — no JPA entity yet.
 *
 * @param filePath    absolute path to the .java file on disk
 * @param packageName declared package, empty string if none (default package)
 * @param imports     fully-qualified import names declared in the file
 * @param classes     all top-level type declarations in the file
 * @param testFile    {@code true} when the file lives under {@code src/test/java}
 */
public record ParsedFile(
        String filePath,
        String packageName,
        List<String> imports,
        List<ParsedClass> classes,
        boolean testFile
) {}
