import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useApp } from '../context/AppContext'
import { extractionToCsv } from '../utils/tableExport'
import ReportModal from '../components/ReportModal'

// ── Built-in, hardcoded, jurisdiction-neutral templates ─────────────────────
// No country-specific identifiers (no GSTIN/PAN/EIN/VAT/etc.) — those arrive
// later via the deferred Jurisdiction Packs system.
const FIELD_TYPES = ['text', 'number', 'currency', 'date', 'boolean']

const TEMPLATES = {
  invoice: {
    label: 'Invoice',
    fields: [
      { name: 'Invoice Number', type: 'text' },
      { name: 'Invoice Date', type: 'date' },
      { name: 'Vendor / Supplier', type: 'text' },
      { name: 'Customer', type: 'text' },
      { name: 'Currency', type: 'text', description: 'The currency the invoice is stated in' },
      { name: 'Total Amount', type: 'currency' },
      { name: 'Tax Amount', type: 'currency' },
      { name: 'Due Date', type: 'date' },
      { name: 'Payment Status', type: 'text' },
    ],
  },
  contract: {
    label: 'Contract',
    fields: [
      { name: 'Contract Title', type: 'text' },
      { name: 'Parties', type: 'text' },
      { name: 'Effective Date', type: 'date' },
      { name: 'Term / Duration', type: 'text' },
      { name: 'Renewal Type', type: 'text', description: 'e.g. auto-renewal, manual, none' },
      { name: 'Termination Notice', type: 'text' },
      { name: 'Governing Law', type: 'text' },
      { name: 'Total Value', type: 'currency' },
    ],
  },
  resume: {
    label: 'Resume',
    fields: [
      { name: 'Candidate Name', type: 'text' },
      { name: 'Email', type: 'text' },
      { name: 'Phone', type: 'text' },
      { name: 'Current Title', type: 'text' },
      { name: 'Years of Experience', type: 'number' },
      { name: 'Key Skills', type: 'text' },
      { name: 'Most Recent Employer', type: 'text' },
    ],
  },
  bank: {
    label: 'Bank Statement',
    fields: [
      { name: 'Account Holder', type: 'text' },
      { name: 'Account Number', type: 'text' },
      { name: 'Statement Period', type: 'text' },
      { name: 'Opening Balance', type: 'currency' },
      { name: 'Closing Balance', type: 'currency' },
      { name: 'Currency', type: 'text' },
    ],
  },
  custom: {
    label: 'Custom Fields',
    fields: [{ name: '', type: 'text' }],
  },
}

// Explicit currency choices — no locale/document inference, never a silent ₹.
const CURRENCIES = [
  { symbol: '₹', grouping: 'INDIAN', label: '₹ — Indian (₹15,00,000)' },
  { symbol: '$', grouping: 'WESTERN', label: '$ — US Dollar (1,500,000)' },
  { symbol: '£', grouping: 'WESTERN', label: '£ — Pound (1,500,000)' },
  { symbol: '€', grouping: 'WESTERN', label: '€ — Euro (1,500,000)' },
  { symbol: 'USD', grouping: 'WESTERN', label: 'USD — code prefix' },
  { symbol: '', grouping: 'WESTERN', label: 'No symbol — plain number' },
]
const CURRENCY_KEY = 'rudo.workspace.currency'

function loadDefaultCurrency() {
  try {
    const raw = localStorage.getItem(CURRENCY_KEY)
    return raw ? JSON.parse(raw) : null
  } catch { return null }
}

export default function Extract({ active }) {
  const { api, connected, toast } = useApp()

  const [files, setFiles] = useState([])
  const [filesLoading, setFilesLoading] = useState(false)
  const [fileFilter, setFileFilter] = useState('')
  const [selected, setSelected] = useState(() => new Set())

  const [templateKey, setTemplateKey] = useState('invoice')
  const [fields, setFields] = useState(() => TEMPLATES.invoice.fields.map(f => ({ ...f })))

  const [currency, setCurrency] = useState(loadDefaultCurrency)

  const [running, setRunning] = useState(false)
  const [progress, setProgress] = useState(null)   // {processed,total}
  const [result, setResult] = useState(null)        // {columns, rows, truncatedBatches}
  const [error, setError] = useState('')
  const [exported, setExported] = useState(false)
  const [reportOpen, setReportOpen] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const pollRef = useRef(null)

  // Load indexed files once connected.
  const loadFiles = useCallback(async () => {
    setFilesLoading(true)
    try {
      const res = await api.listFiles()
      setFiles(res.files ?? [])
    } catch (e) {
      toast(e.message, 'e')
    } finally {
      setFilesLoading(false)
    }
  }, [api, toast])

  useEffect(() => { if (connected) loadFiles() }, [connected, loadFiles])
  useEffect(() => () => { if (pollRef.current) clearTimeout(pollRef.current) }, [])

  const filtered = useMemo(() => {
    const q = fileFilter.trim().toLowerCase()
    return q ? files.filter(f => f.name.toLowerCase().includes(q)) : files
  }, [files, fileFilter])

  const hasCurrencyField = fields.some(f => f.type === 'currency')
  const validFields = fields.filter(f => f.name.trim())
  const canRun = connected && !running && selected.size > 0 && validFields.length > 0
    && (!hasCurrencyField || !!currency)

  // ── selection ──
  const toggleFile = (path) => setSelected(s => {
    const n = new Set(s); n.has(path) ? n.delete(path) : n.add(path); return n
  })
  const selectAllVisible = () => setSelected(s => {
    const n = new Set(s); filtered.forEach(f => n.add(f.path)); return n
  })
  const clearSelection = () => setSelected(new Set())

  // ── template + fields ──
  const chooseTemplate = (key) => {
    setTemplateKey(key)
    setFields(TEMPLATES[key].fields.map(f => ({ ...f })))
    setResult(null); setError('')
  }
  const updateField = (i, patch) => setFields(fs => fs.map((f, x) => x === i ? { ...f, ...patch } : f))
  const addField = () => setFields(fs => [...fs, { name: '', type: 'text' }])
  const removeField = (i) => setFields(fs => fs.filter((_, x) => x !== i))

  // ── currency ──
  const pickCurrency = (c) => setCurrency(c)
  const saveDefaultCurrency = () => {
    if (!currency) return
    try { localStorage.setItem(CURRENCY_KEY, JSON.stringify(currency)) } catch { /* ignore */ }
    toast('Saved as your workspace default currency', 's')
  }

  // ── run + poll ──
  const poll = useCallback(async () => {
    try {
      const s = await api.pollExtract()
      if (s.progress) setProgress(s.progress)
      if (s.running) {
        pollRef.current = setTimeout(poll, 1200)
        return
      }
      // finished
      setRunning(false); setProgress(null); setCancelling(false)
      if (s.error) { setError(s.error); toast(s.error, 'e'); return }
      if (s.done) {
        setResult({ columns: s.columns ?? [], rows: s.rows ?? [], truncatedBatches: s.truncatedBatches ?? 0, cancelled: !!s.cancelled })
        if (s.cancelled) toast(`Stopped — ${s.rows?.length ?? 0} document(s) processed`, 'i')
        else toast(`Extracted ${s.count ?? (s.rows?.length ?? 0)} document(s)`, 's')
      }
    } catch (e) {
      setRunning(false); setProgress(null); setError(e.message); toast(e.message, 'e')
    }
  }, [api, toast])

  const run = async () => {
    if (!canRun) return
    setError(''); setResult(null); setRunning(true); setProgress({ processed: 0, total: selected.size })
    try {
      const body = {
        paths: Array.from(selected),
        fields: validFields.map(f => ({
          name: f.name.trim(), type: f.type,
          description: f.description ?? '', required: !!f.required,
        })),
        options: currency ? { currency: { symbol: currency.symbol, grouping: currency.grouping } } : {},
      }
      const res = await api.startExtract(body)
      if (res?.error) { setRunning(false); setError(res.error); toast(res.error, 'e'); return }
      pollRef.current = setTimeout(poll, 900)
    } catch (e) {
      setRunning(false); setError(e.message); toast(e.message, 'e')
    }
  }

  // ── export ──
  const exportCsv = async () => {
    if (!result) return
    const csv = extractionToCsv(result.columns, result.rows)
    if (!csv) return
    const name = `rudo-extract-${new Date().toISOString().slice(0, 10)}.csv`
    try {
      if (window.electron?.exportFile) {
        const r = await window.electron.exportFile({ suggestedName: name, content: csv })
        if (!r?.ok) return
      } else {
        const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url; a.download = name
        document.body.appendChild(a); a.click(); a.remove()
        setTimeout(() => URL.revokeObjectURL(url), 0)
      }
      setExported(true); setTimeout(() => setExported(false), 1500)
    } catch { /* export unavailable */ }
  }

  const stop = async () => {
    setCancelling(true)
    try { await api.cancelExtract() } catch { /* the poll still resolves the run */ }
  }

  const openSource = (path) => { if (path) window.electron?.openPath?.(path) }

  return (
    <div className={`view ex-root${active ? ' active' : ''}`} id="view-extract">
      <div className="view-header">
        <div>
          <h1 className="view-title">Extract</h1>
          <div className="view-subtitle">Pull structured fields from your documents into a table.</div>
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
        {/* 1 — Select documents */}
        <section className="ex-card">
          <div className="ex-card-head">
            <span className="ex-step">01</span>
            <span className="ex-card-title">Select documents</span>
            <span className="ex-count">{selected.size} selected</span>
          </div>
          <div className="ex-select-bar">
            <input className="ex-filter" placeholder="Filter files…" value={fileFilter}
              onChange={e => setFileFilter(e.target.value)} />
            <button className="ex-linkbtn" onClick={selectAllVisible} disabled={!filtered.length}>Select all</button>
            <button className="ex-linkbtn" onClick={clearSelection} disabled={!selected.size}>Clear</button>
          </div>
          <div className="ex-filelist">
            {filesLoading && <div className="ex-empty">Loading files…</div>}
            {!filesLoading && filtered.length === 0 && <div className="ex-empty">No indexed files. Index a folder first (Library).</div>}
            {filtered.map(f => (
              <label key={f.path} className={`ex-filerow${selected.has(f.path) ? ' on' : ''}`}>
                <input type="checkbox" checked={selected.has(f.path)} onChange={() => toggleFile(f.path)} />
                <span className="ex-filename">{f.name}</span>
              </label>
            ))}
          </div>
        </section>

        {/* 2 — Choose template / fields */}
        <section className="ex-card">
          <div className="ex-card-head">
            <span className="ex-step">02</span>
            <span className="ex-card-title">Choose template</span>
          </div>
          <div className="ex-templates">
            {Object.entries(TEMPLATES).map(([key, t]) => (
              <button key={key} className={`ex-tpl${templateKey === key ? ' on' : ''}`}
                onClick={() => chooseTemplate(key)}>{t.label}</button>
            ))}
          </div>
          <div className="ex-fields">
            {fields.map((f, i) => (
              <div className="ex-field" key={i}>
                <input className="ex-field-name" placeholder="Field name" value={f.name}
                  onChange={e => updateField(i, { name: e.target.value })} />
                <select className="ex-field-type" value={f.type}
                  onChange={e => updateField(i, { type: e.target.value })}>
                  {FIELD_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                </select>
                <button className="ex-field-del" title="Remove field" onClick={() => removeField(i)}>✕</button>
              </div>
            ))}
            <button className="ex-linkbtn ex-addfield" onClick={addField}>+ Add field</button>
          </div>
        </section>

        {/* 3 — Options (currency; shown only when a currency field exists) */}
        {hasCurrencyField && (
          <section className="ex-card">
            <div className="ex-card-head">
              <span className="ex-step">03</span>
              <span className="ex-card-title">Currency</span>
              {!currency && <span className="ex-req">Required — choose a currency</span>}
            </div>
            <div className="ex-currencies">
              {CURRENCIES.map((c, i) => (
                <button key={i}
                  className={`ex-cur${currency && currency.symbol === c.symbol && currency.grouping === c.grouping ? ' on' : ''}`}
                  onClick={() => pickCurrency(c)}>{c.label}</button>
              ))}
            </div>
            {currency && (
              <button className="ex-linkbtn" onClick={saveDefaultCurrency}>Set as workspace default</button>
            )}
            <div className="ex-hint">Currency is applied exactly as chosen — never inferred from your files or system.</div>
          </section>
        )}

        {/* Run */}
        <div className="ex-run-row">
          <button className="btn-primary" onClick={run} disabled={!canRun}>
            {running ? 'Extracting…' : `Run extraction${selected.size ? ` (${selected.size})` : ''}`}
          </button>
          {running && (
            <button className="btn-ghost" onClick={stop} disabled={cancelling}>{cancelling ? 'Stopping…' : 'Stop'}</button>
          )}
          {running && progress && (
            <div className="ex-prog">
              <div className="ex-prog-bar"><div className="ex-prog-fill"
                style={{ width: `${progress.total ? (progress.processed / progress.total) * 100 : 0}%` }} /></div>
              <span className="ex-prog-txt">{progress.processed}/{progress.total}</span>
            </div>
          )}
          {!running && selected.size === 0 && <span className="ex-hint">Select at least one document.</span>}
          {!running && selected.size > 0 && validFields.length === 0 && <span className="ex-hint">Add at least one field.</span>}
          {!running && selected.size > 0 && validFields.length > 0 && hasCurrencyField && !currency &&
            <span className="ex-hint">Choose a currency for the currency field.</span>}
          {error && <span className="ex-error">{error}</span>}
        </div>

        {/* 4 — Results */}
        {result && (
          <section className="ex-card ex-results">
            <div className="ex-card-head">
              <span className="ex-step">04</span>
              <span className="ex-card-title">Results</span>
              <span className="ex-count">{result.rows.length} row(s)</span>
              {result.cancelled && (
                <span className="ex-warn-pill" title="You stopped this run early; only the documents processed so far are shown.">stopped early</span>
              )}
              {result.truncatedBatches > 0 && (
                <span className="ex-warn-pill" title="Some documents produced an unreadable model reply and are shown as blank rows.">
                  {result.truncatedBatches} batch(es) unreadable
                </span>
              )}
            </div>
            <div className="ex-table-wrap">
              <table className="ex-table">
                <thead>
                  <tr>
                    {result.columns.map(c => <th key={c.name}>{c.name}</th>)}
                    <th>Source</th>
                  </tr>
                </thead>
                <tbody>
                  {result.rows.map((r, ri) => (
                    <tr key={ri}>
                      {result.columns.map(col => {
                        const cell = (r.cells || []).find(c => c.name === col.name)
                        return <ExCell key={col.name} cell={cell} />
                      })}
                      <td className="ex-src">
                        <button className="ex-src-chip" onClick={() => openSource(r.absolutePath)}
                          title={r.absolutePath || r.fileName}>{r.fileName}</button>
                      </td>
                    </tr>
                  ))}
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
          context={{ source: 'extract', columns: result.columns, rows: result.rows }}
          toast={toast}
          onClose={() => setReportOpen(false)}
        />
      )}
    </div>
  )
}

// One result cell. Ambiguous → muted + marker + tooltip; missing → em dash.
function ExCell({ cell }) {
  if (!cell || cell.status === 'MISSING') return <td className="ex-cell ex-cell-missing">—</td>
  if (cell.status === 'AMBIGUOUS') {
    return (
      <td className="ex-cell ex-cell-ambiguous" title={cell.note || 'Ambiguous value'}>
        {cell.value} <span className="ex-amb-flag">⚠ ambiguous</span>
      </td>
    )
  }
  return <td className="ex-cell">{cell.value}</td>
}
