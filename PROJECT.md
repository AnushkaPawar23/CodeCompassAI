# CodeCompass AI

> **Intelligent codebase understanding and change-impact analysis assistant for developer onboarding.**

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5 (Tomcat, Spring Web MVC, Spring Data JPA) |
| Frontend | React 19, Vite, React Flow (@xyflow/react), Vanilla CSS |
| AI / LLM | LangChain4j 1.18.1 -> Groq API (`qwen/qwen3.8-27b`, free tier) |
| Embedding | LangChain4j Google AI Gemini -> `gemini-embedding-001` (768-dim, cloud API) |
| Vector Store | pgvector (PostgreSQL 16 extension) via LangChain4j PgVector store |
| AST / Parsing | JavaParser 3.28.2 (symbol-solver-core) |
| Git Cloning | JGit 7.7.1 |
| Database | PostgreSQL 16 (`pgvector/pgvector:pg16` Docker image locally, Neon PostgreSQL in cloud) |
| Boilerplate | Lombok |
| Observability | Spring Boot Actuator (`/actuator/health`, `/actuator/info`) |
| Build | Maven (Spring Boot parent 3.5.16), npm (Vite) |
| Infra | Docker Compose (local pgvector), Neon (DB), Render (backend), Vercel (frontend) |

---

## Repository Layout

```
CodeCompass/
+-- docker-compose.yml          # pgvector/pgvector:pg16 service
+-- PROJECT.md
+-- ROADMAP.md
+-- backend/                    # Spring Boot Maven module
|   +-- pom.xml
|   +-- src/
|       +-- main/
|           +-- java/com/codecompass/
|           |   +-- CodeCompassApplication.java   # @SpringBootApplication entry point
|           |   +-- api/           # REST controllers & error handling
|           |   +-- ingestion/     # JGit clone, repo-cache + Java file walker
|           |   +-- parsing/       # JavaParser AST -> CodeChunk & Endpoint discovery
|           |   +-- llm/           # LangChain4j AI service wrappers (Groq)
|           |   +-- rag/           # RAG pipeline: Gemini embed + pgvector retrieve
|           |   +-- graph/         # Call-graph builder, impact analysis, test coverage
|           +-- resources/
|               +-- application.yml
+-- frontend/                   # React + Vite frontend
    +-- package.json
    +-- src/
        +-- App.jsx
        +-- api.js
        +-- pages/
            +-- GraphPage.jsx        # Interactive call graph visualization
            +-- ApiExplorerPage.jsx  # Spring MVC endpoints inspector
```

---

## Stages

| # | Stage | Status |
|---|---|---|
| 1 | Project scaffold -- Spring Boot skeleton, pom.xml, application.yml, Docker Compose, Actuator health | COMPLETE |
| 2 | Ingestion API -- `POST /api/ingest`, JGit clone, JavaFileWalker, AST parse → ParsedFile/ParsedClass/ParsedMethod | COMPLETE |
| 3 | AST parsing + embedding -- JavaParser -> CodeChunk JPA entity, pgvector store (swapped to Gemini 768-dim) | COMPLETE (BUILD SUCCESS; failedFiles:0; 55/55 embeddings non-null) |
| 4 | Q&A RAG endpoint -- `POST /api/qa`, similarity search + Groq LLM answer | COMPLETE (BUILD SUCCESS; 200 OK; model: `qwen/qwen3.8-27b`; topK=5 confirmed) |
| 5 | Call-graph API -- `GET /api/graph/dependents`, JavaParser symbol resolution, BFS transitive traversal | COMPLETE (BUILD SUCCESS; 200 OK; 99 edges; transitive caller resolution verified) |
| 6 | Change-impact analysis -- `POST /api/impact`, graph traversal + LLM explanation | COMPLETE (BUILD SUCCESS; 200 OK; cascading failure analysis verified) |
| 7 | Endpoint discovery -- `GET /api/endpoints?repoId=...` (Spring MVC AST scan) | COMPLETE (BUILD SUCCESS; 200 OK; 5 endpoints discovered) |
| 8 | Risk-scored impact & test coverage -- High/Med/Low risk rating, src/test/java scan | COMPLETE (BUILD SUCCESS; 200 OK; risk-weighted blast radius) |
| 9 | Frontend Core UI -- Ingestion status, Q&A console, Impact Analysis dashboard | COMPLETE (React + Vite, dark-mode glassmorphic theme) |
| 10 | Interactive Call Graph & API Explorer -- React Flow visualizer, live API inspector | COMPLETE (Bidirectional graph nodes, endpoint discovery UI) |
| - | Deployment Compatibility -- Gemini embedding swap (`gemini-embedding-001`, 768-dim), persistent repo cache, retry logic | COMPLETE & VERIFIED (4.1s rate-limit pacing + 429 retry; Neon/Render/Vercel ready) |

---

## Running Locally

### Prerequisites

1. **Docker Desktop** running (for local pgvector)
2. **Java 21** on PATH
3. **Maven 3.9+** on PATH
4. **Node.js 18+** & npm on PATH (for frontend)
5. `GROQ_API_KEY` environment variable set (for LLM inference)
6. `GEMINI_API_KEY` environment variable set (for `gemini-embedding-001` embeddings)

> **Note on Embedding Quota & Speed:**  
> Embeddings use Google Gemini `gemini-embedding-001` (768 dimensions), replacing local Ollama for full cloud deployment compatibility.  
> **Known trade-off:** The free-tier Gemini API has a 15 RPM rate cap. To prevent HTTP 429 errors, ingestion paces calls with a ~4.1s delay per chunk (`codecompass.embedding.delay-ms=4100`) and includes an automatic 65s retry back-off. For paid-tier Gemini keys or higher quotas, set `codecompass.embedding.delay-ms=0` for maximum speed.

### Start the database

```bash
docker compose up -d
```

### Run the backend

```bash
cd backend
mvn spring-boot:run
```

### Run the frontend

```bash
cd frontend
npm install
npm run dev
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
| `GROQ_API_KEY` | Yes (Runtime) | API key from https://console.groq.com (for Q&A and Impact Analysis) |
| `GEMINI_API_KEY` | Yes (Runtime) | API key from https://aistudio.google.com (for `gemini-embedding-001` 768-dim embeddings) |
| `SPRING_DATASOURCE_URL` | Optional (Prod) | Postgres JDBC URL (defaults to `jdbc:postgresql://localhost:5432/codecompass`, use Neon in prod) |
| `SPRING_DATASOURCE_USERNAME`| Optional (Prod) | Database username (defaults to `codecompass`) |
| `SPRING_DATASOURCE_PASSWORD`| Optional (Prod) | Database password (defaults to `codecompass`) |

