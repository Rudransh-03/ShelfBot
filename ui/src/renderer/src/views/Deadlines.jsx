import { useEffect, useState, useCallback, useMemo } from 'react'
import { useApp } from '../context/AppContext'

// ── Icons ───────────────────────────────────────────────────────────────────
const CalIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3" y="4" width="18" height="18" rx="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/>
  </svg>
)
const BellIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/>
  </svg>
)
const XIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
)
const TrashIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/></svg>
)
const FileIcon = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
)
const ScanIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round"><path d="M3 7V5a2 2 0 0 1 2-2h2"/><path d="M17 3h2a2 2 0 0 1 2 2v2"/><path d="M21 17v2a2 2 0 0 1-2 2h-2"/><path d="M7 21H5a2 2 0 0 1-2-2v-2"/><line x1="7" y1="12" x2="17" y2="12"/></svg>
)

// ── Helpers ──────────────────────────────────────────────────────────────────
function relDue(item) {
  if (item.dueDate == null || item.daysUntil == null) return 'No date'
  const d = item.daysUntil
  if (d < 0)  return `${Math.abs(d)} day${Math.abs(d) === 1 ? '' : 's'} overdue`
  if (d === 0) return 'Today'
  if (d === 1) return 'Tomorrow'
  if (d <= 7)  return `in ${d} days`
  return `in ${d} days`
}
function absDate(iso) {
  if (!iso) return ''
  try { return new Date(iso + 'T00:00:00').toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) }
  catch { return iso }
}
const KIND_LABEL = { DEADLINE: 'Deadline', RENEWAL: 'Renewal', ACTION: 'Action' }
const BUCKET_ORDER = ['OVERDUE', 'DUE_SOON', 'UPCOMING', 'NO_DATE']
const BUCKET_LABEL = { OVERDUE: 'Overdue', DUE_SOON: 'Due soon', UPCOMING: 'Upcoming', NO_DATE: 'No date' }

// ── Editable confirm card (shown BEFORE any reminder is created) ──────────────
function ReminderModal({ item, onClose, onConfirm }) {
  const soon = item.daysUntil != null && item.daysUntil <= 3
  const [form, setForm] = useState({
    title:       item.title || 'Reminder',
    date:        item.dueDate || '',
    time:        '09:00',
    leadDays:    soon ? 0 : 3,
    recurring:   (item.recurring || 'NONE').toLowerCase(),
    description: item.description || '',
  })
  const [busy, setBusy] = useState(false)
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  useEffect(() => {
    const onKey = e => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const submit = async () => {
    if (!form.date) return
    setBusy(true)
    try { await onConfirm(form) } finally { setBusy(false) }
  }

  return (
    <div className="summary-overlay" onClick={onClose}>
      <div className="dl-modal" onClick={e => e.stopPropagation()}>
        <div className="dl-modal-head">
          <div className="dl-modal-title">Set a reminder</div>
          <button className="summary-close" onClick={onClose} aria-label="Close"><XIcon /></button>
        </div>
        <div className="dl-modal-sub">Review and edit before it’s added to your calendar.</div>

        <label className="dl-field">
          <span>Title</span>
          <input value={form.title} onChange={e => set('title', e.target.value)} />
        </label>

        <div className="dl-field-row">
          <label className="dl-field">
            <span>Date</span>
            <input type="date" value={form.date} onChange={e => set('date', e.target.value)} />
          </label>
          <label className="dl-field">
            <span>Time</span>
            <input type="time" value={form.time} onChange={e => set('time', e.target.value)} />
          </label>
        </div>

        <div className="dl-field-row">
          <label className="dl-field">
            <span>Remind me</span>
            <select value={form.leadDays} onChange={e => set('leadDays', Number(e.target.value))}>
              <option value={0}>At the time</option>
              <option value={1}>1 day before</option>
              <option value={3}>3 days before</option>
              <option value={7}>1 week before</option>
              <option value={14}>2 weeks before</option>
            </select>
          </label>
          <label className="dl-field">
            <span>Repeat</span>
            <select value={form.recurring} onChange={e => set('recurring', e.target.value)}>
              <option value="none">Doesn’t repeat</option>
              <option value="weekly">Weekly</option>
              <option value="monthly">Monthly</option>
              <option value="quarterly">Quarterly</option>
              <option value="yearly">Yearly</option>
            </select>
          </label>
        </div>

        <label className="dl-field">
          <span>Notes</span>
          <textarea rows={2} value={form.description} onChange={e => set('description', e.target.value)} />
        </label>

        <div className="dl-modal-actions">
          <button className="btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button className="btn-primary" onClick={submit} disabled={busy || !form.date}>
            {busy ? 'Adding…' : 'Add to calendar'}
          </button>
        </div>
      </div>
    </div>
  )
}

// ── One deadline row ──────────────────────────────────────────────────────────
function DeadlineCard({ item, onSetReminder, onDelete, onOpenSource }) {
  const reminded = item.status === 'DONE' || item.reminderSet
  return (
    <li className={`dl-card${reminded ? ' done' : ''} bucket-${(item.bucket || '').toLowerCase()}`}>
      <div className="dl-card-main">
        <div className="dl-card-top">
          <span className={`dl-kind k-${(item.kind || 'ACTION').toLowerCase()}`}>{KIND_LABEL[item.kind] || 'Action'}</span>
          <span className="dl-due">{relDue(item)}</span>
          {item.dueDate && <span className="dl-date">· {absDate(item.dueDate)}</span>}
          {item.confidence === 'LOW' && <span className="dl-low" title="Low confidence — double-check this one">unsure</span>}
          {item.recurring && item.recurring !== 'NONE' && <span className="dl-repeat">{item.recurring.toLowerCase()}</span>}
        </div>
        <div className="dl-title">{item.title}</div>
        {item.description && <div className="dl-desc">{item.description}</div>}
        <div className="dl-foot">
          <button className="dl-src" onClick={() => onOpenSource(item)} title={`Open ${item.path}`}>
            <FileIcon /> {item.fileName}
          </button>
        </div>
      </div>

      <div className="dl-actions">
        {reminded ? (
          // No delete here: the calendar app owns the event and .ics can't remove
          // it, so a trash button would be misleading. Past ones are cleared
          // automatically on app open.
          <span className="dl-reminded"><CalIcon /> On your calendar</span>
        ) : (
          <>
            <button className="dl-act primary" onClick={() => onSetReminder(item)} title="Set a calendar reminder">
              <BellIcon /> Set reminder
            </button>
            <button className="dl-act danger" onClick={() => onDelete(item)} title="Remove from list"><TrashIcon /></button>
          </>
        )}
      </div>
    </li>
  )
}

// ── Deadlines view ────────────────────────────────────────────────────────────
export default function Deadlines({ active }) {
  const {
    api, connected,
    deadlineStats, scanningDeadlines, deadlineScanProgress, deadlinesEnabled,
    scanDeadlines, loadDeadlineStats, toast,
  } = useApp()

  const gated = !deadlinesEnabled
  const onUpgrade = () => toast('Pro plans are coming soon — this is where upgrade will live.', 'i')

  const [items, setItems] = useState(null)       // ALL items; filtered client-side
  const [tab, setTab] = useState('open')          // 'open' | 'reminders'
  const [loading, setLoading] = useState(false)
  const [reminderItem, setReminderItem] = useState(null)

  const load = useCallback(async () => {
    if (!api || !connected) return
    setLoading(true)
    try { const d = await api.listDeadlines('all'); setItems(d.items ?? []) }
    catch (e) { toast(e.message, 'e') }
    finally { setLoading(false) }
  }, [api, connected, toast])

  useEffect(() => { if (active && connected) load() }, [active, connected, load])
  // Refresh when a scan finishes (deadlineStats changes) so new items appear.
  useEffect(() => { if (active && connected) load() }, [deadlineStats]) // eslint-disable-line react-hooks/exhaustive-deps

  // Setting a reminder marks the item handled (DONE) so it leaves the open list
  // and moves to "Reminders set".
  const onSetReminder = useCallback(async (form, item) => {
    const E = window.electron
    if (!E?.createReminder) { toast('Reminders are available in the desktop app.', 'e'); return }
    const res = await E.createReminder({ events: [{
      title: form.title, description: form.description,
      date: form.date, time: form.time, leadDays: form.leadDays,
      recurring: form.recurring, sourceFile: item.fileName, sourcePath: item.path,
    }] })
    if (!res?.ok) { toast(res?.error || 'Could not open your calendar app.', 'e'); return }
    try {
      await api.updateDeadline(item.id, {
        status: 'DONE', reminderSet: true, title: form.title, dueDate: form.date,
        recurring: form.recurring.toUpperCase(), description: form.description,
      })
    } catch { /* calendar event still created; flag persistence is best-effort */ }
    toast('Added to your calendar', 's')
    setReminderItem(null)
    load(); loadDeadlineStats()
  }, [api, toast, load, loadDeadlineStats])

  const onDelete = useCallback(async (item) => {
    try { await api.deleteDeadline(item.id); load(); loadDeadlineStats() }
    catch (e) { toast(e.message, 'e') }
  }, [api, load, loadDeadlineStats, toast])

  const onOpenSource = useCallback((item) => {
    const E = window.electron
    if (E?.openPath) E.openPath(item.path).then(err => { if (err) toast(err, 'e') })
  }, [toast])

  // Open = pending & upcoming. Overdue and undated are hidden — deadlines that
  // were already in the past when their doc was indexed aren't actionable (the
  // user started using the app later), and there's no point making them scroll
  // past hundreds of them. Items that lapse while in use simply drop off here.
  // "Reminders set" = items the user has put on their calendar.
  const { openItems, remindedItems, stat } = useMemo(() => {
    const all = items ?? []
    const open = all.filter(it => it.status === 'PENDING' && (it.bucket === 'DUE_SOON' || it.bucket === 'UPCOMING'))
    const reminded = all.filter(it => it.status === 'DONE' || it.reminderSet)
    return {
      openItems: open,
      remindedItems: reminded,
      stat: {
        dueSoon:   open.filter(it => it.bucket === 'DUE_SOON').length,
        upcoming:  open.filter(it => it.bucket === 'UPCOMING').length,
        reminders: reminded.length,
      },
    }
  }, [items])

  const visible = tab === 'open' ? openItems : remindedItems
  const groups = {}
  for (const it of visible) (groups[it.bucket] ||= []).push(it)

  const scanProgLabel = deadlineScanProgress
    ? `Scanning ${deadlineScanProgress.processed}/${deadlineScanProgress.total}…`
    : 'Scanning…'

  return (
    <div className={`view${active ? ' active' : ''}`} id="view-due">
      <div className="view-header">
        <div>
          <h1 className="view-title">Deadlines</h1>
          <div className="view-subtitle">Upcoming dates, renewals and to-dos Rudo found across your documents.</div>
        </div>
        <div className="header-actions">
          {gated ? (
            <button className="btn-primary" onClick={onUpgrade}>Upgrade to Pro</button>
          ) : (
            <button className="btn-primary" onClick={scanDeadlines} disabled={!connected || scanningDeadlines}>
              {scanningDeadlines
                ? (<><div className="spin-sm" style={{ borderTopColor: '#1a1610', borderColor: 'rgba(26,22,16,.3)' }} />{scanProgLabel}</>)
                : (<><ScanIcon /> Find deadlines</>)}
            </button>
          )}
        </div>
      </div>
      <div className="view-divider" />

      {gated ? (
        <div className="dl-body">
          <div className="dl-upgrade">
            <div className="dl-upgrade-title">Deadlines is a Pro feature</div>
            <div className="dl-upgrade-sub">
              Rudo reads your documents, finds upcoming deadlines and renewals, and turns them into
              calendar reminders — automatically. Upgrade to Pro to switch it on.
            </div>
            <button className="btn-primary" onClick={onUpgrade}>Upgrade to Pro</button>
          </div>
        </div>
      ) : (
      <div className="dl-body">
        {/* Live scan progress — mirrors the indexing progress bar so the user
            can see what's happening when a scan runs (manual or automatic). */}
        {scanningDeadlines && (
          <div className="prog-section dl-prog">
            {deadlineScanProgress?.total > 0 ? (
              <>
                <div className="prog-bg-real">
                  <div className="prog-fill-real"
                       style={{ width: `${Math.min(100, (deadlineScanProgress.processed / deadlineScanProgress.total) * 100)}%` }} />
                </div>
                <div className="prog-label-real">
                  <div className="spin-sm" />
                  <span>
                    Reading <strong>{deadlineScanProgress.processed}</strong> of <strong>{deadlineScanProgress.total}</strong> docs
                    {(deadlineScanProgress.found ?? 0) > 0 && <> · {deadlineScanProgress.found} found</>}
                  </span>
                  {deadlineScanProgress.currentFile && (
                    <span className="prog-current" title={deadlineScanProgress.currentFile}>{deadlineScanProgress.currentFile}</span>
                  )}
                </div>
              </>
            ) : (
              <>
                <div className="prog-bg"><div className="prog-fill" /></div>
                <div className="prog-label"><div className="spin-sm" /><span>Reading your documents for deadlines…</span></div>
              </>
            )}
          </div>
        )}

        {/* Summary chips */}
        <div className="dl-stats">
          <div className="dl-stat soon"><div className="dl-stat-n">{stat.dueSoon}</div><div className="dl-stat-l">Due soon</div></div>
          <div className="dl-stat up"><div className="dl-stat-n">{stat.upcoming}</div><div className="dl-stat-l">Upcoming</div></div>
          <div className="dl-stat"><div className="dl-stat-n">{stat.reminders}</div><div className="dl-stat-l">Reminders set</div></div>
        </div>

        {/* Tabs */}
        <div className="dl-tabs">
          <button className={`dl-tab${tab === 'open' ? ' active' : ''}`} onClick={() => setTab('open')}>
            Open{openItems.length ? ` (${openItems.length})` : ''}
          </button>
          <button className={`dl-tab${tab === 'reminders' ? ' active' : ''}`} onClick={() => setTab('reminders')}>
            Reminders set{remindedItems.length ? ` (${remindedItems.length})` : ''}
          </button>
        </div>

        {/* List */}
        {items == null ? (
          <div className="dl-empty">{loading ? 'Loading…' : ''}</div>
        ) : visible.length === 0 ? (
          <div className="dl-empty">
            {tab === 'open'
              ? (<>No upcoming deadlines. {connected && <button className="link-btn" onClick={scanDeadlines} disabled={scanningDeadlines}>Scan your documents</button>} to find new ones.</>)
              : 'No reminders set yet.'}
          </div>
        ) : tab === 'open' ? (
          BUCKET_ORDER.filter(b => (b === 'DUE_SOON' || b === 'UPCOMING') && groups[b]?.length).map(b => (
            <div key={b} className="dl-group">
              <div className="dl-group-head">{BUCKET_LABEL[b]} <span className="dl-group-n">{groups[b].length}</span></div>
              <ul className="dl-list">
                {groups[b].map(it => (
                  <DeadlineCard key={it.id} item={it}
                    onSetReminder={(x) => setReminderItem(x)} onDelete={onDelete} onOpenSource={onOpenSource} />
                ))}
              </ul>
            </div>
          ))
        ) : (
          <ul className="dl-list">
            {visible.map(it => (
              <DeadlineCard key={it.id} item={it}
                onSetReminder={(x) => setReminderItem(x)} onDelete={onDelete} onOpenSource={onOpenSource} />
            ))}
          </ul>
        )}
      </div>
      )}

      {reminderItem && (
        <ReminderModal
          item={reminderItem}
          onClose={() => setReminderItem(null)}
          onConfirm={(form) => onSetReminder(form, reminderItem)}
        />
      )}
    </div>
  )
}
