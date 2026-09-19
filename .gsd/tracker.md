# CodeCompass AI — GSD Task Tracker

> This file is the ground-truth task tracker for the CodeCompass AI build.
> It is updated at every stage boundary (start / complete) so state survives
> quota pauses and session gaps.
>
> Last reconciled: 2026-09-19  
> Last updated: 2026-09-19 (Stage 6 complete — change-impact analysis; POST /api/impact; 200 OK; 3-hop chain; LLM explanation verified)

---

## Stages

- [x] **Stage 1 — Project Scaffold**
  - Spring Boot skeleton, pom.xml, application.yml, docker-compose.yml, Actuator health
  - All deliverables on disk; `GET /actuator/health` verified UP
  - Status: **COMPLETE**

- [x] **Stage 2 — Ingestion API**
  - `POST /api/ingest`, JGit clone, JavaFileWalker, AST parse → ParsedFile/ParsedClass/ParsedMethod
  - All deliverables on disk; endpoint verified
  - Status: **COMPLETE**

- [x] **Stage 3 — AST Parsing + Embedding** *(COMPLETE)*
  - Files written:
    - `parsing/JavaAstParserService.java` ✅ *(thread-safety fix applied 2026-09-17)*
    - `parsing/ParsedClass.java` ✅
    - `parsing/ParsedFile.java` ✅
    - `parsing/ParsedMethod.java` ✅
    - `rag/CodeChunk.java` (JPA entity + pgvector) ✅
    - `rag/CodeChunkRepository.java` ✅
    - `rag/ChunkingService.java` ✅
    - `rag/EmbeddingService.java` ✅
  - **pom.xml fixes applied**:
    - `maven-compiler-plugin` `<annotationProcessorPaths>` for Lombok
    - `org.postgresql` scope changed from `runtime` → `compile` (required for `PGobject` at compile time)
  - **Thread-safety fix** (`JavaAstParserService`): replaced `StaticJavaParser` singleton +
    `@PostConstruct` mutation with `static final PARSER_CONFIG` (JAVA_21, set once) and
    `new JavaParser(PARSER_CONFIG)` per call — eliminates shared mutable state across threads.
  - Build verification: ✅ **BUILD SUCCESS** (`mvn install` — 1 test passed, 0 failures, 14.6s — 2026-09-17)
  - Smoke test (`POST /api/ingest` — `backend/src`, type=local): ✅ **200 OK** *(2026-09-17, post-fix)*
    - `filesParsed: 21`, `classesFound: 15`, `methodsFound: 23`, `chunksCreated: 38`, `durationMs: 27747`
    - `failedFiles: []` ✅ **(was 8 before fix — now 0)**
  - Postgres verification: ✅ `SELECT COUNT(*) FROM code_chunk` → **55 total rows, 55/55 non-null embeddings, 0 null**
  - Status: **COMPLETE**

- [x] **Stage 4 — Q&A RAG Endpoint** *(COMPLETE — post-audit fixes applied 2026-09-18)*
  - Files written:
    - `api/QaRequest.java` ✅
    - `api/QaResponse.java` ✅ (with nested `SourceReference` record)
    - `api/QaController.java` ✅
    - `llm/GroqChatService.java` ✅
    - `rag/RagService.java` ✅
  - **Post-audit fixes (2026-09-18)**:
    - **Model audit**: `groq/compound` (Groq Compound Beta routing agent) was silently set during Stage 4 without disclosure. Identified as the cause of 413 errors at topK≥3 (smaller effective context window). Corrected.
    - **Model availability**: `llama-3.3-70b-versatile` (original Stage 1 agreement) no longer available on this Groq free-tier key (404 `model_not_found`). Available text-LLMs queried via `/v1/models`; switched to `qwen/qwen3.8-27b` (131K context, real LLM, free tier) with explicit user approval.
    - **Chunk truncation fix**: `RagService.buildPrompt()` now caps each chunk at `codecompass.rag.max-chunk-chars` chars (default 2000, ~500 tokens) via new `truncateContent()` helper before inserting into the prompt. Configurable without code changes.
    - **Guard comment**: `application.yml` now includes model history and a "do not silently change" note.
  - Re-smoke test (`POST /api/qa`, topK=5, model=`qwen/qwen3.8-27b`): ✅ **200 OK** *(2026-09-18)*
    - Question: `"where is the ingestion pipeline implemented?"`
    - Answer: correctly identified `RepoIngestionService#ingest` (lines 73–96) + `IngestionController#ingest` (lines 53–116) with accurate file paths
    - `sources[]`: 5 entries — IngestionController (CLASS), RepoIngestionService#ingest (METHOD), IngestionController#ingest (METHOD), CodeChunkRepository#deleteByRepoId (METHOD), QaController (CLASS)
    - No 413 errors at topK=5 ✅
  - Status: **COMPLETE**

- [x] **Stage 5 — Call-Graph API** *(COMPLETE — 2026-09-19)*
  - Files written:
    - `graph/CallGraphEdge.java` ✅ (JPA entity — caller/callee columns, 3 DB indexes, ddl-auto managed)
    - `graph/CallGraphEdgeRepository.java` ✅ (findByRepoIdAndCalleeClassAndCalleeMethod, deleteByRepoId, countByRepoId)
    - `graph/GraphBuilderService.java` ✅ (symbol-solver pipeline: ReflectionTypeSolver + JavaParserTypeSolver, dedup, batch persist)
    - `graph/GraphTraversalService.java` ✅ (BFS transitive caller traversal, DependentMethod record)
    - `api/GraphController.java` ✅ (`GET /api/graph/dependents` endpoint)
    - `api/IngestResponse.java` ✅ (graphEdgesCreated field added)
    - `api/IngestionController.java` ✅ (wired to return graphEdgesCreated)
    - `ingestion/RepoIngestionService.java` ✅ (GraphBuilderService injected + called in pipeline)
  - **No Flyway migration** — call_graph_edge table managed by ddl-auto: update (consistent with Stage 3)
  - Build verification: ✅ **BUILD SUCCESS** (`mvn install` — 1 test passed, 0 failures, 17.4s — 2026-09-19)
  - Ingest smoke test (`POST /api/ingest` — backend/src, type=local): ✅ **200 OK** *(2026-09-19)*
    - `filesParsed: 31`, `classesFound: 25`, `methodsFound: 40`, `chunksCreated: 65`, `graphEdgesCreated: 99`, `failedFiles: []`
    - Graph: 31 files processed, 99 intra-project edges persisted, 61 external calls skipped
  - Dependents smoke test (`GET /api/graph/dependents?class=RepoIngestionService&method=ingest`): ✅ **200 OK** *(2026-09-19)*
    - `totalCount: 1`, `dependents[0]: IngestionController#ingest`, `depth: 1`, `callerStartLine: 53`, `callerEndLine: 118`
    - Correctly identified IngestionController as the sole direct caller ✅
  - Status: **COMPLETE**

- [x] **Stage 6 — Change-Impact Analysis** *(COMPLETE — 2026-09-19)*
  - Files written:
    - `api/ImpactRequest.java` ✅ (DTO: repoId, targetClass, targetMethod)
    - `api/ImpactController.java` ✅ (`POST /api/impact` controller)
    - `graph/ImpactAnalysisService.java` ✅ (graph traversal + chunk lookup + Groq LLM prompt)
  - Files modified:
    - `rag/CodeChunkRepository.java` ✅ (added findByRepoIdAndClassNameAndMethodName + findByRepoIdAndClassName)
  - Build verification: ✅ **BUILD SUCCESS** (`mvn install -DskipTests` — 33 source files, 8.5s — 2026-09-19)
  - Smoke test (`POST /api/impact` — ChunkingService#chunk): ✅ **200 OK** *(2026-09-19)*
    - `dependentsFound: 3` — all 3 transitive callers returned (depth 1/2/3) ✅
    - LLM explanation: generated by `qwen/qwen3.8-27b`, correctly identified propagation chain ✅
    - Named all 3 dependents by class/method/file/line in explanation ✅
    - Edge case: zero-dependents fast path coded (skips LLM, returns "safe to change" message) ✅
  - Status: **COMPLETE**

- [ ] **Stage 7 — Endpoint Discovery**
  - `GET /api/endpoints/{repoId}`, SpringMvcEndpointScanner
  - Status: **PLANNED**

---

## Update Protocol

At every stage boundary, update this file AND PROJECT.md AND ROADMAP.md:
- Mark stage `[/]` (in-progress) when work begins
- Mark stage `[x]` (complete) when build verification passes
- Commit message format: `tracker: Stage N [in-progress|complete]`
