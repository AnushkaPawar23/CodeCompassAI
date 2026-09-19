package com.codecompass.graph;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Stage 5 — JPA entity representing a single directed edge in the method-level
 * call graph for an ingested repository.
 *
 * <h2>Semantics</h2>
 * <p>An edge {@code caller → callee} means: within the body of {@code callerMethod}
 * (declared in {@code callerClass}), there exists at least one call site that
 * invokes {@code calleeMethod} on {@code calleeClass}.
 *
 * <h2>Storage</h2>
 * <p>Stored in the {@code call_graph_edge} table in PostgreSQL, managed by
 * Hibernate ({@code ddl-auto: update}).  Indexed on both caller and callee
 * coordinates so both "who does X call?" and "who calls X?" queries are fast.
 *
 * <h2>Re-ingestion</h2>
 * <p>All edges for a given {@code repoId} are deleted and re-created on every
 * {@code POST /api/ingest} call, keeping the graph in sync with the source.
 *
 * <h2>External calls</h2>
 * <p>Only intra-project edges (both caller and callee resolved to a class within
 * the ingested source tree) are stored.  Calls to JDK or third-party library
 * classes are silently skipped during graph construction.
 */
@Entity
@Table(
    name = "call_graph_edge",
    indexes = {
        @Index(name = "idx_cge_repo_callee", columnList = "repo_id, callee_class, callee_method"),
        @Index(name = "idx_cge_repo_caller", columnList = "repo_id, caller_class, caller_method"),
        @Index(name = "idx_cge_repo_id",     columnList = "repo_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallGraphEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── Repo scope ────────────────────────────────────────────────────────────

    /** Logical identifier of the ingested repository (local path or Git URL). */
    @Column(name = "repo_id", nullable = false, length = 512)
    private String repoId;

    // ── Caller ────────────────────────────────────────────────────────────────

    /** Simple name of the class that contains the call site (e.g. {@code IngestionController}). */
    @Column(name = "caller_class", nullable = false, length = 256)
    private String callerClass;

    /** Simple name of the method that contains the call site (e.g. {@code ingest}). */
    @Column(name = "caller_method", nullable = false, length = 256)
    private String callerMethod;

    /** Absolute path to the file that contains the caller method. */
    @Column(name = "caller_file", nullable = false, length = 1024)
    private String callerFile;

    /** 1-based start line of the caller method declaration. */
    @Column(name = "caller_start_line")
    private int callerStartLine;

    /** 1-based end line of the caller method declaration. */
    @Column(name = "caller_end_line")
    private int callerEndLine;

    // ── Callee ────────────────────────────────────────────────────────────────

    /** Simple name of the class whose method is being called (e.g. {@code RepoIngestionService}). */
    @Column(name = "callee_class", nullable = false, length = 256)
    private String calleeClass;

    /** Simple name of the method being called (e.g. {@code ingest}). */
    @Column(name = "callee_method", nullable = false, length = 256)
    private String calleeMethod;

    /**
     * Absolute path to the file that declares the callee method.
     * {@code null} only if the callee could not be resolved to a source file
     * (should not occur for intra-project edges, as external edges are not stored).
     */
    @Column(name = "callee_file", length = 1024)
    private String calleeFile;

    /** 1-based start line of the callee method declaration. */
    @Column(name = "callee_start_line")
    private int calleeStartLine;

    /** 1-based end line of the callee method declaration. */
    @Column(name = "callee_end_line")
    private int calleeEndLine;
}
