# CodeCompass AI — Roadmap

This document tracks planned work across all stages.
For the current status of each stage see PROJECT.md.

---

## Stage 1 — Scaffold (COMPLETE)

**Goal:** Runnable Spring Boot skeleton with verified DB connectivity.

### Deliverables
- `pom.xml` with all dependency blocks (Spring Boot 3.5, LangChain4j 1.18.1 BOM, JavaParser 3.28.2, JGit 7.7.1, Lombok, pgvector)
- `application.yml` with datasource, JPA, Actuator, LangChain4j (Groq + Ollama), and custom `codecompass.*` properties
- `CodeCompassApplication.java` (@SpringBootApplication entry point)
- Per-package `package-info.java` files for api, ingestion, parsing, llm, rag, graph
- `docker-compose.yml` (pgvector/pgvector:pg16)
- `GET /actuator/health` returns `{"status":"UP"}`

---

## Stage 2 — Ingestion API (COMPLETE)

**Goal:** Accept a public Git repo URL and store all parsed Java chunks.

### Planned classes
| Class | Package | Responsibility |
|---|---|---|
| `IngestionController` | api | `POST /api/ingest` — accepts `{"repoUrl": "..."}` |
| `IngestRequest` | api | Request DTO |
| `IngestResponse` | api | Response DTO (repoId, chunkCount, elapsedMs) |
| `RepoIngestionService` | ingestion | Orchestrates clone -> walk -> parse pipeline |
| `JavaFileWalker` | ingestion | Recursive `.java` file collector using `Files.walkFileTree` |

### Acceptance criteria
- `POST /api/ingest` with a valid public GitHub URL returns `200 OK` + repoId
- All `.java` files in the cloned repo are discovered and their paths logged
- Temp clone directory is cleaned up after ingestion

---

## Stage 3 — AST Parsing + Embedding (COMPLETE)

**Goal:** Parse each .java file into `CodeChunk` entities and store embeddings.

**Verification (2026-09-17, post thread-safety fix):**
- `mvn install` — BUILD SUCCESS (1 test passed, 0 failures, 14.6s)
- `POST /api/ingest` (type=local, source=backend/src) — 200 OK
  - `filesParsed: 21`, `classesFound: 15`, `methodsFound: 23`, `chunksCreated: 38`, `durationMs: 27747`
  - `failedFiles: []` ✅ (was 8 before fix — all records/text-block files now parse correctly)
- Postgres: **55/55** rows have non-null `vector(768)` embeddings, 0 null
- Thread-safety fix: `StaticJavaParser` singleton + `@PostConstruct` mutation replaced with
  `static final PARSER_CONFIG` (JAVA_21) + `new JavaParser(PARSER_CONFIG)` per call
- pom.xml fix: `org.postgresql` scope `runtime` → `compile` (needed for `PGobject`)

### Planned classes
| Class | Package | Responsibility |
|---|---|---|
| `JavaAstParser` | parsing | JavaParser -> method/class/field CodeChunks |
| `CodeChunk` | parsing | JPA entity (repoId, filePath, chunkType, content, startLine, endLine) |
| `CodeChunkRepository` | parsing | Spring Data JPA repository |
| `EmbeddingService` | rag | Calls Ollama nomic-embed-text, stores in pgvector |

---

## Stage 4 — Q&A RAG Endpoint (COMPLETE)

**Goal:** Answer natural-language questions about the ingested codebase.

**Verification (2026-09-18 — post-audit):**
- `mvn install` — BUILD SUCCESS (1 test, 0 failures)
- `POST /api/qa` `{"question": "where is the ingestion pipeline implemented?", "topK": 5}` — **200 OK**
  - Answer: correctly identified `RepoIngestionService#ingest` (lines 73–96) + `IngestionController#ingest` (lines 53–116) with full file paths
  - `sources[]`: 5 entries (IngestionController CLASS, RepoIngestionService#ingest METHOD, IngestionController#ingest METHOD, CodeChunkRepository#deleteByRepoId METHOD, QaController CLASS)
  - No 413 errors at topK=5 ✅

**Post-audit fixes (2026-09-18):**
- **Model correction**: `groq/compound` (Compound Beta routing agent, silently set in Stage 4) replaced.
  - `llama-3.3-70b-versatile` not available on this Groq free-tier key (404 model_not_found).
  - Current model: **`qwen/qwen3.8-27b`** — 131K context, real LLM, Groq free tier. Approved explicitly.
- **Chunk truncation**: `RagService.buildPrompt()` now caps each chunk at `max-chunk-chars` characters
  (default 2000 ≈ 500 tokens) before insertion. Configurable via `codecompass.rag.max-chunk-chars`.
- **Guard**: `application.yml` includes full model history + "do not silently change" note.

### Planned classes
| Class | Package | Responsibility |
|---|---|---|
| `QaController` | api | `POST /api/qa` — accepts `{"repoId":"...", "question":"..."}` |
| `RagService` | rag | Embed question → similarity search → build prompt → Groq LLM |
| `GroqChatService` | llm | Thin wrapper around LangChain4j OpenAI-compatible client |

---

## Stage 5 — Call-Graph API

**Goal:** Expose caller/callee relationships for any method in the codebase.

### Planned classes
| Class | Package | Responsibility |
|---|---|---|
| `GraphController` | api | `GET /api/graph/{repoId}/callers?method=...` |
| `CallGraphBuilder` | graph | JavaParser symbol resolution -> directed graph |
| `CallGraphEdge` | graph | JPA entity (caller, callee, repoId) |

---

## Stage 6 — Change-Impact Analysis

**Goal:** Given a changed method signature, return all transitively affected callers.

### Planned classes
| Class | Package | Responsibility |
|---|---|---|
| `ImpactController` | api | `POST /api/impact` |
| `ImpactAnalysisService` | graph | BFS/DFS over CallGraphEdge table |

---

## Stage 7 — Endpoint Discovery

**Goal:** Scan a Spring MVC codebase and return all REST endpoints it exposes.

### Planned classes
| Class | Package | Responsibility |
|---|---|---|
| `EndpointController` | api | `GET /api/endpoints/{repoId}` |
| `SpringMvcEndpointScanner` | parsing | AST scan for @RequestMapping/@GetMapping etc. |
| `EndpointDescriptor` | parsing | DTO (httpMethod, path, controllerClass, handlerMethod) |
