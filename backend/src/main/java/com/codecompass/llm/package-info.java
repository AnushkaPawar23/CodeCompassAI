/**
 * LLM package – Stage 4 / Stage 6 / Stage 7
 *
 * Responsible for:
 *  - Configuring the Groq chat client (OpenAI-compatible, model: llama-3.3-70b-versatile)
 *  - Defining and rendering prompt templates for Q&A, impact explanation, and API docs
 *  - Housing LangChain4j AiService interfaces (annotated with @SystemMessage / @UserMessage)
 *
 * Key classes (added progressively from Stage 4 onward):
 *  - GroqChatConfig         : @Configuration for the Groq OpenAiChatModel bean
 *  - CodeQaAiService        : AiService interface for RAG-based Q&A
 *  - ImpactExplainAiService : AiService interface for change-impact narration
 *  - EndpointDocAiService   : AiService interface for REST endpoint documentation
 */
@NonNullApi
package com.codecompass.llm;

import org.springframework.lang.NonNullApi;
