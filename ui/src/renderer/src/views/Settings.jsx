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

export default function Settings({ active }) {
  const { api, connected, apiBase, toast } = useApp()
  const [rootPath,  setRootPath]  = useState('')
  const [chromaUrl, setChromaUrl] = useState('—')

  useEffect(() => {
    if (!active || !connected || !api) return
    api.getConfig()
      .then(cfg => {
        setRootPath(cfg.rootPath  ?? '')
        setChromaUrl(cfg.chromaUrl ?? '—')
      })
      .catch(() => {})
  }, [active, connected, api])

  const browse = async () => {
    const E = window.electron
    if (E?.selectFolder) {
      const p = await E.selectFolder()
      if (p) setRootPath(p)
    } else {
      toast('Folder picker only available in the desktop app', 'i')
    }
  }

  const savePath = async () => {
    if (!rootPath.trim()) { toast('Please enter a path', 'e'); return }
    try {
      await api.saveConfig(rootPath.trim())
      toast('Path saved — remember to re-index!', 's')
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
        {/* Root path */}
        <div className="scard">
          <div className="scard-title">Documents folder</div>
          <div className="scard-sub">
            ShelfBot recursively indexes all supported files
            (PDF, DOCX, TXT, MD, XLSX, …) inside this folder.
          </div>

          <div className="form-group">
            <label className="form-lbl">Root path</label>
            <div className="row-with-btn">
              <input
                className="txt-input"
                type="text"
                placeholder="/path/to/your/documents"
                value={rootPath}
                onChange={e => setRootPath(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && savePath()}
              />
              <button className="browse-btn" onClick={browse}>
                <FolderIcon />
                Browse
              </button>
            </div>
          </div>
          <button className="btn-primary" onClick={savePath} disabled={!connected}>
            <SaveIcon />
            Save Path
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
                ChromaDB
              </span>
              <span className="svc-val">
                <span className="dot a" />
                {chromaUrl}
              </span>
            </div>
          </div>
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
