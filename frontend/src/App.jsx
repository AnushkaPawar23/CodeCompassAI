import { useState } from 'react';
import IngestPage from './pages/IngestPage.jsx';
import QaPage     from './pages/QaPage.jsx';
import ImpactPage from './pages/ImpactPage.jsx';

const NAV_ITEMS = [
  { id: 'ingest', label: 'Ingest',          icon: '🚀', section: 'Core' },
  { id: 'qa',     label: 'Q&A Chat',         icon: '💬', section: 'Core' },
  { id: 'impact', label: 'Impact Analysis',  icon: '⚡', section: 'Core' },
  { id: 'graph',  label: 'Call Graph',       icon: '🕸️', section: 'Coming in Stage 10', disabled: true },
  { id: 'apidocs',label: 'API Explorer',     icon: '📖', section: 'Coming in Stage 10', disabled: true },
];

const sections = [...new Set(NAV_ITEMS.map(n => n.section))];

export default function App() {
  const [activePage, setActivePage] = useState('ingest');

  function renderPage() {
    switch (activePage) {
      case 'ingest': return <IngestPage />;
      case 'qa':     return <QaPage />;
      case 'impact': return <ImpactPage />;
      default:       return <IngestPage />;
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
            <span>AI · v0.9</span>
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
                  className={`nav-item${activePage === item.id ? ' active' : ''}${item.disabled ? ' disabled' : ''}`}
                  onClick={() => !item.disabled && setActivePage(item.id)}
                  disabled={item.disabled}
                  title={item.disabled ? 'Coming in Stage 10' : undefined}
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
