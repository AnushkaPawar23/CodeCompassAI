package com.codecompass.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Stage 3 — JPA entity representing a single code chunk stored in Postgres.
 *
 * <p>One row is created for each logical unit the chunking pipeline extracts:
 * <ul>
 *   <li>{@code CLASS}  — the full source text of a type declaration.</li>
 *   <li>{@code METHOD} — the full source text of a single method.</li>
 * </ul>
 *
 * <h2>pgvector embedding storage</h2>
 * <p>The {@code embedding} column is a native {@code vector(768)} pgvector column.
 * Hibernate cannot reliably bind a {@code float[]} (or a plain {@code String}) to
 * the {@code vector} JDBC type — it either sends {@code bytea} or {@code varchar},
 * both of which PostgreSQL rejects for a {@code vector} column in a prepared
 * statement without an explicit cast.
 *
 * <p>The solution used here is to keep the {@code embedding} field as
 * {@link Transient} so that JPA/Hibernate never touches it.  The actual
 * {@code vector(768)} column is written by {@link EmbeddingService} via a
 * Spring {@code JdbcTemplate} batch UPDATE using
 * {@code org.postgresql.util.PGobject} with {@code type = "vector"}, which the
 * PostgreSQL JDBC driver correctly serialises as the {@code vector} wire type.
 *
 * <p>Cosine-similarity search against this column is performed via native SQL
 * in {@link CodeChunkRepository} using the {@code <=>} pgvector operator.
 */
@Entity
@Table(name = "code_chunk")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CodeChunk {

    // ── Primary key ───────────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // ── Provenance ────────────────────────────────────────────────────────────

    /**
     * Logical identifier for the repository this chunk came from.
     * For local ingestions this is the canonical path string; for Git
     * ingestions it is the remote URL.
     */
    @Column(name = "repo_id", nullable = false)
    private String repoId;

    /** Absolute path to the .java source file that contains this chunk. */
    @Column(name = "file_path", nullable = false, columnDefinition = "TEXT")
    private String filePath;

    // ── Chunk metadata ────────────────────────────────────────────────────────

    /**
     * Granularity of this chunk.
     * {@code CLASS} chunks cover the whole type declaration;
     * {@code METHOD} chunks cover a single method body.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "chunk_type", nullable = false, length = 16)
    private ChunkType chunkType;

    /** Simple name of the enclosing type (e.g. {@code StudentService}). */
    @Column(name = "class_name", nullable = false)
    private String className;

    /**
     * Simple name of the method for {@code METHOD} chunks;
     * {@code null} for {@code CLASS} chunks.
     */
    @Column(name = "method_name")
    private String methodName;

    // ── Content ───────────────────────────────────────────────────────────────

    /** The raw source text that will be embedded and stored. */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 1-based line number in the file where this chunk starts. */
    @Column(name = "start_line", nullable = false)
    private int startLine;

    /** 1-based line number in the file where this chunk ends. */
    @Column(name = "end_line", nullable = false)
    private int endLine;

    // ── Vector embedding ──────────────────────────────────────────────────────

    /**
     * In-memory 768-dim embedding from {@code nomic-embed-text} via Ollama.
     *
     * <p>This field is {@link Transient} — Hibernate never reads or writes it.
     * {@link EmbeddingService} stores the vector into the database {@code vector(768)}
     * column via a {@code JdbcTemplate} batch UPDATE with {@code PGobject(type="vector")},
     * which is the only way to avoid the {@code bytea}/{@code varchar} cast rejection
     * that occurs when Hibernate tries to bind a {@code float[]} or {@code String}
     * to a pgvector column via a prepared-statement parameter.
     */
    @Transient
    private float[] embedding;

    // ── Chunk type enum ───────────────────────────────────────────────────────

    /** Granularity levels produced by the chunking pipeline. */
    public enum ChunkType {
        /** A complete type declaration (class / interface / enum / annotation). */
        CLASS,
        /** A single method declaration within a type. */
        METHOD
    }
}
