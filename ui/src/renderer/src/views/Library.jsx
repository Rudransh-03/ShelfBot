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

function fmtTokens(n) {
  if (n == null) return '—'
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`
  if (n >= 1_000)     return `${(n / 1_000).toFixed(0)}K`
  return String(n)
}

// ── Icons ───────────────────────────────────────────────────────────────────
const FilesIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
    <polyline points="14 2 14 8 20 8"/>
  </svg>
)
const ChunksIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3"  y="3"  width="7" height="7" rx="1.5"/>
    <rect x="14" y="3"  width="7" height="7" rx="1.5"/>
    <rect x="3"  y="14" width="7" height="7" rx="1.5"/>
    <rect x="14" y="14" width="7" height="7" rx="1.5"/>
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
const FileTypeIcon = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
    <polyline points="14 2 14 8 20 8"/>
    <line x1="9"  y1="13" x2="15" y2="13"/>
    <line x1="9"  y1="17" x2="15" y2="17"/>
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

// ── Token budget meter ──────────────────────────────────────────────────────

function TokenMeter({ used = 0, limit = 1, perFileLimit = 0 }) {
  const pct = Math.min(100, Math.max(0, (used / Math.max(1, limit)) * 100))
  const tier = pct >= 95 ? 'critical' : pct >= 75 ? 'warn' : 'ok'

  return (
    <div className={`token-meter tier-${tier}`}>
      <div className="token-meter-head">
        <div className="token-meter-title">Token budget</div>
        <div className="token-meter-pct">{pct.toFixed(1)}%</div>
      </div>
      <div className="token-meter-bar">
        <div className="token-meter-fill" style={{ width: `${pct}%` }} />
      </div>
      <div className="token-meter-foot">
        <span>
          <strong>{fmtTokens(used)}</strong> of {fmtTokens(limit)} tokens used
        </span>
        {perFileLimit > 0 && (
          <span className="token-meter-sub">
            Per-file limit: {fmtTokens(perFileLimit)}
          </span>
        )}
      </div>
      {tier !== 'ok' && (
        <div className="token-meter-msg">
          {tier === 'critical'
            ? 'You’ve nearly hit your indexing limit. Delete files below to free up budget.'
            : 'Approaching the indexing limit. Consider trimming large files.'}
        </div>
      )}
    </div>
  )
}

// ── Per-file row with inline delete confirmation ────────────────────────────

function FileRow({ file, onDelete }) {
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
          <span>{fmtBytes(file.sizeBytes)}</span>
          <span className="dot-sep">·</span>
          <span>{fmt(file.chunkCount)} chunks</span>
          <span className="dot-sep">·</span>
          <span>{fmtTokens(file.tokenCount)} tokens</span>
          <span className="dot-sep">·</span>
          <span>{fmtAge(file.lastIndexedAt)}</span>
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
        <button
          className="file-row-del"
          onClick={() => setPendingDelete(true)}
          title="Remove from index (file on disk is not touched)"
          aria-label={`Remove ${file.name} from index`}
        >
          <TrashIcon />
        </button>
      )}
    </li>
  )
}

// ── Indexed files panel ─────────────────────────────────────────────────────

function IndexedFilesPanel({ api, connected, refreshKey, onDeleted, toast }) {
  const [files, setFiles] = useState(null)
  const [loading, setLoading] = useState(false)

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

      {files == null ? (
        <div className="files-panel-empty">{loading ? 'Loading…' : ''}</div>
      ) : files.length === 0 ? (
        <div className="files-panel-empty">No files indexed yet.</div>
      ) : (
        <ul className="file-list">
          {files.map(f => (
            <FileRow key={f.path} file={f} onDelete={handleDelete} />
          ))}
        </ul>
      )}
    </div>
  )
}

// ── Library view ────────────────────────────────────────────────────────────

export default function Library({ active, onGoSettings }) {
  const {
    api, connected,
    stats, indexing, progress,
    lastJob: result,
    loadStats, triggerIndex: handleIndex,
    toast,
  } = useApp()

  // Bumping this forces IndexedFilesPanel to refetch — e.g. after an indexing
  // job finishes or after the user deletes a file.
  const [filesRefreshKey, setFilesRefreshKey] = useState(0)
  const bumpFiles = useCallback(() => setFilesRefreshKey(k => k + 1), [])

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
        <div className="stats-grid">
          <div className="stat-card g">
            <div className="stat-icon"><FilesIcon /></div>
            <div className="stat-val">{fmt(stats?.indexedFiles)}</div>
            <div className="stat-lbl">Files Indexed</div>
          </div>
          <div className="stat-card">
            <div className="stat-icon"><ChunksIcon /></div>
            <div className="stat-val">{fmt(stats?.totalChunks)}</div>
            <div className="stat-lbl">Chunks Stored</div>
          </div>
          <div className="stat-card r">
            <div className="stat-icon"><FailedIcon /></div>
            <div className="stat-val">{fmt(stats?.failedFiles)}</div>
            <div className="stat-lbl">Failed Files</div>
          </div>
        </div>

        {/* Token budget */}
        <TokenMeter
          used={stats?.tokensUsed ?? 0}
          limit={stats?.tokensLimit ?? 40_000_000}
          perFileLimit={stats?.tokensLimitPerFile ?? 0}
        />

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
              Configure Path
              <ArrowRightSm />
            </button>
          </div>

          {indexing && (
            <div className="prog-section">
              {progress?.total > 0 ? (
                <>
                  <div className="prog-bg-real">
                    <div
                      className="prog-fill-real"
                      style={{ width: `${Math.min(100, (progress.processed / progress.total) * 100)}%` }}
                    />
                  </div>
                  <div className="prog-label-real">
                    <div className="spin-sm" />
                    <span>
                      <strong>{progress.processed}</strong> of <strong>{progress.total}</strong> files
                      {progress.failed > 0 && <span className="prog-failed"> · {progress.failed} skipped</span>}
                    </span>
                    {progress.currentFile && (
                      <span className="prog-current" title={progress.currentFile}>
                        {progress.currentFile}
                      </span>
                    )}
                  </div>
                </>
              ) : (
                <>
                  <div className="prog-bg"><div className="prog-fill" /></div>
                  <div className="prog-label">
                    <div className="spin-sm" />
                    <span>Scanning files…</span>
                  </div>
                </>
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
                      ['Chunks',    result.data.totalChunksCreated],
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
    </div>
  )
}
