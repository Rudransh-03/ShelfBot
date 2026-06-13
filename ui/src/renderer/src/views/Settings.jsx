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

// ── Per-client workspaces management ─────────────────────────────────────────
function ClientsCard({ api, connected, toast }) {
  const [clients, setClients] = useState([])
  const [suggestions, setSuggestions] = useState([])
  const [newName, setNewName] = useState('')
  const [newIds, setNewIds]   = useState('')
  const [busy, setBusy]       = useState(false)

  const load = () => {
    if (!api || !connected) return
    api.listClients().then(d => setClients(d.clients ?? [])).catch(() => {})
    api.listClientSuggestions().then(d => setSuggestions(d.suggestions ?? [])).catch(() => {})
  }
  useEffect(load, [api, connected]) // eslint-disable-line react-hooks/exhaustive-deps

  const accept = async (s) => {
    try { await api.acceptClientSuggestion({ name: s.name, gstin: s.gstin, pan: s.pan }); load(); toast('Client added', 's') }
    catch (e) { toast(e.message, 'e') }
  }
  const dismiss = async (key) => {
    try { await api.dismissClientSuggestion(key); load() } catch (e) { toast(e.message, 'e') }
  }

  const create = async () => {
    if (!newName.trim()) return
    setBusy(true)
    try {
      // Identifiers: comma/newline separated GSTIN / PAN / names / aliases.
      const ids = newIds.split(/[,\n]/).map(s => s.trim()).filter(Boolean)
      await api.createClient(newName.trim(), ids)
      setNewName(''); setNewIds(''); load()
      toast('Client added — re-tagged your files', 's')
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
  const recompute = async () => {
    setBusy(true)
    try { const r = await api.recomputeClients(); load(); toast(`Re-tagged: ${r.assigned} assigned, ${r.conflicted} shared, ${r.unmatched} unmatched`, 's') }
    catch (e) { toast(e.message, 'e') } finally { setBusy(false) }
  }

  return (
    <div className="scard">
      <div className="scard-title">Client workspaces</div>
      <div className="scard-sub">
        Keep each client's documents isolated. Register a client with a unique
        identifier — their GSTIN, PAN, or exact name — and Rudo tags matching files
        to them. In chat, answers about one client never pull from another's files.
      </div>

      {suggestions.length > 0 && (
        <div className="client-suggest">
          <div className="client-suggest-head">Suggested from your documents</div>
          <ul className="clients-list">
            {suggestions.map(s => (
              <li className="client-row suggest" key={s.key}>
                <div className="client-head">
                  <span className="client-name">{s.name}</span>
                  {s.gstin && <span className="client-id-chip">{s.gstin}</span>}
                  {s.pan && <span className="client-id-chip">{s.pan}</span>}
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

      {clients.length === 0 ? (
        <div className="paths-empty">No clients yet. Add one below{suggestions.length ? ', or accept a suggestion above' : ''}.</div>
      ) : (
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

      <div className="client-add">
        <input className="form-input" placeholder="Client name (e.g. Sharma Bakery)"
               value={newName} onChange={e => setNewName(e.target.value)} />
        <input className="form-input" placeholder="Identifiers — GSTIN, PAN, aliases (comma-separated)"
               value={newIds} onChange={e => setNewIds(e.target.value)} />
        <div className="client-add-actions">
          <button className="btn-primary" onClick={create} disabled={busy || !connected || !newName.trim()}>Add client</button>
          <button className="btn-ghost" onClick={recompute} disabled={busy || !connected} title="Re-scan all files against the client list">Re-tag files</button>
        </div>
      </div>
    </div>
  )
}

export default function Settings({ active }) {
  const { api, connected, apiBase, toast, stats, auth, refreshAuth } = useApp()

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
      toast('Folders saved — remember to re-index!', 's')
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
        <ClientsCard api={api} connected={connected} toast={toast} />

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
            {dirty ? 'Save changes' : 'Saved'}
          </button>
        </div>

        {/* Services */}
        <div className="scard">
          <div className="scard-title">Services</div>
          <div className="scard-sub">
            Backend processes Rudo is connected to.
          </div>
          <div className="svc-list">
            <div className="svc-row">
              <span className="svc-name">
                <ServerIcon />
                API Server
              </span>
              <span className="svc-val">
                <span className={`dot ${connected ? 'g' : 'r'}`} />
                localhost:{port}
              </span>
            </div>
            <div className="svc-row">
              <span className="svc-name">
                <DatabaseIcon />
                Vector index
              </span>
              <span className="svc-val" title={vectorIndexPath}>
                <span className="dot g" />
                Embedded · {vectorIndexPath}
              </span>
            </div>
            <div className="svc-row">
              <span className="svc-name">
                <ServerIcon />
                Embedding model
              </span>
              <span className="svc-val" title={embeddingModel}>
                <span className={`dot ${embeddingIsLocal ? 'g' : 'a'}`} />
                {embeddingLabel}
              </span>
            </div>
            <div className="svc-row">
              <span className="svc-name">
                <ServerIcon />
                Image search (OCR)
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
            Your subscription is bound to this device. Upgrade to lift the daily query cap.
          </div>
          <div className="svc-list">
            <div className="svc-row">
              <span className="svc-name">Tier</span>
              <span className="svc-val">
                <span className={`dot ${auth?.plan === 'pro' ? 'g' : 'a'}`} />
                {auth?.plan === 'pro' ? 'Pro' : 'Free'}
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
          {auth?.plan !== 'pro' && (
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
