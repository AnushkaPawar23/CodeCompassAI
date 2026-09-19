package com.codecompass.graph;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Stage 5 — Spring Data JPA repository for {@link CallGraphEdge}.
 *
 * <h2>Query strategy</h2>
 * <ul>
 *   <li>{@link #findByRepoIdAndCalleeClassAndCalleeMethod} — used by
 *       {@link GraphTraversalService} to find all <em>direct callers</em> of a
 *       given method during BFS traversal.  Backed by
 *       {@code idx_cge_repo_callee}.</li>
 *   <li>{@link #deleteByRepoId} — used by {@link GraphBuilderService} to
 *       clear stale edges before rebuilding the graph on re-ingest.</li>
 *   <li>{@link #countByRepoId} — used to log graph size after build.</li>
 * </ul>
 */
@Repository
public interface CallGraphEdgeRepository extends JpaRepository<CallGraphEdge, UUID> {

    /**
     * Returns all edges where the callee matches {@code (repoId, calleeClass, calleeMethod)}.
     * These represent the direct callers of the specified method.
     *
     * @param repoId       repository scope
     * @param calleeClass  simple class name of the callee
     * @param calleeMethod simple method name of the callee
     * @return direct caller edges (may be empty)
     */
    List<CallGraphEdge> findByRepoIdAndCalleeClassAndCalleeMethod(
            String repoId, String calleeClass, String calleeMethod);

    /**
     * Returns all edges for the given repository — used for bulk graph inspection.
     *
     * @param repoId repository scope
     * @return all edges for the repo
     */
    List<CallGraphEdge> findByRepoId(String repoId);

    /**
     * Counts all edges for the given repository.
     *
     * @param repoId repository scope
     * @return edge count
     */
    long countByRepoId(String repoId);

    /**
     * Deletes all edges for the given repository.
     * Called before rebuilding the graph on re-ingest (idempotent).
     *
     * @param repoId repository scope
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM CallGraphEdge e WHERE e.repoId = :repoId")
    void deleteByRepoId(@Param("repoId") String repoId);
}
