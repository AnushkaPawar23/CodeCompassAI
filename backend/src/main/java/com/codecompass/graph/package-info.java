/**
 * Graph package – Stage 5 / Stage 6
 *
 * Responsible for:
 *  - Modelling the static call graph as a directed graph (nodes = methods, edges = calls)
 *  - Persisting call graph edges to PostgreSQL
 *  - Providing BFS/DFS traversal for change-impact analysis
 *
 * Key classes (added in Stage 5 and Stage 6):
 *  - CallGraphEdge          : JPA entity storing a single caller→callee directed edge
 *  - CallGraphRepository    : Spring Data JPA repository for edge queries
 *  - GraphService           : builds the full graph for a repo; exposes traversal API
 *  - ImpactAnalysisService  : BFS/DFS starting from a target method, collects affected set
 */
@NonNullApi
package com.codecompass.graph;

import org.springframework.lang.NonNullApi;
