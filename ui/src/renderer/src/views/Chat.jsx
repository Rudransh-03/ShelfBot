import { useState, useRef, useEffect, useCallback, useMemo } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { useApp } from '../context/AppContext'
import BookshelfIcon from '../components/BookshelfIcon'
import Mascot       from '../components/Mascot'
import { extractTables, tablesToCsv } from '../utils/tableExport'

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

const PlusIcon = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <line x1="12" y1="5" x2="12" y2="19"/>
    <line x1="5" y1="12" x2="19" y2="12"/>
  </svg>
)

const ChevronUpIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="18 15 12 9 6 15"/>
  </svg>
)
const ChevronDownIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="6 9 12 15 18 9"/>
  </svg>
)
const CloseIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <line x1="18" y1="6" x2="6" y2="18"/>
    <line x1="6" y1="6" x2="18" y2="18"/>
  </svg>
)

const CopyIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
  </svg>
)
const CheckIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="20 6 9 17 4 12"/>
  </svg>
)
const MailIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    <rect x="2" y="4" width="20" height="16" rx="2"/>
    <polyline points="22 6 12 13 2 6"/>
  </svg>
)
const SheetIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
    <polyline points="14 2 14 8 20 8"/>
    <line x1="8" y1="13" x2="16" y2="13"/>
    <line x1="8" y1="17" x2="16" y2="17"/>
  </svg>
)

// Renders an assistant answer as markdown (tables, lists, code, bold via GFM).
// Links open in the user's browser, not inside the app window.
function MarkdownView({ text }) {
  return (
    <div className="md">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a: ({ href, children }) => (
            <a
              href={href}
              onClick={(e) => {
                e.preventDefault()
                if (!href) return
                if (window.electron?.openExternal) window.electron.openExternal(href)
                else window.open(href, '_blank')
              }}
            >
              {children}
            </a>
          ),
        }}
      >
        {text || ''}
      </ReactMarkdown>
    </div>
  )
}

// Renders message text, wrapping each (case-insensitive) match of `query` in a
// <mark>; the match whose running global index equals `activeMatch` gets the
// active style + ref so the find bar can scroll it into view.
function renderHighlighted(text, query, occOffset, activeMatch, activeRef) {
  if (!query || !text) return text
  const lower = text.toLowerCase()
  const ql = query.toLowerCase()
  const nodes = []
  let i = 0, key = 0, occ = occOffset
  while (true) {
    const idx = lower.indexOf(ql, i)
    if (idx === -1) { nodes.push(text.slice(i)); break }
    if (idx > i) nodes.push(text.slice(i, idx))
    const isActive = occ === activeMatch
    nodes.push(
      <mark
        key={key++}
        className={`find-hit${isActive ? ' find-active' : ''}`}
        ref={isActive ? activeRef : null}
      >
        {text.slice(idx, idx + query.length)}
      </mark>
    )
    occ++
    i = idx + query.length
  }
  return nodes
}

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

// Formats the contributing page numbers for display: "(page 18)",
// "(page 17-18)" (contiguous), or "(pages 5, 9, 14)" (scattered, capped).
// Empty when no pages.
function formatPages(pages) {
  if (!Array.isArray(pages) || pages.length === 0) return ''
  const ps = [...new Set(pages)].sort((a, b) => a - b)
  if (ps.length === 1) return `(page ${ps[0]})`
  const contiguous = ps.every((v, i) => i === 0 || v === ps[i - 1] + 1)
  if (contiguous) return `(page ${ps[0]}-${ps[ps.length - 1]})`
  const shown = ps.slice(0, 4).join(', ')
  return `(pages ${shown}${ps.length > 4 ? '…' : ''})`
}

function SourceChip({ source, onOpen }) {
  // Backend now returns objects { fileName, absolutePath, snippets, pages }.
  // We still accept plain strings so older messages / fallback shapes don't break.
  const isString = typeof source === 'string'
  const fileName = isString ? source : source.fileName
  const absPath  = isString ? null   : source.absolutePath
  const snippets = isString ? []     : (source.snippets ?? [])
  const pageLabel = isString ? ''    : formatPages(source.pages)

  const clickable = Boolean(absPath)
  const handleClick = () => { if (clickable) onOpen(absPath, fileName) }

  return (
    <span className={`src-chip-wrap${snippets.length ? ' has-preview' : ''}`}>
      <button
        type="button"
        className={`src-chip${clickable ? ' clickable' : ''}`}
        onClick={handleClick}
        disabled={!clickable}
        title={clickable ? `Open ${fileName}${pageLabel ? ` ${pageLabel}` : ''}` : fileName}
      >
        {fileName}
        {pageLabel && <span className="src-chip-page"> {pageLabel}</span>}
      </button>
      {snippets.length > 0 && (
        <span className="src-preview" role="tooltip">
          <span className="src-preview-head">From {fileName}{pageLabel ? ` ${pageLabel}` : ''}</span>
          {snippets.map((s, i) => (
            <span className="src-preview-snippet" key={i}>“{s}”</span>
          ))}
        </span>
      )}
    </span>
  )
}

function Message({ role, text, sources = [], variant, onOpenSource, streaming = false,
                   highlight = '', occOffset = 0, activeMatch = -1, activeRef,
                   clarify, clarifyQuestion, scope, onClarify }) {
  const [copied, setCopied] = useState(false)
  const [exported, setExported] = useState(false)

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text || '')
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch { /* clipboard unavailable */ }
  }

  const email = () => {
    const url = `mailto:?subject=${encodeURIComponent('Rudo — analysis')}&body=${encodeURIComponent(text || '')}`
    if (window.electron?.openExternal) window.electron.openExternal(url)
    else window.location.href = url
  }

  // Detect any GFM tables in a finished answer so finance-style results can be
  // sent straight to Excel. Memoised so we don't re-parse on every render.
  const tables = useMemo(
    () => (role === 'ai' && !streaming ? extractTables(text || '') : []),
    [role, streaming, text]
  )

  const exportExcel = async () => {
    const csv = tablesToCsv(tables)
    if (!csv) return
    const name = `rudo-export-${new Date().toISOString().slice(0, 10)}.csv`
    try {
      if (window.electron?.exportFile) {
        const res = await window.electron.exportFile({ suggestedName: name, content: csv })
        if (!res?.ok) return                 // user cancelled or write failed
      } else {
        // Non-Electron fallback (e.g. browser dev): trigger a Blob download.
        const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
        const url  = URL.createObjectURL(blob)
        const a    = document.createElement('a')
        a.href = url; a.download = name
        document.body.appendChild(a); a.click(); a.remove()
        setTimeout(() => URL.revokeObjectURL(url), 0)
      }
      setExported(true)
      setTimeout(() => setExported(false), 1500)
    } catch { /* export unavailable */ }
  }

  // While finding (⌘F) we fall back to plain highlighted text so matches can
  // be marked; otherwise assistant turns render as rich markdown.
  const body = highlight
    ? renderHighlighted(text, highlight, occOffset, activeMatch, activeRef)
    : role === 'ai'
      ? <MarkdownView text={text} />
      : text

  // Copy/Email only on a finished, normal answer (not while streaming, not on
  // error/not-found notices).
  const showActions = role === 'ai' && !streaming && !variant && (text || '').trim().length > 0

  return (
    <div className={`msg-row ${role}`}>
      {role === 'ai' ? <AiAvatar /> : <UserAvatar />}
      <div className="msg-content">
        <div className={`msg-bubble${variant ? ` ${variant}` : ''}`}>{body}</div>
        {Array.isArray(clarify) && clarify.length > 0 && (
          <div className="clarify-chips">
            {clarify.map(opt => (
              <button key={opt.id} className="clarify-chip"
                      onClick={() => onClarify?.(clarifyQuestion, opt.id)}>
                {opt.name}
              </button>
            ))}
          </div>
        )}
        {scope && <div className="msg-scope">Answering about: {scope}</div>}
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
        {showActions && (
          <div className="msg-actions">
            <button className="msg-action" onClick={copy} title="Copy response">
              {copied ? <CheckIcon /> : <CopyIcon />}
              <span>{copied ? 'Copied' : 'Copy'}</span>
            </button>
            <button className="msg-action" onClick={email} title="Email this response">
              <MailIcon />
              <span>Email</span>
            </button>
            {tables.length > 0 && (
              <button className="msg-action" onClick={exportExcel}
                      title="Export table to Excel (.csv)">
                {exported ? <CheckIcon /> : <SheetIcon />}
                <span>{exported ? 'Exported' : 'Export'}</span>
              </button>
            )}
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
  const {
    api, connected, toast, refreshAuth,
    activeConversationId, setActiveConversationId, newConversation, refreshConversations,
  } = useApp()
  const [messages, setMessages]   = useState([])
  const [input,    setInput]      = useState('')
  const [loading,  setLoading]    = useState(false)
  const [justAnswered, setJustAnswered] = useState(false)
  // Per-client workspaces. `clients` drives the scope picker (hidden when none
  // exist, so the feature is invisible until set up). `scopeChoice`: 'auto'
  // (let Rudo decide / ask), a client id, '__unassigned__', or '__all__'.
  const [clients, setClients] = useState([])
  const [scopeChoice, setScopeChoice] = useState('auto')
  const msgsRef  = useRef(null)
  const inputRef = useRef(null)
  // Which conversation's messages are currently displayed. Lets us skip a
  // reload when we *adopt* a freshly-created thread after sending (the
  // messages are already on screen) vs. when the user *picks* a thread.
  const loadedIdRef = useRef(null)

  // In-chat find (⌘F)
  const [findOpen,    setFindOpen]    = useState(false)
  const [findQuery,   setFindQuery]   = useState('')
  const [activeMatch, setActiveMatch] = useState(0)
  const findInputRef   = useRef(null)
  const activeMatchRef = useRef(null)

  // Keep focus in the chat input whenever this view is active.
  useEffect(() => {
    if (active && inputRef.current) {
      setTimeout(() => inputRef.current?.focus(), 0)
    }
  }, [active])

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

  // Load a thread's messages when the user selects it in the sidebar. Skips
  // the case where activeConversationId already matches what's on screen.
  useEffect(() => {
    if (activeConversationId === loadedIdRef.current) return
    let cancelled = false
    ;(async () => {
      if (!activeConversationId) {
        loadedIdRef.current = null
        setMessages([])
        return
      }
      try {
        const { messages: stored } = await api.getConversation(activeConversationId)
        if (cancelled) return
        loadedIdRef.current = activeConversationId
        setMessages((stored || []).map(m => {
          let sources
          if (m.sources) { try { sources = JSON.parse(m.sources) } catch { sources = undefined } }
          return { role: m.role === 'assistant' ? 'ai' : 'user', text: m.content, sources }
        }))
      } catch (e) {
        if (!cancelled) toast(e.message, 'e')
      }
    })()
    return () => { cancelled = true }
  }, [activeConversationId, api, toast])

  // ── In-chat find (⌘F) ──────────────────────────────────────────────────────
  const closeFind = useCallback(() => { setFindOpen(false); setFindQuery('') }, [])

  useEffect(() => {
    if (!active) return
    const onKey = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'f') {
        e.preventDefault()
        setFindOpen(true)
        setTimeout(() => findInputRef.current?.select(), 0)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [active])

  // Running match offset per message + total, used to index highlights.
  const find = useMemo(() => {
    const q = findOpen ? findQuery.trim().toLowerCase() : ''
    if (!q) return { total: 0, offsets: [] }
    let total = 0
    const offsets = messages.map(m => {
      const off = total
      if (m.text) {
        const t = m.text.toLowerCase()
        let i = t.indexOf(q)
        while (i !== -1) { total++; i = t.indexOf(q, i + q.length) }
      }
      return off
    })
    return { total, offsets }
  }, [messages, findQuery, findOpen])

  useEffect(() => { setActiveMatch(0) }, [findQuery])
  useEffect(() => {
    if (findOpen) activeMatchRef.current?.scrollIntoView({ block: 'center' })
  }, [activeMatch, findOpen, find.total])

  const gotoMatch = useCallback((dir) => {
    setActiveMatch(a => (find.total === 0 ? 0 : (a + dir + find.total) % find.total))
  }, [find.total])

  // Load the client list so the scope picker can appear (only when clients exist).
  useEffect(() => {
    if (!api || !connected) return
    api.listClients().then(d => setClients(d.clients ?? [])).catch(() => {})
  }, [api, connected, active])

  const sendMessage = useCallback(async (question, overrideClientId) => {
    if (!question?.trim() || !connected || loading) return

    setInput('')
    setMessages(m => [...m, { role: 'user', text: question }])
    setLoading(true)
    setTimeout(() => inputRef.current?.focus(), 0)

    setMessages(m => [...m, { role: 'ai', text: '', streaming: true }])

    const appendToken = (chunk) => {
      setMessages(m => {
        const next = m.slice()
        const last = next[next.length - 1]
        if (last && last.role === 'ai') {
          next[next.length - 1] = { ...last, text: (last.text || '') + chunk }
        }
        return next
      })
    }

    const finalize = (patch) => {
      setMessages(m => {
        const next = m.slice()
        const last = next[next.length - 1]
        if (last && last.role === 'ai') {
          next[next.length - 1] = { ...last, streaming: false, ...patch }
        }
        return next
      })
    }

    await new Promise((resolve) => {
      const clientId = overrideClientId ?? (scopeChoice === 'auto' ? undefined : scopeChoice)
      api.queryStream(question, {
        conversationId: activeConversationId,
        clientId,
        onToken: appendToken,
        onDone:  ({ sources = [], found = true, conversationId: cid, clarify, scope }) => {
          // `clarify` (array of {id,name}) means Rudo needs to know which client;
          // we stash it + the question on the bubble so its chips can re-ask.
          finalize({ sources, variant: !found ? 'not-found' : undefined,
                     clarify, clarifyQuestion: question, scope })
          // Adopt the thread id without triggering a reload of the messages
          // we just streamed (loadedIdRef is set before the state update).
          if (cid) { loadedIdRef.current = cid; setActiveConversationId(cid) }
          resolve()
        },
        onError: (err) => {
          finalize({ text: err.message || 'Something went wrong.', variant: 'error', sources: [] })
          resolve()
        },
      })
    })

    setLoading(false)
    setJustAnswered(true)
    setTimeout(() => setJustAnswered(false), 2200)
    refreshAuth()
    refreshConversations() // surface the new/updated thread + auto-title in the rail
  }, [api, connected, loading, refreshAuth, activeConversationId, setActiveConversationId, refreshConversations, scopeChoice])

  const handleKey = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(input) }
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
      {findOpen && (
        <div className="find-bar">
          <input
            ref={findInputRef}
            className="find-input"
            type="text"
            placeholder="Find in chat…"
            value={findQuery}
            onChange={e => setFindQuery(e.target.value)}
            onKeyDown={e => {
              if (e.key === 'Enter') { e.preventDefault(); gotoMatch(e.shiftKey ? -1 : 1) }
              else if (e.key === 'Escape') { e.preventDefault(); closeFind() }
            }}
          />
          <span className="find-count">{find.total ? `${activeMatch + 1}/${find.total}` : '0/0'}</span>
          <button className="find-btn" onClick={() => gotoMatch(-1)} disabled={!find.total} title="Previous (⇧↵)"><ChevronUpIcon /></button>
          <button className="find-btn" onClick={() => gotoMatch(1)}  disabled={!find.total} title="Next (↵)"><ChevronDownIcon /></button>
          <button className="find-btn" onClick={closeFind} title="Close (Esc)"><CloseIcon /></button>
        </div>
      )}
      {hasMessages && (
        <>
          <div className="view-header">
            <div>
              <h1 className="view-title">Chat</h1>
              <div className="view-subtitle">Conversation with your library</div>
            </div>
            <div className="header-actions">
              {clients.length > 0 && (
                <select
                  className="scope-picker"
                  value={scopeChoice}
                  onChange={e => setScopeChoice(e.target.value)}
                  title="Which client this chat is about"
                >
                  <option value="auto">Auto (ask if unsure)</option>
                  {clients.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                  <option value="__unassigned__">Personal / unfiled</option>
                  <option value="__all__">All documents</option>
                </select>
              )}
              <button className="icon-btn" onClick={newConversation} title="New chat">
                <PlusIcon />
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
            m.role === 'ai' && m.streaming && !m.text ? (
              <TypingIndicator key={i} />
            ) : (
              <Message
                key={i}
                role={m.role}
                text={m.text}
                sources={m.sources}
                variant={m.variant}
                streaming={m.streaming}
                onOpenSource={openSource}
                highlight={findOpen ? findQuery.trim() : ''}
                occOffset={find.offsets[i] || 0}
                activeMatch={activeMatch}
                activeRef={activeMatchRef}
                clarify={m.clarify}
                clarifyQuestion={m.clarifyQuestion}
                scope={m.scope}
                onClarify={(q, cid) => sendMessage(q, cid)}
              />
            )
          ))}
        </div>
      )}

      <div className="chat-input-area">
        <div className="input-row">
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
