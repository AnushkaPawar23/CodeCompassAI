import { useState } from 'react';
import IngestPage      from './pages/IngestPage.jsx';
import QaPage          from './pages/QaPage.jsx';
import ImpactPage      from './pages/ImpactPage.jsx';
import GraphPage       from './pages/GraphPage.jsx';
import ApiExplorerPage from './pages/ApiExplorerPage.jsx';

const NAV_ITEMS = [
  { id: 'ingest',  label: 'Ingest',          icon: '🚀', section: 'Core' },
  { id: 'qa',      label: 'Q&A Chat',         icon: '💬', section: 'Core' },
  { id: 'impact',  label: 'Impact Analysis',  icon: '⚡', section: 'Core' },
  { id: 'graph',   label: 'Call Graph',       icon: '🕸️', section: 'Core' },
  { id: 'apidocs', label: 'API Explorer',     icon: '📖', section: 'Core' },
];

const sections = [...new Set(NAV_ITEMS.map(n => n.section))];

export default function App() {
  const [activePage, setActivePage] = useState('ingest');

  function renderPage() {
    switch (activePage) {
      case 'ingest':  return <IngestPage />;
      case 'qa':      return <QaPage />;
      case 'impact':  return <ImpactPage />;
      case 'graph':   return <GraphPage />;
      case 'apidocs': return <ApiExplorerPage />;
      default:        return <IngestPage />;
    }
  }

  return (
    <div className="app-shell">
      {/* ── Sidebar ───────────────────────────────────────── */}
      <aside className="sidebar" role="navigation" aria-label="Main navigation">
        <div className="sidebar-logo">
          <div className="sidebar-logo-icon">🧭</div>
          <div className="sidebar-logo-text">
            <strong>CodeCompass</strong>
            <span>AI · v1.0</span>
          </div>
        </div>

        <nav className="sidebar-nav">
          {sections.map(section => (
            <div key={section}>
              <div className="nav-section-label">{section}</div>
              {NAV_ITEMS.filter(n => n.section === section).map(item => (
                <button
                  key={item.id}
                  id={`nav-${item.id}`}
                  className={`nav-item${activePage === item.id ? ' active' : ''}`}
                  onClick={() => setActivePage(item.id)}
                  aria-current={activePage === item.id ? 'page' : undefined}
                >
                  <span className="nav-item-icon">{item.icon}</span>
                  {item.label}
                </button>
              ))}
            </div>
          ))}
        </nav>

        <div className="sidebar-footer">
          <span className="status-dot" id="status-dot" />
          <span>Backend :8080</span>
        </div>
      </aside>

      {/* ── Main Content ──────────────────────────────────── */}
      <main className="main-content" role="main">
        {renderPage()}
      </main>
    </div>
  );
}

