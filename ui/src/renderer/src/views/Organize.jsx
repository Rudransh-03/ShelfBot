import { useEffect, useMemo, useState } from 'react'
import { useApp } from '../context/AppContext'

/**
 * Reorganization view. Workflow:
 *
 *   1. User picks a folder via the system file dialog.
 *   2. We POST /api/reorg/preview → either a proposal or a scope error.
 *   3. If proposal: render moves grouped by destination, with per-row
 *      checkboxes. User Apply runs /api/reorg/execute → batch result.
 *   4. The batch result includes a batchId → "Undo" button reverses it.
 *   5. If scope error: show the friendly explanation + suggestion chips.
 *      Clicking a suggestion re-runs preview with that narrower scope.
 *
 * State machine (kept simple — one screen at a time):
 *   idle ─pick→ analyzing ─done→ proposal | scopeError | empty
 *      proposal ─apply→ executing ─done→ executionResult ─undo→ undone
 *      scopeError ─retry→ analyzing
 */
export default function Organize({ active }) {
  const { api, connected, toast } = useApp()

  const [targetDir,        setTargetDir]        = useState(null)
  const [phase,            setPhase]            = useState('idle')
       // 'idle' | 'analyzing' | 'proposal' | 'scopeError' | 'executing' | 'result'
  const [scopeError,       setScopeError]       = useState(null)
  const [proposal,         setProposal]         = useState(null)
  const [summary,          setSummary]          = useState(null)
  const [selectedKeys,     setSelectedKeys]     = useState(new Set())
  const [executionResult,  setExecutionResult]  = useState(null)
  const [history,          setHistory]          = useState([])
  const [error,            setError]            = useState(null)

  // Refresh undo history when the view opens or after a successful execute.
  useEffect(() => {
    if (!active || !connected) return
    api.reorgHistory(5).then(r => setHistory(r.batches || [])).catch(() => {})
  }, [active, connected, api, phase])

  function reset() {
    setPhase('idle')
    setProposal(null)
    setScopeError(null)
    setSummary(null)
    setSelectedKeys(new Set())
    setExecutionResult(null)
    setError(null)
  }

  async function pickFolder() {
    if (!window.electron) {
      toast('Folder picker only works in the desktop app.', 'e')
      return
    }
    const dir = await window.electron.selectFolder()
    if (!dir) return
    setTargetDir(dir)
    await runPreview(dir)
  }

  async function runPreview(dir) {
    setPhase('analyzing')
    setError(null)
    setProposal(null)
    setScopeError(null)
    try {
      const r = await api.reorgPreview(dir)
      setSummary(r.summary || null)
      if (r.kind === 'scopeError') {
        setScopeError(r.scopeError)
        setPhase('scopeError')
      } else {
        setProposal(r.proposal)
        // Default: every proposed move is checked. User unchecks ones they don't like.
        const keys = new Set((r.proposal?.moves || []).map(moveKey))
        setSelectedKeys(keys)
        setPhase('proposal')
      }
    } catch (e) {
      setError(e.message || 'Preview failed')
      setPhase('idle')
    }
  }

  async function applySelected() {
    if (!proposal || selectedKeys.size === 0) return
    const moves = proposal.moves.filter(m => selectedKeys.has(moveKey(m)))
    setPhase('executing')
    setError(null)
    try {
      const r = await api.reorgExecute(targetDir, moves)
      setExecutionResult(r)
      setPhase('result')
      toast(`Moved ${r.successCount} of ${moves.length} file${moves.length === 1 ? '' : 's'}.`,
            r.failedCount > 0 ? 'e' : 's')
    } catch (e) {
      setError(e.message || 'Execute failed')
      setPhase('proposal')
    }
  }

  async function undoBatch(batchId) {
    try {
      const r = await api.reorgUndo(batchId)
      toast(`Undid ${r.successCount} of ${r.outcomes.length} move${r.outcomes.length === 1 ? '' : 's'}.`,
            r.failedCount > 0 ? 'e' : 's')
      // Refresh history; if we were viewing the result for this batch, reset.
      api.reorgHistory(5).then(rs => setHistory(rs.batches || [])).catch(() => {})
      if (executionResult && executionResult.batchId === batchId) reset()
    } catch (e) {
      toast(e.message || 'Undo failed', 'e')
    }
  }

  // Group moves by destination folder so the UI shows them like a real
  // file manager: one section per target, with the source files listed
  // under it.
  const grouped = useMemo(() => groupMovesByDestination(proposal?.moves || []), [proposal])

  function toggleMove(key) {
    setSelectedKeys(s => {
      const next = new Set(s)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  function toggleGroup(group, checked) {
    setSelectedKeys(s => {
      const next = new Set(s)
      for (const m of group.moves) {
        const k = moveKey(m)
        if (checked) next.add(k)
        else next.delete(k)
      }
      return next
    })
  }

  // ── Render ───────────────────────────────────────────────────────────────

  return (
    <div className={`view${active ? ' active' : ''}`} id="view-org">
      <div className="view-header">
        <div>
          <h1 className="view-title">Organize</h1>
          <div className="view-subtitle">
            Tidy a folder. Rudo proposes moves; you decide which ones to apply.
          </div>
        </div>
      </div>
      <div className="view-divider" />

      <div className="org-body">
        {/* Top control row */}
        <div className="org-controls">
          <button
            className="btn btn-primary"
            onClick={pickFolder}
            disabled={!connected || phase === 'analyzing' || phase === 'executing'}
          >
            {phase === 'analyzing' ? 'Analyzing…' : 'Pick a folder'}
          </button>
          {targetDir && (
            <div className="org-target">
              <span className="org-target-label">Target:</span>
              <code className="org-target-path">{targetDir}</code>
              {phase !== 'idle' && phase !== 'analyzing' && (
                <button className="btn btn-ghost btn-sm" onClick={reset}>Clear</button>
              )}
            </div>
          )}
        </div>

        {error && <div className="org-error">{error}</div>}

        {/* Body — one panel at a time */}
        {phase === 'idle' && !targetDir && (
          <IdleHero history={history} api={api} onUndo={undoBatch} />
        )}

        {phase === 'analyzing' && (
          <div className="org-loading">
            <div className="org-spinner" />
            <div>Analyzing folder… this is local, no LLM cost yet.</div>
          </div>
        )}

        {phase === 'scopeError' && scopeError && (
          <ScopeErrorPanel
            err={scopeError}
            onRetry={(dir) => { setTargetDir(dir); runPreview(dir) }}
          />
        )}

        {phase === 'proposal' && proposal && (
          <ProposalPanel
            proposal={proposal}
            summary={summary}
            grouped={grouped}
            selected={selectedKeys}
            onToggleMove={toggleMove}
            onToggleGroup={toggleGroup}
            onApply={applySelected}
            onCancel={reset}
          />
        )}

        {phase === 'executing' && (
          <div className="org-loading">
            <div className="org-spinner" />
            <div>Moving files…</div>
          </div>
        )}

        {phase === 'result' && executionResult && (
          <ResultPanel
            result={executionResult}
            onUndo={() => undoBatch(executionResult.batchId)}
            onDone={reset}
          />
        )}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-components
// ─────────────────────────────────────────────────────────────────────────────

function IdleHero({ history, api, onUndo }) {
  return (
    <div className="org-hero">
      <div className="org-hero-emoji">🗂️</div>
      <div className="org-hero-title">Pick a folder to organize</div>
      <div className="org-hero-sub">
        Rudo will scan the folder, group related files, and propose a tidy
        layout. Nothing moves until you approve.
      </div>

      {history.length > 0 && (
        <div className="org-history">
          <div className="org-history-title">Recent reorganizations</div>
          {history.map(h => (
            <div className="org-history-row" key={h.batchId}>
              <div>
                <div className="org-history-id">{h.batchId.slice(0, 8)}…</div>
                <div className="org-history-meta">
                  {h.moveCount} move{h.moveCount === 1 ? '' : 's'} ·{' '}
                  {fmtRelative(h.executedAt)}
                </div>
              </div>
              <button className="btn btn-ghost btn-sm" onClick={() => onUndo(h.batchId)}>
                Undo
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function ScopeErrorPanel({ err, onRetry }) {
  return (
    <div className="org-scope-err">
      <div className="org-scope-err-title">{err.title}</div>
      <div className="org-scope-err-detail">{err.detail}</div>

      {err.suggestions && err.suggestions.length > 0 && (
        <div className="org-suggestions">
          <div className="org-suggestions-label">Try one of these instead:</div>
          {err.suggestions.map((s, i) => (
            <button
              key={i}
              className="org-suggestion"
              onClick={() => s.scopePath && onRetry(s.scopePath)}
              disabled={!s.scopePath}
              title={s.scopePath || ''}
            >
              {s.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

function ProposalPanel({
  proposal, summary, grouped, selected,
  onToggleMove, onToggleGroup, onApply, onCancel
}) {
  const total      = proposal.moves.length
  const numChecked = selected.size

  return (
    <div className="org-proposal">
      <ProposalStatsBar proposal={proposal} summary={summary} />

      {total === 0 ? (
        <div className="org-empty">
          <div>Nothing to reorganize — this folder already looks tidy.</div>
          <button className="btn btn-ghost" onClick={onCancel}>Pick another</button>
        </div>
      ) : (
        <>
          <div className="org-groups">
            {grouped.map(g => (
              <ProposalGroup
                key={g.toPath}
                group={g}
                selected={selected}
                onToggleMove={onToggleMove}
                onToggleGroup={(checked) => onToggleGroup(g, checked)}
              />
            ))}
          </div>

          <div className="org-actions">
            <button className="btn btn-ghost" onClick={onCancel}>Cancel</button>
            <button
              className="btn btn-primary"
              onClick={onApply}
              disabled={numChecked === 0}
            >
              Apply {numChecked} move{numChecked === 1 ? '' : 's'}
            </button>
          </div>

          {proposal.dropped.length > 0 && (
            <details className="org-dropped">
              <summary>{proposal.dropped.length} skipped (low confidence)</summary>
              <ul>
                {proposal.dropped.map((d, i) => (
                  <li key={i}>
                    <code>{basename(d.file)}</code> — {d.reason}
                  </li>
                ))}
              </ul>
            </details>
          )}

          {proposal.stoppedReason && (
            <div className="org-stopped">
              Note: {proposal.stoppedReason}{' '}
              ({proposal.llmCallsSuccessful}/{proposal.llmCallsAttempted} decisions completed)
            </div>
          )}
        </>
      )}
    </div>
  )
}

function ProposalStatsBar({ proposal, summary }) {
  if (!summary) return null
  return (
    <div className="org-stats-bar">
      <span className="org-stat"><b>{proposal.moves.length}</b> proposed moves</span>
      {summary.assignedToExisting > 0 && (
        <span className="org-stat"><b>{summary.assignedToExisting}</b> into existing folders</span>
      )}
      {summary.newClusters > 0 && (
        <span className="org-stat"><b>{summary.newClusters}</b> new groups</span>
      )}
      {proposal.leftAlone.length > 0 && (
        <span className="org-stat org-stat-muted"><b>{proposal.leftAlone.length}</b> left alone</span>
      )}
    </div>
  )
}

function ProposalGroup({ group, selected, onToggleMove, onToggleGroup }) {
  const checkedCount = group.moves.filter(m => selected.has(moveKey(m))).length
  const allChecked   = checkedCount === group.moves.length
  const someChecked  = checkedCount > 0 && !allChecked

  // Tri-state header checkbox
  const handleHeaderClick = () => onToggleGroup(!allChecked)

  return (
    <div className="org-group">
      <div className="org-group-header">
        <label className="org-checkbox">
          <input
            type="checkbox"
            checked={allChecked}
            ref={el => { if (el) el.indeterminate = someChecked }}
            onChange={handleHeaderClick}
          />
        </label>
        <div className="org-group-title">
          <span className={`org-group-icon${group.isNew ? ' new' : ''}`}>
            {group.isNew ? '+' : '→'}
          </span>
          <code>{basename(group.toPath)}/</code>
          {group.isNew && <span className="org-group-badge">new</span>}
        </div>
        <div className="org-group-count">
          {checkedCount}/{group.moves.length}
        </div>
      </div>
      <div className="org-group-body">
        {group.moves.map(m => (
          <ProposalRow
            key={moveKey(m)}
            move={m}
            checked={selected.has(moveKey(m))}
            onToggle={() => onToggleMove(moveKey(m))}
          />
        ))}
      </div>
    </div>
  )
}

function ProposalRow({ move, checked, onToggle }) {
  return (
    <label className={`org-row${checked ? '' : ' unchecked'}`}>
      <input type="checkbox" checked={checked} onChange={onToggle} />
      <div className="org-row-text">
        <code className="org-row-file">{basename(move.from)}</code>
        <div className="org-row-reason">{move.reason}</div>
      </div>
      <div className="org-row-conf">
        {(move.confidence * 100).toFixed(0)}%
      </div>
    </label>
  )
}

function ResultPanel({ result, onUndo, onDone }) {
  const okCount = result.successCount
  const total   = result.outcomes.length
  return (
    <div className="org-result">
      <div className={`org-result-title${result.failedCount > 0 ? ' warn' : ''}`}>
        {okCount === total
          ? `Done — moved all ${okCount} file${okCount === 1 ? '' : 's'}.`
          : `Moved ${okCount} of ${total}.`}
      </div>
      {result.failedCount > 0 || result.skippedCount > 0 ? (
        <ul className="org-result-list">
          {result.outcomes.filter(o => o.status !== 'SUCCESS').map((o, i) => (
            <li key={i}>
              <code>{basename(o.from)}</code> — <em>{o.status.toLowerCase()}</em>: {o.reason}
            </li>
          ))}
        </ul>
      ) : null}
      <div className="org-actions">
        <button className="btn btn-ghost" onClick={onUndo}>Undo</button>
        <button className="btn btn-primary" onClick={onDone}>Done</button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

function moveKey(m)   { return m.from + '→' + m.destinationFile }
function basename(p)  { if (!p) return ''; const ix = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\')); return ix >= 0 ? p.slice(ix + 1) : p }

function groupMovesByDestination(moves) {
  const byDest = new Map()
  for (const m of moves) {
    if (!byDest.has(m.to)) {
      byDest.set(m.to, { toPath: m.to, isNew: m.destinationIsNew, moves: [] })
    }
    byDest.get(m.to).moves.push(m)
  }
  // New folders first (they're the more visible change), then by name.
  return Array.from(byDest.values()).sort((a, b) => {
    if (a.isNew !== b.isNew) return a.isNew ? -1 : 1
    return basename(a.toPath).localeCompare(basename(b.toPath))
  })
}

function fmtRelative(iso) {
  if (!iso) return ''
  try {
    const sec = (Date.now() - new Date(iso)) / 1000
    if (sec < 60)    return 'just now'
    if (sec < 3600)  return `${Math.floor(sec / 60)}m ago`
    if (sec < 86400) return `${Math.floor(sec / 3600)}h ago`
    return new Date(iso).toLocaleDateString()
  } catch { return iso }
}
