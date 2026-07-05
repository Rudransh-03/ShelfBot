import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useApp } from '../context/AppContext'
import { extractionToCsv } from '../utils/tableExport'
import ReportModal from '../components/ReportModal'

// Bulk Q&A = the Structured Extraction Engine with ONE field: the user's
// question. It calls the same /api/extract endpoint (no separate execution
// path) with a single TEXT field whose description is the question, and renders
// one row per document: Document | Answer | Source.
const ANSWER_FIELD = 'Answer'

function dirOf(path) {
  const i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
  return i > 0 ? path.slice(0, i) : path
}

export default function BulkQA({ active }) {
  const { api, connected, toast } = useApp()

  const [question, setQuestion] = useState('')
  const [files, setFiles] = useState([])
  const [clients, setClients] = useState([])
  const [scopeMode, setScopeMode] = useState('files') // files | client | folder | all
  const [selected, setSelected] = useState(() => new Set())
  const [clientId, setClientId] = useState('')
  const [folder, setFolder] = useState('')
  const [fileFilter, setFileFilter] = useState('')

  const [running, setRunning] = useState(false)
  const [progress, setProgress] = useState(null)
  const [result, setResult] = useState(null)   // {rows, columns}
  const [askedQuestion, setAskedQuestion] = useState('')
  const [error, setError] = useState('')
  const [exported, setExported] = useState(false)
  const [reportOpen, setReportOpen] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const pollRef = useRef(null)

  const load = useCallback(async () => {
    try {
      const [f, c] = await Promise.all([api.listFiles(), api.listClients().catch(() => ({ clients: [] }))])
      setFiles(Array.isArray(f?.files) ? f.files : [])
      setClients(Array.isArray(c?.clients) ? c.clients : (Array.isArray(c) ? c : []))
    } catch (e) { toast(e.message, 'e') }
  }, [api, toast])

  useEffect(() => { if (connected) load() }, [connected, load])
  useEffect(() => () => { if (pollRef.current) clearTimeout(pollRef.current) }, [])

  const folders = useMemo(() => {
    const set = new Set(files.map(f => dirOf(f.path)))
    return Array.from(set).sort()
  }, [files])

  const filtered = useMemo(() => {
    const q = fileFilter.trim().toLowerCase()
    return q ? files.filter(f => f.name.toLowerCase().includes(q)) : files
  }, [files, fileFilter])

  const toggleFile = (path) => setSelected(s => {
    const n = new Set(s); n.has(path) ? n.delete(path) : n.add(path); return n
  })
  const selectAllVisible = () => setSelected(s => { const n = new Set(s); filtered.forEach(f => n.add(f.path)); return n })
  const clearSelection = () => setSelected(new Set())

  const scopeReady =
    scopeMode === 'files' ? selected.size > 0 :
    scopeMode === 'client' ? !!clientId :
    scopeMode === 'folder' ? !!folder : true
  const canRun = connected && !running && question.trim().length > 0 && scopeReady

  const poll = useCallback(async () => {
    try {
      const s = await api.pollExtract()
      if (s.progress) setProgress(s.progress)
      if (s.running) { pollRef.current = setTimeout(poll, 1200); return }
      setRunning(false); setProgress(null); setCancelling(false)
      if (s.error) { setError(s.error); toast(s.error, 'e'); return }
      if (s.done) {
        setResult({ columns: s.columns ?? [], rows: s.rows ?? [] })
        if (s.cancelled) toast(`Stopped — ${s.rows?.length ?? 0} document(s) answered`, 'i')
        else toast(`Answered across ${s.count ?? (s.rows?.length ?? 0)} document(s)`, 's')
      }
    } catch (e) { setRunning(false); setProgress(null); setError(e.message); toast(e.message, 'e') }
  }, [api, toast])

  const run = async () => {
    if (!canRun) return
    setError(''); setResult(null); setRunning(true); setProgress(null)
    const q = question.trim()
    setAskedQuestion(q)
    const body = {
      fields: [{ name: ANSWER_FIELD, type: 'text', description: q, required: false }],
    }
    if (scopeMode === 'files') body.paths = Array.from(selected)
    else if (scopeMode === 'client') body.clientId = clientId
    else if (scopeMode === 'folder') body.folder = folder
    try {
      const res = await api.startExtract(body)
      if (res?.error) { setRunning(false); setError(res.error); toast(res.error, 'e'); return }
      pollRef.current = setTimeout(poll, 900)
    } catch (e) { setRunning(false); setError(e.message); toast(e.message, 'e') }
  }

  const exportCsv = async () => {
    if (!result) return
    const csv = extractionToCsv(result.columns, result.rows)
    if (!csv) return
    const name = `rudo-bulk-qa-${new Date().toISOString().slice(0, 10)}.csv`
    try {
      if (window.electron?.exportFile) {
        const r = await window.electron.exportFile({ suggestedName: name, content: csv }); if (!r?.ok) return
      } else {
        const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
        const url = URL.createObjectURL(blob); const a = document.createElement('a')
        a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove()
        setTimeout(() => URL.revokeObjectURL(url), 0)
      }
      setExported(true); setTimeout(() => setExported(false), 1500)
    } catch { /* ignore */ }
  }

  const stop = async () => {
    setCancelling(true)
    try { await api.cancelExtract() } catch { /* the poll still resolves the run */ }
  }

  const openSource = (p) => { if (p) window.electron?.openPath?.(p) }
  const clientName = clients.find(c => c.id === clientId)?.name

  return (
    <div className={`view ex-root${active ? ' active' : ''}`} id="view-bulkqa">
      <div className="view-header">
        <div>
          <h1 className="view-title">Bulk Q&amp;A</h1>
          <div className="view-subtitle">Ask one question across many documents — one answer per file.</div>
        </div>
        {result && (
          <div className="header-actions">
            <button className="btn-ghost" onClick={exportCsv}>{exported ? 'Exported ✓' : 'Export CSV'}</button>
            <button className="btn-ghost" onClick={() => setReportOpen(true)}>Export PDF</button>
          </div>
        )}
      </div>
      <div className="view-divider" />

      <div className="ex-body">
        {/* Question */}
        <section className="ex-card">
          <div className="ex-card-head"><span className="ex-step">01</span><span className="ex-card-title">Your question</span></div>
          <textarea className="ex-question" rows={2} value={question}
            onChange={e => setQuestion(e.target.value)}
            placeholder="e.g. Does this contract contain an auto-renewal clause?" />
        </section>

        {/* Scope */}
        <section className="ex-card">
          <div className="ex-card-head"><span className="ex-step">02</span><span className="ex-card-title">Documents</span></div>
          <div className="ex-templates">
            {[['files', 'Selected files'], ['client', 'By client'], ['folder', 'By folder'], ['all', 'All indexed']].map(([m, label]) => (
              <button key={m} className={`ex-tpl${scopeMode === m ? ' on' : ''}`} onClick={() => setScopeMode(m)}>{label}</button>
            ))}
          </div>

          {scopeMode === 'files' && (
            <>
              <div className="ex-select-bar">
                <input className="ex-filter" placeholder="Filter files…" value={fileFilter} onChange={e => setFileFilter(e.target.value)} />
                <button className="ex-linkbtn" onClick={selectAllVisible} disabled={!filtered.length}>Select all</button>
                <button className="ex-linkbtn" onClick={clearSelection} disabled={!selected.size}>Clear</button>
                <span className="ex-count">{selected.size} selected</span>
              </div>
              <div className="ex-filelist">
                {filtered.length === 0 && <div className="ex-empty">No indexed files. Index a folder first (Library).</div>}
                {filtered.map(f => (
                  <label key={f.path} className={`ex-filerow${selected.has(f.path) ? ' on' : ''}`}>
                    <input type="checkbox" checked={selected.has(f.path)} onChange={() => toggleFile(f.path)} />
                    <span className="ex-filename">{f.name}</span>
                  </label>
                ))}
              </div>
            </>
          )}
          {scopeMode === 'client' && (
            <select className="ex-field-type ex-scope-select" value={clientId} onChange={e => setClientId(e.target.value)}>
              <option value="">Choose a client…</option>
              {clients.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          )}
          {scopeMode === 'folder' && (
            <select className="ex-field-type ex-scope-select" value={folder} onChange={e => setFolder(e.target.value)}>
              <option value="">Choose a folder…</option>
              {folders.map(d => <option key={d} value={d}>{d}</option>)}
            </select>
          )}
          {scopeMode === 'all' && <div className="ex-hint">Every indexed document will be answered.</div>}
        </section>

        <div className="ex-run-row">
          <button className="btn-primary" onClick={run} disabled={!canRun}>{running ? 'Answering…' : 'Ask across documents'}</button>
          {running && (
            <button className="btn-ghost" onClick={stop} disabled={cancelling}>{cancelling ? 'Stopping…' : 'Stop'}</button>
          )}
          {!running && question.trim().length === 0 && <span className="ex-hint">Type a question.</span>}
          {!running && question.trim().length > 0 && !scopeReady && <span className="ex-hint">Choose documents to ask across.</span>}
          {running && progress && (
            <div className="ex-prog">
              <div className="ex-prog-bar"><div className="ex-prog-fill" style={{ width: `${progress.total ? (progress.processed / progress.total) * 100 : 0}%` }} /></div>
              <span className="ex-prog-txt">{progress.processed}/{progress.total}</span>
            </div>
          )}
          {error && <span className="ex-error">{error}</span>}
        </div>

        {result && (
          <section className="ex-card ex-results">
            <div className="ex-card-head">
              <span className="ex-step">03</span><span className="ex-card-title">Answers</span>
              <span className="ex-count">{result.rows.length} document(s)</span>
            </div>
            <div className="ex-table-wrap">
              <table className="ex-table">
                <thead><tr><th>Document</th><th>Answer</th><th>Source</th></tr></thead>
                <tbody>
                  {result.rows.map((r, ri) => {
                    const cell = r.cells?.[0]
                    return (
                      <tr key={ri}>
                        <td className="ex-cell">{r.fileName}</td>
                        <BulkCell cell={cell} />
                        <td className="ex-src"><button className="ex-src-chip" onClick={() => openSource(r.absolutePath)} title={r.absolutePath}>{r.fileName}</button></td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
            <div className="ex-export-row">
              <button className="btn-ghost" onClick={exportCsv}>{exported ? 'Exported ✓' : 'Export CSV / Excel'}</button>
              <button className="btn-primary" onClick={() => setReportOpen(true)}>Export PDF report</button>
            </div>
          </section>
        )}
      </div>

      {reportOpen && result && (
        <ReportModal
          context={{ source: 'bulkqa', question: askedQuestion, rows: result.rows, clientName }}
          toast={toast}
          onClose={() => setReportOpen(false)}
        />
      )}
    </div>
  )
}

function BulkCell({ cell }) {
  if (!cell || cell.status === 'MISSING') return <td className="ex-cell ex-cell-missing">— not found in this document</td>
  if (cell.status === 'AMBIGUOUS') return <td className="ex-cell ex-cell-ambiguous" title={cell.note}>{cell.value} <span className="ex-amb-flag">⚠ ambiguous</span></td>
  return <td className="ex-cell">{cell.value}</td>
}
