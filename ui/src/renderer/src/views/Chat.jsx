import { useState, useRef, useEffect, useCallback } from 'react'
import { useApp } from '../context/AppContext'
import BookshelfIcon from '../components/BookshelfIcon'

const SUGGESTIONS = [
  { emoji: '💡', text: 'Summarize the main topics across my documents' },
  { emoji: '🔍', text: 'What are the most important decisions documented?' },
  { emoji: '📋', text: 'Find anything related to project requirements or specs' },
]

// ─────────────────────────────────────────────────────────────────────────────
// Sub-components
// ─────────────────────────────────────────────────────────────────────────────

function AiAvatar() {
  return (
    <div className="msg-avatar">
      <BookshelfIcon size={15} />
    </div>
  )
}

function UserAvatar() {
  return (
    <div className="msg-avatar">
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/>
        <circle cx="12" cy="7" r="4"/>
      </svg>
    </div>
  )
}

function Message({ role, text, sources = [], variant }) {
  return (
    <div className={`msg-row ${role}`}>
      {role === 'ai' ? <AiAvatar /> : <UserAvatar />}
      <div className="msg-content">
        <div className={`msg-bubble${variant ? ` ${variant}` : ''}`}>{text}</div>
        {sources.length > 0 && (
          <div className="msg-sources">
            {sources.map(s => <span key={s} className="src-chip">{s}</span>)}
          </div>
        )}
      </div>
    </div>
  )
}

function TypingIndicator() {
  return (
    <div className="msg-row ai">
      <AiAvatar />
      <div className="msg-content">
        <div className="msg-bubble">
          <div className="typing-wrap">
            <span className="tdot" />
            <span className="tdot" />
            <span className="tdot" />
          </div>
        </div>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Chat view
// ─────────────────────────────────────────────────────────────────────────────

export default function Chat({ active }) {
  const { api, connected, toast } = useApp()
  const [messages, setMessages]   = useState([])
  const [input,    setInput]      = useState('')
  const [loading,  setLoading]    = useState(false)
  const msgsRef = useRef(null)

  useEffect(() => {
    if (msgsRef.current) {
      msgsRef.current.scrollTop = msgsRef.current.scrollHeight
    }
  }, [messages, loading])

  const sendMessage = useCallback(async (question) => {
    if (!question?.trim() || !connected || loading) return

    setInput('')
    setMessages(m => [...m, { role: 'user', text: question }])
    setLoading(true)

    try {
      const res = await api.query(question)
      setMessages(m => [...m, {
        role: 'ai',
        text: res.answer,
        sources: res.sources ?? [],
        variant: !res.found ? 'not-found' : undefined,
      }])
    } catch (e) {
      setMessages(m => [...m, { role: 'ai', text: e.message, variant: 'error' }])
    } finally {
      setLoading(false)
    }
  }, [api, connected, loading])

  const handleKey = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(input) }
  }

  const clearChat = async () => {
    try {
      await api.clearConvo()
      setMessages([])
      toast('Conversation cleared', 'i')
    } catch (e) {
      toast(e.message, 'e')
    }
  }

  const hasMessages = messages.length > 0 || loading

  return (
    <div className={`view${active ? ' active' : ''}`} id="view-chat">
      <div className="view-header">
        <h1 className="view-title">Chat</h1>
        <div className="header-actions">
          <button className="icon-btn" onClick={clearChat} title="Clear conversation">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="1 4 1 10 7 10"/>
              <path d="M3.51 15a9 9 0 102.13-9.36L1 10"/>
            </svg>
          </button>
        </div>
      </div>

      {!hasMessages ? (
        <div className="chat-empty">
          <div className="empty-icon">
            <BookshelfIcon size={34} />
          </div>
          <div className="empty-title">Ask your files anything</div>
          <div className="empty-sub">
            ShelfBot searches your indexed documents and answers using AI.
          </div>
          <div className="suggestions">
            {SUGGESTIONS.map(s => (
              <button
                key={s.text}
                className="sugg-chip"
                onClick={() => sendMessage(s.text)}
                disabled={!connected}
              >
                {s.emoji}&nbsp;&nbsp;{s.text}
              </button>
            ))}
          </div>
        </div>
      ) : (
        <div className="messages" ref={msgsRef}>
          {messages.map((m, i) => (
            <Message key={i} role={m.role} text={m.text} sources={m.sources} variant={m.variant} />
          ))}
          {loading && <TypingIndicator />}
        </div>
      )}

      <div className="chat-input-area">
        <div className="input-row">
          <div className="input-box">
            <input
              type="text"
              placeholder="Ask anything about your files…"
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={handleKey}
              disabled={!connected || loading}
            />
          </div>
          <button
            className="send-btn"
            onClick={() => sendMessage(input)}
            disabled={!connected || !input.trim() || loading}
          >
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2">
              <line x1="22" y1="2" x2="11" y2="13"/>
              <polygon points="22 2 15 22 11 13 2 9 22 2"/>
            </svg>
          </button>
        </div>
      </div>
    </div>
  )
}
