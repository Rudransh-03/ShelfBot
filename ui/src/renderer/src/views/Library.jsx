import { useEffect, useState, useCallback } from 'react'
import { useApp } from '../context/AppContext'

function fmt(n) {
  return n == null ? '—' : Number(n).toLocaleString()
}

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

function fmtBytes(n) {
  if (n == null) return '—'
  const k = 1024
  if (n < k)            return `${n} B`
  if (n < k * k)        return `${(n / k).toFixed(1)} KB`
  if (n < k * k * k)    return `${(n / (k * k)).toFixed(1)} MB`
  return `${(n / (k * k * k)).toFixed(2)} GB`
}

// ── Icons ───────────────────────────────────────────────────────────────────
const FilesIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
    <polyline points="14 2 14 8 20 8"/>
  </svg>
)
const FailedIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
    <line x1="12" y1="9"  x2="12"    y2="13"/>
    <line x1="12" y1="17" x2="12.01" y2="17"/>
  </svg>
)
const FolderIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
  </svg>
)
const RefreshIcon = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="1 4 1 10 7 10"/>
    <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/>
  </svg>
)
const PlayIcon = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
    <polygon points="6 4 20 12 6 20 6 4"/>
  </svg>
)
const ArrowRightSm = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <line x1="5" y1="12" x2="19" y2="12"/>
    <polyline points="12 5 19 12 12 19"/>
  </svg>
)
const CheckIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="20 6 9 17 4 12"/>
  </svg>
)
const XIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
    <line x1="18" y1="6" x2="6"  y2="18"/>
    <line x1="6"  y1="6" x2="18" y2="18"/>
  </svg>
)
const TrashIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="3 6 5 6 21 6"/>
    <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>
    <path d="M10 11v6"/>
    <path d="M14 11v6"/>
    <path d="M9 6V4a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2"/>
  </svg>
)
const SummaryIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
    <polyline points="14 2 14 8 20 8"/>
    <line x1="8"  y1="13" x2="16" y2="13"/>
    <line x1="8"  y1="17" x2="13" y2="17"/>
  </svg>
)
const CloseIconSm = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <line x1="18" y1="6" x2="6"  y2="18"/>
    <line x1="6"  y1="6" x2="18" y2="18"/>
  </svg>
)
const FileTypeIcon = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
    <polyline points="14 2 14 8 20 8"/>
    <line x1="9"  y1="13" x2="15" y2="13"/>
    <line x1="9"  y1="17" x2="15" y2="17"/>
  </svg>
)
const SearchIcon = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="11" cy="11" r="7"/>
    <line x1="21" y1="21" x2="16.65" y2="16.65"/>
  </svg>
)

// ── macOS Full Disk Access banner ───────────────────────────────────────────
// Shown only when the backend reports it can't read one or more configured
// roots — the classic symptom of macOS gating folders behind Full Disk Access.

const ALERT_ICON = (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="12" cy="12" r="10"/>
    <line x1="12" y1="8"  x2="12"    y2="12"/>
    <line x1="12" y1="16" x2="12.01" y2="16"/>
  </svg>
)

function AccessBanner({ stats }) {
  const issues   = stats?.accessIssues ?? []
  const isMac    = (stats?.platform ?? '').includes('mac')
  if (issues.length === 0) return null

  const openSettings = () => {
    const E = window.electron
    if (isMac && E?.openExternal) {
      E.openExternal('x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles')
    }
  }

  return (
    <div className="access-banner">
      <div className="access-banner-icon">{ALERT_ICON}</div>
      <div className="access-banner-body">
        <div className="access-banner-title">
          {isMac
            ? 'Some folders are blocked by macOS'
            : 'Some folders are unreadable'}
        </div>
        <div className="access-banner-sub">
          {isMac
            ? 'Rudo can\'t read these folders without Full Disk Access. Open System Settings → Privacy & Security → Full Disk Access and enable Rudo.'
            : 'Rudo can\'t read these folders. Check permissions or remove them in Settings.'}
        </div>
        <ul className="access-banner-list">
          {issues.map(p => <li key={p}>{p}</li>)}
        </ul>
        {isMac && (
          <button className="access-banner-btn" onClick={openSettings}>
            Open Privacy Settings
          </button>
        )}
      </div>
    </div>
  )
}

// ── Per-file row with inline delete confirmation ────────────────────────────

function FileRow({ file, onDelete, onSummarise }) {
  const [pendingDelete, setPendingDelete] = useState(false)
  const [busy, setBusy] = useState(false)

  const confirmDelete = async () => {
    setBusy(true)
    try { await onDelete(file) } finally {
      setBusy(false)
      setPendingDelete(false)
    }
  }

  return (
    <li className={`file-row${pendingDelete ? ' confirming' : ''}`} title={file.path}>
      <span className="file-row-icon"><FileTypeIcon /></span>
      <div className="file-row-main">
        <div className="file-row-name">{file.name}</div>
        <div className="file-row-meta">
          {file.docType && file.docType !== 'Other' && (
            <>
              <span className="file-type-pill">{file.docType}</span>
              <span className="dot-sep">·</span>
            </>
          )}
          <span>{fmtBytes(file.sizeBytes)}</span>
          <span className="dot-sep">·</span>
          <span>Indexed {fmtAge(file.lastIndexedAt)}</span>
        </div>
      </div>

      {pendingDelete ? (
        <div className="file-row-confirm">
          <span className="file-row-confirm-msg">Remove from index?</span>
          <button
            className="file-row-confirm-cancel"
            onClick={() => setPendingDelete(false)}
            disabled={busy}
          >
            Cancel
          </button>
          <button
            className="file-row-confirm-yes"
            onClick={confirmDelete}
            disabled={busy}
          >
            {busy ? 'Removing…' : 'Remove'}
          </button>
        </div>
      ) : (
        <div className="file-row-actions">
          <button
            className="file-row-action"
            onClick={() => onSummarise(file)}
            title="Generate a one-page brief"
            aria-label={`Summarise ${file.name}`}
          >
            <SummaryIcon />
            <span>Summarise</span>
          </button>
          <button
            className="file-row-del"
            onClick={() => setPendingDelete(true)}
            title="Remove from index (file on disk is not touched)"
            aria-label={`Remove ${file.name} from index`}
          >
            <TrashIcon />
          </button>
        </div>
      )}
    </li>
  )
}

// ── Summary modal ───────────────────────────────────────────────────────────

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * Render the GPT-4o mini brief as minimal HTML — the model emits **bold**
 * section headers and "- " bullets. We escape first, then re-introduce
 * those two markdown forms. Anything else (links, italics) is preserved
 * as plain text.
 */
function renderBrief(text) {
  const esc = escapeHtml(text || '')
  const lines = esc.split('\n')
  const html = lines.map(line => {
    const bolded = line.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    if (/^\s*-\s+/.test(bolded)) {
      return '<li>' + bolded.replace(/^\s*-\s+/, '') + '</li>'
    }
    return bolded
  })
  // Wrap consecutive <li> sequences with <ul>.
  const out = []
  let inList = false
  for (const ln of html) {
    if (ln.startsWith('<li>')) {
      if (!inList) { out.push('<ul>'); inList = true }
      out.push(ln)
    } else {
      if (inList) { out.push('</ul>'); inList = false }
      out.push(ln)
    }
  }
  if (inList) out.push('</ul>')
  return out.join('\n').replace(/\n+/g, '\n').replace(/\n/g, '<br>')
}

function SummaryModal({ file, data, error, loading, onClose }) {
  // Close on Escape — nicer keyboard ergonomics than forcing a click.
  useEffect(() => {
    const onKey = e => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="summary-overlay" onClick={onClose}>
      <div className="summary-card" onClick={e => e.stopPropagation()}>
        <div className="summary-head">
          <div className="summary-head-main">
            <div className="summary-head-title">{file.name}</div>
            <div className="summary-head-sub">
              {loading
                ? 'Reading the document…'
                : error
                  ? 'Failed to summarise'
                  : data?.cached
                    ? `Cached · generated ${fmtAge(data.generatedAt)}`
                    : data
                      ? `Generated just now · ${data.llmCalls} LLM call${data.llmCalls === 1 ? '' : 's'}`
                      : ''}
            </div>
          </div>
          <button className="summary-close" onClick={onClose} aria-label="Close summary">
            <CloseIconSm />
          </button>
        </div>

        <div className="summary-body">
          {loading ? (
            <div className="summary-loading">
              <div className="spin-sm" />
              <span>Pulling chunks and writing the brief…</span>
            </div>
          ) : error ? (
            <div className="summary-error">{error}</div>
          ) : data ? (
            <div
              className="summary-brief"
              dangerouslySetInnerHTML={{ __html: renderBrief(data.summary) }}
            />
          ) : null}
        </div>
      </div>
    </div>
  )
}

// ── Live indexing status modal ──────────────────────────────────────────────

function stageLabel(f) {
  switch (f.stage) {
    case 'extracting': return 'Extracting text…'
    case 'chunking':   return 'Chunking…'
    case 'embedding':  return `Embedding ${f.done ?? 0}/${f.total ?? 0} chunks`
    case 'saving':     return 'Saving…'
    default:           return 'Starting…'
  }
}

// Fractional completion (0–1) for an in-flight file, by stage. Lets the overall
// bar advance smoothly even though files finish in parallel waves — without this
// the "files processed" count only ticks on whole-file completion, so with a few
// files the bar sits at 0 then jumps to 100 at the end.
function stageFraction(f) {
  switch (f.stage) {
    case 'extracting': return 0.15
    case 'chunking':   return 0.30
    case 'embedding':  return f.total > 0 ? 0.35 + 0.55 * Math.min(1, (f.done || 0) / f.total) : 0.45
    case 'saving':     return 0.95
    default:           return 0.05
  }
}

/**
 * Shows the files currently being indexed and how far along each one is.
 * Driven by the `activeFiles` snapshot from /api/index — skipped and finished
 * files are never in that list, so they never appear here.
 */
function IndexStatusModal({ files, onClose }) {
  useEffect(() => {
    const onKey = e => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="summary-overlay" onClick={onClose}>
      <div className="summary-card status-card" onClick={e => e.stopPropagation()}>
        <div className="summary-head">
          <div className="summary-head-main">
            <div className="summary-head-title">Indexing status</div>
            <div className="summary-head-sub">
              {files.length === 0
                ? 'Finishing up…'
                : `${files.length} file${files.length === 1 ? '' : 's'} in progress`}
            </div>
          </div>
          <button className="summary-close" onClick={onClose} aria-label="Close status">
            <CloseIconSm />
          </button>
        </div>

        <div className="summary-body">
          {files.length === 0 ? (
            <div className="files-panel-empty">No files are being indexed right now.</div>
          ) : (
            <ul className="status-list">
              {files.map(f => {
                const det = f.stage === 'embedding' && f.total > 0
                const pct = det ? Math.min(100, Math.round((f.done / f.total) * 100)) : null
                return (
                  <li key={f.path} className="status-item" title={f.path}>
                    <div className="status-item-top">
                      <span className="status-item-name">{f.name}</span>
                      <span className="status-item-stage">{stageLabel(f)}</span>
                    </div>
                    {det && (
                      <div className="prog-bg-real">
                        <div className="prog-fill-real" style={{ width: `${pct}%` }} />
                      </div>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
        </div>
      </div>
    </div>
  )
}

// ── Failed-files detail modal ───────────────────────────────────────────────
// Opened from the "Failed" stat card. Shows which files couldn't be indexed and
// why, so a dead-end number becomes something the user can actually act on.

function FailedFilesModal({ api, toast, onClose }) {
  const [files, setFiles]     = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const onKey = e => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  useEffect(() => {
    let cancelled = false
    api.listFailedFiles()
      .then(r => { if (!cancelled) setFiles(r.files ?? []) })
      .catch(e => { if (!cancelled) { toast(e.message, 'e'); setFiles([]) } })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [api, toast])

  return (
    <div className="summary-overlay" onClick={onClose}>
      <div className="summary-card status-card" onClick={e => e.stopPropagation()}>
        <div className="summary-head">
          <div className="summary-head-main">
            <div className="summary-head-title">Files that couldn’t be indexed</div>
            <div className="summary-head-sub">
              {loading
                ? 'Loading…'
                : files && files.length > 0
                  ? `${files.length} file${files.length === 1 ? '' : 's'} skipped — fix the issue, then re-index.`
                  : 'All files indexed cleanly.'}
            </div>
          </div>
          <button className="summary-close" onClick={onClose} aria-label="Close"><CloseIconSm /></button>
        </div>

        <div className="summary-body">
          {loading ? (
            <div className="summary-loading"><div className="spin-sm" /><span>Loading…</span></div>
          ) : files && files.length > 0 ? (
            <ul className="status-list">
              {files.map(f => (
                <li key={f.path} className="status-item" title={f.path}>
                  <div className="status-item-top">
                    <span className="status-item-name">{f.name}</span>
                    <span className="failed-reason-tag">failed</span>
                  </div>
                  <div className="failed-reason">{f.reason || 'Unknown error'}</div>
                </li>
              ))}
            </ul>
          ) : (
            <div className="files-panel-empty">No failed files.</div>
          )}
        </div>
      </div>
    </div>
  )
}

// ── Indexed files panel ─────────────────────────────────────────────────────

function IndexedFilesPanel({ api, connected, refreshKey, onDeleted, toast }) {
  const [files, setFiles] = useState(null)
  const [loading, setLoading] = useState(false)
  const [query, setQuery] = useState('')
  const [typeFilter, setTypeFilter] = useState('All') // active doc-type chip

  // Active summary modal state: { file, data, error, loading }.
  // Only one modal at a time — clicking Summarise on a different row replaces.
  const [summaryState, setSummaryState] = useState(null)

  const load = useCallback(async () => {
    if (!api || !connected) return
    setLoading(true)
    try {
      const res = await api.listFiles()
      setFiles(res.files ?? [])
    } catch (e) {
      toast(e.message, 'e')
    } finally {
      setLoading(false)
    }
  }, [api, connected, toast])

  // Reload on mount, on refresh trigger, and when the file list might have changed.
  useEffect(() => { load() }, [load, refreshKey])

  const handleDelete = useCallback(async (file) => {
    try {
      await api.deleteFile(file.path)
      setFiles(prev => (prev ?? []).filter(f => f.path !== file.path))
      toast(`Removed “${file.name}” from index`, 's')
      onDeleted?.()
    } catch (e) {
      toast(e.message, 'e')
    }
  }, [api, toast, onDeleted])

  const runSummary = useCallback(async (file, opts = {}) => {
    setSummaryState({ file, data: null, error: null, loading: true })
    try {
      const data = await api.summarizeFile(file.path, opts)
      setSummaryState({ file, data, error: null, loading: false })
    } catch (e) {
      setSummaryState({ file, data: null, error: e.message, loading: false })
    }
  }, [api])

  const handleSummarise = useCallback((file) => { runSummary(file) }, [runSummary])
  const closeSummary   = useCallback(() => setSummaryState(null), [])

  // Self-sorting "folders": group counts by auto document type, ordered by
  // count desc, with "All" first. Computed from the in-memory list, so it
  // updates instantly as files are added/removed.
  const typeGroups = (() => {
    const counts = new Map()
    for (const f of files ?? []) {
      const t = f.docType || 'Other'
      counts.set(t, (counts.get(t) || 0) + 1)
    }
    const ordered = [...counts.entries()].sort((a, b) =>
      a[0] === 'Other' ? 1 : b[0] === 'Other' ? -1 : b[1] - a[1])
    return [['All', (files ?? []).length], ...ordered]
  })()

  // Reset the type chip if the active one no longer exists (e.g. last file of a
  // type was deleted) so the list never silently shows nothing.
  useEffect(() => {
    if (typeFilter !== 'All' && !(files ?? []).some(f => (f.docType || 'Other') === typeFilter)) {
      setTypeFilter('All')
    }
  }, [files, typeFilter])

  // Instant client-side filter on file name/path AND the active type chip.
  // Filtering an in-memory array on each keystroke is sub-millisecond, so no
  // debounce is needed — the list updates the moment a key is pressed.
  const q = query.trim().toLowerCase()
  const visible = (files ?? []).filter(f => {
    if (typeFilter !== 'All' && (f.docType || 'Other') !== typeFilter) return false
    if (!q) return true
    return (f.name ?? '').toLowerCase().includes(q) ||
           (f.path ?? '').toLowerCase().includes(q)
  })

  return (
    <div className="files-panel">
      <div className="files-panel-head">
        <div>
          <div className="files-panel-title">Indexed files</div>
          <div className="files-panel-sub">
            Sorted by size · largest first. Removing a file frees its tokens; the file on disk is untouched.
          </div>
        </div>
        <button
          className="icon-btn"
          onClick={load}
          title="Refresh list"
          disabled={loading || !connected}
        >
          <RefreshIcon />
        </button>
      </div>

      {/* Self-sorting folders — tap a type to filter. Hidden until there's
          more than one type, so a tiny library isn't cluttered with one chip. */}
      {files != null && typeGroups.length > 2 && (
        <div className="type-chips" role="tablist" aria-label="Filter by document type">
          {typeGroups.map(([type, count]) => (
            <button
              key={type}
              className={`type-chip${typeFilter === type ? ' active' : ''}`}
              onClick={() => setTypeFilter(type)}
              role="tab"
              aria-selected={typeFilter === type}
              title={`${count} ${type === 'All' ? 'file' : type}${count === 1 ? '' : 's'}`}
            >
              {type}
              <span className="type-chip-count">{count}</span>
            </button>
          ))}
        </div>
      )}

      {files != null && files.length > 0 && (
        <div className="files-search">
          <span className="files-search-icon"><SearchIcon /></span>
          <input
            className="files-search-input"
            type="text"
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder="Search indexed files by name…"
            aria-label="Search indexed files"
            autoComplete="off"
            spellCheck={false}
          />
          {query && (
            <button
              className="files-search-clear"
              onClick={() => setQuery('')}
              title="Clear search"
              aria-label="Clear search"
            >
              <CloseIconSm />
            </button>
          )}
        </div>
      )}

      {files == null ? (
        <div className="files-panel-empty">{loading ? 'Loading…' : ''}</div>
      ) : files.length === 0 ? (
        <div className="files-panel-empty">No files indexed yet.</div>
      ) : visible.length === 0 ? (
        <div className="files-panel-empty">No files match “{query}”.</div>
      ) : (
        <>
          {(q || typeFilter !== 'All') && (
            <div className="files-search-count">
              Showing {visible.length} of {files.length} files
              {typeFilter !== 'All' && <> · {typeFilter}</>}
            </div>
          )}
          <ul className="file-list">
            {visible.map(f => (
              <FileRow
                key={f.path}
                file={f}
                onDelete={handleDelete}
                onSummarise={handleSummarise}
              />
            ))}
          </ul>
        </>
      )}

      {summaryState && (
        <SummaryModal
          file={summaryState.file}
          data={summaryState.data}
          error={summaryState.error}
          loading={summaryState.loading}
          onClose={closeSummary}
        />
      )}
    </div>
  )
}

// ── Library view ────────────────────────────────────────────────────────────

export default function Library({ active, onGoSettings }) {
  const {
    api, connected,
    stats, indexing, progress, activeFiles,
    lastJob: result,
    loadStats, triggerIndex: handleIndex,
    toast,
  } = useApp()

  // Bumping this forces IndexedFilesPanel to refetch — e.g. after an indexing
  // job finishes or after the user deletes a file.
  const [filesRefreshKey, setFilesRefreshKey] = useState(0)
  const bumpFiles = useCallback(() => setFilesRefreshKey(k => k + 1), [])

  // "View status" modal — auto-closes when the job finishes.
  const [showStatus, setShowStatus] = useState(false)
  useEffect(() => { if (!indexing) setShowStatus(false) }, [indexing])

  // "Failed files" detail modal (opened from the Failed stat card).
  const [showFailed, setShowFailed] = useState(false)

  useEffect(() => {
    if (active && connected) loadStats()
  }, [active, connected, loadStats])

  // When an indexing job completes (result transitions from null → set), the
  // file list and token totals have likely changed — refresh both.
  useEffect(() => {
    if (result) {
      bumpFiles()
      loadStats()
    }
  }, [result, bumpFiles, loadStats])

  // Keep the indexed-files list in sync with the actual index even when files
  // were indexed OUTSIDE a manual job — e.g. the live file-watcher picking up a
  // dropped-in file, or the startup re-scan. stats.lastIndexed / indexedFiles
  // refresh on the idle poll, so a newly-indexed file shows up on its own
  // without the user clicking "Index".
  useEffect(() => {
    bumpFiles()
  }, [stats?.lastIndexed, stats?.indexedFiles, bumpFiles])

  return (
    <div className={`view${active ? ' active' : ''}`} id="view-lib">
      <div className="view-header">
        <div>
          <h1 className="view-title">Library</h1>
          <div className="view-subtitle">Index, monitor, and manage your knowledge base.</div>
        </div>
        <div className="header-actions">
          <button className="icon-btn" onClick={loadStats} title="Refresh stats">
            <RefreshIcon />
          </button>
        </div>
      </div>
      <div className="view-divider" />

      <div className="lib-body">
        <AccessBanner stats={stats} />

        {/* Stats */}
        <div className="stats-grid two">
          <div className="stat-card g">
            <div className="stat-icon"><FilesIcon /></div>
            <div className="stat-val">{fmt(stats?.indexedFiles)}</div>
            <div className="stat-lbl">Files Indexed</div>
          </div>
          {(() => {
            const failed = stats?.failedFiles ?? 0
            const clickable = failed > 0
            return (
              <div
                className={`stat-card${failed > 0 ? ' r' : ''}${clickable ? ' clickable' : ''}`}
                onClick={clickable ? () => setShowFailed(true) : undefined}
                role={clickable ? 'button' : undefined}
                tabIndex={clickable ? 0 : undefined}
                onKeyDown={clickable ? (e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setShowFailed(true) } }) : undefined}
                title={clickable ? 'See which files failed and why' : 'No files failed to index'}
              >
                <div className="stat-icon"><FailedIcon /></div>
                <div className="stat-val">{fmt(failed)}</div>
                <div className="stat-lbl">{clickable ? 'Failed — view details' : 'Failed Files'}</div>
              </div>
            )
          })()}
        </div>

        {/* Index control */}
        <div className="index-card">
          <div className="index-card-top">
            <span className="index-card-title">Index Control</span>
            <span className="last-badge">
              Last:&nbsp;<strong>{fmtAge(stats?.lastIndexed)}</strong>
            </span>
          </div>

          {(() => {
            const paths = Array.isArray(stats?.rootPaths) && stats.rootPaths.length
              ? stats.rootPaths
              : (stats?.rootPath ? [stats.rootPath] : [])
            if (paths.length === 0) {
              return (
                <div className="path-display">
                  <FolderIcon />
                  <span>No folders configured — go to Settings</span>
                </div>
              )
            }
            return (
              <div className="paths-display">
                {paths.map(p => (
                  <div className="path-display path-display-item" key={p} title={p}>
                    <FolderIcon />
                    <span>{p}</span>
                  </div>
                ))}
              </div>
            )
          })()}

          <div className="index-actions">
            <button className="btn-primary" onClick={handleIndex} disabled={indexing || !connected}>
              {indexing ? (
                <>
                  <div className="spin-sm" style={{ borderTopColor: '#1a1610', borderColor: 'rgba(26,22,16,.3)' }} />
                  Indexing…
                </>
              ) : (
                <>
                  <PlayIcon />
                  Index Now
                </>
              )}
            </button>
            <button className="btn-ghost" onClick={onGoSettings}>
              Configure folders
              <ArrowRightSm />
            </button>
          </div>

          {indexing && (
            <div className="prog-section">
              {progress?.total > 0 ? (() => {
                // Smooth fill: completed files + partial credit for in-flight ones
                // (by stage), so the bar moves continuously instead of jumping.
                const inflight = (activeFiles || []).reduce((s, f) => s + stageFraction(f), 0)
                const pct = Math.min(100, ((progress.processed + inflight) / progress.total) * 100)
                return (
                  <>
                    <div className="prog-bg-real">
                      <div className="prog-fill-real" style={{ width: `${pct}%` }} />
                    </div>
                    <div className="prog-label-real">
                      <div className="spin-sm" />
                      <span>
                        <strong>{progress.processed}</strong> of <strong>{progress.total}</strong> files indexed
                        {progress.failed > 0 && <span className="prog-failed"> · {progress.failed} skipped</span>}
                      </span>
                    </div>
                    {activeFiles.length > 0 && (
                      <ul className="prog-active">
                        {activeFiles.slice(0, 3).map(f => (
                          <li className="prog-active-row" key={f.path} title={f.path}>
                            <span className="prog-active-name">{f.name}</span>
                            <span className="prog-active-stage">{stageLabel(f)}</span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </>
                )
              })() : (
                <>
                  <div className="prog-bg"><div className="prog-fill" /></div>
                  <div className="prog-label">
                    <div className="spin-sm" />
                    <span>Scanning files…</span>
                  </div>
                </>
              )}
              {activeFiles.length > 3 && (
                <button
                  className="btn-ghost btn-sm prog-status-btn"
                  onClick={() => setShowStatus(true)}
                >
                  View all {activeFiles.length} files
                </button>
              )}
            </div>
          )}

          {result && (
            <div className={`result-box${result.ok ? '' : ' err'}`}>
              {result.ok ? (
                <>
                  <div className="result-head">
                    <CheckIcon />
                    Indexing complete — {(result.data.durationMs / 1000).toFixed(1)}s
                  </div>
                  <div className="result-grid">
                    {[
                      ['Processed', result.data.filesProcessed],
                      ['Skipped',   result.data.filesSkipped],
                      ['Failed',    result.data.filesFailed],
                    ].map(([lbl, num]) => (
                      <div key={lbl} className="rg-item">
                        <div className="rg-num">{num}</div>
                        <div className="rg-lbl">{lbl}</div>
                      </div>
                    ))}
                  </div>
                </>
              ) : (
                <>
                  <div className="result-head">
                    <XIcon />
                    Indexing failed
                  </div>
                  <div style={{ fontSize: 12, color: 'var(--text-3)', marginTop: 6, lineHeight: 1.5 }}>{result.msg}</div>
                </>
              )}
            </div>
          )}
        </div>

        {/* Indexed files list */}
        <IndexedFilesPanel
          api={api}
          connected={connected}
          refreshKey={filesRefreshKey}
          onDeleted={loadStats}
          toast={toast}
        />
      </div>

      {showStatus && (
        <IndexStatusModal files={activeFiles} onClose={() => setShowStatus(false)} />
      )}

      {showFailed && (
        <FailedFilesModal api={api} toast={toast} onClose={() => setShowFailed(false)} />
      )}
    </div>
  )
}
