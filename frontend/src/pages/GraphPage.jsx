import { useState, useCallback } from 'react';
import { graphDependents } from '../api.js';
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

// Depth → visual risk tier colours (matches Impact Analysis palette)
const DEPTH_META = [
  { bg: '#6c8fff', border: '#8aa8ff', text: '#fff', label: 'Target',         cls: 'badge-accent'  }, // 0
  { bg: '#ff5c5c', border: '#ff8080', text: '#fff', label: 'HIGH – depth 1', cls: 'badge-high'    }, // 1
  { bg: '#f5c542', border: '#ffd76b', text: '#222', label: 'MEDIUM – depth 2',cls: 'badge-medium' }, // 2
  { bg: '#4caf7d', border: '#6ed89f', text: '#fff', label: 'LOW – depth 3+', cls: 'badge-low'     }, // 3
];
const dc = (d) => DEPTH_META[Math.min(d, 3)];

function buildGraph(data) {
  const nodes = [];
  const edges = [];
  const CX = 500, CY = 300;

  // Target centre node
  const tc = dc(0);
  nodes.push({
    id: '__target__',
    position: { x: CX - 90, y: CY - 30 },
    data: {
      label: `${data.targetClass}\n.${data.targetMethod}()`,
      depth: 0,
      detail: `Target method\n${data.targetClass}.${data.targetMethod}()`,
    },
    style: {
      background: tc.bg,
      border: `2px solid ${tc.border}`,
      color: tc.text,
      borderRadius: 10,
      padding: '10px 18px',
      fontWeight: 700,
      fontSize: 13,
      whiteSpace: 'pre',
      textAlign: 'center',
      minWidth: 160,
      boxShadow: `0 0 28px ${tc.bg}55`,
    },
  });

  // Group by depth
  const byDepth = {};
  for (const dep of data.dependents) {
    const d = dep.depth;
    if (!byDepth[d]) byDepth[d] = [];
    byDepth[d].push(dep);
  }

  const RADII = [0, 240, 420, 580];

  Object.entries(byDepth).forEach(([depthStr, deps]) => {
    const depth = Number(depthStr);
    const radius = RADII[Math.min(depth, 3)] || depth * 160;
    const total = deps.length;
    const c = dc(depth);

    deps.forEach((dep, i) => {
      const angleOffset = depth * 0.35;
      const angle = total === 1
        ? -Math.PI / 2
        : (2 * Math.PI * i) / total - Math.PI / 2 + angleOffset;
      const x = CX + radius * Math.cos(angle) - 80;
      const y = CY + radius * Math.sin(angle) - 24;
      const nodeId = `d${depth}_${i}_${dep.callerClass}_${dep.callerMethod}`;

      nodes.push({
        id: nodeId,
        position: { x, y },
        data: {
          label: `${dep.callerClass}\n.${dep.callerMethod}()`,
          depth,
          detail: `${dep.callerClass}.${dep.callerMethod}()\n\nFile: ${dep.callerFile}\nLines: ${dep.callerStartLine} – ${dep.callerEndLine}\nDepth: ${depth}`,
          dep,
        },
        style: {
          background: c.bg + '1a',
          border: `1.5px solid ${c.border}`,
          color: depth === 2 ? '#cdb040' : c.text,
          borderRadius: 8,
          padding: '8px 14px',
          fontSize: 12,
          whiteSpace: 'pre',
          textAlign: 'center',
          minWidth: 140,
        },
      });

      edges.push({
        id: `e_${nodeId}`,
        source: nodeId,
        target: '__target__',
        animated: depth === 1,
        style: { stroke: c.border, strokeWidth: depth === 1 ? 2 : 1.2, opacity: 0.7 },
        type: 'smoothstep',
      });
    });
  });

  return { nodes, edges };
}

export default function GraphPage() {
  const [repoId,      setRepoId]      = useState('');
  const [className,   setClassName]   = useState('');
  const [methodName,  setMethodName]  = useState('');
  const [loading,     setLoading]     = useState(false);
  const [error,       setError]       = useState(null);
  const [result,      setResult]      = useState(null);
  const [selected,    setSelected]    = useState(null);

  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);

  async function handleSubmit(e) {
    e.preventDefault();
    if (!repoId.trim() || !className.trim() || !methodName.trim()) return;
    setLoading(true);
    setError(null);
    setResult(null);
    setSelected(null);

    const res = await graphDependents(className.trim(), methodName.trim(), repoId.trim());
    setLoading(false);
    if (res.ok) {
      setResult(res.data);
      const { nodes: n, edges: eg } = buildGraph(res.data);
      setNodes(n);
      setEdges(eg);
    } else {
      setError(res.message);
    }
  }

  const onNodeClick = useCallback((_evt, node) => {
    setSelected(node.data.detail || null);
  }, []);

  return (
    <>
      <div className="page-header">
        <div className="page-title">🕸️ Call Graph</div>
        <div className="page-subtitle">
          Visualise the transitive call graph for any method — see exactly who calls it and at what depth.
        </div>
      </div>

      <div className="page-body">
        {/* ── Form ── */}
        <div className="card">
          <div className="card-title">🎯 Target Method</div>
          <form onSubmit={handleSubmit}>
            <div className="form-group">
              <label htmlFor="graph-repo">
                Repo ID (from Ingest) <span className="required">required</span>
              </label>
              <input
                id="graph-repo"
                type="text"
                value={repoId}
                onChange={e => setRepoId(e.target.value)}
                placeholder="C:/path/to/project/src"
                spellCheck={false}
              />
            </div>
            <div className="input-row" style={{ gap: 12 }}>
              <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
                <label htmlFor="graph-class">
                  Class name <span className="required">required</span>
                </label>
                <input
                  id="graph-class"
                  type="text"
                  value={className}
                  onChange={e => setClassName(e.target.value)}
                  placeholder="RepoIngestionService"
                  spellCheck={false}
                />
              </div>
              <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
                <label htmlFor="graph-method">
                  Method name <span className="required">required</span>
                </label>
                <input
                  id="graph-method"
                  type="text"
                  value={methodName}
                  onChange={e => setMethodName(e.target.value)}
                  placeholder="ingest"
                  spellCheck={false}
                />
              </div>
            </div>
            <div style={{ marginTop: 16 }}>
              <button
                type="submit"
                id="graph-submit-btn"
                className="btn btn-primary"
                disabled={loading || !repoId.trim() || !className.trim() || !methodName.trim()}
              >
                {loading ? <><span className="spinner" />Traversing…</> : <>🕸️ Render Graph</>}
              </button>
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

        {/* ── Result ── */}
        {result && (
          <>
            <div className="card" style={{ padding: '12px 20px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
                <span style={{ fontWeight: 600, color: 'var(--accent)' }}>
                  {result.targetClass}.{result.targetMethod}()
                </span>
                <span className="badge badge-accent">
                  {result.totalCount} dependent{result.totalCount !== 1 ? 's' : ''}
                </span>
                {result.totalCount === 0 && (
                  <span style={{ color: 'var(--text-secondary)', fontSize: 13 }}>
                    No callers found — this method has no transitive dependents in the graph.
                  </span>
                )}
              </div>
            </div>

            {result.totalCount > 0 && (
              <div className="graph-layout">
                {/* Canvas */}
                <div className="graph-canvas-wrap">
                  <ReactFlow
                    nodes={nodes}
                    edges={edges}
                    onNodesChange={onNodesChange}
                    onEdgesChange={onEdgesChange}
                    onNodeClick={onNodeClick}
                    fitView
                    fitViewOptions={{ padding: 0.22 }}
                    minZoom={0.2}
                    maxZoom={2.5}
                    colorMode="dark"
                  >
                    <Background color="#1a1d27" gap={24} size={1} />
                    <Controls style={{ background: 'var(--bg-elevated)', borderColor: 'var(--border)' }} />
                    <MiniMap
                      nodeColor={n => dc(n.data.depth ?? 0).bg}
                      style={{ background: 'var(--bg-elevated)', borderColor: 'var(--border)' }}
                    />
                  </ReactFlow>
                </div>

                {/* Side panel */}
                <div className="graph-sidebar">
                  {/* Legend */}
                  <div className="card" style={{ marginBottom: 12 }}>
                    <div className="card-title" style={{ fontSize: 12, marginBottom: 8 }}>Depth Legend</div>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                      {DEPTH_META.map(m => (
                        <span key={m.label} className={`badge ${m.cls}`} style={{ width: 'fit-content' }}>
                          {m.label}
                        </span>
                      ))}
                    </div>
                  </div>

                  {/* Node detail */}
                  <div className="card graph-detail-panel">
                    <div className="card-title" style={{ fontSize: 12 }}>Node Detail</div>
                    {selected
                      ? <pre style={{ fontSize: 12, color: 'var(--text-primary)', whiteSpace: 'pre-wrap', wordBreak: 'break-all', marginTop: 8 }}>{selected}</pre>
                      : <p style={{ color: 'var(--text-secondary)', fontSize: 12, marginTop: 8 }}>Click any node to see its file path and line range.</p>
                    }
                  </div>

                  {/* Dependents table */}
                  <div className="card" style={{ marginTop: 12, overflow: 'auto', flex: 1 }}>
                    <div className="card-title" style={{ fontSize: 12, marginBottom: 8 }}>All Dependents</div>
                    <table className="endpoint-table" style={{ fontSize: 11 }}>
                      <thead>
                        <tr>
                          <th>Depth</th>
                          <th>Class.Method</th>
                          <th>Lines</th>
                        </tr>
                      </thead>
                      <tbody>
                        {result.dependents.map((dep, i) => {
                          const m = dc(dep.depth);
                          return (
                            <tr key={i}>
                              <td><span className={`badge ${m.cls}`}>{dep.depth}</span></td>
                              <td style={{ color: 'var(--text-code)' }}>{dep.callerClass}.{dep.callerMethod}()</td>
                              <td style={{ color: 'var(--text-secondary)' }}>{dep.callerStartLine}–{dep.callerEndLine}</td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                </div>
              </div>
            )}
          </>
        )}

        {/* ── Empty state ── */}
        {!result && !error && !loading && (
          <div className="empty-state">
            <div className="empty-state-icon">🕸️</div>
            <h3>No graph rendered yet</h3>
            <p>Enter a Repo ID, class name, and method name above to visualise its call graph.</p>
          </div>
        )}
      </div>
    </>
  );
}
