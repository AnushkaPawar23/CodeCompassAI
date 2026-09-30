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
import java.util.regex.Pattern;

/**
 * Stage 2 + 3 + 5 — top-level ingestion orchestrator.
 *
 * <p>Accepts either a <em>local directory path</em> or a <em>Git URL</em>:
 * <ul>
 *   <li><b>local</b> — resolves the path and uses it directly.</li>
 *   <li><b>git</b> — clones the repository into a <em>persistent</em> cache
 *       directory under {@code codecompass.clone-base-dir}, keyed by a
 *       sanitised slug derived from the Git URL.  The clone is kept on disk
 *       after ingestion so that Impact Analysis, API Explorer, and other
 *       on-demand disk scanners can find the source files later.  Re-ingesting
 *       the same URL fully deletes the old clone first (including clearing
 *       Windows read-only attributes on {@code .git} files) and verifies the
 *       directory is gone before starting the fresh clone.</li>
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

    /** Characters that are illegal in directory names on Windows and/or Unix. */
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");

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
     * <p>For Git sources the clone is kept in a persistent cache directory so
     * that later on-demand disk operations (Impact Analysis test-coverage scan,
     * API Explorer endpoint scan) can still access the files.
     *
     * @param source a local filesystem path or a Git remote URL
     * @param isGit  {@code true} → clone via JGit; {@code false} → use path directly
     * @return aggregated {@link IngestionResult} including the resolved
     *         {@code repoId} (persistent cache path for Git repos)
     * @throws IllegalArgumentException if the local path does not exist / is not a directory
     * @throws Exception                wraps JGit or I/O errors
     */
    public IngestionResult ingest(String source, boolean isGit) throws Exception {
        Path repoRoot;
        String repoId;

        if (isGit) {
            // Clone into a persistent, URL-keyed cache directory.
            // Re-ingesting the same URL will delete and re-clone.
            repoRoot = cloneRepoPersistent(source);
            repoId   = repoRoot.toString();
        } else {
            repoRoot = Paths.get(source).toAbsolutePath().normalize();
            if (!Files.isDirectory(repoRoot)) {
                throw new IllegalArgumentException(
                        "Local path does not exist or is not a directory: " + source);
            }
            repoId = repoRoot.toString();
        }

        return parseAndEmbed(repoRoot, repoId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Derives a filesystem-safe slug from a Git URL for use as a cache
     * directory name.  Replaces any character that is not alphanumeric,
     * a dot, a dash, or an underscore with an underscore, then trims to
     * at most 120 characters to avoid path-length issues on Windows.
     */
    private static String urlSlug(String gitUrl) {
        String slug = UNSAFE_CHARS.matcher(gitUrl).replaceAll("_");
        return slug.length() > 120 ? slug.substring(slug.length() - 120) : slug;
    }

    /**
     * Clones {@code gitUrl} into a <em>persistent</em> cache directory.
     *
     * <p>Sequence guaranteed:
     * <ol>
     *   <li>Check if the target directory already exists.</li>
     *   <li>If yes, fully delete it via {@link #deleteDirectoryForReclone}
     *       (clears Windows read-only bits, throws on any failure).</li>
     *   <li>Verify the directory is actually gone — throws if not.</li>
     *   <li>Only then call JGit's clone.</li>
     * </ol>
     *
     * @param gitUrl the remote Git repository URL
     * @return the path to the cloned repository root
     */
    private Path cloneRepoPersistent(String gitUrl) throws Exception {
        Path baseDir  = Paths.get(cloneBaseDir);
        Files.createDirectories(baseDir);

        Path targetDir = baseDir.resolve(urlSlug(gitUrl));

        // ── Step 1: delete stale clone if it exists ───────────────────────────
        if (Files.exists(targetDir)) {
            log.info("[re-ingest] Stale cache found at '{}' — beginning deletion now", targetDir);

            // Retry loop: OneDrive / antivirus may briefly hold locks on .git files.
            // Wait up to ~3.5 s total (500 ms, 1 s, 2 s between attempts).
            int maxAttempts = 4;
            long[] delayMs  = {0, 500, 1000, 2000};
            IOException lastErr = null;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                if (delayMs[attempt - 1] > 0) {
                    log.info("[re-ingest] Delete attempt {} of {} — waiting {}ms before retry",
                            attempt, maxAttempts, delayMs[attempt - 1]);
                    try { Thread.sleep(delayMs[attempt - 1]); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while waiting to retry directory deletion", ie);
                    }
                }
                try {
                    deleteDirectoryForReclone(targetDir);
                    lastErr = null;
                    break; // success
                } catch (IOException e) {
                    lastErr = e;
                    log.warn("[re-ingest] Delete attempt {} failed: {}", attempt, e.getMessage());
                }
            }

            if (lastErr != null) {
                throw new IOException(
                        "Failed to delete stale clone directory '" + targetDir +
                        "' after " + maxAttempts + " attempts. " +
                        "OneDrive or another process may be holding a lock. " +
                        "Try pausing OneDrive sync and re-ingesting. Last error: " + lastErr.getMessage(),
                        lastErr);
            }

            // ── Step 2: verify deletion completed ─────────────────────────────
            if (Files.exists(targetDir)) {
                throw new IOException(
                        "Failed to fully delete stale clone directory '" + targetDir +
                        "' — cannot clone into a non-empty directory. " +
                        "Close any applications that may have files open inside it and retry.");
            }
            log.info("[re-ingest] Stale cache fully deleted. Proceeding to fresh clone.");
        }


        // ── Step 3: clone fresh (only reached after confirmed deletion) ───────
        log.info("Cloning '{}' → '{}' (persistent cache)", gitUrl, targetDir);

        try (Git git = Git.cloneRepository()
                .setURI(gitUrl)
                .setDirectory(targetDir.toFile())
                .call()) {
            log.info("Clone complete — {} (cached at '{}')",
                    git.getRepository().getDirectory(), targetDir);
        }
        return targetDir;
    }

    /**
     * Recursively deletes {@code dir}, clearing Windows read-only attributes
     * on every file before deleting it (required for {@code .git} object and
     * pack files which JGit marks read-only on Windows).
     *
     * <p>This method <em>throws</em> on any I/O failure so the caller can
     * detect an incomplete deletion before attempting a fresh clone.
     */
    private void deleteDirectoryForReclone(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // Clear read-only flag before deleting — .git object/pack files are often read-only
                try { file.toFile().setWritable(true, false); } catch (SecurityException ignored) {}
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                throw new IOException("Could not access file during deletion: " + file, exc);
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (exc != null) throw exc;
                // Clear read-only flag on the directory itself before deleting.
                // Windows marks some .git subdirs (e.g. .git\branches) as read-only,
                // causing AccessDeniedException on Files.delete without this step.
                try { d.toFile().setWritable(true, false); } catch (SecurityException ignored) {}
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }


    /**
     * Best-effort recursive delete used for non-critical cleanup paths.
     * Failures are logged as warnings but never thrown.
     */
    private void deleteDirectory(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    file.toFile().setWritable(true, false);
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
            log.warn("Could not fully delete directory '{}': {}", dir, e.getMessage());
        }
    }

    /**
     * Stage 2 + 3 + 5 pipeline: walk → parse → chunk → embed → build graph.
     *
     * @param repoRoot root directory of the repository on disk
     * @param repoId   logical identifier for this repo (persistent path)
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

        return new IngestionResult(repoId, parsed, failed, chunksCreated, graphEdgesCreated);
    }

    // ── Result type ───────────────────────────────────────────────────────────

    /**
     * Aggregated output of one ingestion run.
     *
     * @param repoId            the resolved repository identifier — for local
     *                          repos this is the normalised absolute path; for
     *                          Git repos this is the persistent cache path that
     *                          was cloned to (NOT the original Git URL)
     * @param parsedFiles       successfully parsed .java files (with full metadata)
     * @param failedFiles       relative paths of files that could not be parsed
     * @param chunksCreated     number of code chunks successfully embedded and stored
     * @param graphEdgesCreated number of intra-project call-graph edges persisted (Stage 5)
     */
    public record IngestionResult(
            String repoId,
            List<ParsedFile> parsedFiles,
            List<String> failedFiles,
            int chunksCreated,
            int graphEdgesCreated
    ) {}
}
