import { useEffect, useState } from 'react'
import { useApp } from '../context/AppContext'

/**
 * The "Needs attention" panel — opened from the sidebar button. Strictly
 * TODAY's action items: due today, overdue, and likely-missing recurring
 * documents. Anything due later lives only in the Deadlines tab (the holistic
 * view) — this panel answers "what needs me right now" and nothing else.
 *
 * Every item comes from the backend's deterministic /api/attention merge of
 * already-extracted data (no LLM), links to its source file for one-click
 * verification, and can be cleared for good with its ✓ button — so the list
 * never grows unbounded and a handled item never comes back.
 */

const OpenIcon = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>
    <polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/>
  </svg>
)
const CheckIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="20 6 9 17 4 12"/>
  </svg>
)

const MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec']

function fmtDate(iso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso || '')
  if (!m) return iso || ''
  return `${Number(m[3])} ${MONTHS[Number(m[2]) - 1]} ${m[1]}`
}

function relLabel(daysUntil) {
  if (daysUntil == null) return ''
  const d = daysUntil
  if (d === 0)  return 'today'
  if (d === -1) return 'yesterday'
  if (d < 0)    return `${-d} days ago`
  return ''
}

const GROUPS = [
  { key: 'DUE_TODAY', label: 'Due today',        cls: 'soon' },
  { key: 'OVERDUE',   label: 'Overdue',          cls: 'over' },
  { key: 'MISSING',   label: 'Possibly missing', cls: 'miss' },
]

function AttentionRow({ item, onOpenFile, onDismiss, dismissing }) {
  return (
    <li className={`attn-row attn-${(item.bucket || '').toLowerCase()}`}>
      <span className="attn-dot" />
      <div className="attn-main">
        <div className="attn-title">
          {item.title}
          {item.docType && item.docType !== 'Other' && (
            <span className="file-type-pill">{item.docType}</span>
          )}
          {item.date && (
            <span className="attn-when">
              {fmtDate(item.date)} · {relLabel(item.daysUntil)}
            </span>
          )}
        </div>
        {item.detail && (
          <div className="attn-detail">
            {item.kind === 'missing' ? item.detail : `“${item.detail}”`}
          </div>
        )}
        {item.fileName && (
          <button className="attn-file" onClick={() => onOpenFile(item)} title={`Open ${item.fileName}`}>
            <OpenIcon />
            <span className="attn-file-name">{item.fileName}</span>
          </button>
        )}
      </div>
      <button
        className="attn-clear"
        onClick={() => onDismiss(item)}
        disabled={dismissing}
        title="Handled — remove from this list for good"
      >
        <CheckIcon />
      </button>
    </li>
  )
}

export default function AttentionModal({ open, onClose, onGoDeadlines }) {
  const { api, attention, loadAttention, toast } = useApp()
  const [dismissing, setDismissing] = useState(false)

  // Refresh on every open so the list is never stale, even mid-session.
  useEffect(() => { if (open) loadAttention() }, [open, loadAttention])

  // Esc closes, like the other overlays.
  useEffect(() => {
    if (!open) return
    const onKey = (e) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null

  const items = attention?.items ?? []
  const groups = {}
  for (const it of items) (groups[it.bucket] ||= []).push(it)

  const onOpenFile = (item) => {
    const E = window.electron
    if (E?.openPath) E.openPath(item.path).then(err => { if (err) toast(err, 'e') })
  }

  const onDismiss = async (item) => {
    if (!api || !item.id) return
    setDismissing(true)
    try {
      await api.dismissAttention(item.id)
      await loadAttention()
    } catch (e) {
      toast(e.message, 'e')
    } finally {
      setDismissing(false)
    }
  }

  return (
    <div className="summary-overlay" onClick={onClose}>
      <div className="dl-modal attn-modal" onClick={e => e.stopPropagation()}>
        <div className="dl-modal-head">
          <div className="dl-modal-title">
            {items.length === 0 ? 'All clear for today' : 'Needs your attention today'}
          </div>
          <button className="summary-close" onClick={onClose} aria-label="Close">✕</button>
        </div>

        {items.length === 0 ? (
          <div className="dl-modal-sub">
            Nothing is due today, overdue, or missing. Everything further out is
            waiting in Deadlines — Rudo keeps watching your documents and this
            list updates automatically.
          </div>
        ) : (
          <>
            <div className="dl-modal-sub">
              Found in your documents — click a file to verify, or ✓ when handled
              so it never comes back.
            </div>
            <div className="attn-body">
              {GROUPS.map(g => (
                groups[g.key]?.length ? (
                  <div className="attn-group" key={g.key}>
                    <div className={`attn-group-head ${g.cls}`}>
                      {g.label}
                      <span className="attn-group-n">{groups[g.key].length}</span>
                    </div>
                    <ul className="attn-list">
                      {groups[g.key].map((it, i) => (
                        <AttentionRow key={it.id || `${g.key}-${i}`} item={it}
                                      onOpenFile={onOpenFile} onDismiss={onDismiss}
                                      dismissing={dismissing} />
                      ))}
                    </ul>
                  </div>
                ) : null
              ))}
            </div>
          </>
        )}

        <div className="dl-modal-actions">
          <button className="btn-ghost" onClick={() => { onClose(); onGoDeadlines?.() }}>
            See all deadlines
          </button>
          <button className="btn-primary" onClick={onClose}>Done</button>
        </div>
      </div>
    </div>
  )
}
