/**
 * RAG (Retrieval-Augmented Generation) package – Stage 3 / Stage 4
 *
 * Responsible for:
 *  - Embedding code chunks via Ollama (nomic-embed-text, 768-dim vectors)
 *  - Storing and querying embeddings in PostgreSQL via the pgvector extension
 *  - Retrieving top-k semantically similar chunks for a given question
 *
 * Key classes (added in Stage 3 and Stage 4):
 *  - EmbeddingConfig        : Spring @Configuration wiring OllamaEmbeddingModel
 *                             and PgVectorEmbeddingStore
 *  - EmbeddingService       : embeds a list of CodeChunks and upserts to pgvector
 *  - CodeRetriever          : wraps EmbeddingStoreRetriever for Q&A queries
 */
@NonNullApi
package com.codecompass.rag;

import org.springframework.lang.NonNullApi;
