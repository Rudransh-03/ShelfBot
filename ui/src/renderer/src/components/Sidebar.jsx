import BookshelfIcon from './BookshelfIcon'
import { useApp } from '../context/AppContext'

const RescanIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="23 4 23 10 17 10"/>
    <polyline points="1 20 1 14 7 14"/>
    <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
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

export default function Sidebar({ active, onNav, connected }) {
  const { stats, indexing, triggerIndex } = useApp()

  // What the badge says:
  //   • during a job  → "Indexing…" (live)
  //   • after a job   → "X minutes ago" based on /api/status lastIndexed
  //   • before any job → "Never"
  const syncedLabel = indexing
    ? 'Indexing…'
    : fmtAge(stats?.lastIndexed)

  const syncedTooltip = indexing
    ? 'A re-index is currently running'
    : stats?.lastIndexed
      ? `Last synced: ${new Date(stats.lastIndexed).toLocaleString()}\nClick the refresh icon to re-index now.`
      : 'Files have not been indexed yet. Click the refresh icon to start.'

  return (
    <nav className="sidebar">
      <div className="sb-brand">
        <div className="sb-brand-mark">
          <BookshelfIcon size={16} color="#d4a574" />
        </div>
        <div className="sb-brand-name">ShelfBot</div>
      </div>

      <div className="sb-section">Workspace</div>

      {ITEMS.map(item => (
        <button
          key={item.id}
          className={`nav-item${active === item.id ? ' active' : ''}`}
          onClick={() => onNav(item.id)}
        >
          {item.icon}
          <span>{item.label}</span>
        </button>
      ))}

      <div className="nav-spacer" />

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
    </nav>
  )
}
