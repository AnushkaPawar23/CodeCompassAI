package com.codecompass.rag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Stage 3 — Spring Data JPA repository for {@link CodeChunk}.
 *
 * <p>Provides standard CRUD plus two utility methods needed by the ingestion
 * pipeline, and the native pgvector cosine-similarity query that Stage 4
 * (retrieval) will call.
 *
 * <h2>pgvector cosine distance</h2>
 * The {@code <=>} operator computes the cosine distance between two
 * {@code vector} values.  We cast the {@code :embedding} bind parameter
 * explicitly to {@code vector} so the PostgreSQL planner can use any
 * pgvector index (IVFFLAT / HNSW) that exists on the column.
 *
 * <p><b>Note:</b> {@link #findSimilar} is declared here for Stage 4 readiness
 * but is <em>not</em> called by any Stage 3 code.
 */
@Repository
public interface CodeChunkRepository extends JpaRepository<CodeChunk, UUID> {

    // ── Ingestion utilities ───────────────────────────────────────────────────

    /**
     * Deletes all chunks that belong to a given repository.
     * Called at the start of re-ingestion to remove stale chunks before
     * inserting fresh ones.
     *
     * @param repoId the logical repository identifier (path or Git URL)
     */
    void deleteByRepoId(String repoId);

    /**
     * Returns the number of chunks stored for a given repository.
     * Used to populate {@code chunksCreated} in the ingestion response.
     *
     * @param repoId the logical repository identifier
     * @return chunk count
     */
    long countByRepoId(String repoId);

    // ── Impact analysis lookups (used by Stage 6) ─────────────────────────────

    /**
     * Returns all chunks for a given method within a repository.
     *
     * <p>Primarily used to locate the {@code METHOD}-type chunk for a specific
     * method so {@link com.codecompass.graph.ImpactAnalysisService} can include
     * its actual source code in the impact-analysis prompt.
     *
     * @param repoId     logical repository identifier
     * @param className  simple class name (e.g. {@code ChunkingService})
     * @param methodName simple method name (e.g. {@code chunk})
     * @return matching chunks (typically 0 or 1 for METHOD type)
     */
    List<CodeChunk> findByRepoIdAndClassNameAndMethodName(
            String repoId, String className, String methodName);

    /**
     * Returns all chunks for a given class within a repository.
     *
     * <p>Used as a fallback by {@link com.codecompass.graph.ImpactAnalysisService}
     * when no {@code METHOD}-type chunk can be found for a specific method
     * (e.g. for constructors, accessors, or methods not individually chunked).
     * Returns the {@code CLASS}-type chunk so the prompt still has some code context.
     *
     * @param repoId    logical repository identifier
     * @param className simple class name
     * @return matching chunks (CLASS and/or METHOD chunks for the class)
     */
    List<CodeChunk> findByRepoIdAndClassName(String repoId, String className);

    // ── Similarity search (used by Stage 4) ──────────────────────────────────

    /**
     * Returns the {@code limit} most similar chunks to the supplied query
     * embedding, ordered by ascending cosine distance (closest first).
     *
     * <p>The query uses pgvector's {@code <=>} cosine-distance operator.
     * The explicit {@code CAST(:embedding AS vector)} is required so
     * PostgreSQL resolves the operator correctly when the parameter is
     * passed as a byte array by the JDBC driver.
     *
     * <p><b>Stage 4 note:</b> callers should additionally filter by
     * {@code 1 - (embedding <=> ...) >= minScore} to apply a similarity
     * threshold.  That filter is omitted here to keep the query generic.
     *
     * @param embedding the query vector (must be 768 floats)
     * @param limit     maximum number of results to return
     * @return chunks ordered by cosine similarity descending
     */
    @Query(value = """
            SELECT *
            FROM   code_chunk
            ORDER  BY embedding <=> CAST(:embedding AS vector)
            LIMIT  :limit
            """,
            nativeQuery = true)
    List<CodeChunk> findSimilar(@Param("embedding") float[] embedding,
                                @Param("limit") int limit);
}
