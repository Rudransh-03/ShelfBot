import { useState, useEffect, useCallback } from 'react'
import { useApp } from '../context/AppContext'
import BookshelfIcon from './BookshelfIcon'

const STORAGE_KEY = 'shelfbot.welcomed.v1'

// ── Icons ───────────────────────────────────────────────────────────────────
const FolderIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
  </svg>
)
const PlusIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <line x1="12" y1="5"  x2="12" y2="19"/>
    <line x1="5"  y1="12" x2="19" y2="12"/>
  </svg>
)
const TrashIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="3 6 5 6 21 6"/>
    <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>
  </svg>
)
const CheckIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="20 6 9 17 4 12"/>
  </svg>
)
const SparkIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <path d="M12 2v6M12 16v6M2 12h6M16 12h6"/>
  </svg>
)

// ── Modal ───────────────────────────────────────────────────────────────────

export default function WelcomeModal() {
  const { api, connected, triggerIndex, toast } = useApp()
  const [open, setOpen]       = useState(false)
  const [step, setStep]       = useState(0)
  const [folders, setFolders] = useState([])
  const [region, setRegion]   = useState('IN')
  const [busy, setBusy]       = useState(false)

  // Decide whether to show. We wait for the backend to be reachable so the
  // defaults endpoint resolves correctly.
  useEffect(() => {
    if (!connected || !api) return
    if (localStorage.getItem(STORAGE_KEY) === 'true') return

    let cancelled = false
    ;(async () => {
      let existing = []
      try {
        const cfg = await api.getConfig()
        existing = Array.isArray(cfg.rootPaths) ? cfg.rootPaths : []
      } catch { /* fall through to suggestions */ }
      if (cancelled) return

      if (existing.length > 0) {
        setFolders(existing)
      } else {
        // Fresh install → pre-select the user's usual document folders (the ones
        // that actually exist) so they can index in one click. They can remove
        // any before starting.
        try {
          const suggested = (await window.electron?.suggestFolders?.()) || []
          if (!cancelled) setFolders(suggested)
        } catch { /* leave empty; user can add manually */ }
      }
      if (!cancelled) setOpen(true)
    })()

    return () => { cancelled = true }
  }, [connected, api])

  const close = useCallback((markComplete) => {
    if (markComplete) localStorage.setItem(STORAGE_KEY, 'true')
    setOpen(false)
  }, [])

  const addFolder = async () => {
    const E = window.electron
    if (!E?.selectFolder) {
      toast('Folder picker only available in the desktop app', 'i')
      return
    }
    const p = await E.selectFolder()
    if (!p) return
    if (folders.includes(p)) { toast('Already added', 'i'); return }
    setFolders([...folders, p])
  }

  const removeFolder = (path) => setFolders(folders.filter(p => p !== path))

  const finish = async () => {
    if (folders.length === 0) {
      toast('Add at least one folder to continue', 'e')
      return
    }
    setBusy(true)
    try {
      await api.saveConfig(folders)
      try { await api.saveRegion(region) } catch { /* non-fatal; can set later in Settings */ }
      close(true)
      // Kick indexing off so the user immediately sees the progress bar
      // instead of an empty Library view.
      setTimeout(() => triggerIndex(), 250)
    } catch (e) {
      toast(e.message, 'e')
    } finally {
      setBusy(false)
    }
  }

  if (!open) return null

  return (
    <div className="welcome-overlay" role="dialog" aria-modal="true">
      <div className="welcome-card">
        <div className="welcome-steps">
          {[0, 1, 2].map(i => (
            <div key={i} className={`welcome-dot${i === step ? ' active' : ''}${i < step ? ' done' : ''}`} />
          ))}
        </div>

        {step === 0 && (
          <div className="welcome-step">
            <div className="welcome-hero">
              <BookshelfIcon size={36} />
            </div>
            <h2 className="welcome-title">Welcome to Rudo</h2>
            <p className="welcome-sub">
              Ask questions about the files on your device — resumes, notes, PDFs, anything —
              and Rudo finds the answer across all of them, instantly.
            </p>
            <div className="welcome-actions">
              <button className="btn-primary welcome-next" onClick={() => setStep(1)}>
                Get started
              </button>
              <button className="welcome-skip" onClick={() => close(true)}>
                I’ll set this up later
              </button>
            </div>
          </div>
        )}

        {step === 1 && (
          <div className="welcome-step">
            <h2 className="welcome-title">Folders to index</h2>
            <p className="welcome-sub">
              {folders.length > 0
                ? 'We’ve added your usual document folders. Remove any you don’t want, or add more — you can change this anytime in Settings.'
                : 'Add the folders whose files you want Rudo to read (and their subfolders). You can change this anytime in Settings.'}
            </p>

            <div className="welcome-region">
              <label className="form-lbl" htmlFor="welcome-region">Your region</label>
              <select id="welcome-region" className="region-select"
                      value={region} onChange={e => setRegion(e.target.value)}>
                <option value="US">United States ($)</option>
                <option value="UK">United Kingdom (£)</option>
                <option value="EU">Ireland / Euro (€)</option>
                <option value="IN">India (₹)</option>
              </select>
            </div>

            {folders.length === 0 ? (
              <div className="welcome-empty">No folders chosen yet</div>
            ) : (
              <ul className="welcome-folders">
                {folders.map(p => (
                  <li key={p} className="welcome-folder">
                    <FolderIcon />
                    <span className="welcome-folder-path" title={p}>{p}</span>
                    <button
                      className="welcome-folder-del"
                      onClick={() => removeFolder(p)}
                      aria-label={`Remove ${p}`}
                    >
                      <TrashIcon />
                    </button>
                  </li>
                ))}
              </ul>
            )}

            <button className="browse-btn welcome-add" onClick={addFolder}>
              <PlusIcon />
              Add folder
            </button>

            <div className="welcome-actions">
              <button className="welcome-back" onClick={() => setStep(0)}>Back</button>
              <button
                className="btn-primary welcome-next"
                onClick={() => setStep(2)}
                disabled={folders.length === 0}
              >
                Continue
              </button>
            </div>
          </div>
        )}

        {step === 2 && (
          <div className="welcome-step">
            <div className="welcome-hero subtle">
              <SparkIcon />
            </div>
            <h2 className="welcome-title">A few things to know</h2>

            <div className="welcome-bullets">
              <div className="welcome-bullet">
                <CheckIcon />
                <span>
                  Indexing usually takes <strong>under a minute</strong> for typical libraries.
                  You can keep using the app while it runs.
                </span>
              </div>
              <div className="welcome-bullet">
                <CheckIcon />
                <span>
                  Rudo watches your folders and stays in sync automatically as files change.
                </span>
              </div>
              <div className="welcome-bullet">
                <CheckIcon />
                <span>
                  You're on a <strong>free trial</strong> — ask away. Your files
                  are read locally and never leave your device.
                </span>
              </div>
            </div>

            <div className="welcome-actions">
              <button className="welcome-back" onClick={() => setStep(1)}>Back</button>
              <button className="btn-primary welcome-next" onClick={finish} disabled={busy}>
                {busy ? 'Starting…' : 'Start indexing'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
