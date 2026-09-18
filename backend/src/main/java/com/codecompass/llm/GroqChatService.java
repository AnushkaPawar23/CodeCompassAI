package com.codecompass.llm;

import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Stage 4 — thin wrapper around the LangChain4j {@link ChatLanguageModel} bean.
 *
 * <h2>Model</h2>
 * <p>{@code qwen/qwen3.8-27b} served by the Groq API (OpenAI-compatible endpoint).
 * The {@link ChatModel} bean is auto-configured by the
 * {@code langchain4j-open-ai-spring-boot-starter} from the
 * {@code langchain4j.open-ai.chat-model.*} properties in {@code application.yml}:
 *
 * <ul>
 *   <li>{@code base-url: https://api.groq.com/openai/v1}</li>
 *   <li>{@code model-name: qwen/qwen3.8-27b} — 131K context, real LLM, Groq free tier.
 *       Original plan was {@code llama-3.3-70b-versatile} but Groq removed it from the
 *       free tier (2026-09). <b>Do not silently change this value.</b></li>
 *   <li>{@code temperature: 0.3}</li>
 *   <li>{@code max-tokens: 2048}</li>
 * </ul>
 *
 * <h2>Design</h2>
 * <p>Intentionally thin: all prompt construction is the responsibility of
 * {@link com.codecompass.rag.RagService}.  This class exists solely to isolate
 * the LangChain4j dependency from the RAG orchestration logic and to make
 * the LLM call easy to mock in future tests.
 *
 * <h2>Error handling</h2>
 * <p>Any exception from the Groq API is propagated to the caller
 * ({@link com.codecompass.rag.RagService}), which logs it and returns a
 * {@code 500} to the client.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroqChatService {

    /** Auto-configured by langchain4j-open-ai-spring-boot-starter from application.yml. */
    private final ChatModel chatModel;

    /**
     * Sends {@code prompt} to Groq and returns the model's text response.
     *
     * @param prompt the full prompt string (system context + retrieved chunks + question)
     * @return the generated answer text
     * @throws RuntimeException if the Groq API call fails
     */
    public String chat(String prompt) {
        log.debug("Sending prompt to Groq ({} chars) …", prompt.length());
        String answer = chatModel.chat(prompt);
        log.debug("Groq response received ({} chars)", answer == null ? 0 : answer.length());
        return answer;
    }
}
