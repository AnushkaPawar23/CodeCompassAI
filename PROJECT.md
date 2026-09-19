# CodeCompass AI

> **Intelligent codebase understanding and change-impact analysis assistant for developer onboarding.**

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5 (Tomcat, Spring Web MVC, Spring Data JPA) |
| AI / LLM | LangChain4j 1.18.1 -> Groq API (`qwen/qwen3.8-27b`, free tier) |
| Embedding | LangChain4j Ollama -> `nomic-embed-text` (local, 768-dim) |
| Vector Store | pgvector (PostgreSQL 16 extension) via LangChain4j PgVector store |
| AST / Parsing | JavaParser 3.28.2 (symbol-solver-core) |
| Git Cloning | JGit 7.7.1 |
| Database | PostgreSQL 16 (`pgvector/pgvector:pg16` Docker image) |
| Boilerplate | Lombok |
| Observability | Spring Boot Actuator (`/actuator/health`, `/actuator/info`) |
| Build | Maven (Spring Boot parent 3.5.16) |
| Infra | Docker Compose (postgres service) |

---

## Repository Layout

```
CodeCompass/
+-- docker-compose.yml          # pgvector/pgvector:pg16 service
+-- PROJECT.md
+-- ROADMAP.md
+-- backend/                    # Spring Boot Maven module
    +-- pom.xml
    +-- src/
        +-- main/
            +-- java/com/codecompass/
            |   +-- CodeCompassApplication.java   # @SpringBootApplication entry point
            |   +-- api/           # REST controllers (Stage 2+)
            |   +-- ingestion/     # JGit clone + Java file walker (Stage 2)
            |   +-- parsing/       # JavaParser AST -> CodeChunk (Stage 3)
            |   +-- llm/           # LangChain4j AI service wrappers (Stage 4)
            |   +-- rag/           # RAG pipeline: embed + retrieve (Stage 3-4)
            |   +-- graph/         # Call-graph builder / impact analysis (Stage 5-6)
            +-- resources/
                +-- application.yml
```

---

## Stages

| # | Stage | Status |
|---|---|---|
| 1 | Project scaffold -- Spring Boot skeleton, pom.xml, application.yml, Docker Compose, Actuator health | COMPLETE |
| 2 | Ingestion API -- `POST /api/ingest`, JGit clone, JavaFileWalker, AST parse → ParsedFile/ParsedClass/ParsedMethod | COMPLETE |
| 3 | AST parsing + embedding -- JavaParser -> CodeChunk JPA entity, Ollama embed, pgvector store | COMPLETE (BUILD SUCCESS; failedFiles:0; 38 chunks; 55/55 embeddings non-null; thread-safety fix applied 2026-09-17) |
| 4 | Q&A RAG endpoint -- `POST /api/qa`, similarity search + Groq LLM answer | COMPLETE (BUILD SUCCESS; 200 OK; answer + traceable sources[] verified; model: `qwen/qwen3.8-27b`; chunk-truncation fix; topK=5 confirmed 2026-09-18) |
| 5 | Call-graph API -- `GET /api/graph/dependents`, JavaParser symbol resolution, BFS transitive traversal | COMPLETE (BUILD SUCCESS; 200 OK; 99 edges; `IngestionController#ingest` correctly identified as depth-1 caller of `RepoIngestionService#ingest`; 2026-09-19) |
| 6 | Change-impact analysis -- `POST /api/impact`, graph traversal + LLM explanation | COMPLETE (BUILD SUCCESS; 200 OK; dependentsFound:3; LLM explanation named all 3 callers by class/method/file/line; zero-dependents fast path; 2026-09-19) |
| 7 | Endpoint discovery -- `GET /api/endpoints?repoId=...` (Spring MVC AST scan) | COMPLETE (BUILD SUCCESS; 200 OK; endpointCount:5; all 4 required endpoints found + self-discovery; 2026-09-19) |

---

## Running Locally

### Prerequisites

1. **Docker Desktop** running
2. **Java 21** on PATH
3. **Maven 3.9+** on PATH
4. **Ollama** with `nomic-embed-text` pulled *(needed from Stage 3)*
5. `GROQ_API_KEY` environment variable set *(needed from Stage 4)*

### Start the database

```bash
docker compose up -d
```

### Run the backend

```bash
cd backend
mvn spring-boot:run
```

### Verify health

```bash
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP", ...}
```

---

## Environment Variables

| Variable | Required | Description |
|---|---|---|
| `GROQ_API_KEY` | Stage 4+ | API key from https://console.groq.com |
