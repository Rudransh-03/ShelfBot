import { useState } from 'react'
import BookshelfIcon from './BookshelfIcon'
import Mascot       from './Mascot'
import { useApp } from '../context/AppContext'

const RescanIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="23 4 23 10 17 10"/>
    <polyline points="1 20 1 14 7 14"/>
    <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
  </svg>
)

const HamburgerIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <line x1="3" y1="6"  x2="21" y2="6"/>
    <line x1="3" y1="12" x2="21" y2="12"/>
    <line x1="3" y1="18" x2="21" y2="18"/>
  </svg>
)

const PlusIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <line x1="12" y1="5" x2="12" y2="19"/>
    <line x1="5" y1="12" x2="19" y2="12"/>
  </svg>
)

const SearchIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="11" cy="11" r="8"/>
    <line x1="21" y1="21" x2="16.65" y2="16.65"/>
  </svg>
)

const PencilIcon = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M12 20h9"/>
    <path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"/>
  </svg>
)

const TrashIcon = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="3 6 5 6 21 6"/>
    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
  </svg>
)

function fmtAge(iso) {
  if (!iso) return 'Never'
  try {
    const sec = (Date.now() - new Date(iso)) / 1000
    if (sec < 60)    return 'just now'
    if (sec < 3600)  return `${Math.floor(sec / 60)}m ago`
    if (sec < 86400) return `${Math.floor(sec / 3600)}h ago`
    return new Date(iso).toLocaleDateString()
  } catch { return iso }
}

const ITEMS = [
  {
    id: 'chat',
    label: 'Chat',
    icon: (
      <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
        <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/>
      </svg>
    ),
  },
  {
    id: 'lib',
    label: 'Library',
    icon: (
      <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
        <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/>
        <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>
      </svg>
    ),
  },
  {
    id: 'org',
    label: 'Organize',
    icon: (
      <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
        <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
        <path d="M9 13l2 2 4-4"/>
      </svg>
    ),
  },
  {
    id: 'set',
    label: 'Settings',
    icon: (
      <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="3"/>
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82 1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
      </svg>
    ),
  },
]

// ── Chat library (search + thread list), lives inside the one sidebar ────────
function ChatLibrary({ onNav, onOpenSearch }) {
  const {
    conversations, activeConversationId,
    openConversation, newConversation, renameConversation, deleteConversation,
  } = useApp()

  const [editingId, setEditingId] = useState(null)
  const [editTitle, setEditTitle] = useState('')

  const open = (id) => { openConversation(id); onNav('chat') }
  const startNew = () => { newConversation(); onNav('chat') }

  const commitRename = (id) => {
    const title = editTitle.trim()
    setEditingId(null)
    const cur = conversations.find(c => c.id === id)
    if (!title || (cur && title === cur.title)) return
    renameConversation(id, title)
  }

  const remove = (e, id) => {
    e.stopPropagation()
    if (window.confirm('Delete this chat? This cannot be undone.')) deleteConversation(id)
  }

  return (
    <div className="sb-chats">
      <div className="sb-chats-head">
        <span className="sb-section sb-section-inline">Chats</span>
        <button className="sb-chats-new" onClick={startNew} title="New chat">
          <PlusIcon />
        </button>
      </div>

      <button className="sb-search-trigger" onClick={onOpenSearch} title="Search chats (⌘K)">
        <SearchIcon />
        <span className="sb-search-trigger-text">Search chats…</span>
        <span className="sb-search-kbd">⌘K</span>
      </button>

      <div className="sb-chat-list">
        {conversations.length === 0 && (
          <div className="sb-chat-empty">No saved chats yet</div>
        )}
        {conversations.map(c => (
          <div
            key={c.id}
            className={`sb-chat-item${c.id === activeConversationId ? ' active' : ''}`}
            onClick={() => open(c.id)}
            title={c.title}
          >
            {editingId === c.id ? (
              <input
                className="sb-chat-edit"
                value={editTitle}
                autoFocus
                onClick={e => e.stopPropagation()}
                onChange={e => setEditTitle(e.target.value)}
                onKeyDown={e => {
                  if (e.key === 'Enter') commitRename(c.id)
                  else if (e.key === 'Escape') setEditingId(null)
                }}
                onBlur={() => commitRename(c.id)}
              />
            ) : (
              <>
                <span className="sb-chat-title">{c.title}</span>
                <span className="sb-chat-actions">
                  <button
                    className="sb-chat-act"
                    title="Rename"
                    onClick={e => { e.stopPropagation(); setEditingId(c.id); setEditTitle(c.title) }}
                  >
                    <PencilIcon />
                  </button>
                  <button className="sb-chat-act" title="Delete" onClick={e => remove(e, c.id)}>
                    <TrashIcon />
                  </button>
                </span>
              </>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}

export default function Sidebar({ active, onNav, connected, collapsed = false, onToggle, onOpenSearch }) {
  const { stats, indexing, triggerIndex } = useApp()

  const mascotState = !connected ? 'sleeping' : indexing ? 'thinking' : 'idle'

  const syncedLabel = indexing
    ? 'Indexing…'
    : fmtAge(stats?.lastIndexed)

  const syncedTooltip = indexing
    ? 'A re-index is currently running'
    : stats?.lastIndexed
      ? `Last synced: ${new Date(stats.lastIndexed).toLocaleString()}\nClick the refresh icon to re-index now.`
      : 'Files have not been indexed yet. Click the refresh icon to start.'

  return (
    <nav className={`sidebar${collapsed ? ' collapsed' : ''}`}>
      <div className="sb-header">
        <button
          className="sb-burger"
          onClick={onToggle}
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          title={collapsed ? 'Expand' : 'Collapse'}
        >
          <HamburgerIcon />
        </button>
        {!collapsed && (
          <div className="sb-brand">
            <Mascot size="sm" state={mascotState} className="sb-brand-mascot" />
            <div className="sb-brand-name">Rudo</div>
          </div>
        )}
      </div>

      {!collapsed && <div className="sb-section">Workspace</div>}

      {ITEMS.map(item => (
        <button
          key={item.id}
          className={`nav-item${active === item.id ? ' active' : ''}`}
          onClick={() => onNav(item.id)}
          title={collapsed ? item.label : undefined}
        >
          {item.icon}
          {!collapsed && <span>{item.label}</span>}
        </button>
      ))}

      {/* Chat library lives below the nav, separated, inside the one sidebar */}
      {!collapsed ? (
        <>
          <div className="sb-separator" />
          <ChatLibrary onNav={onNav} onOpenSearch={onOpenSearch} />
        </>
      ) : (
        <div className="nav-spacer" />
      )}

      {collapsed ? (
        <>
          <button
            type="button"
            className={`nav-item nav-item-iconish${indexing ? ' busy' : ''}`}
            onClick={triggerIndex}
            disabled={!connected || indexing}
            title={`${indexing ? 'Indexing…' : `Synced ${syncedLabel}`} · click to re-index`}
            aria-label="Re-index now"
          >
            <RescanIcon />
          </button>
          <div
            className="sb-collapsed-status"
            title={connected ? 'Backend connected' : 'Backend offline'}
          >
            <div className={`sb-status-dot${connected ? ' on' : ' err'}`} />
          </div>
        </>
      ) : (
        <>
          <div className="sb-sync-row" title={syncedTooltip}>
            <div className={`sb-sync-icon${indexing ? ' busy' : ''}`}>
              <RescanIcon />
            </div>
            <div className="sb-sync-text">
              <div className="sb-status-sublabel">Synced</div>
              <div className="sb-sync-label">{syncedLabel}</div>
            </div>
            <button
              type="button"
              className="sb-sync-btn"
              onClick={triggerIndex}
              disabled={!connected || indexing}
              title="Re-index now"
              aria-label="Re-index now"
            >
              <RescanIcon />
            </button>
          </div>

          <div className="sb-status-row" title={connected ? 'Backend connected' : 'Backend offline'}>
            <div className={`sb-status-dot${connected ? ' on' : ' err'}`} />
            <div>
              <div className="sb-status-label">{connected ? 'Connected' : 'Offline'}</div>
              <div className="sb-status-sublabel">Backend</div>
            </div>
          </div>
        </>
      )}
    </nav>
  )
}
