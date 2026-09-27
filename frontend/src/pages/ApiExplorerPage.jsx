import { useState } from 'react';
import { endpoints } from '../api.js';

// HTTP method → colour badge class
const METHOD_CLS = {
  GET:    'method-get',
  POST:   'method-post',
  PUT:    'method-put',
  DELETE: 'method-delete',
  PATCH:  'method-patch',
  ANY:    'method-any',
};

export default function ApiExplorerPage() {
  const [repoId,  setRepoId]  = useState('');
  const [loading, setLoading] = useState(false);
  const [error,   setError]   = useState(null);
  const [result,  setResult]  = useState(null);

  async function handleSubmit(e) {
    e.preventDefault();
    if (!repoId.trim()) return;
    setLoading(true);
    setError(null);
    setResult(null);

    const res = await endpoints(repoId.trim());
    setLoading(false);
    if (res.ok) {
      setResult(res.data);
    } else {
      setError(res.message);
    }
  }

  // Shorten absolute file path to just filename for display
  const shortPath = (fp) => {
    if (!fp) return '';
    const parts = fp.replace(/\\/g, '/').split('/');
    return parts[parts.length - 1];
  };

  return (
    <>
      <div className="page-header">
        <div className="page-title">📖 API Explorer</div>
        <div className="page-subtitle">
          Discover all Spring MVC REST endpoints in your repository — HTTP method, path, controller, and source location.
        </div>
      </div>

      <div className="page-body">
        {/* ── Form ── */}
        <div className="card">
          <div className="card-title">🔍 Repository Source</div>
          <form onSubmit={handleSubmit}>
            <div className="form-group">
              <label htmlFor="api-repo">
                Repo ID (from Ingest) <span className="required">required</span>
              </label>
              <div className="input-row">
                <input
                  id="api-repo"
                  type="text"
                  value={repoId}
                  onChange={e => setRepoId(e.target.value)}
                  placeholder="C:/path/to/project/src"
                  spellCheck={false}
                />
                <button
                  type="submit"
                  id="api-submit-btn"
                  className="btn btn-primary"
                  disabled={loading || !repoId.trim()}
                >
                  {loading ? <><span className="spinner" />Scanning…</> : <>📖 Discover</>}
                </button>
              </div>
            </div>
          </form>
        </div>

        {/* ── Error ── */}
        {error && (
          <div className="alert alert-error" role="alert">
            <span className="alert-icon">⚠️</span>
            <span>{error}</span>
          </div>
        )}

        {/* ── Results ── */}
        {result && (
          <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
            {/* Header */}
            <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', gap: 12 }}>
              <span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>
                {result.endpointCount} endpoint{result.endpointCount !== 1 ? 's' : ''} discovered
              </span>
              <span style={{ color: 'var(--text-secondary)', fontSize: 12 }}>in {result.repoId}</span>
            </div>

            {result.endpointCount === 0 ? (
              <div style={{ padding: '32px 20px', textAlign: 'center', color: 'var(--text-secondary)' }}>
                No Spring MVC endpoints found in this repository.
              </div>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table className="endpoint-table">
                  <thead>
                    <tr>
                      <th>Method</th>
                      <th>Path</th>
                      <th>Controller</th>
                      <th>Handler</th>
                      <th>File</th>
                      <th>Line</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.endpoints.map((ep, i) => (
                      <tr key={i} className="endpoint-row">
                        <td>
                          <span className={`http-method ${METHOD_CLS[ep.httpMethod] ?? 'method-any'}`}>
                            {ep.httpMethod}
                          </span>
                        </td>
                        <td>
                          <code className="endpoint-path">{ep.path}</code>
                        </td>
                        <td style={{ color: 'var(--text-secondary)' }}>{ep.controllerClass}</td>
                        <td style={{ color: 'var(--text-code)' }}>{ep.handlerMethod}()</td>
                        <td>
                          <span
                            title={ep.filePath}
                            style={{ color: 'var(--text-muted)', cursor: 'default', fontSize: 12 }}
                          >
                            {shortPath(ep.filePath)}
                          </span>
                        </td>
                        <td style={{ color: 'var(--text-muted)', fontSize: 12 }}>:{ep.startLine}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}

        {/* ── Empty state ── */}
        {!result && !error && !loading && (
          <div className="empty-state">
            <div className="empty-state-icon">📖</div>
            <h3>No endpoints discovered yet</h3>
            <p>Enter the Repo ID of a locally-ingested repository to scan its Spring MVC controllers.</p>
          </div>
        )}
      </div>
    </>
  );
}
