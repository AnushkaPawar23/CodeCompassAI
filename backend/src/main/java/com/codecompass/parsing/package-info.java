/**
 * Parsing package – Stage 2 / Stage 5
 *
 * Responsible for:
 *  - AST-level parsing of Java source files via JavaParser
 *  - Extracting method and class chunks (name, body, file path, line range)
 *  - Building the static call graph (MethodCallExpr → resolved callee)
 *  - Detecting Spring REST endpoint annotations (@RestController, @GetMapping, …)
 *
 * Key classes (added in Stage 2 and Stage 5):
 *  - AstParser              : visits each .java file and extracts CodeChunks
 *  - CodeChunk              : JPA entity representing one method or class chunk
 *  - CallGraphBuilder       : VoidVisitorAdapter that records caller→callee edges
 */
@NonNullApi
package com.codecompass.parsing;

import org.springframework.lang.NonNullApi;
