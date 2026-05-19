import { useState, useEffect, useCallback } from 'react'
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

// ─────────────────────────────────────────────────────────────────────────────

export default function Library({ active, onGoSettings }) {
  const { api, connected, toast } = useApp()

  const [stats,    setStats]    = useState(null)
  const [indexing, setIndexing] = useState(false)
  const [result,   setResult]   = useState(null)   // { ok, data } | null
  const [pollTmr,  setPollTmr]  = useState(null)

  // Load / refresh stats
  const loadStats = useCallback(async () => {
    if (!connected || !api) return
    try {
      const d = await api.status()
      setStats(d)
    } catch {}
  }, [api, connected])

  // Refresh when view becomes active
  useEffect(() => {
    if (active) loadStats()
  }, [active, loadStats])

  // Poll indexing status
  const startPolling = useCallback(() => {
    const t = setInterval(async () => {
      try {
        const s = await api.pollIndex()
        if (!s.running) {
          clearInterval(t)
          setIndexing(false)
          if (s.result)  { setResult({ ok: true,  data: s.result }); loadStats(); toast('Indexing complete!', 's') }
          if (s.error)   { setResult({ ok: false, msg:  s.error  }); toast('Indexing failed', 'e') }
        }
      } catch {
        clearInterval(t)
        setIndexing(false)
      }
    }, 2200)
    setPollTmr(t)
  }, [api, loadStats, toast])

  // Cleanup on unmount
  useEffect(() => () => clearInterval(pollTmr), [pollTmr])

  const handleIndex = async () => {
    try {
      setResult(null)
      await api.startIndex()
      setIndexing(true)
      toast('Indexing started…', 'i')
      startPolling()
    } catch (e) {
      toast(e.message, 'e')
    }
  }

  return (
    <div className={`view${active ? ' active' : ''}`} id="view-lib">
      <div className="view-header">
        <h1 className="view-title">Library</h1>
        <div className="header-actions">
          <button className="icon-btn" onClick={loadStats} title="Refresh stats">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="1 4 1 10 7 10"/>
              <path d="M3.51 15a9 9 0 102.13-9.36L1 10"/>
            </svg>
          </button>
        </div>
      </div>

      <div className="lib-body">
        {/* Stats */}
        <div className="stats-grid">
          <div className="stat-card g">
            <div className="stat-icon">📁</div>
            <div className="stat-val">{fmt(stats?.indexedFiles)}</div>
            <div className="stat-lbl">Files Indexed</div>
          </div>
          <div className="stat-card">
            <div className="stat-icon">🧩</div>
            <div className="stat-val">{fmt(stats?.totalChunks)}</div>
            <div className="stat-lbl">Chunks Stored</div>
          </div>
          <div className="stat-card r">
            <div className="stat-icon">⚠️</div>
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

          <div className="path-display">
            {stats?.rootPath || 'No folder configured — go to Settings'}
          </div>

          <div className="index-actions">
            <button className="btn-primary" onClick={handleIndex} disabled={indexing || !connected}>
              {indexing ? 'Indexing…' : 'Index Now'}
            </button>
            <button className="btn-ghost" onClick={onGoSettings}>
              Configure Path →
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
                    ✓ Indexing complete — {(result.data.durationMs / 1000).toFixed(1)}s
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
                  <div className="result-head">✕ Indexing failed</div>
                  <div style={{ fontSize: 12, color: 'var(--text-3)', marginTop: 4 }}>{result.msg}</div>
                </>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
