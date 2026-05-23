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

export default function Settings({ active }) {
  const { api, connected, apiBase, toast, stats, auth, logout, refreshAuth } = useApp()

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
          <div className="view-subtitle">Configure ShelfBot to fit your workflow.</div>
        </div>
      </div>
      <div className="view-divider" />

      <div className="settings-body">
        {/* Indexed folders */}
        <div className="scard">
          <div className="scard-title">Indexed folders</div>
          <div className="scard-sub">
            ShelfBot recursively indexes all supported files
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
            Backend processes ShelfBot is connected to.
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
          </div>
        </div>

        {/* Account */}
        <div className="scard">
          <div className="scard-title">Account</div>
          <div className="scard-sub">
            Your sign-in unlocks the daily query quota on the proxy server.
          </div>
          <div className="svc-list">
            <div className="svc-row">
              <span className="svc-name">Signed in as</span>
              <span className="svc-val">
                <span className="dot g" />
                {auth?.email || '—'}
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
          </div>
          <button
            className="btn-ghost"
            style={{ marginTop: 14 }}
            onClick={async () => {
              await logout()
              toast('Signed out', 'i')
            }}
          >
            Sign out
          </button>
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
              <h3>ShelfBot <span className="ver-badge">v1.0.0</span></h3>
              <p>Ask anything. Your files have the answer.</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
