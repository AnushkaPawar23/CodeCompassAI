// Shared API client — all 5 backend endpoints
// Base URL: http://localhost:8080

const BASE = 'http://localhost:8080';

/**
 * Shared fetch wrapper that normalises errors into
 * { ok: false, message: "..." } or { ok: true, data: ... }
 */
async function apiFetch(path, options = {}) {
  try {
    const res = await fetch(`${BASE}${path}`, {
      headers: { 'Content-Type': 'application/json', ...options.headers },
      ...options,
    });

    const contentType = res.headers.get('content-type') || '';
    const isJson = contentType.includes('application/json');
    const body = isJson ? await res.json() : await res.text();

    if (!res.ok) {
      // Unwrap ErrorResponse { status, error, message } or plain string
      const message =
        (typeof body === 'object' && body.message) ||
        (typeof body === 'string' && body) ||
        `HTTP ${res.status}`;
      return { ok: false, status: res.status, message };
    }

    return { ok: true, data: body };
  } catch (err) {
    return { ok: false, status: 0, message: `Network error: ${err.message}` };
  }
}

// ── 1. POST /api/ingest ───────────────────────────────────
// body: { source: string, type: "local"|"git" }
// 200 : { repoId, filesParsed, chunksCreated, durationMs, failedFiles }
export function ingest(source, type = 'local') {
  return apiFetch('/api/ingest', {
    method: 'POST',
    body: JSON.stringify({ source, type }),
  });
}

// ── 2. POST /api/qa ───────────────────────────────────────
// body: { question: string, topK?: number }
// 200 : { answer: string, sources: [...] }
export function qa(question, topK = 5) {
  return apiFetch('/api/qa', {
    method: 'POST',
    body: JSON.stringify({ question, topK }),
  });
}

// ── 3. GET /api/graph/dependents ─────────────────────────
// params: { class, method, repoId }
// 200   : { targetClass, targetMethod, repoId, totalCount, dependents }
export function graphDependents(className, methodName, repoId) {
  const params = new URLSearchParams({ class: className, method: methodName, repoId });
  return apiFetch(`/api/graph/dependents?${params}`);
}

// ── 4. POST /api/impact ───────────────────────────────────
// body: { repoId, targetClass, targetMethod }
// 200 : { targetClass, targetMethod, repoId, dependentsFound,
//         highRiskCount, mediumRiskCount, lowRiskCount,
//         untestedDependentsCount, dependents, explanation }
export function impact(repoId, targetClass, targetMethod) {
  return apiFetch('/api/impact', {
    method: 'POST',
    body: JSON.stringify({ repoId, targetClass, targetMethod }),
  });
}

// ── 5. GET /api/endpoints ────────────────────────────────
// params: { repoId }
// 200   : { repoId, endpointCount, endpoints: [...] }
export function endpoints(repoId) {
  const params = new URLSearchParams({ repoId });
  return apiFetch(`/api/endpoints?${params}`);
}
