import { useState } from 'react';
import { impact } from '../api.js';

const RISK_LABELS = { HIGH: 'HIGH', MEDIUM: 'MEDIUM', LOW: 'LOW' };
const RISK_BADGE  = { HIGH: 'badge-high', MEDIUM: 'badge-medium', LOW: 'badge-low' };

export default function ImpactPage() {
  const [repoId, setRepoId]           = useState('');
  const [targetClass, setTargetClass] = useState('');
  const [targetMethod, setTargetMethod] = useState('');
  const [loading, setLoading]         = useState(false);
  const [error, setError]             = useState(null);
  const [result, setResult]           = useState(null);

  async function handleSubmit(e) {
    e.preventDefault();
    if (!repoId.trim() || !targetClass.trim() || !targetMethod.trim()) return;
    setLoading(true);
    setError(null);
    setResult(null);

    const res = await impact(repoId.trim(), targetClass.trim(), targetMethod.trim());
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
        <div className="page-title">⚡ Impact Analysis</div>
        <div className="page-subtitle">
          Find all transitive callers of a method, with risk-level colour-coding and an LLM explanation.
        </div>
      </div>

      <div className="page-body">
        <div className="card">
          <div className="card-title">🎯 Target Method</div>
          <form onSubmit={handleSubmit}>
            <div className="form-group">
              <label htmlFor="impact-repo">
                Repo ID (from Ingest) <span className="required">required</span>
              </label>
              <input
                id="impact-repo"
                type="text"
                value={repoId}
                onChange={e => setRepoId(e.target.value)}
                placeholder="C:/path/to/project/src"
                spellCheck={false}
              />
            </div>

            <div className="input-row" style={{ gap: 12 }}>
              <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
                <label htmlFor="impact-class">
                  Class name <span className="required">required</span>
                </label>
                <input
                  id="impact-class"
                  type="text"
                  value={targetClass}
                  onChange={e => setTargetClass(e.target.value)}
                  placeholder="ChunkingService"
                  spellCheck={false}
                />
              </div>
              <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
                <label htmlFor="impact-method">
                  Method name <span className="required">required</span>
                </label>
                <input
                  id="impact-method"
                  type="text"
                  value={targetMethod}
                  onChange={e => setTargetMethod(e.target.value)}
                  placeholder="chunk"
                  spellCheck={false}
                />
              </div>
            </div>

            <div style={{ marginTop: 14 }}>
              <button
                type="submit"
                className="btn btn-primary"
                disabled={loading || !repoId.trim() || !targetClass.trim() || !targetMethod.trim()}
                id="impact-submit-btn"
              >
                {loading ? (
                  <><span className="spinner" />Analysing…</>
                ) : (
                  <>⚡ Analyse Impact</>
                )}
              </button>
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

        {/* Results */}
        {result && (
          <div className="impact-result">
            {/* Summary Stats */}
            <div className="impact-summary-grid">
              <StatBox value={result.dependentsFound} label="Dependents" color="var(--accent)" />
              <StatBox value={result.highRiskCount}   label="High risk"   color="var(--high)" />
              <StatBox value={result.mediumRiskCount} label="Medium risk" color="var(--medium)" />
              <StatBox value={result.lowRiskCount}    label="Low risk"    color="var(--low)" />
              <StatBox value={result.untestedDependentsCount} label="Untested" color="var(--text-muted)" />
            </div>

            {/* Zero dependents case */}
            {result.dependentsFound === 0 && (
              <div className="alert alert-success">
                <span className="alert-icon">✅</span>
                <span>
                  <strong>{result.targetClass}#{result.targetMethod}</strong> has no dependents — safe to change in isolation.
                </span>
              </div>
            )}

            {/* Dependents list */}
            {result.dependents && result.dependents.length > 0 && (
              <>
                <div className="section-heading">📋 Dependents ({result.dependentsFound})</div>
                <div className="dependents-list">
                  {result.dependents.map((dep, i) => (
                    <DependentRow key={i} dep={dep} />
                  ))}
                </div>
              </>
            )}

            {/* LLM Explanation */}
            {result.explanation && (
              <>
                <div className="divider" />
                <div className="section-heading">🤖 LLM Explanation</div>
                <div className="explanation-box">{result.explanation}</div>
              </>
            )}
          </div>
        )}

        {/* Empty state */}
        {!result && !error && !loading && (
          <div className="empty-state">
            <div className="empty-state-icon">🕸️</div>
            <h3>No analysis run yet</h3>
            <p>Enter a repo ID, class, and method name above to see all transitive dependents.</p>
          </div>
        )}
      </div>
    </>
  );
}

function DependentRow({ dep }) {
  const risk = dep.riskLevel || 'LOW';
  const badgeClass = RISK_BADGE[risk] || 'badge-low';
  const fileName = dep.callerFile
    ? dep.callerFile.replace(/\\/g, '/').split('/').pop()
    : null;

  return (
    <div className={`dependent-row risk-${risk}`}>
      <div className="dependent-header">
        <span className="dependent-name">
          {dep.callerClass}
          <span style={{ color: 'var(--text-muted)' }}>#</span>
          {dep.callerMethod}
        </span>
        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
          <span className={`badge ${badgeClass}`}>{RISK_LABELS[risk]}</span>
          {dep.hasTestCoverage != null && (
            <span className={`badge ${dep.hasTestCoverage ? 'badge-low' : 'badge-info'}`}>
              {dep.hasTestCoverage ? '✓ tested' : '✗ untested'}
            </span>
          )}
        </div>
      </div>
      <div className="dependent-meta">
        {fileName && (
          <span title={dep.callerFile}>{fileName}</span>
        )}
        {dep.callerStartLine != null && (
          <span>L{dep.callerStartLine}–{dep.callerEndLine}</span>
        )}
        {dep.depth != null && (
          <span>depth {dep.depth}</span>
        )}
      </div>
    </div>
  );
}

function StatBox({ value, label, color }) {
  return (
    <div className="stat-box">
      <div className="stat-value" style={{ color: color || 'var(--accent)' }}>
        {value ?? 0}
      </div>
      <div className="stat-label">{label}</div>
    </div>
  );
}
