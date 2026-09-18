package com.codecompass.parsing;

import java.util.List;

/**
 * Immutable snapshot of one type declaration (class / interface / enum / annotation)
 * found within a .java source file.
 *
 * <p>Populated by {@link JavaAstParserService}; consumed by
 * {@code ChunkingService} (Stage 3) to produce {@code CodeChunk} objects.
 *
 * @param name      simple type name (e.g. {@code StudentRepository})
 * @param classType one of {@code "CLASS"}, {@code "INTERFACE"}, {@code "ENUM"}, {@code "ANNOTATION"}
 * @param methods   all method declarations extracted from this type
 * @param bodyText  full source text of the type declaration (annotations + class body)
 * @param startLine 1-based line number where the type declaration begins in the file
 * @param endLine   1-based line number where the type declaration ends in the file
 */
public record ParsedClass(
        String name,
        String classType,
        List<ParsedMethod> methods,
        String bodyText,
        int startLine,
        int endLine
) {}
