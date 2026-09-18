package com.codecompass.parsing;

import java.util.List;

/**
 * Immutable snapshot of a single method extracted from a .java source file.
 *
 * <p>Populated by {@link JavaAstParserService}; consumed by
 * {@code ChunkingService} (Stage 3) to produce {@code CodeChunk} objects.
 *
 * @param name           simple method name (e.g. {@code findById})
 * @param returnType     return type as a string (e.g. {@code Optional<Student>})
 * @param parameterTypes ordered list of parameter type names (e.g. {@code ["Long", "String"]})
 * @param modifiers      keyword modifiers in lowercase (e.g. {@code ["public", "static"]})
 * @param bodyText       full source text of the method declaration (annotations + signature + body)
 * @param startLine      1-based line number where this method declaration begins in the file
 * @param endLine        1-based line number where this method declaration ends in the file
 */
public record ParsedMethod(
        String name,
        String returnType,
        List<String> parameterTypes,
        List<String> modifiers,
        String bodyText,
        int startLine,
        int endLine
) {}
