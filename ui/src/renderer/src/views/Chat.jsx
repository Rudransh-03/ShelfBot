import { useState, useRef, useEffect, useCallback } from 'react'
import { useApp } from '../context/AppContext'
import BookshelfIcon from '../components/BookshelfIcon'

const SUGGESTIONS = [
  {
    text: 'Summarize the main topics across my documents',
    icon: (
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M9 11l3 3L22 4"/>
        <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/>
      </svg>
    ),
  },
  {
    text: 'What are the most important decisions documented?',
    icon: (
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="11" cy="11" r="8"/>
        <line x1="21" y1="21" x2="16.65" y2="16.65"/>
      </svg>
    ),
  },
  {
    text: 'Find anything related to project requirements or specs',
    icon: (
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
        <polyline points="14 2 14 8 20 8"/>
      </svg>
    ),
  },
]

const ArrowRightIcon = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <line x1="5" y1="12" x2="19" y2="12"/>
    <polyline points="12 5 19 12 12 19"/>
  </svg>
)

function AiAvatar() {
  return (
    <div className="msg-avatar">
      <BookshelfIcon size={14} color="#e8c995" />
    </div>
  )
}

function UserAvatar() {
  return (
    <div className="msg-avatar">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/>
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
      {hasMessages && (
        <>
          <div className="view-header">
            <div>
              <h1 className="view-title">Chat</h1>
              <div className="view-subtitle">Conversation with your library</div>
            </div>
            <div className="header-actions">
              <button className="icon-btn" onClick={clearChat} title="Clear conversation">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <polyline points="1 4 1 10 7 10"/>
                  <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/>
                </svg>
              </button>
            </div>
          </div>
          <div className="view-divider" />
        </>
      )}

      {!hasMessages ? (
        <div className="chat-empty">
          <div className="empty-icon">
            <BookshelfIcon size={28} color="#e8c995" />
          </div>
          <div className="empty-title">
            Your library, <em>in conversation</em>
          </div>
          <div className="empty-sub">
            ShelfBot reads across your indexed documents and answers in context.
            Begin with one of these, or ask anything.
          </div>
          <div className="suggestions">
            {SUGGESTIONS.map(s => (
              <button
                key={s.text}
                className="sugg-chip"
                onClick={() => sendMessage(s.text)}
                disabled={!connected}
              >
                <span className="sugg-ico">{s.icon}</span>
                <span>{s.text}</span>
                <span className="sugg-arrow"><ArrowRightIcon /></span>
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
              placeholder="Ask your library anything…"
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
            title="Send (Enter)"
          >
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
              <line x1="22" y1="2" x2="11" y2="13"/>
              <polygon points="22 2 15 22 11 13 2 9 22 2"/>
            </svg>
          </button>
        </div>
      </div>
    </div>
  )
}
