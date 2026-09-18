# CodeCompass AI — GSD Task Tracker

> This file is the ground-truth task tracker for the CodeCompass AI build.
> It is updated at every stage boundary (start / complete) so state survives
> quota pauses and session gaps.
>
> Last reconciled: 2026-09-18  
> Last updated: 2026-09-18 (Stage 4 post-audit — model fixed to qwen/qwen3.8-27b, chunk-truncation fix in RagService, smoke test re-confirmed at topK=5)

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

- [ ] **Stage 5 — Call-Graph API**
  - `GET /api/graph/{repoId}/callers`, CallGraphBuilder, CallGraphEdge
  - Status: **PLANNED**

- [ ] **Stage 6 — Change-Impact Analysis**
  - `POST /api/impact`, ImpactAnalysisService BFS/DFS
  - Status: **PLANNED**

- [ ] **Stage 7 — Endpoint Discovery**
  - `GET /api/endpoints/{repoId}`, SpringMvcEndpointScanner
  - Status: **PLANNED**

---

## Update Protocol

At every stage boundary, update this file AND PROJECT.md AND ROADMAP.md:
- Mark stage `[/]` (in-progress) when work begins
- Mark stage `[x]` (complete) when build verification passes
- Commit message format: `tracker: Stage N [in-progress|complete]`
