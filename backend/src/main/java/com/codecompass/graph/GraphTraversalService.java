package com.codecompass.graph;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage 5 — traverses the persisted call graph to find all methods that
 * transitively call a given target method.
 *
 * <h2>Query semantics</h2>
 * <p>Given a target {@code (calleeClass, calleeMethod)}, this service answers the
 * question: <em>"who depends on this method?"</em>.  It returns every method in
 * the codebase that, directly or through a chain of intermediate calls, eventually
 * invokes the target.
 *
 * <h2>Algorithm</h2>
 * <p>Breadth-first search (BFS) over the call graph, traversing edges in reverse
 * (callee → caller direction):
 * <ol>
 *   <li>Start with the target method as the BFS frontier.</li>
 *   <li>At each step, query {@link CallGraphEdgeRepository#findByRepoIdAndCalleeClassAndCalleeMethod}
 *       to find all direct callers.</li>
 *   <li>Add newly-discovered callers to the frontier; skip any already visited
 *       (prevents infinite loops on recursive/mutually-recursive call chains).</li>
 *   <li>Continue until the frontier is empty.</li>
 * </ol>
 *
 * <h2>Result</h2>
 * <p>Returns a flat {@link List} of {@link DependentMethod} records, each carrying
 * the caller's class name, method name, source file, line range, and BFS depth
 * (1 = direct caller, 2 = caller-of-caller, etc.).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GraphTraversalService {

    private final CallGraphEdgeRepository edgeRepository;

    /**
     * Immutable result record describing one method that depends (directly or
     * transitively) on the queried target.
     *
     * @param callerClass   simple class name of the dependent method's class
     * @param callerMethod  simple name of the dependent method
     * @param callerFile    absolute path to the file containing the dependent method
     * @param callerStartLine 1-based start line of the method declaration
     * @param callerEndLine   1-based end line of the method declaration
     * @param depth         BFS depth: 1 = direct caller, 2 = caller-of-caller, …
     */
    public record DependentMethod(
            String callerClass,
            String callerMethod,
            String callerFile,
            int callerStartLine,
            int callerEndLine,
            int depth
    ) {}

    /**
     * Finds all methods that transitively depend on the specified target method.
     *
     * @param repoId       logical repository identifier (scope the graph query)
     * @param targetClass  simple class name of the target method's class
     * @param targetMethod simple method name of the target
     * @return ordered list of dependent methods (BFS order, depth ascending);
     *         empty if no callers exist or the target is not in the graph
     */
    public List<DependentMethod> findTransitiveDependents(
            String repoId, String targetClass, String targetMethod) {

        log.info("Graph traversal — repoId='{}', target={}.{}", repoId, targetClass, targetMethod);

        List<DependentMethod> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        // BFS queue entries: [className, methodName, depth]
        record BfsEntry(String className, String methodName, int depth) {}
        Deque<BfsEntry> queue = new ArrayDeque<>();

        // Seed: find all direct callers of the target
        String targetKey = targetClass + "#" + targetMethod;
        visited.add(targetKey);
        queue.add(new BfsEntry(targetClass, targetMethod, 0));

        while (!queue.isEmpty()) {
            BfsEntry current = queue.poll();

            List<CallGraphEdge> directCallers = edgeRepository
                    .findByRepoIdAndCalleeClassAndCalleeMethod(
                            repoId, current.className(), current.methodName());

            for (CallGraphEdge edge : directCallers) {
                String callerKey = edge.getCallerClass() + "#" + edge.getCallerMethod();
                if (visited.contains(callerKey)) continue;

                visited.add(callerKey);
                int depth = current.depth() + 1;

                result.add(new DependentMethod(
                        edge.getCallerClass(),
                        edge.getCallerMethod(),
                        edge.getCallerFile(),
                        edge.getCallerStartLine(),
                        edge.getCallerEndLine(),
                        depth
                ));

                queue.add(new BfsEntry(edge.getCallerClass(), edge.getCallerMethod(), depth));
            }
        }

        log.info("Graph traversal complete — {}.{} has {} transitive dependent(s)",
                targetClass, targetMethod, result.size());

        return result;
    }
}
