import { useState, useEffect } from 'react'
import { useApp } from '../context/AppContext'
import BookshelfIcon from '../components/BookshelfIcon'

const FolderIcon = ({ size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
  </svg>
)
const ServerIcon = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <rect x="2" y="3" width="20" height="7" rx="1.5"/>
    <rect x="2" y="14" width="20" height="7" rx="1.5"/>
    <line x1="6" y1="6.5" x2="6.01" y2="6.5"/>
    <line x1="6" y1="17.5" x2="6.01" y2="17.5"/>
  </svg>
)
const DatabaseIcon = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <ellipse cx="12" cy="5" rx="9" ry="3"/>
    <path d="M3 5v14a9 3 0 0 0 18 0V5"/>
    <path d="M3 12a9 3 0 0 0 18 0"/>
  </svg>
)
const SaveIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/>
    <polyline points="17 21 17 13 7 13 7 21"/>
    <polyline points="7 3 7 8 15 8"/>
  </svg>
)
const PlusIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <line x1="12" y1="5" x2="12" y2="19"/>
    <line x1="5" y1="12" x2="19" y2="12"/>
  </svg>
)
const CloseIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <line x1="18" y1="6" x2="6" y2="18"/>
    <line x1="6" y1="6" x2="18" y2="18"/>
  </svg>
)

// ── Clients (per-client document separation) ─────────────────────────────────
// Niche feature for people whose files span MULTIPLE clients (accountants,
// freelancers, bookkeepers). Auto-detection (free, local GSTIN/PAN scan) is the
// hero; manual add is the fallback for name-only clients. Stays dormant/quiet
// when there's nothing to show, so a single-person user isn't nagged.
function ClientsCard({ api, connected, toast }) {
  const [clients, setClients] = useState([])
  const [suggestions, setSuggestions] = useState([])
  const [names, setNames]     = useState({})   // per-suggestion typed name (key → name)
  const [newName, setNewName] = useState('')
  const [newIds, setNewIds]   = useState('')
  const [busy, setBusy]       = useState(false)
  const [scanning, setScanning] = useState(false)
  const [showManual, setShowManual] = useState(false)

  const load = () => {
    if (!api || !connected) return
    api.listClients().then(d => setClients(d.clients ?? [])).catch(() => {})
    api.listClientSuggestions().then(d => setSuggestions(d.suggestions ?? [])).catch(() => {})
  }
  useEffect(load, [api, connected]) // eslint-disable-line react-hooks/exhaustive-deps

  // A suggestion has no friendly name when the local scan only found an
  // identifier (EntitySuggester then falls back name→GSTIN/PAN). In that case
  // we ask the user to name it before adding.
  const isIdLike = (s) => s.name && (s.name === s.gstin || s.name === s.pan)
  const nameFor  = (s) => (names[s.key] !== undefined ? names[s.key] : (isIdLike(s) ? '' : s.name))

  const scan = async () => {
    setScanning(true)
    try {
      const r = await api.scanClients()
      load()
      toast(r.found > 0 ? `Found client identifiers in ${r.found} file${r.found === 1 ? '' : 's'}`
                        : 'No client identifiers (GSTIN/PAN) found in your files', r.found > 0 ? 's' : 'i')
    } catch (e) { toast(e.message, 'e') } finally { setScanning(false) }
  }

  const accept = async (s) => {
    const name = (nameFor(s) || '').trim()
    if (!name) { toast('Give this client a name first', 'i'); return }
    try { await api.acceptClientSuggestion({ name, gstin: s.gstin, pan: s.pan }); load(); toast('Client added', 's') }
    catch (e) { toast(e.message, 'e') }
  }
  const dismiss = async (key) => {
    try { await api.dismissClientSuggestion(key); load() } catch (e) { toast(e.message, 'e') }
  }

  const create = async () => {
    if (!newName.trim()) return
    setBusy(true)
    try {
      const ids = newIds.split(/[,\n]/).map(s => s.trim()).filter(Boolean)
      await api.createClient(newName.trim(), ids)
      setNewName(''); setNewIds(''); setShowManual(false); load()
      toast('Client added — tagged your files', 's')
    } catch (e) { toast(e.message, 'e') } finally { setBusy(false) }
  }

  const del = async (id) => {
    try { await api.deleteClient(id); load() } catch (e) { toast(e.message, 'e') }
  }
  const addId = async (id, value) => {
    if (!value.trim()) return
    try { await api.editClient(id, { addIdentifier: value.trim() }); load() } catch (e) { toast(e.message, 'e') }
  }
  const removeId = async (id, value) => {
    try { await api.editClient(id, { removeIdentifier: value }); load() } catch (e) { toast(e.message, 'e') }
  }

  const hasAny = clients.length > 0 || suggestions.length > 0

  return (
    <div className="scard">
      <div className="scard-title">Clients</div>
      <div className="scard-sub">
        If your files cover multiple people or businesses, Rudo can keep each one's
        documents separate — so a question about one client never pulls another's
        files. It finds them automatically from your documents; you can also add one
        yourself.
      </div>

      {/* Hero: auto-detected clients */}
      {suggestions.length > 0 && (
        <div className="client-suggest">
          <div className="client-suggest-head">Rudo found these in your files</div>
          <ul className="clients-list">
            {suggestions.map(s => (
              <li className="client-row suggest" key={s.key}>
                <div className="client-head">
                  <input
                    className="form-input"
                    style={{ maxWidth: 220 }}
                    placeholder="Name this client"
                    value={nameFor(s)}
                    onChange={e => setNames(n => ({ ...n, [s.key]: e.target.value }))}
                  />
                  {s.gstin && <span className="client-id-chip">{s.gstin}</span>}
                  {s.pan && !s.gstin && <span className="client-id-chip">{s.pan}</span>}
                  <span className="client-count">{s.fileCount} file{s.fileCount === 1 ? '' : 's'}</span>
                  <div className="client-add-actions" style={{ marginLeft: 'auto' }}>
                    <button className="btn-primary" onClick={() => accept(s)} disabled={busy}>Add</button>
                    <button className="btn-ghost" onClick={() => dismiss(s.key)} disabled={busy}>Dismiss</button>
                  </div>
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* Existing clients */}
      {clients.length > 0 && (
        <ul className="clients-list">
          {clients.map(c => (
            <li className="client-row" key={c.id}>
              <div className="client-head">
                <span className="client-name">{c.name}</span>
                <span className="client-count">{c.fileCount} file{c.fileCount === 1 ? '' : 's'}</span>
                <button className="path-remove-btn" onClick={() => del(c.id)} title="Delete client">✕</button>
              </div>
              <div className="client-ids">
                {(c.identifiers || []).map(v => (
                  <span className="client-id-chip" key={v}>
                    {v}<button onClick={() => removeId(c.id, v)} title="Remove identifier">×</button>
                  </span>
                ))}
                <input
                  className="client-id-input"
                  placeholder="+ add GSTIN / PAN / alias"
                  onKeyDown={e => { if (e.key === 'Enter') { addId(c.id, e.target.value); e.target.value = '' } }}
                />
              </div>
            </li>
          ))}
        </ul>
      )}

      {!hasAny && (
        <div className="paths-empty">
          No clients yet. Scan your files to detect them, or add one manually.
        </div>
      )}

      {/* Actions */}
      <div className="client-add-actions" style={{ marginTop: 12 }}>
        <button className="btn-primary" onClick={scan} disabled={scanning || !connected}>
          {scanning ? 'Scanning…' : 'Scan for clients'}
        </button>
        <button className="btn-ghost" onClick={() => setShowManual(v => !v)} disabled={!connected}>
          {showManual ? 'Cancel' : 'Add manually'}
        </button>
      </div>

      {/* Manual add (fallback) */}
      {showManual && (
        <div className="client-add" style={{ marginTop: 12 }}>
          <input className="form-input" placeholder="Client name (e.g. Sharma Bakery)"
                 value={newName} onChange={e => setNewName(e.target.value)} />
          <input className="form-input" placeholder="Match by (optional): GSTIN, PAN, or other names"
                 value={newIds} onChange={e => setNewIds(e.target.value)} />
          <div className="client-add-actions">
            <button className="btn-primary" onClick={create} disabled={busy || !connected || !newName.trim()}>Add client</button>
          </div>
        </div>
      )}
    </div>
  )
}

export default function Settings({ active, onGoLibrary }) {
  const { api, connected, apiBase, toast, stats, auth, refreshAuth, triggerIndex, signInWithGoogle } = useApp()
  const [signingIn, setSigningIn] = useState(false)

  const handleGoogleSignIn = async () => {
    if (signingIn) return
    setSigningIn(true)
    try {
      const r = await signInWithGoogle()
      if (r?.ok) toast('Signed in' + (r.account?.email ? ` as ${r.account.email}` : ''), 's')
      else if (r?.error) toast(r.error, 'e')
    } finally {
      setSigningIn(false)
    }
  }

  useEffect(() => {
    if (active) refreshAuth()
  }, [active, refreshAuth])
  const [rootPaths,       setRootPaths]       = useState([])
  const [vectorIndexPath, setVectorIndexPath] = useState('—')
  const [dirty,           setDirty]           = useState(false)

  const embeddingModel = stats?.embeddingModel ?? 'unknown'
  const embeddingIsLocal = embeddingModel.startsWith('local:')
  const embeddingLabel = embeddingIsLocal
    ? `On-device · ${embeddingModel.slice('local:'.length)}`
    : embeddingModel.startsWith('openai:')
      ? `OpenAI · ${embeddingModel.slice('openai:'.length)}`
      : embeddingModel

  useEffect(() => {
    if (!active || !connected || !api) return
    api.getConfig()
      .then(cfg => {
        // Prefer the new array field; fall back to the legacy single field.
        const paths = Array.isArray(cfg.rootPaths) && cfg.rootPaths.length
          ? cfg.rootPaths
          : (cfg.rootPath ? [cfg.rootPath] : [])
        setRootPaths(paths)
        setVectorIndexPath(cfg.vectorIndexPath ?? '—')
        setDirty(false)
      })
      .catch(() => {})
  }, [active, connected, api])

  const addFolder = async () => {
    const E = window.electron
    if (!E?.selectFolder) {
      toast('Folder picker only available in the desktop app', 'i')
      return
    }
    const p = await E.selectFolder()
    if (!p) return
    if (rootPaths.includes(p)) {
      toast('That folder is already added', 'i')
      return
    }
    setRootPaths([...rootPaths, p])
    setDirty(true)
  }

  const removeFolder = (path) => {
    setRootPaths(rootPaths.filter(p => p !== path))
    setDirty(true)
  }

  const savePaths = async () => {
    if (rootPaths.length === 0) {
      toast('Add at least one folder before saving', 'e')
      return
    }
    try {
      await api.saveConfig(rootPaths)
      setDirty(false)
      // Saving folders only matters once they're indexed, so kick off the
      // re-index right here — no separate "remember to re-index" step.
      // triggerIndex surfaces its own "Indexing started…" toast.
      triggerIndex()
      // Jump to Library so the user can watch indexing progress live.
      onGoLibrary?.()
    } catch (e) {
      toast(e.message, 'e')
    }
  }

  const port = apiBase?.match(/:(\d+)$/)?.[1] ?? '—'

  return (
    <div className={`view${active ? ' active' : ''}`} id="view-set">
      <div className="view-header">
        <div>
          <h1 className="view-title">Settings</h1>
          <div className="view-subtitle">Configure Rudo to fit your workflow.</div>
        </div>
      </div>
      <div className="view-divider" />

      <div className="settings-body">
        {/* Indexed folders */}
        <div className="scard">
          <div className="scard-title">Indexed folders</div>
          <div className="scard-sub">
            Rudo recursively indexes all supported files
            (PDF, DOCX, TXT, MD, XLSX, …) inside every folder listed below.
            Add as many as you need — your Desktop, Downloads, Documents, project folders, anything.
          </div>

          <div className="form-group">
            <label className="form-lbl">
              Folders ({rootPaths.length})
            </label>

            {rootPaths.length === 0 ? (
              <div className="paths-empty">
                No folders added yet. Click <em>Add folder</em> to choose one.
              </div>
            ) : (
              <ul className="paths-list">
                {rootPaths.map(path => (
                  <li className="path-row" key={path}>
                    <FolderIcon />
                    <span className="path-row-text" title={path}>{path}</span>
                    <button
                      className="path-remove-btn"
                      onClick={() => removeFolder(path)}
                      title="Remove this folder"
                      aria-label={`Remove ${path}`}
                    >
                      <CloseIcon />
                    </button>
                  </li>
                ))}
              </ul>
            )}

            <button className="browse-btn add-folder-btn" onClick={addFolder}>
              <PlusIcon />
              Add folder
            </button>
          </div>

          <button
            className="btn-primary"
            onClick={savePaths}
            disabled={!connected || !dirty || rootPaths.length === 0}
          >
            <SaveIcon />
            {dirty ? 'Save & re-index' : 'Saved'}
          </button>
        </div>

        {/* Client workspaces — advanced/niche, so it sits below the basics */}
        <ClientsCard api={api} connected={connected} toast={toast} />

        {/* Services */}
        <div className="scard">
          <div className="scard-title">Status</div>
          <div className="scard-sub">
            How Rudo is running on your device.
          </div>
          <div className="svc-list">
            <div className="svc-row">
              <span className="svc-name">
                <ServerIcon />
                Rudo engine
              </span>
              <span className="svc-val" title={`localhost:${port}`}>
                <span className={`dot ${connected ? 'g' : 'r'}`} />
                {connected ? 'Connected' : 'Offline'}
              </span>
            </div>
            <div className="svc-row">
              <span className="svc-name">
                <DatabaseIcon />
                Document storage
              </span>
              <span className="svc-val" title={vectorIndexPath}>
                <span className="dot g" />
                On your device
              </span>
            </div>
            <div className="svc-row">
              <span className="svc-name">
                <ServerIcon />
                Search model
              </span>
              <span className="svc-val" title={embeddingLabel}>
                <span className={`dot ${embeddingIsLocal ? 'g' : 'a'}`} />
                {embeddingIsLocal ? 'Runs on your device' : 'Cloud (OpenAI)'}
              </span>
            </div>
            <div className="svc-row">
              <span className="svc-name">
                <ServerIcon />
                Scanned-image search
              </span>
              <span
                className="svc-val"
                title={stats?.ocrAvailable
                  ? 'Tesseract is installed — images and scanned PDFs are searchable.'
                  : 'Install Tesseract (e.g. `brew install tesseract` on macOS) to make images and scanned PDFs searchable.'}
              >
                <span className={`dot ${stats?.ocrAvailable ? 'g' : 'a'}`} />
                {stats?.ocrAvailable ? 'Enabled' : 'Install Tesseract to enable'}
              </span>
            </div>
          </div>
        </div>

        {/* Plan */}
        <div className="scard">
          <div className="scard-title">Plan</div>
          <div className="scard-sub">
            {auth?.email
              ? 'Signed in with Google. Your plan is bound to your account.'
              : 'Sign in with Google to start your free trial.'}
          </div>
          <div className="svc-list">
            {auth?.email && (
              <div className="svc-row">
                <span className="svc-name">Account</span>
                <span className="svc-val">{auth.email}</span>
              </div>
            )}
            <div className="svc-row">
              <span className="svc-name">Tier</span>
              <span className="svc-val">
                <span className={`dot ${auth?.plan === 'pro' ? 'g' : 'a'}`} />
                {auth?.plan === 'pro' ? 'Pro' : auth?.plan === 'trial' ? 'Free trial' : 'Free'}
              </span>
            </div>
            {auth?.usage && (
              <div className="svc-row">
                <span className="svc-name">Today's queries</span>
                <span className="svc-val">
                  {auth.usage.used} / {auth.usage.limit}
                </span>
              </div>
            )}
            {auth?.trial && !auth.trial.active && (
              <div className="svc-row">
                <span className="svc-name">Trial</span>
                <span className="svc-val"><span className="dot a" />Ended — upgrade to continue</span>
              </div>
            )}
            {auth?.offline && (
              <div className="svc-row">
                <span className="svc-name">Connection</span>
                <span className="svc-val">
                  <span className="dot a" />
                  Proxy offline — using cached session
                </span>
              </div>
            )}
          </div>
          {!auth?.email ? (
            <button
              className="btn-primary"
              style={{ marginTop: 14 }}
              onClick={handleGoogleSignIn}
              disabled={signingIn}
            >
              {signingIn ? 'Opening browser…' : 'Sign in with Google'}
            </button>
          ) : auth?.plan !== 'pro' && (
            <button
              className="btn-primary"
              style={{ marginTop: 14 }}
              disabled
              title="Coming soon"
            >
              Upgrade to Pro
            </button>
          )}
        </div>

        {/* About */}
        <div className="scard">
          <div className="scard-title">About</div>
          <div className="scard-sub">Project information.</div>
          <div className="about-inner">
            <div className="about-logo">
              <BookshelfIcon size={28} color="#e8c995" />
            </div>
            <div className="about-info">
              <h3>Rudo <span className="ver-badge">v1.0.0</span></h3>
              <p>Ask anything. Your files have the answer.</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
