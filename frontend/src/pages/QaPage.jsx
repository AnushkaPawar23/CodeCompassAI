import { useState, useRef, useEffect } from 'react';
import { qa } from '../api.js';

export default function QaPage() {
  const [question, setQuestion] = useState('');
  const [messages, setMessages] = useState([]);
  const [loading, setLoading] = useState(false);
  const messagesEndRef = useRef(null);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, loading]);

  async function handleSend() {
    const q = question.trim();
    if (!q || loading) return;

    setQuestion('');
    setMessages(prev => [...prev, { role: 'user', text: q }]);
    setLoading(true);

    const res = await qa(q);
    setLoading(false);

    if (res.ok) {
      setMessages(prev => [
        ...prev,
        { role: 'assistant', text: res.data.answer, sources: res.data.sources ?? [] },
      ]);
    } else {
      setMessages(prev => [
        ...prev,
        { role: 'error', text: res.message },
      ]);
    }
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  function handleClear() {
    setMessages([]);
  }

  return (
    <>
      <div className="page-header">
        <div className="page-title" style={{ justifyContent: 'space-between' }}>
          <span>💬 Q&amp;A Chat</span>
          {messages.length > 0 && (
            <button className="btn btn-secondary btn-sm" onClick={handleClear} id="qa-clear-btn">
              Clear chat
            </button>
          )}
        </div>
        <div className="page-subtitle">
          Ask questions about your ingested codebase. Answers are grounded in your actual source files.
        </div>
      </div>

      <div className="page-body" style={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
        <div className="chat-messages">
          {messages.length === 0 && !loading && (
            <div className="empty-state">
              <div className="empty-state-icon">🔍</div>
              <h3>Ask anything about your codebase</h3>
              <p>Try: "Where is the ingestion pipeline implemented?" or "How does embedding work?"</p>
            </div>
          )}

          {messages.map((msg, i) => (
            <div key={i} className="chat-message">
              {msg.role === 'user' && (
                <>
                  <div className="msg-role">👤 You</div>
                  <div className="msg-bubble user">{msg.text}</div>
                </>
              )}
              {msg.role === 'assistant' && (
                <>
                  <div className="msg-role">🧭 CodeCompass</div>
                  <div className="msg-bubble">{msg.text}</div>
                  {msg.sources && msg.sources.length > 0 && (
                    <div className="sources-section">
                      <div className="sources-label">📎 Sources ({msg.sources.length})</div>
                      {msg.sources.map((src, si) => (
                        <SourceChip key={si} source={src} />
                      ))}
                    </div>
                  )}
                </>
              )}
              {msg.role === 'error' && (
                <>
                  <div className="msg-role">⚠️ Error</div>
                  <div className="alert alert-error" style={{ marginBottom: 0 }}>
                    <span className="alert-icon">⚠️</span>
                    <span>{msg.text}</span>
                  </div>
                </>
              )}
            </div>
          ))}

          {loading && (
            <div className="chat-message">
              <div className="msg-role">🧭 CodeCompass</div>
              <div className="msg-bubble" style={{ display: 'flex', alignItems: 'center', gap: 10, color: 'var(--text-muted)' }}>
                <span className="spinner" />
                Thinking…
              </div>
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>

        <div className="chat-input-area">
          <textarea
            id="qa-question-input"
            placeholder="Ask a question about your codebase… (Enter to send, Shift+Enter for newline)"
            value={question}
            onChange={e => setQuestion(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={loading}
            rows={2}
          />
          <button
            id="qa-send-btn"
            className="btn btn-primary"
            onClick={handleSend}
            disabled={loading || !question.trim()}
            style={{ alignSelf: 'flex-end' }}
          >
            {loading ? <span className="spinner" /> : '↑ Send'}
          </button>
        </div>
      </div>
    </>
  );
}

function SourceChip({ source }) {
  const fileName = source.filePath
    ? source.filePath.replace(/\\/g, '/').split('/').pop()
    : '?';
  return (
    <div className="source-chip">
      <span className="source-chip-file" title={source.filePath}>
        {source.className ? `${source.className}` : fileName}
        {source.methodName ? `#${source.methodName}` : ''}
      </span>
      {source.startLine != null && (
        <span className="source-chip-lines">L{source.startLine}–{source.endLine}</span>
      )}
      {source.chunkType && (
        <span className="badge badge-info source-chip-type">{source.chunkType}</span>
      )}
    </div>
  );
}
