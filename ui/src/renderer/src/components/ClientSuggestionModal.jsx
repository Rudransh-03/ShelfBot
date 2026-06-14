import { useEffect, useRef, useState } from 'react'
import { useApp } from '../context/AppContext'

/**
 * Prompts the user to confirm the clients Rudo auto-detected in their documents,
 * so they don't have to dig into Settings. Pops once per newly-seen suggestion
 * set — on connect and whenever a deadline scan finishes (that's what extracts
 * document owners). Add → creates the client + tags its files; Dismiss → hides it.
 */
export default function ClientSuggestionModal() {
  const { api, connected, deadlineStats, toast, autoModalOpen, setAutoModalOpen } = useApp()
  const [suggestions, setSuggestions] = useState([])
  const [open, setOpen] = useState(false)
  const [pending, setPending] = useState(false)
  const [busy, setBusy] = useState(false)
  const shownKeys = useRef(new Set()) // keys already auto-shown this session

  // Closing releases the shared auto-modal slot so a queued pop-up can show.
  const close = () => { setOpen(false); setAutoModalOpen(false) }

  useEffect(() => {
    if (!api || !connected) return
    api.listClientSuggestions().then(d => {
      const s = d.suggestions ?? []
      setSuggestions(s)
      // Queue an auto-open when there's a suggestion we haven't shown this session.
      if (s.length > 0 && s.some(x => !shownKeys.current.has(x.key))) {
        setPending(true)
        s.forEach(x => shownKeys.current.add(x.key))
      }
    }).catch(() => {})
  }, [api, connected, deadlineStats])

  // Open only when the shared auto-modal slot is free (queue, don't overlap).
  useEffect(() => {
    if (pending && suggestions.length > 0 && !open && !autoModalOpen) {
      setOpen(true); setAutoModalOpen(true); setPending(false)
    }
  }, [pending, suggestions, open, autoModalOpen])

  // If the list empties while open (all added/dismissed), free the slot.
  useEffect(() => {
    if (open && suggestions.length === 0) { setOpen(false); setAutoModalOpen(false) }
  }, [open, suggestions, setAutoModalOpen])

  if (!open || suggestions.length === 0) return null

  const accept = async (s) => {
    setBusy(true)
    try {
      await api.acceptClientSuggestion({ name: s.name, gstin: s.gstin, pan: s.pan })
      setSuggestions(list => list.filter(x => x.key !== s.key))
      toast(`Added ${s.name}`, 's')
    } catch (e) { toast(e.message, 'e') } finally { setBusy(false) }
  }
  const dismiss = async (s) => {
    setBusy(true)
    try {
      await api.dismissClientSuggestion(s.key)
      setSuggestions(list => list.filter(x => x.key !== s.key))
    } catch (e) { toast(e.message, 'e') } finally { setBusy(false) }
  }
  const addAll = async () => {
    setBusy(true)
    try {
      // Defer the whole-library re-tag until all are added, then run it once.
      for (const s of [...suggestions]) {
        await api.acceptClientSuggestion({ name: s.name, gstin: s.gstin, pan: s.pan, recompute: false })
      }
      await api.recomputeClients()
      setSuggestions([]); close(); toast('Clients added', 's')
    } catch (e) { toast(e.message, 'e') } finally { setBusy(false) }
  }

  return (
    <div className="summary-overlay" onClick={close}>
      <div className="dl-modal" onClick={e => e.stopPropagation()}>
        <div className="dl-modal-head">
          <div className="dl-modal-title">Set up your clients</div>
          <button className="summary-close" onClick={close} aria-label="Close">✕</button>
        </div>
        <div className="dl-modal-sub">
          Rudo found these clients in your documents. Confirm the ones you want — each becomes its own
          workspace, so answers about one client never pull from another's files.
        </div>

        <ul className="clients-list">
          {suggestions.map(s => (
            <li className="client-row" key={s.key}>
              <div className="client-head">
                <span className="client-name">{s.name}</span>
                {s.gstin && <span className="client-id-chip">{s.gstin}</span>}
                {s.pan && <span className="client-id-chip">{s.pan}</span>}
                <span className="client-count">{s.fileCount} file{s.fileCount === 1 ? '' : 's'}</span>
                <div className="client-add-actions" style={{ marginLeft: 'auto' }}>
                  <button className="btn-primary" disabled={busy} onClick={() => accept(s)}>Add</button>
                  <button className="btn-ghost" disabled={busy} onClick={() => dismiss(s)}>Dismiss</button>
                </div>
              </div>
            </li>
          ))}
        </ul>

        <div className="dl-modal-actions">
          <button className="btn-ghost" onClick={close} disabled={busy}>Not now</button>
          <button className="btn-primary" onClick={addAll} disabled={busy || suggestions.length === 0}>Add all</button>
        </div>
      </div>
    </div>
  )
}
