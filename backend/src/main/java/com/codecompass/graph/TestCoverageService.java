package com.codecompass.graph;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * Stage 8b — heuristic test-coverage detector.
 *
 * <h2>Purpose</h2>
 * <p>Given a {@code (repoPath, className, methodName)} triple, scans the
 * repository's {@code src/test/java} directory for {@code .java} files whose
 * content references both the target class and the target method name.  The
 * check is intentionally simple — it does <em>not</em> parse ASTs or resolve
 * types — but provides a reliable signal for the common case where tests
 * directly call or mock the method under analysis.
 *
 * <h2>Heuristic</h2>
 * <p>A test file is considered to cover a method if its raw text contains
 * <em>both</em> the target class name and the target method name (case-sensitive
 * substring match).  This catches:
 * <ul>
 *   <li>Direct test calls: {@code chunkingService.chunk(...)}</li>
 *   <li>Mockito stubs: {@code when(mock.chunk(...))}</li>
 *   <li>Verify calls: {@code verify(mock).chunk(...)}</li>
 *   <li>Annotation references: {@code @Test /* ChunkingService#chunk *\/}</li>
 * </ul>
 *
 * <h2>Limitations</h2>
 * <p>False positives are possible if a method name is generic (e.g. {@code get},
 * {@code run}) — the class-name requirement reduces but does not eliminate this.
 * False negatives occur when tests use indirect invocation or the test directory
 * is located outside {@code src/test/java}.  Both are acceptable trade-offs for
 * a Stage 8 heuristic.
 *
 * <h2>Test-directory resolution</h2>
 * <p>The service derives the test root as {@code <repoPath>/src/test/java}.
 * {@code repoPath} is the same string supplied as {@code repoId} to
 * {@code POST /api/ingest} (usually an absolute path to a source directory or
 * repo root).  If the test directory does not exist the method returns
 * {@code false} and logs a warning.
 */
@Service
@Slf4j
public class TestCoverageService {

    /**
     * Returns {@code true} if at least one test file under
     * {@code <repoPath>/src/test/java} contains both {@code className}
     * and {@code methodName} as substrings.
     *
     * @param repoPath   absolute path to the repository root (same value as
     *                   the {@code repoId} field used during ingestion)
     * @param className  simple class name of the method being checked
     *                   (e.g. {@code "ChunkingService"})
     * @param methodName simple method name being checked (e.g. {@code "chunk"})
     * @return {@code true} if test coverage evidence is found; {@code false}
     *         otherwise
     */
    public boolean hasTestCoverage(String repoPath, String className, String methodName) {

        // ── Resolve the test root ──────────────────────────────────────────────
        Path testRoot = resolveTestRoot(repoPath);
        if (testRoot == null || !Files.isDirectory(testRoot)) {
            log.debug("Test-coverage check — test root not found under '{}'; assuming no coverage for {}.{}",
                    repoPath, className, methodName);
            return false;
        }

        log.debug("Test-coverage check — scanning '{}' for references to {}.{}",
                testRoot, className, methodName);

        // ── Walk all .java files ───────────────────────────────────────────────
        try (Stream<Path> walk = Files.walk(testRoot)) {
            List<Path> testFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();

            log.debug("Test-coverage check — {} test file(s) to scan for {}.{}",
                    testFiles.size(), className, methodName);

            for (Path testFile : testFiles) {
                if (fileContainsBoth(testFile, className, methodName)) {
                    log.debug("Test-coverage check — coverage evidence found in '{}' for {}.{}",
                            testFile.getFileName(), className, methodName);
                    return true;
                }
            }

        } catch (IOException e) {
            log.warn("Test-coverage check — I/O error while scanning '{}' for {}.{}: {}",
                    testRoot, className, methodName, e.getMessage());
            return false;
        }

        log.debug("Test-coverage check — no coverage evidence found for {}.{}", className, methodName);
        return false;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves the {@code src/test/java} directory relative to {@code repoPath}.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Try {@code <repoPath>/src/test/java} (repo root → standard Maven layout).</li>
     *   <li>Walk up from {@code repoPath} to find a parent that contains
     *       {@code src/test/java} (handles the case where {@code repoPath} points
     *       at {@code src/main/java} rather than the repo root).</li>
     * </ol>
     */
    private Path resolveTestRoot(String repoPath) {
        Path base = Paths.get(repoPath);

        // Attempt 1 — treat repoPath as repo root
        Path direct = base.resolve("src/test/java");
        if (Files.isDirectory(direct)) {
            return direct;
        }

        // Attempt 2 — walk up up to 4 levels looking for src/test/java sibling
        Path current = base;
        for (int i = 0; i < 4; i++) {
            current = current.getParent();
            if (current == null) break;
            Path candidate = current.resolve("src/test/java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Returns {@code true} if the file at {@code path} contains both
     * {@code className} and {@code methodName} as substrings (case-sensitive).
     */
    private boolean fileContainsBoth(Path path, String className, String methodName) {
        try {
            String content = Files.readString(path);
            return content.contains(className) && content.contains(methodName);
        } catch (IOException e) {
            log.trace("Test-coverage check — could not read '{}': {}", path, e.getMessage());
            return false;
        }
    }
}
