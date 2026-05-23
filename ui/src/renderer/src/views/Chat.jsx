import { useState, useRef, useEffect, useCallback } from 'react'
import { useApp } from '../context/AppContext'
import BookshelfIcon from '../components/BookshelfIcon'
import Mascot       from '../components/Mascot'

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

function SourceChip({ source, onOpen }) {
  // Backend now returns objects { fileName, absolutePath, snippets }.
  // We still accept plain strings so older messages / fallback shapes don't break.
  const isString = typeof source === 'string'
  const fileName = isString ? source : source.fileName
  const absPath  = isString ? null   : source.absolutePath
  const snippets = isString ? []     : (source.snippets ?? [])

  const clickable = Boolean(absPath)
  const handleClick = () => { if (clickable) onOpen(absPath, fileName) }

  return (
    <span className={`src-chip-wrap${snippets.length ? ' has-preview' : ''}`}>
      <button
        type="button"
        className={`src-chip${clickable ? ' clickable' : ''}`}
        onClick={handleClick}
        disabled={!clickable}
        title={clickable ? `Open ${fileName}` : fileName}
      >
        {fileName}
      </button>
      {snippets.length > 0 && (
        <span className="src-preview" role="tooltip">
          <span className="src-preview-head">From {fileName}</span>
          {snippets.map((s, i) => (
            <span className="src-preview-snippet" key={i}>“{s}”</span>
          ))}
        </span>
      )}
    </span>
  )
}

function Message({ role, text, sources = [], variant, onOpenSource }) {
  return (
    <div className={`msg-row ${role}`}>
      {role === 'ai' ? <AiAvatar /> : <UserAvatar />}
      <div className="msg-content">
        <div className={`msg-bubble${variant ? ` ${variant}` : ''}`}>{text}</div>
        {sources.length > 0 && (
          <div className="msg-sources">
            {sources.map((s, i) => (
              <SourceChip
                key={(typeof s === 'string' ? s : s.absolutePath || s.fileName) + ':' + i}
                source={s}
                onOpen={onOpenSource}
              />
            ))}
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
        <div className="msg-bubble typing-bubble">
          <div className="typing-wrap">
            <span className="tdot" />
            <span className="tdot" />
            <span className="tdot" />
            <span className="typing-label">Rudo is thinking</span>
          </div>
        </div>
      </div>
    </div>
  )
}

export default function Chat({ active }) {
  const { api, connected, toast, refreshAuth } = useApp()
  const [messages, setMessages]   = useState([])
  const [input,    setInput]      = useState('')
  const [loading,  setLoading]    = useState(false)
  const [justAnswered, setJustAnswered] = useState(false)
  const msgsRef  = useRef(null)
  const inputRef = useRef(null)

  // Keep focus in the chat input whenever this view is active. So:
  //   • on view mount / activation → cursor lands in the input
  //   • after submitting a prompt   → cursor returns to the input even
  //     though the answer is still loading. We don't disable the input
  //     during loading anymore (see disabled prop below), so the user
  //     can immediately type their next question and it'll send the
  //     moment the current one finishes.
  useEffect(() => {
    if (active && inputRef.current) {
      // Defer one tick so React has actually mounted the input before
      // we try to grab focus (otherwise focus() silently no-ops).
      setTimeout(() => inputRef.current?.focus(), 0)
    }
  }, [active])

  // Pick the right Rudo mood based on chat state.
  //   loading      → thinking (orbit dots, eyes closed)
  //   justAnswered → happy for 2s (eyes squint, brief bounce)
  //   input typed  → listening (tech-colored aura)
  //   else         → idle (gentle breathing)
  const mascotState = loading
    ? 'thinking'
    : justAnswered
      ? 'happy'
      : input.trim().length > 0
        ? 'listening'
        : 'idle'

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
    // Return focus to the input the moment the user submits so they can
    // start typing the next prompt without clicking back into the field.
    // We still gate the actual send below on !loading so we don't fire
    // two requests in parallel.
    setTimeout(() => inputRef.current?.focus(), 0)

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
      setJustAnswered(true)
      setTimeout(() => setJustAnswered(false), 2200)
      // Refresh /me so the daily-usage counter in Settings updates within
      // a second of each query, not on the slow 30s poller.
      refreshAuth()
    }
  }, [api, connected, loading, refreshAuth])

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

  const openSource = useCallback(async (absolutePath, fileName) => {
    const E = window.electron
    if (!E?.openPath) {
      toast('Opening files is only supported in the desktop app', 'i')
      return
    }
    const err = await E.openPath(absolutePath)
    if (err) toast(`Couldn't open ${fileName}: ${err}`, 'e')
  }, [toast])

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
          <Mascot size="lg" state={mascotState} label="Rudo" />
          <div className="empty-title">
            Hi, I'm <em>Rudo</em>.
          </div>
          <div className="empty-sub">
            I read across every file you've indexed and answer in context — pick a prompt or
            ask me anything.
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
            <Message
              key={i}
              role={m.role}
              text={m.text}
              sources={m.sources}
              variant={m.variant}
              onOpenSource={openSource}
            />
          ))}
          {loading && <TypingIndicator />}
        </div>
      )}

      <div className="chat-input-area">
        <div className="input-row">
          {/* Small live-reacting Rudo next to the input. Only shown when the
              user is mid-conversation so it doesn't compete with the big hero. */}
          {hasMessages && (
            <Mascot size="md" state={mascotState} className="chat-input-mascot" />
          )}
          <div className="input-box">
            <input
              ref={inputRef}
              type="text"
              placeholder={loading ? 'Type the next one — I\'ll answer when ready' : 'Ask your library anything…'}
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={handleKey}
              // Stays enabled during loading so the cursor lives here
              // and the next prompt can be typed. Only disabled when the
              // backend is fully unreachable.
              disabled={!connected}
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
