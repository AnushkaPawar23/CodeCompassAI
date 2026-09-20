package com.codecompass.graph;

import com.codecompass.llm.GroqChatService;
import com.codecompass.rag.CodeChunk;
import com.codecompass.rag.CodeChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Stage 6 — change-impact analysis service.
 * Stage 8a — risk-scored impact analysis (HIGH / MEDIUM / LOW per BFS depth).
 * Stage 8b — test-coverage detection per dependent via {@link TestCoverageService}.
 *
 * <h2>Purpose</h2>
 * <p>Given a target method {@code (repoId, targetClass, targetMethod)}, this
 * service answers the question: <em>"If I change this method, what else could
 * break?"</em>.  It combines the static call-graph traversal from
 * {@link GraphTraversalService} with an LLM-generated natural-language
 * explanation of which dependent methods are affected and why.
 *
 * <h2>Stage 8a additions</h2>
 * <ul>
 *   <li>Each dependent is assigned a {@link RiskLevel} based on its BFS depth:
 *       {@code HIGH} (depth 1 — direct caller), {@code MEDIUM} (depth 2),
 *       {@code LOW} (depth 3+).</li>
 *   <li>The LLM prompt now includes the risk level per dependent so the
 *       generated explanation can reference it (e.g. "HIGH RISK:
 *       IngestionController#ingest directly depends on this…").</li>
 *   <li>The response includes top-level summary counts:
 *       {@code highRiskCount}, {@code mediumRiskCount}, {@code lowRiskCount}.</li>
 * </ul>
 *
 * <h2>Stage 8b additions</h2>
 * <ul>
 *   <li>Each dependent gains a {@code hasTestCoverage} boolean produced by
 *       {@link TestCoverageService#hasTestCoverage}.</li>
 *   <li>The response includes a top-level {@code untestedDependentsCount}.</li>
 * </ul>
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Graph traversal</b> — delegates to
 *       {@link GraphTraversalService#findTransitiveDependents} to obtain the
 *       full transitive caller set (BFS, all depths).</li>
 *   <li><b>Zero-dependents fast path</b> — if no callers are found the method
 *       is an entry point or leaf; return immediately with a "safe to change"
 *       message and skip the LLM call.</li>
 *   <li><b>Enrichment</b> — for each dependent, compute its {@link RiskLevel}
 *       and run the test-coverage heuristic to produce {@link RichDependent}
 *       records.</li>
 *   <li><b>Code chunk lookup</b> — for the target method and for each
 *       dependent, fetches the corresponding {@link CodeChunk} from
 *       {@link CodeChunkRepository}.  Primary lookup is by
 *       {@code (repoId, className, methodName)} which returns the
 *       {@code METHOD}-type chunk.  If no method chunk exists, falls back
 *       to the {@code CLASS}-type chunk so the prompt still has code context.</li>
 *   <li><b>Prompt construction</b> — builds a structured prompt that includes
 *       the target method's source code and each dependent's source code,
 *       each capped at {@code codecompass.rag.max-chunk-chars} characters to
 *       avoid Groq 413 errors (same guard as {@link com.codecompass.rag.RagService}).
 *       Risk level is embedded in each dependent's prompt section.</li>
 *   <li><b>LLM call</b> — delegates to {@link GroqChatService#chat(String)}.</li>
 *   <li><b>Return</b> — packages the enriched dependent list, summary counts,
 *       and LLM explanation into an {@link ImpactResult} for the controller.</li>
 * </ol>
 *
 * <h2>Context-overflow safety</h2>
 * <p>Each chunk's content is truncated to {@code codecompass.rag.max-chunk-chars}
 * characters (default 2 000, ~500 tokens) before inclusion in the prompt — the
 * same limit applied in {@link com.codecompass.rag.RagService#buildPrompt}.
 * The number of dependents included in the prompt is additionally capped at
 * {@code codecompass.impact.max-dependents-in-prompt} (default 10) to keep
 * the total prompt size bounded for repos with many callers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImpactAnalysisService {

    private final GraphTraversalService traversalService;
    private final CodeChunkRepository   chunkRepository;
    private final GroqChatService       groqChatService;
    private final TestCoverageService   testCoverageService;

    /**
     * Per-chunk character cap — reuses the Stage 4 property so a single
     * config value controls context size across both RAG and impact analysis.
     */
    @Value("${codecompass.rag.max-chunk-chars:2000}")
    private int maxChunkChars;

    /**
     * Maximum number of dependents whose code is included in the LLM prompt.
     * Dependents beyond this limit are still listed in the response but their
     * code is omitted from the prompt (the LLM still knows they exist via the
     * summary header).
     */
    @Value("${codecompass.impact.max-dependents-in-prompt:10}")
    private int maxDependentsInPrompt;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Risk classification for a dependent method, derived from its BFS depth
     * in the transitive call graph.
     *
     * <ul>
     *   <li>{@code HIGH}   — depth 1: directly calls the changed method.</li>
     *   <li>{@code MEDIUM} — depth 2: calls a method that calls the changed method.</li>
     *   <li>{@code LOW}    — depth 3+: further transitive dependency.</li>
     * </ul>
     */
    public enum RiskLevel {
        HIGH, MEDIUM, LOW;

        /**
         * Maps a BFS depth (1-based) to the corresponding risk level.
         *
         * @param depth BFS depth ≥ 1
         * @return {@code HIGH} for depth 1, {@code MEDIUM} for depth 2,
         *         {@code LOW} for depth ≥ 3
         */
        public static RiskLevel fromDepth(int depth) {
            return switch (depth) {
                case 1  -> HIGH;
                case 2  -> MEDIUM;
                default -> LOW;
            };
        }
    }

    /**
     * Enriched representation of one dependent method — extends the raw
     * {@link GraphTraversalService.DependentMethod} with a risk classification
     * (Stage 8a) and a test-coverage indicator (Stage 8b).
     *
     * @param callerClass     simple class name of the dependent method's class
     * @param callerMethod    simple name of the dependent method
     * @param callerFile      absolute path to the file containing the dependent
     * @param callerStartLine 1-based start line of the method declaration
     * @param callerEndLine   1-based end line of the method declaration
     * @param depth           BFS depth: 1 = direct caller, 2 = caller-of-caller, …
     * @param riskLevel       risk classification derived from {@code depth}
     * @param hasTestCoverage {@code true} if a test file referencing this
     *                        class and method was found in {@code src/test/java}
     */
    public record RichDependent(
            String    callerClass,
            String    callerMethod,
            String    callerFile,
            int       callerStartLine,
            int       callerEndLine,
            int       depth,
            RiskLevel riskLevel,
            boolean   hasTestCoverage
    ) {}

    /**
     * Immutable result of a change-impact analysis run.
     *
     * @param targetClass            simple class name of the method under analysis
     * @param targetMethod           simple method name of the method under analysis
     * @param repoId                 repository scope
     * @param dependentsFound        total number of transitive callers found
     * @param highRiskCount          number of dependents at depth 1 (HIGH risk)
     * @param mediumRiskCount        number of dependents at depth 2 (MEDIUM risk)
     * @param lowRiskCount           number of dependents at depth 3+ (LOW risk)
     * @param untestedDependentsCount number of dependents with no detected test coverage
     * @param dependents             ordered list of enriched dependent methods (BFS order,
     *                               depth ascending); empty if none found
     * @param explanation            LLM-generated natural-language explanation of
     *                               the potential impact; a "safe to change" message
     *                               if {@code dependentsFound == 0}
     */
    public record ImpactResult(
            String            targetClass,
            String            targetMethod,
            String            repoId,
            int               dependentsFound,
            int               highRiskCount,
            int               mediumRiskCount,
            int               lowRiskCount,
            int               untestedDependentsCount,
            List<RichDependent> dependents,
            String            explanation
    ) {}

    /**
     * Analyses the change impact for the specified method.
     *
     * @param repoId       logical repository identifier
     * @param targetClass  simple class name of the method being changed
     * @param targetMethod simple method name being changed
     * @return {@link ImpactResult} with enriched dependent list, risk counts,
     *         and LLM explanation
     */
    public ImpactResult analyse(String repoId, String targetClass, String targetMethod) {

        log.info("Impact analysis — repoId='{}', target={}.{}", repoId, targetClass, targetMethod);

        // ── 1. Traverse the call graph ────────────────────────────────────────
        List<GraphTraversalService.DependentMethod> rawDependents =
                traversalService.findTransitiveDependents(repoId, targetClass, targetMethod);

        // ── 2. Zero-dependents fast path ──────────────────────────────────────
        if (rawDependents.isEmpty()) {
            log.info("Impact analysis — {}.{} has no dependents (safe to change in isolation)",
                    targetClass, targetMethod);
            return new ImpactResult(
                    targetClass,
                    targetMethod,
                    repoId,
                    0, 0, 0, 0, 0,
                    List.of(),
                    "No dependents found — %s#%s is not called by any other method in the ".formatted(
                            targetClass, targetMethod) +
                    "ingested codebase. It can be changed in isolation without risk of " +
                    "breaking transitive callers."
            );
        }

        log.info("Impact analysis — {}.{} has {} transitive dependent(s); enriching and building LLM prompt",
                targetClass, targetMethod, rawDependents.size());

        // ── 3. Enrich dependents (risk level + test coverage) ─────────────────
        List<RichDependent> enriched = rawDependents.stream()
                .map(d -> new RichDependent(
                        d.callerClass(),
                        d.callerMethod(),
                        d.callerFile(),
                        d.callerStartLine(),
                        d.callerEndLine(),
                        d.depth(),
                        RiskLevel.fromDepth(d.depth()),
                        testCoverageService.hasTestCoverage(repoId, d.callerClass(), d.callerMethod())))
                .toList();

        // ── 4. Compute summary counts ─────────────────────────────────────────
        int highCount      = (int) enriched.stream().filter(d -> d.riskLevel() == RiskLevel.HIGH).count();
        int mediumCount    = (int) enriched.stream().filter(d -> d.riskLevel() == RiskLevel.MEDIUM).count();
        int lowCount       = (int) enriched.stream().filter(d -> d.riskLevel() == RiskLevel.LOW).count();
        int untestedCount  = (int) enriched.stream().filter(d -> !d.hasTestCoverage()).count();

        log.info("Impact analysis — risk counts: HIGH={}, MEDIUM={}, LOW={}, untested={}",
                highCount, mediumCount, lowCount, untestedCount);

        // ── 5. Look up target chunk ───────────────────────────────────────────
        String targetContent = resolveChunkContent(repoId, targetClass, targetMethod);

        // Build prompt only for up to maxDependentsInPrompt dependents
        List<RichDependent> promptDependents =
                enriched.size() <= maxDependentsInPrompt
                        ? enriched
                        : enriched.subList(0, maxDependentsInPrompt);

        // ── 6. Build the prompt ───────────────────────────────────────────────
        String prompt = buildImpactPrompt(
                targetClass, targetMethod, targetContent,
                promptDependents, enriched.size(), repoId,
                highCount, mediumCount, lowCount);

        log.debug("Impact prompt built — {} chars, {} dependents in prompt (of {} total)",
                prompt.length(), promptDependents.size(), enriched.size());

        // ── 7. Call Groq ──────────────────────────────────────────────────────
        String explanation = groqChatService.chat(prompt);
        log.info("Impact analysis complete — LLM explanation {} chars",
                explanation == null ? 0 : explanation.length());

        return new ImpactResult(
                targetClass, targetMethod, repoId,
                enriched.size(), highCount, mediumCount, lowCount, untestedCount,
                enriched, explanation);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Fetches the source code content for a given method from the chunk store.
     *
     * <p>Lookup strategy (in priority order):
     * <ol>
     *   <li>Find a {@code METHOD}-type chunk matching
     *       {@code (repoId, className, methodName)}.</li>
     *   <li>If not found, fall back to the first {@code CLASS}-type chunk
     *       matching {@code (repoId, className)} — gives at least some context.</li>
     *   <li>If neither exists, return a "(source not available)" placeholder.</li>
     * </ol>
     *
     * <p>The returned content is truncated to {@code maxChunkChars} characters.
     */
    private String resolveChunkContent(String repoId, String className, String methodName) {
        // Primary: METHOD chunk
        List<CodeChunk> methodChunks =
                chunkRepository.findByRepoIdAndClassNameAndMethodName(repoId, className, methodName);
        if (!methodChunks.isEmpty()) {
            return truncate(methodChunks.get(0).getContent());
        }

        // Fallback: CLASS chunk
        List<CodeChunk> classChunks = chunkRepository.findByRepoIdAndClassName(repoId, className);
        for (CodeChunk c : classChunks) {
            if (c.getChunkType() == CodeChunk.ChunkType.CLASS) {
                return truncate(c.getContent());
            }
        }

        log.warn("No code chunk found for {}.{} in repoId='{}' — prompt will note unavailability",
                className, methodName, repoId);
        return "(source code not available in chunk store for " + className + "#" + methodName + ")";
    }

    /**
     * Assembles the structured change-impact prompt sent to Groq.
     *
     * <p>Stage 8a: each dependent's section now includes its risk level label
     * (e.g. {@code Risk: HIGH (direct caller)}) and the TASK instruction
     * explicitly asks the LLM to reference risk levels per dependent.
     *
     * <p>The prompt includes:
     * <ul>
     *   <li>System persona — expert Java code reviewer performing change-impact analysis.</li>
     *   <li>Risk summary — HIGH/MEDIUM/LOW counts at the top.</li>
     *   <li>Target method section — class name, method name, and source code.</li>
     *   <li>Dependents section — for each dependent (up to {@code maxDependentsInPrompt}):
     *       class name, method name, file path, BFS depth, risk level, and source code.</li>
     *   <li>Task instruction — ask the LLM to explain what could break, citing
     *       specific methods with their risk level, class name, method name,
     *       file, and line range.</li>
     * </ul>
     */
    private String buildImpactPrompt(
            String targetClass,
            String targetMethod,
            String targetContent,
            List<RichDependent> dependents,
            int totalDependents,
            String repoId,
            int highCount,
            int mediumCount,
            int lowCount) {

        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are an expert Java code reviewer performing change-impact analysis. \
                Your task is to explain, in clear natural language, what could break if \
                the specified target method is changed — and why. For each affected method, \
                prefix your explanation with its risk label (HIGH RISK, MEDIUM RISK, or \
                LOW RISK) and reference it by its exact class name, method name, source \
                file, and line range so the developer can navigate directly to it. \
                Be concise but thorough.

                """);

        // ── Risk summary header ───────────────────────────────────────────────
        sb.append("=== RISK SUMMARY ===\n\n");
        sb.append("HIGH   (depth 1 — direct callers)       : ").append(highCount).append('\n');
        sb.append("MEDIUM (depth 2 — one hop removed)      : ").append(mediumCount).append('\n');
        sb.append("LOW    (depth 3+ — transitive callers)  : ").append(lowCount).append('\n');
        sb.append('\n');

        // ── Target method ─────────────────────────────────────────────────────
        sb.append("=== TARGET METHOD (being changed) ===\n\n");
        sb.append("Class  : ").append(targetClass).append('\n');
        sb.append("Method : ").append(targetMethod).append('\n');
        sb.append("```java\n");
        sb.append(targetContent);
        sb.append("\n```\n\n");

        // ── Dependents ────────────────────────────────────────────────────────
        sb.append("=== DEPENDENT METHODS (callers — direct and transitive) ===\n\n");

        if (totalDependents > dependents.size()) {
            sb.append("Note: ").append(totalDependents)
              .append(" total dependents found; showing code for the first ")
              .append(dependents.size()).append(" (shallowest callers first).\n\n");
        }

        for (int i = 0; i < dependents.size(); i++) {
            RichDependent dep = dependents.get(i);
            String depContent = resolveChunkContent(repoId, dep.callerClass(), dep.callerMethod());
            String riskLabel  = dep.riskLevel().name() + " RISK";
            String depthLabel = dep.depth() == 1
                    ? "depth 1 — direct caller"
                    : "depth " + dep.depth() + " — transitive caller";

            sb.append("[Dependent ").append(i + 1).append("] *** ").append(riskLabel).append(" ***\n");
            sb.append("Class      : ").append(dep.callerClass()).append('\n');
            sb.append("Method     : ").append(dep.callerMethod()).append('\n');
            sb.append("File       : ").append(dep.callerFile()).append('\n');
            sb.append("Lines      : ").append(dep.callerStartLine())
              .append('–').append(dep.callerEndLine()).append('\n');
            sb.append("Depth      : ").append(dep.depth())
              .append(" (").append(depthLabel).append(")\n");
            sb.append("Risk       : ").append(riskLabel).append('\n');
            sb.append("```java\n");
            sb.append(depContent);
            sb.append("\n```\n\n");
        }

        // ── Task ──────────────────────────────────────────────────────────────
        sb.append("=== TASK ===\n\n");
        sb.append("Explain what could break if ").append(targetClass).append('#')
          .append(targetMethod).append(" is changed. ");
        sb.append("For each dependent method listed above, start your description with its ");
        sb.append("risk label (e.g. \"HIGH RISK: ").append(targetClass)
          .append("…\") and describe specifically how it might be affected ");
        sb.append("(e.g. contract violations, incorrect data, runtime exceptions, or cascading failures). ");
        sb.append("If some dependents are at depth > 1 (transitive callers), explain the ");
        sb.append("propagation path. Use method names and file locations in your explanation.\n");

        return sb.toString();
    }

    /** Truncates {@code content} to {@code maxChunkChars} characters. */
    private String truncate(String content) {
        if (content == null) return "";
        if (content.length() <= maxChunkChars) return content;
        return content.substring(0, maxChunkChars) + "\n… [truncated]";
    }
}
