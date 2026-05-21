import { useEffect } from 'react'
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

// Icons
const FilesIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
    <polyline points="14 2 14 8 20 8"/>
  </svg>
)
const ChunksIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3" y="3" width="7" height="7" rx="1.5"/>
    <rect x="14" y="3" width="7" height="7" rx="1.5"/>
    <rect x="3" y="14" width="7" height="7" rx="1.5"/>
    <rect x="14" y="14" width="7" height="7" rx="1.5"/>
  </svg>
)
const FailedIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
    <line x1="12" y1="9" x2="12" y2="13"/>
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
    <line x1="18" y1="6" x2="6" y2="18"/>
    <line x1="6" y1="6" x2="18" y2="18"/>
  </svg>
)

export default function Library({ active, onGoSettings }) {
  const {
    connected,
    stats,
    indexing,
    lastJob: result,
    loadStats,
    triggerIndex: handleIndex,
  } = useApp()

  // Refresh stats whenever Library becomes the active view — gives the user
  // an immediate, up-to-date snapshot the moment they click into it,
  // independent of the slow background poll.
  useEffect(() => {
    if (active && connected) loadStats()
  }, [active, connected, loadStats])

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
        {/* Stats — unified strip */}
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
              <div className="prog-bg"><div className="prog-fill" /></div>
              <div className="prog-label">
                <div className="spin-sm" />
                <span>Scanning and embedding files…</span>
              </div>
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
      </div>
    </div>
  )
}
