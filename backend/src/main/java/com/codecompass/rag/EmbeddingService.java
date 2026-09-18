package com.codecompass.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 3 — generates embeddings for a batch of {@link CodeChunk} objects and
 * persists them to PostgreSQL.
 *
 * <h2>Embedding model</h2>
 * Uses {@code nomic-embed-text} (768-dim) running locally via Ollama.
 * The {@link EmbeddingModel} bean is auto-configured by the
 * {@code langchain4j-ollama-spring-boot-starter} from {@code application.yml}.
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
 * <h2>Performance note</h2>
 * Embedding is currently <b>synchronous and sequential</b>.  This is acceptable
 * for small demo repos (≤ 20 files).  Async/batched embedding is planned for a
 * later stage.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final CodeChunkRepository chunkRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Embeds each chunk, saves non-vector fields via JPA, then writes the
     * embedding vectors via a JDBC batch UPDATE using {@code PGobject}.
     *
     * @param chunks un-embedded chunks produced by {@link ChunkingService}
     * @param repoId logical repository identifier — used to clear stale chunks
     * @return the number of chunks successfully embedded and saved
     */
    @Transactional
    public int embedAndSave(List<CodeChunk> chunks, String repoId) {
        if (chunks.isEmpty()) {
            log.info("No chunks to embed for repoId='{}'", repoId);
            return 0;
        }

        log.info("Embedding {} chunk(s) for repoId='{}' using nomic-embed-text …", chunks.size(), repoId);

        // ── Delete stale chunks for this repo (idempotent re-ingestion) ───────
        long existing = chunkRepository.countByRepoId(repoId);
        if (existing > 0) {
            log.info("Removing {} stale chunk(s) for repoId='{}'", existing, repoId);
            chunkRepository.deleteByRepoId(repoId);
        }

        // ── Embed each chunk sequentially ─────────────────────────────────────
        List<CodeChunk> embedded = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            CodeChunk chunk = chunks.get(i);
            try {
                Response<Embedding> response = embeddingModel.embed(chunk.getContent());
                chunk.setEmbedding(response.content().vector());
                embedded.add(chunk);

                if (log.isDebugEnabled()) {
                    log.debug("  [{}/{}] embedded {}/{} ({}…)",
                            i + 1, chunks.size(),
                            chunk.getChunkType(), chunk.getClassName(),
                            abbreviate(chunk.getContent(), 60));
                }

            } catch (Exception e) {
                log.warn("Failed to embed chunk {}/{} (class={}, method={}): {}",
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
                saved.size(), chunks.size(), repoId);
        return saved.size();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
