package com.codecompass.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the embedding model used during RAG ingestion.
 *
 * <h2>Why a manual @Bean?</h2>
 * <p>{@code langchain4j-google-ai-gemini} is a plain library, not a Spring Boot
 * auto-configure starter.  There is no {@code langchain4j.google-ai-gemini.*}
 * auto-configuration; the bean must be created explicitly here.
 *
 * <h2>Model &amp; dimension choice</h2>
 * <ul>
 *   <li>Model: {@code gemini-embedding-001} — Google's current production embedding model,
 *       using Matryoshka Representation Learning (MRL) to support configurable output sizes.</li>
 *   <li>Output dimensionality: <b>768</b> — configured via {@code outputDimensionality} to
 *       match the existing {@code vector(768)} pgvector column exactly.
 *       No schema migration required.</li>
 *   <li>Task type: {@code RETRIEVAL_DOCUMENT} for ingestion (document-side embeddings);
 *       query-side embeddings in {@link RagService} use {@code RETRIEVAL_QUERY} on a
 *       separate model instance.</li>
 * </ul>
 *
 * <h2>API key</h2>
 * <p>Set the {@code GEMINI_API_KEY} environment variable (obtained from
 * <a href="https://aistudio.google.com/app/apikey">Google AI Studio</a>).
 * Falls back to {@code GEMINI_API_KEY_NOT_SET} so the app fails fast with a
 * clear error at runtime rather than a cryptic NPE at startup.
 *
 * <h2>Free-tier note</h2>
 * <p>The Gemini API free tier does not require a credit card and provides ~1,500 RPD
 * (requests per day) with a per-minute rate limit.  Suitable for demo/portfolio
 * deployments ingesting public repositories.
 */
@Configuration
@Slf4j
public class EmbeddingConfig {

    /**
     * Dimensions produced by this model.  Must match the {@code vector(N)} column
     * in Postgres.  Declared as a constant so it can be referenced in logs and
     * assertions without magic numbers scattered across the codebase.
     */
    public static final int EMBEDDING_DIMENSION = 768;

    @Value("${gemini.api-key}")
    private String geminiApiKey;

    /**
     * The {@link EmbeddingModel} bean used by {@link EmbeddingService} during
     * ingestion (document-side embeddings, task type {@code RETRIEVAL_DOCUMENT}).
     *
     * <p>A second model instance with {@code RETRIEVAL_QUERY} is created separately
     * in {@link RagConfig} for query-time retrieval, keeping task types correct
     * for optimal embedding quality.
     *
     * @return configured {@link GoogleAiEmbeddingModel}
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("Configuring GoogleAiEmbeddingModel: model=gemini-embedding-001, "
                + "outputDimensionality={}, taskType=RETRIEVAL_DOCUMENT", EMBEDDING_DIMENSION);

        return GoogleAiEmbeddingModel.builder()
                .apiKey(geminiApiKey)
                .modelName("gemini-embedding-001")
                .outputDimensionality(EMBEDDING_DIMENSION)
                .taskType(GoogleAiEmbeddingModel.TaskType.RETRIEVAL_DOCUMENT)
                .httpClientBuilder(new dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilder())
                .build();
    }
}
