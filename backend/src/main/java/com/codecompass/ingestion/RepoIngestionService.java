package com.codecompass.ingestion;

import com.codecompass.graph.GraphBuilderService;
import com.codecompass.parsing.JavaAstParserService;
import com.codecompass.parsing.ParsedFile;
import com.codecompass.rag.ChunkingService;
import com.codecompass.rag.CodeChunk;
import com.codecompass.rag.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stage 2 + 3 + 5 — top-level ingestion orchestrator.
 *
 * <p>Accepts either a <em>local directory path</em> or a <em>Git URL</em>:
 * <ul>
 *   <li><b>local</b> — resolves the path and uses it directly.</li>
 *   <li><b>git</b> — clones the repository into a UUID-named subdirectory
 *       under {@code codecompass.clone-base-dir} via JGit, processes it,
 *       then deletes the clone on completion (success or failure).</li>
 * </ul>
 *
 * <p><b>Stage 2</b> delegates file collection to {@link FileWalkerService} and
 * AST parsing to {@link JavaAstParserService}.
 *
 * <p><b>Stage 3</b> extends the pipeline: after parsing, the service passes the
 * parsed files through {@link ChunkingService} (producing {@link CodeChunk} objects)
 * and then {@link EmbeddingService} (generating embeddings and persisting to
 * PostgreSQL). The final {@link IngestionResult} includes a {@code chunksCreated}
 * count.
 *
 * <p><b>Stage 5</b> adds call-graph construction: after embedding, the service
 * calls {@link GraphBuilderService#build} to extract intra-project method call
 * edges and persist them in the {@code call_graph_edge} table. The result includes
 * a {@code graphEdgesCreated} count.
 *
 * <p>Files that fail to parse are recorded in {@link IngestionResult#failedFiles()}
 * and do not abort the run. Chunks that fail to embed are similarly skipped.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RepoIngestionService {

    private final FileWalkerService fileWalker;
    private final JavaAstParserService astParser;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final GraphBuilderService graphBuilderService;

    @Value("${codecompass.clone-base-dir}")
    private String cloneBaseDir;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Ingest a repository: parse → chunk → embed → build call graph.
     *
     * @param source a local filesystem path or a Git remote URL
     * @param isGit  {@code true} → clone via JGit; {@code false} → use path directly
     * @return aggregated {@link IngestionResult} with parsed files, failure list,
     *         chunk count, and graph edge count
     * @throws IllegalArgumentException if the local path does not exist / is not a directory
     * @throws Exception                wraps JGit or I/O errors
     */
    public IngestionResult ingest(String source, boolean isGit) throws Exception {
        Path repoRoot;
        Path tempCloneDir = null;

        if (isGit) {
            tempCloneDir = cloneRepo(source);
            repoRoot = tempCloneDir;
        } else {
            repoRoot = Paths.get(source).toAbsolutePath().normalize();
            if (!Files.isDirectory(repoRoot)) {
                throw new IllegalArgumentException(
                        "Local path does not exist or is not a directory: " + source);
            }
        }

        try {
            return parseAndEmbed(repoRoot, source);
        } finally {
            if (tempCloneDir != null) {
                deleteDirectory(tempCloneDir);
                log.info("Cleaned up temp clone dir: {}", tempCloneDir);
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Path cloneRepo(String gitUrl) throws Exception {
        Path baseDir = Paths.get(cloneBaseDir);
        Files.createDirectories(baseDir);
        Path targetDir = baseDir.resolve(UUID.randomUUID().toString());
        log.info("Cloning '{}' → '{}'", gitUrl, targetDir);

        // Auto-closed Git handle flushes ref-locks before we read the dir
        try (Git git = Git.cloneRepository()
                .setURI(gitUrl)
                .setDirectory(targetDir.toFile())
                .call()) {
            log.info("Clone complete ({})", git.getRepository().getDirectory());
        }
        return targetDir;
    }

    /**
     * Stage 2 + 3 + 5 pipeline: walk → parse → chunk → embed → build graph.
     *
     * @param repoRoot root directory of the repository on disk
     * @param repoId   logical identifier for this repo (path or Git URL)
     */
    private IngestionResult parseAndEmbed(Path repoRoot, String repoId) throws IOException {

        // ── Stage 2: parse ────────────────────────────────────────────────────
        List<Path> javaFiles = fileWalker.walkJavaFiles(repoRoot);
        List<ParsedFile> parsed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (Path file : javaFiles) {
            boolean isTest = fileWalker.isTestFile(file, repoRoot);
            astParser.parse(file, isTest).ifPresentOrElse(
                    parsed::add,
                    () -> failed.add(repoRoot.relativize(file).toString().replace('\\', '/'))
            );
        }

        log.info("Parse summary — {} ok, {} failed out of {} file(s)",
                parsed.size(), failed.size(), javaFiles.size());

        // ── Stage 3: chunk → embed → persist ─────────────────────────────────
        List<CodeChunk> chunks = chunkingService.chunk(parsed, repoId);
        int chunksCreated = embeddingService.embedAndSave(chunks, repoId);

        // ── Stage 5: build call graph ─────────────────────────────────────────
        int graphEdgesCreated = graphBuilderService.build(parsed, repoId, repoRoot);

        return new IngestionResult(parsed, failed, chunksCreated, graphEdgesCreated);
    }

    /** Best-effort recursive delete; warnings are logged but never thrown. */
    private void deleteDirectory(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Could not fully delete temp dir '{}': {}", dir, e.getMessage());
        }
    }

    // ── Result type ───────────────────────────────────────────────────────────

    /**
     * Aggregated output of one ingestion run.
     *
     * @param parsedFiles      successfully parsed .java files (with full metadata)
     * @param failedFiles      relative paths of files that could not be parsed
     * @param chunksCreated    number of code chunks successfully embedded and stored
     * @param graphEdgesCreated number of intra-project call-graph edges persisted (Stage 5)
     */
    public record IngestionResult(
            List<ParsedFile> parsedFiles,
            List<String> failedFiles,
            int chunksCreated,
            int graphEdgesCreated
    ) {}
}
