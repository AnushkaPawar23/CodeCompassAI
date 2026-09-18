/**
 * API package – all stages
 *
 * Responsible for:
 *  - Exposing REST endpoints consumed by the React frontend
 *  - Routing requests to the appropriate service layer
 *  - Handling request/response DTOs and error responses
 *
 * Controllers added per stage:
 *  - Stage 2: IngestionController  (POST /api/ingest)
 *  - Stage 4: QaController         (POST /api/qa)
 *  - Stage 5: GraphController      (GET  /api/graph/{repoId}/callers)
 *  - Stage 6: ImpactController     (POST /api/impact)
 *  - Stage 7: EndpointController   (GET  /api/endpoints/{repoId})
 */
@NonNullApi
package com.codecompass.api;

import org.springframework.lang.NonNullApi;
