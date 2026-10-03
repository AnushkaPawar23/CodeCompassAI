package com.codecompass.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 3 — generates embeddings for a batch of {@link CodeChunk} objects and
 * persists them to PostgreSQL.
 *
 * <h2>Embedding model</h2>
 * Uses {@code gemini-embedding-001} (768-dim, MRL-configurable) via the Google AI
 * Gemini API.  The {@link EmbeddingModel} bean is configured in
 * {@link EmbeddingConfig} with {@code outputDimensionality = 768} to match the
 * existing {@code vector(768)} pgvector column with no schema migration required.
 *
 * <h2>pgvector persistence strategy</h2>
 * <p>Hibernate cannot reliably bind a {@code float[]} to a pgvector {@code vector}
 * column via a prepared-statement parameter — it sends {@code bytea} (or
 * {@code varchar} if we use a String field), both of which PostgreSQL rejects.
 *
 * <p>The solution is a two-phase write:
 * <ol>
 *   <li><b>JPA save</b> — {@link CodeChunkRepository#saveAll} persists all
 *       non-vector fields (content, metadata, etc.) and returns the rows with
 *       their database-assigned UUIDs.</li>
 *   <li><b>JDBC batch UPDATE</b> — {@link JdbcTemplate} runs a batch
 *       {@code UPDATE code_chunk SET embedding = ? WHERE id = ?} binding each
 *       embedding via {@code org.postgresql.util.PGobject} with
 *       {@code type = "vector"}.  The PostgreSQL JDBC driver correctly serialises
 *       a {@code PGobject} as the named custom type, bypassing Hibernate
 *       entirely for this column.</li>
 * </ol>
 *
 * <h2>Re-ingestion</h2>
 * Before inserting new chunks, any existing chunks for the same {@code repoId}
 * are deleted via {@link CodeChunkRepository#deleteByRepoId(String)}, so
 * re-ingesting the same repo replaces rather than duplicates data.
 *
 * <h2>Error tolerance</h2>
 * If embedding a single chunk fails (e.g. content too long for the model),
 * that chunk is skipped and logged.  The final count reflects only successfully
 * embedded and saved chunks.
 *
 * <h2>Rate-limit handling (Gemini free tier)</h2>
 * The Gemini free tier allows ~15 RPM for {@code gemini-embedding-001}.
 * To stay within this limit:
 * <ul>
 *   <li>{@code codecompass.embedding.delay-ms} (default 4100 ms) is injected
 *       between every successful embedding call (≈ 14.6 req/min).</li>
 *   <li>If a {@link RateLimitException} (HTTP 429) is still received, the
 *       service waits {@code codecompass.embedding.rate-limit-retry-delay-ms}
 *       (default 65 s) and retries exactly once before skipping that chunk.</li>
 * </ul>
 *
 * <h2>Performance note</h2>
 * Embedding is currently <b>synchronous and sequential</b>.  For 102 chunks at
 * 4.1 s/chunk, ingestion takes ≈ 7 minutes on the free tier.  Async/batched
 * embedding is planned for a later stage.
 */
@Service
@Slf4j
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final CodeChunkRepository chunkRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * Milliseconds to sleep between consecutive embedding API calls.
     * Default 4100 ms ≈ 14.6 req/min — safely under the 15 RPM free-tier cap.
     * Set to 0 to disable throttling (e.g. for paid API keys with higher limits).
     */
    @Value("${codecompass.embedding.delay-ms:4100}")
    private long embedDelayMs;

    /**
     * Milliseconds to sleep after a 429 RateLimitException before retrying.
     * Default 65 000 ms (65 s) — slightly longer than the 1-minute RPM window.
     */
    @Value("${codecompass.embedding.rate-limit-retry-delay-ms:65000}")
    private long rateLimitRetryDelayMs;

    public EmbeddingService(EmbeddingModel embeddingModel,
                            CodeChunkRepository chunkRepository,
                            JdbcTemplate jdbcTemplate,
                            TransactionTemplate transactionTemplate) {
        this.embeddingModel = embeddingModel;
        this.chunkRepository = chunkRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Embeds each chunk, saves non-vector fields via JPA, then writes the
     * embedding vectors via a JDBC batch UPDATE using {@code PGobject}.
     *
     * @param chunks un-embedded chunks produced by {@link ChunkingService}
     * @param repoId logical repository identifier — used to clear stale chunks
     * @return the number of chunks successfully embedded and saved
     */
    // NOT @Transactional: the embedding loop is throttled and can run for minutes.
    // Holding a DB transaction open that long lets Neon / the network drop the idle
    // connection ("Unable to rollback against JDBC Connection"). DB writes happen in
    // one short transaction after all embeddings are computed.
    public int embedAndSave(List<CodeChunk> chunks, String repoId) {
        if (chunks.isEmpty()) {
            log.info("No chunks to embed for repoId='{}'", repoId);
            return 0;
        }

        log.info("Embedding {} chunk(s) for repoId='{}' using gemini-embedding-001 …", chunks.size(), repoId);

        // ── Embed each chunk sequentially ─────────────────────────────────────
        List<CodeChunk> embedded = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            CodeChunk chunk = chunks.get(i);
            try {
                Response<Embedding> response = embedWithThrottle(chunk, i, chunks.size());
                chunk.setEmbedding(response.content().vector());
                embedded.add(chunk);

                log.info("  [{}/{}] embedded {}/{} ({}…)",
                        i + 1, chunks.size(),
                        chunk.getChunkType(), chunk.getClassName(),
                        abbreviate(chunk.getContent(), 60));

            } catch (Exception e) {
                log.warn("Failed to embed chunk {}/{} (class={}, method={}) after retries: {}",
                        i + 1, chunks.size(),
                        chunk.getClassName(), chunk.getMethodName(),
                        e.getMessage());
                // Continue — non-fatal, matching ingestion error-tolerance pattern
            }
        }

        if (embedded.isEmpty()) {
            log.warn("All {} chunk(s) failed to embed for repoId='{}'", chunks.size(), repoId);
            return 0;
        }

        // ── Short transaction: delete stale chunks, save, write vectors ───────
        Integer count = transactionTemplate.execute(status -> persist(embedded, chunks.size(), repoId));
        return count == null ? 0 : count;
    }

    private int persist(List<CodeChunk> embedded, int totalChunks, String repoId) {
        // Delete stale chunks for this repo (idempotent re-ingestion)
        long existing = chunkRepository.countByRepoId(repoId);
        if (existing > 0) {
            log.info("Removing {} stale chunk(s) for repoId='{}'", existing, repoId);
            chunkRepository.deleteByRepoId(repoId);
            chunkRepository.flush();
        }

        // ── Phase 1: JPA save (all non-vector fields; IDs assigned here) ──────
        List<CodeChunk> saved = chunkRepository.saveAll(embedded);
        chunkRepository.flush();   // ensure IDs are populated before the JDBC UPDATE

        // ── Phase 2: JDBC batch UPDATE to write vector(768) column ───────────
        //
        // Hibernate cannot bind float[] or String to a pgvector column in a
        // prepared statement — it sends bytea/varchar, which PostgreSQL rejects.
        // PGobject with type="vector" is the only reliable solution without the
        // pgvector JDBC extension.
        jdbcTemplate.batchUpdate(
                "UPDATE code_chunk SET embedding = ? WHERE id = ?",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        CodeChunk chunk = saved.get(i);

                        PGobject pgVec = new PGobject();
                        pgVec.setType("vector");
                        pgVec.setValue(toVectorLiteral(chunk.getEmbedding()));

                        ps.setObject(1, pgVec);   // driver handles 'vector' type correctly
                        ps.setObject(2, chunk.getId());
                    }

                    @Override
                    public int getBatchSize() {
                        return saved.size();
                    }
                }
        );

        log.info("Saved and embedded {}/{} chunk(s) for repoId='{}'",
                saved.size(), totalChunks, repoId);
        return saved.size();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Calls {@link EmbeddingModel#embed} with two rate-limit protections:
     * <ol>
     *   <li>Sleeps {@link #embedDelayMs} <em>before</em> the call (throttle).</li>
     *   <li>On {@link RateLimitException} (HTTP 429), sleeps
     *       {@link #rateLimitRetryDelayMs} then retries exactly once.</li>
     * </ol>
     *
     * @throws Exception if embedding fails after one retry
     */
    private Response<Embedding> embedWithThrottle(CodeChunk chunk, int index, int total)
            throws Exception {

        // Throttle: pace calls at ≈ 14.6 req/min (below the 15 RPM free-tier cap)
        if (embedDelayMs > 0) {
            log.debug("Throttle sleep {}ms before embedding chunk [{}/{}]",
                    embedDelayMs, index + 1, total);
            Thread.sleep(embedDelayMs);
        }

        try {
            return embeddingModel.embed(chunk.getContent());

        } catch (RateLimitException rle) {
            // 429 despite throttle — wait a full window then retry once
            log.warn("429 RateLimitException on chunk [{}/{}] — backing off {}ms then retrying once…",
                    index + 1, total, rateLimitRetryDelayMs);
            Thread.sleep(rateLimitRetryDelayMs);
            log.info("Retrying embedding for chunk [{}/{}] after back-off", index + 1, total);
            return embeddingModel.embed(chunk.getContent());   // let any second failure propagate
        }
    }

    /**
     * Serialises a {@code float[]} to the pgvector text-literal format:
     * {@code [x1,x2,...,xN]}.
     */
    private static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    /** Returns the first {@code maxLen} characters of {@code s}, appending "…" if truncated. */
    private static String abbreviate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
