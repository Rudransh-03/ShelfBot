import { useMemo, useState } from 'react'
import { buildExtractionReport, buildBulkQaReport, buildAnswerReport } from '../utils/reportTemplates'

// Report kinds available per source surface (Chat / Extract / Bulk Q&A).
const KINDS = {
  extract: [
    { key: 'extraction', label: 'Extraction Report' },
    { key: 'client', label: 'Client Summary' },
  ],
  bulkqa: [
    { key: 'bulkqa', label: 'Bulk Q&A Report' },
    { key: 'client', label: 'Client Summary' },
  ],
  chat: [
    { key: 'contract', label: 'Contract Summary' },
    { key: 'answer', label: 'Answer Report' },
  ],
}

const DEFAULT_TITLE = {
  extraction: 'Extraction Report',
  bulkqa: 'Bulk Q&A Report',
  client: 'Client Summary',
  contract: 'Contract Summary',
  answer: 'Summary',
}

function slug(s) {
  return (s || 'report').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'report'
}

/**
 * On-demand PDF report generator. `context` carries the data of the current
 * surface: { source, columns?, rows?, question?, answerText?, sources?, clientName? }.
 * Reuses the shared HTML report templates + the main-process printToPDF IPC.
 */
export default function ReportModal({ context, onClose, toast }) {
  const kinds = KINDS[context.source] || KINDS.chat
  const [kind, setKind] = useState(kinds[0].key)
  const [title, setTitle] = useState(DEFAULT_TITLE[kinds[0].key] || 'Report')
  const [busy, setBusy] = useState(false)

  const pickKind = (k) => { setKind(k); setTitle(DEFAULT_TITLE[k] || 'Report') }

  const html = useMemo(() => {
    const sub = context.clientName ? `Client: ${context.clientName}` : ''
    if (context.source === 'extract') {
      return buildExtractionReport({ title, subtitle: sub, columns: context.columns || [], rows: context.rows || [] })
    }
    if (context.source === 'bulkqa') {
      return buildBulkQaReport({ title, question: context.question, rows: context.rows || [] })
    }
    return buildAnswerReport({ title, subtitle: sub, answerText: context.answerText, sources: context.sources })
  }, [context, title])

  const generate = async () => {
    if (busy) return
    if (!window.electron?.generateReport) { toast?.('PDF export is only available in the desktop app', 'e'); return }
    setBusy(true)
    try {
      const suggestedName = `rudo-${slug(title)}-${new Date().toISOString().slice(0, 10)}.pdf`
      const res = await window.electron.generateReport({ html, suggestedName })
      if (res?.ok) { toast?.('Report saved', 's'); onClose() }
      else if (res && !res.canceled) toast?.(res.error || 'Could not generate report', 'e')
    } catch (e) {
      toast?.(e.message, 'e')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="rptm-overlay" onClick={onClose}>
      <div className="rptm-card" onClick={e => e.stopPropagation()}>
        <div className="rptm-head">
          <div className="rptm-title">Generate PDF report</div>
          <button className="rptm-close" onClick={onClose} aria-label="Close">✕</button>
        </div>
        <div className="rptm-body">
          <div className="rptm-label">Report type</div>
          <div className="rptm-kinds">
            {kinds.map(k => (
              <button key={k.key} className={`rptm-kind${kind === k.key ? ' on' : ''}`}
                onClick={() => pickKind(k.key)}>{k.label}</button>
            ))}
          </div>
          <div className="rptm-label">Title</div>
          <input className="rptm-input" value={title} onChange={e => setTitle(e.target.value)} />
          <div className="rptm-hint">Citations are preserved. Any ambiguous or low-confidence field is visibly flagged in the PDF.</div>
        </div>
        <div className="rptm-foot">
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" onClick={generate} disabled={busy}>{busy ? 'Generating…' : 'Generate PDF'}</button>
        </div>
      </div>
    </div>
  )
}
