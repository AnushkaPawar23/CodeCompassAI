package com.codecompass.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Stage 2 — recursive .java file walker.
 *
 * <p>Walks a repository root using {@link Files#walkFileTree} and collects every
 * {@code .java} file. Common build-artifact and IDE directories are pruned from
 * the traversal so they are never visited.
 *
 * <p>{@code src/test/java} files are included but flagged via
 * {@link #isTestFile(Path, Path)} so callers can record them with a
 * {@code testFile=true} marker.
 */
@Service
@Slf4j
public class FileWalkerService {

    /**
     * Directory names that are skipped entirely (subtree is pruned).
     * These are common build outputs, VCS internals, and IDE artifacts.
     */
    private static final Set<String> SKIP_DIRS = Set.of(
            "target",       // Maven build output
            "build",        // Gradle build output
            "out",          // IntelliJ build output
            "bin",          // Eclipse build output
            ".git",         // Git internals
            ".idea",        // IntelliJ project files
            ".mvn",         // Maven wrapper
            ".gradle",      // Gradle cache
            "node_modules", // Frontend deps (mixed repos)
            ".settings",    // Eclipse settings
            "__pycache__"   // Python artifacts (mixed repos)
    );

    /**
     * Recursively collect all {@code .java} files under {@code root},
     * skipping {@link #SKIP_DIRS}.
     *
     * @param root repository root directory
     * @return ordered list of absolute paths to every discovered .java file
     * @throws IOException if the file tree cannot be walked
     */
    public List<Path> walkJavaFiles(Path root) throws IOException {
        List<Path> javaFiles = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (SKIP_DIRS.contains(name)) {
                    log.debug("Pruning directory from walk: {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    javaFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("Cannot visit '{}': {}", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        log.info("File walk complete — found {} .java file(s) under '{}'", javaFiles.size(), root);
        return javaFiles;
    }

    /**
     * Returns {@code true} when {@code file} lives inside a test source tree.
     *
     * <p>Detection is path-based: the relative path (from repo root) is checked
     * for the {@code src/test/java} or {@code src/test/} segments, which cover
     * both Maven and Gradle standard layouts.
     *
     * @param file     absolute path to the .java file
     * @param repoRoot absolute path to the repository root (used to relativise)
     */
    public boolean isTestFile(Path file, Path repoRoot) {
        // Normalise separators so the check works on Windows too
        String relative = repoRoot.relativize(file).toString().replace('\\', '/');
        return relative.contains("src/test/java/") || relative.contains("src/test/");
    }
}
