import { useState, useEffect, useRef, useCallback } from 'react'
import { useApp } from '../context/AppContext'

const SearchIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="11" cy="11" r="8"/>
    <line x1="21" y1="21" x2="16.65" y2="16.65"/>
  </svg>
)

const XIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <line x1="18" y1="6" x2="6" y2="18"/>
    <line x1="6" y1="6" x2="18" y2="18"/>
  </svg>
)

const BubbleIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
    <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/>
  </svg>
)

// Bold every (case-insensitive) occurrence of `q` inside `text`.
function highlight(text, q) {
  if (!text || !q) return text
  const lower = text.toLowerCase()
  const ql = q.toLowerCase()
  const out = []
  let i = 0, key = 0
  while (true) {
    const idx = lower.indexOf(ql, i)
    if (idx === -1) { out.push(text.slice(i)); break }
    if (idx > i) out.push(text.slice(i, idx))
    out.push(<strong key={key++}>{text.slice(idx, idx + q.length)}</strong>)
    i = idx + q.length
  }
  return out
}

function fmtDate(iso) {
  if (!iso) return ''
  try {
    const d = new Date(iso)
    const sameYear = d.getFullYear() === new Date().getFullYear()
    return d.toLocaleDateString(undefined,
      sameYear ? { day: 'numeric', month: 'short' } : { day: 'numeric', month: 'short', year: 'numeric' })
  } catch { return '' }
}

export default function SearchModal({ onClose, onNavigate }) {
  const { api, openConversation } = useApp()
  const [query,   setQuery]   = useState('')
  const [results, setResults] = useState([])
  const [loading, setLoading] = useState(false)
  const [activeIndex, setActiveIndex] = useState(0)
  const activeRef = useRef(null)

  // Debounced server-side search (title + message content, with snippets).
  useEffect(() => {
    const q = query.trim()
    if (!q || !api) { setResults([]); setLoading(false); return }
    setLoading(true)
    let cancelled = false
    const t = setTimeout(async () => {
      try {
        const { results: r } = await api.searchConversations(q)
        if (!cancelled) { setResults(r || []); setActiveIndex(0) }
      } catch {
        if (!cancelled) setResults([])
      } finally {
        if (!cancelled) setLoading(false)
      }
    }, 180)
    return () => { cancelled = true; clearTimeout(t) }
  }, [query, api])

  // Keep the highlighted row in view as the user arrows through.
  useEffect(() => {
    activeRef.current?.scrollIntoView({ block: 'nearest' })
  }, [activeIndex])

  const pick = useCallback((id) => {
    openConversation(id)
    onNavigate?.()
    onClose?.()
  }, [openConversation, onNavigate, onClose])

  const onKeyDown = (e) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault(); setActiveIndex(i => Math.min(i + 1, results.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault(); setActiveIndex(i => Math.max(i - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault(); if (results[activeIndex]) pick(results[activeIndex].id)
    } else if (e.key === 'Escape') {
      e.preventDefault(); onClose?.()
    }
  }

  const q = query.trim()

  return (
    <div className="search-modal-overlay" onMouseDown={onClose}>
      <div className="search-modal" onMouseDown={e => e.stopPropagation()}>
        <div className="search-modal-head">
          <span className="search-modal-ico"><SearchIcon /></span>
          <input
            className="search-modal-input"
            type="text"
            autoFocus
            placeholder="Search your chats…"
            value={query}
            onChange={e => setQuery(e.target.value)}
            onKeyDown={onKeyDown}
          />
          <button className="search-modal-close" onClick={onClose} title="Close (Esc)">
            <XIcon />
          </button>
        </div>
        <div className="search-modal-divider" />
        <div className="search-modal-list">
          {!q && <div className="search-modal-empty">Type to search across all your chats</div>}
          {q && loading && <div className="search-modal-empty">Searching…</div>}
          {q && !loading && results.length === 0 && (
            <div className="search-modal-empty">No chats match “{query}”</div>
          )}
          {results.map((r, i) => (
            <div
              key={r.id}
              ref={i === activeIndex ? activeRef : null}
              className={`search-result${i === activeIndex ? ' active' : ''}`}
              onMouseEnter={() => setActiveIndex(i)}
              onClick={() => pick(r.id)}
            >
              <span className="search-result-icon"><BubbleIcon /></span>
              <div className="search-result-body">
                <div className="search-result-top">
                  <span className="search-result-title">{highlight(r.title, query)}</span>
                  <span className="search-result-date">{fmtDate(r.updatedAt)}</span>
                </div>
                {r.snippet && (
                  <div className="search-result-snippet">{highlight(r.snippet, query)}</div>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
