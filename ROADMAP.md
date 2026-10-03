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

**Verification & Evolution:**
- `mvn install` — BUILD SUCCESS (1 test passed, 0 failures, 14.6s)
- `POST /api/ingest` (type=local, source=backend/src) — 200 OK
  - `filesParsed: 21`, `classesFound: 15`, `methodsFound: 23`, `chunksCreated: 38`, `durationMs: 27747`
  - `failedFiles: []` ✅ (was 8 before fix — all records/text-block files now parse correctly)
- Postgres: **55/55** rows have non-null `vector(768)` embeddings, 0 null
- Thread-safety fix: `StaticJavaParser` singleton + `@PostConstruct` mutation replaced with
  `static final PARSER_CONFIG` (JAVA_21) + `new JavaParser(PARSER_CONFIG)` per call
- pom.xml fix: `org.postgresql` scope `runtime` → `compile` (needed for `PGobject`)
- **Gemini Embedding Migration (2026-10-02/03):** Swapped from local Ollama (`nomic-embed-text`) to Google Gemini `gemini-embedding-001` (768-dim) for cloud deployment compatibility.
  - Rate-limit handling: 4.1s inter-chunk throttle (`codecompass.embedding.delay-ms=4100`, ≈ 14.6 req/min) to stay under Gemini free-tier 15 RPM cap.
  - Retry back-off: 65s pause on HTTP 429 (`codecompass.embedding.rate-limit-retry-delay-ms=65000`) before single retry.
  - Paid tier compatibility: delay is fully configurable via `codecompass.embedding.delay-ms` (set to 0 for paid-tier keys).

### Classes
| Class | Package | Responsibility |
|---|---|---|
| `JavaAstParser` | parsing | JavaParser -> method/class/field CodeChunks |
| `CodeChunk` | parsing | JPA entity (repoId, filePath, chunkType, content, startLine, endLine) |
| `CodeChunkRepository` | parsing | Spring Data JPA repository |
| `EmbeddingService` | rag | Calls Gemini `gemini-embedding-001` (768-dim), throttles calls, stores in pgvector |
| `EmbeddingConfig` | rag | Spring configuration bean for LangChain4j Gemini EmbeddingModel |

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

## Stage 5 — Call-Graph API (COMPLETE)

**Goal:** Expose caller/callee relationships for any method in the codebase.

**Verification (2026-09-19):**
- `mvn install` — BUILD SUCCESS (1 test, 0 failures, 17.4s)
- `POST /api/ingest` (type=local, source=backend/src) — 200 OK
  - `filesParsed: 31`, `classesFound: 25`, `methodsFound: 40`, `chunksCreated: 65`, `graphEdgesCreated: 99`, `failedFiles: []` ✅
  - Graph build: 31 files processed, 99 intra-project edges persisted, 61 external/unresolvable calls skipped
- `GET /api/graph/dependents?repoId=...&class=RepoIngestionService&method=ingest` — 200 OK ✅
  - `totalCount: 1`, `dependents[0]: IngestionController#ingest`, `callerStartLine: 53`, `callerEndLine: 118`, `depth: 1`
  - Correctly identified `IngestionController#ingest` as the sole direct caller ✅
- No Flyway migration needed — `call_graph_edge` table created by `ddl-auto: update` (consistent with Stage 3)

### Planned classes
| Class | Package | Responsibility |
|---|---|---|
| `GraphController` | api | `GET /api/graph/dependents?repoId=...&class=...&method=...` |
| `GraphBuilderService` | graph | JavaParser symbol resolution → directed edge extraction, batch persist |
| `GraphTraversalService` | graph | BFS transitive caller traversal, `DependentMethod` record |
| `CallGraphEdge` | graph | JPA entity (caller, callee, repoId, file paths, line ranges) |
| `CallGraphEdgeRepository` | graph | Spring Data JPA — find by callee, delete by repoId |

---

## Stage 6 — Change-Impact Analysis (COMPLETE)

**Goal:** Given a changed method signature, return all transitively affected callers with an LLM-generated explanation of what could break.

**Verification (2026-09-19):**
- `mvn install -DskipTests` — BUILD SUCCESS (33 source files, 8.5s)
- `POST /api/impact` `{"repoId":"...","targetClass":"ChunkingService","targetMethod":"chunk"}` — 200 OK ✅
  - `dependentsFound: 3` — full 3-hop chain returned (depth 1: parseAndEmbed, depth 2: ingest, depth 3: IngestionController#ingest)
  - LLM explanation: `qwen/qwen3.8-27b` correctly named all 3 dependents by class, method, file, and line range
  - Explanation correctly described cascading failure paths (contract violations, NPE, HTTP 500, metric corruption)
  - Zero-dependents fast path: coded — skips LLM call, returns "safe to change in isolation" message ✅

### Classes
| Class | Package | Responsibility |
|---|---|---|
| `ImpactRequest` | api | DTO: `repoId`, `targetClass`, `targetMethod` |
| `ImpactController` | api | `POST /api/impact` |
| `ImpactAnalysisService` | graph | BFS traversal + chunk lookup + Groq LLM prompt |
| `CodeChunkRepository` | rag | Added `findByRepoIdAndClassNameAndMethodName` + `findByRepoIdAndClassName` |

---

## Stage 7 — Endpoint Discovery

**Goal:** Scan a Spring MVC codebase and return all REST endpoints it exposes.

**Verification (2026-09-19):**
- `mvn install -DskipTests` — BUILD SUCCESS (36 source files, 8.4s)
- `GET /api/endpoints?repoId=backend/src` — 200 OK ✅
  - `endpointCount: 5` — all 4 required endpoints found; scanner also self-discovered itself ✅
  - `GET  /api/endpoints`        → EndpointController#getEndpoints  (line 93)
  - `GET  /api/graph/dependents` → GraphController#getDependents     (line 95)
  - `POST /api/impact`           → ImpactController#analyseImpact    (line 79)
  - `POST /api/ingest`           → IngestionController#ingest        (line 53)
  - `POST /api/qa`               → QaController#ask                  (line 56)
- No Postgres table — endpoints computed fresh from disk on each request ✅
- `@RequestMapping` class prefix + method annotation correctly combined (e.g. `/api` + `/ingest` → `/api/ingest`) ✅
- All 3 annotation syntactic forms handled: marker, single-member, normal ✅

### Classes
| Class | Package | Responsibility |
|---|---|---|
| `EndpointController` | api | `GET /api/endpoints?repoId=...` |
| `SpringMvcEndpointScanner` | parsing | AST scan for `@RestController`/`@Controller` + all mapping annotations |
| `EndpointDescriptor` | parsing | DTO: httpMethod, path, controllerClass, handlerMethod, filePath, startLine |

---

## Stage 8 — Risk-Scored Impact Analysis + Test Coverage Detection (COMPLETE)

**Goal:** Augment change-impact analysis with depth-based risk levels (HIGH/MEDIUM/LOW) and automated test coverage discovery in `src/test/java`.

**Verification (2026-09-20):**
- `mvn install -DskipTests` — BUILD SUCCESS
- `POST /api/impact` verified with risk counts (`highRiskCount`, `mediumRiskCount`, `lowRiskCount`) and `hasTestCoverage` booleans.
- Heuristic AST test scan checks if any test method in `src/test/java` references the dependent class and method.

### Classes
| Class | Package | Responsibility |
|---|---|---|
| `ImpactAnalysisService` | graph | Risk classification per hop depth + risk-weighted prompt |
| `TestCoverageService` | graph | Scans `src/test/java` for method/class invocations and tests |

---

## Stage 9 — Frontend Core (COMPLETE)

**Goal:** Single-page developer UI for repo ingestion, semantic Q&A, and blast-radius impact analysis.

**Verification (2026-09-27):**
- Built with React 19 + Vite, custom glassmorphic dark theme.
- Tabs:
  - **Ingest View**: Form to ingest public GitHub URLs with live progress and status polling.
  - **Q&A Console**: Natural language search with confidence-ranked source references, line snippets, and jump links.
  - **Impact Analysis**: Target class & method selector, blast radius cards, risk indicators, and LLM explanation renderer.

---

## Stage 10 — Interactive Call Graph & API Explorer (COMPLETE)

**Goal:** Visual node-edge graph of codebase architecture and live REST API catalog.

**Verification (2026-09-27):**
- **Graph Visualizer (`GraphPage.jsx`)**: React Flow (`@xyflow/react`) canvas rendering caller/callee relationships with interactive expansion and inspection drawer.
- **API Explorer (`ApiExplorerPage.jsx`)**: Auto-discovered Spring MVC endpoints with HTTP badges, controller paths, and direct jump to impact analysis.

---

## Deployment — Neon + Render + Vercel (IN PROGRESS)

**Target Completion:** October 10, 2026

**Architecture:**
- **Database:** Neon Serverless PostgreSQL with `pgvector` extension enabled (`vector(768)`).
- **Backend:** Render Web Service (Docker / Java 21 OpenJDK runtime), running Spring Boot jar.
- **Frontend:** Vercel SPA deployment with proxy rewrites or direct CORS connection to Render.
- **AI Services:** Groq Cloud (`qwen/qwen3.8-27b`) + Google AI Studio (`gemini-embedding-001`, 768-dim).

**Key Milestone Steps:**
1. **Neon PostgreSQL Setup**: Provision DB, enable `pgvector`, test connection string with SSL (`sslmode=require`).
2. **Backend Containerization & Config**: Create multi-stage `Dockerfile`, verify production profile (`application-prod.yml`), inject environment variables (`SPRING_DATASOURCE_*`, `GROQ_API_KEY`, `GEMINI_API_KEY`, `CORS_ALLOWED_ORIGINS`).
3. **Render Deployment**: Deploy backend service, configure persistent disk or volume for `.repo-cache`, verify `/actuator/health` and `/api/endpoints`.
4. **Frontend Vercel Deployment**: Configure `VITE_API_BASE_URL` or `vercel.json` rewrites, build & deploy Vite SPA, verify end-to-end integration across all 4 views.
5. **Final Production Verification**: Ingest real sample repository end-to-end on live infrastructure before Oct 10 deadline.

