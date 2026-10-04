import { useState } from 'react';
import { ingest } from '../api.js';

// Local-path ingestion only makes sense when the backend runs on the user's own
// machine. Hidden in production builds (e.g. Vercel -> Render), kept for `npm run dev`.
const LOCAL_INGEST_ENABLED = !import.meta.env.PROD;

export default function IngestPage() {
  const [source, setSource] = useState('');
  const [type, setType] = useState(LOCAL_INGEST_ENABLED ? 'local' : 'git');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);

  async function handleSubmit(e) {
    e.preventDefault();
    if (!source.trim()) return;
    setLoading(true);
    setError(null);
    setResult(null);

    const res = await ingest(source.trim(), type);
    setLoading(false);
    if (res.ok) {
      setResult(res.data);
    } else {
      setError(res.message);
    }
  }

  return (
    <>
      <div className="page-header">
        <div className="page-title">🚀 Ingest Repository</div>
        <div className="page-subtitle">
          {LOCAL_INGEST_ENABLED
            ? 'Point CodeCompass at a local path or Git URL to parse, chunk, and embed your codebase.'
            : 'Point CodeCompass at a public Git URL to parse, chunk, and embed your codebase.'}
        </div>
      </div>

      <div className="page-body">
        <div className="card">
          <div className="card-title">📁 Repository Source</div>
          <form onSubmit={handleSubmit}>
            <div className="form-group">
              <label htmlFor="ingest-type">Repository type</label>
              <select
                id="ingest-type"
                value={type}
                onChange={e => setType(e.target.value)}
              >
                {LOCAL_INGEST_ENABLED && <option value="local">Local path</option>}
                <option value="git">Git URL (cloned)</option>
              </select>
            </div>

            <div className="form-group">
              <label htmlFor="ingest-source">
                {type === 'local' ? 'Absolute path' : 'Git URL'}
                <span className="required">required</span>
              </label>
              <div className="input-row">
                <input
                  id="ingest-source"
                  type="text"
                  value={source}
                  onChange={e => setSource(e.target.value)}
                  placeholder={
                    type === 'local'
                      ? 'C:/path/to/your/project/src'
                      : 'https://github.com/owner/repo.git'
                  }
                  autoComplete="off"
                  spellCheck={false}
                />
                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={loading || !source.trim()}
                  id="ingest-submit-btn"
                >
                  {loading ? (
                    <><span className="spinner" />Ingesting…</>
                  ) : (
                    <>▶ Ingest</>
                  )}
                </button>
              </div>
            </div>
          </form>
        </div>

        {/* Error */}
        {error && (
          <div className="alert alert-error" role="alert">
            <span className="alert-icon">⚠️</span>
            <span>{error}</span>
          </div>
        )}

        {/* Success result */}
        {result && (
          <div className="ingest-result">
            <div className="alert alert-success">
              <span className="alert-icon">✅</span>
              <span>
                Ingestion complete for <code>{result.repoId}</code>
                {result.durationMs != null && ` in ${(result.durationMs / 1000).toFixed(1)}s`}
              </span>
            </div>

            <div className="ingest-stats">
              <StatBox value={result.filesParsed} label="Files processed" />
              <StatBox value={result.chunksCreated} label="Chunks stored" />
              <StatBox value={result.chunksCreated} label="Embeddings" />
            </div>

            <div className="card">
              <div className="card-title">🗂️ Repo ID (use in Impact &amp; Graph queries)</div>
              <code style={{ wordBreak: 'break-all', display: 'block', padding: '8px', background: 'var(--bg-elevated)', borderRadius: 'var(--radius-sm)' }}>
                {result.repoId}
              </code>
            </div>
          </div>
        )}

        {/* Hint */}
        {!result && !error && !loading && (
          <div className="empty-state">
            <div className="empty-state-icon">🗃️</div>
            <h3>No repository ingested yet</h3>
            <p>Enter a path or URL above to start indexing your codebase.</p>
          </div>
        )}
      </div>
    </>
  );
}

function StatBox({ value, label }) {
  return (
    <div className="stat-box">
      <div className="stat-value">{value ?? '—'}</div>
      <div className="stat-label">{label}</div>
    </div>
  );
}
