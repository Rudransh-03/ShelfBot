import { useEffect, useRef, useState } from 'react'
import { useApp } from '../context/AppContext'

/**
 * After indexing, Rudo auto-scans documents for deadlines. When that scan
 * finishes and turns up upcoming, not-yet-reminded items, this modal surfaces
 * them right away — so the user is prompted to set reminders instead of having
 * to remember to open the Deadlines tab themselves.
 *
 * Fires ONLY on a scan completing (scanningDeadlines true → false), never
 * re-nags for the same set of items, and shares a single auto-modal slot with
 * the client-suggestion pop-up so the two never overlap (whichever's free).
 * Clicking "Review & set reminders" jumps straight to the Deadlines view.
 */
function relDue(it) {
  const d = it.daysUntil
  if (d == null) return ''
  if (d < 0)  return `${Math.abs(d)}d overdue`
  if (d === 0) return 'today'
  if (d === 1) return 'tomorrow'
  return `in ${d} days`
}

export default function DeadlineReviewModal({ onNavigate }) {
  const { api, connected, scanningDeadlines, deadlinesEnabled,
          autoModalOpen, setAutoModalOpen } = useApp()
  const [open,  setOpen]  = useState(false)
  const [items, setItems] = useState([])
  const prevScanning = useRef(scanningDeadlines)
  const shownSig     = useRef('')

  // Closing releases the shared slot so a queued pop-up can take its turn.
  const close = () => { setOpen(false); setItems([]); setAutoModalOpen(false) }

  // Collect candidate items when a scan finishes (but don't open yet).
  useEffect(() => {
    const was = prevScanning.current
    prevScanning.current = scanningDeadlines
    if (!(was && !scanningDeadlines)) return          // only on scan true → false
    if (!api || !connected || !deadlinesEnabled) return

    api.listDeadlines('all').then(d => {
      // Same set the Deadlines "Open" tab shows: pending, upcoming, unreminded.
      const upcoming = (d.items ?? []).filter(it =>
        it.status === 'PENDING' && !it.reminderSet &&
        (it.bucket === 'DUE_SOON' || it.bucket === 'UPCOMING'))
      if (upcoming.length === 0) return
      const sig = upcoming.map(it => it.id).sort().join(',')
      if (sig === shownSig.current) return             // already prompted for this set
      shownSig.current = sig
      setItems(upcoming)
    }).catch(() => {})
  }, [scanningDeadlines, api, connected, deadlinesEnabled])

  // Open only when the shared auto-modal slot is free. Re-runs when it frees, so
  // we queue behind the client-suggestion pop-up instead of stacking on top.
  useEffect(() => {
    if (items.length > 0 && !open && !autoModalOpen) {
      setOpen(true); setAutoModalOpen(true)
    }
  }, [items, open, autoModalOpen])

  if (!open || items.length === 0) return null

  const review = () => { close(); onNavigate?.() }

  return (
    <div className="summary-overlay" onClick={close}>
      <div className="dl-modal" onClick={e => e.stopPropagation()}>
        <div className="dl-modal-head">
          <div className="dl-modal-title">
            {items.length} upcoming deadline{items.length === 1 ? '' : 's'} found
          </div>
          <button className="summary-close" onClick={close} aria-label="Close">✕</button>
        </div>
        <div className="dl-modal-sub">
          Rudo spotted these across your documents while indexing. Set calendar reminders so none slip by.
        </div>

        <ul className="clients-list">
          {items.slice(0, 6).map(it => (
            <li className="client-row" key={it.id}>
              <div className="client-head">
                <span className="client-name">{it.title}</span>
                {it.dueDate && <span className="client-count">{relDue(it)}</span>}
              </div>
            </li>
          ))}
        </ul>
        {items.length > 6 && (
          <div className="dl-modal-sub">+{items.length - 6} more in the Deadlines tab</div>
        )}

        <div className="dl-modal-actions">
          <button className="btn-ghost" onClick={close}>Not now</button>
          <button className="btn-primary" onClick={review}>Review &amp; set reminders</button>
        </div>
      </div>
    </div>
  )
}
