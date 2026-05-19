import { useState, useEffect } from 'react'
import { useApp } from '../context/AppContext'
import BookshelfIcon from '../components/BookshelfIcon'

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

  // Port display extracted from apiBase (http://localhost:PORT)
  const port = apiBase?.match(/:(\d+)$/)?.[1] ?? '—'

  return (
    <div className={`view${active ? ' active' : ''}`} id="view-set">
      <div className="view-header">
        <h1 className="view-title">Settings</h1>
      </div>

      <div className="settings-body">
        {/* Root path */}
        <div className="scard">
          <div className="scard-title">Files Root Path</div>
          <div className="form-group">
            <label className="form-lbl">Documents Folder</label>
            <div className="row-with-btn">
              <input
                className="txt-input"
                type="text"
                placeholder="/path/to/your/documents"
                value={rootPath}
                onChange={e => setRootPath(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && savePath()}
              />
              <button className="browse-btn" onClick={browse}>📁 Browse</button>
            </div>
            <div className="form-hint">
              ShelfBot recursively indexes all supported files (PDF, DOCX, TXT, MD, XLSX, …) in this folder.
            </div>
          </div>
          <button className="btn-primary" onClick={savePath} disabled={!connected}>
            Save Path
          </button>
        </div>

        {/* Services */}
        <div className="scard">
          <div className="scard-title">Services</div>
          <div className="svc-list">
            <div className="svc-row">
              <span className="svc-name">API Server</span>
              <span className="svc-val">
                <span className={`dot ${connected ? 'g' : 'r'}`} />
                localhost:{port}
              </span>
            </div>
            <div className="svc-row">
              <span className="svc-name">ChromaDB</span>
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
          <div className="about-inner">
            <div className="about-logo">
              <BookshelfIcon size={26} />
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
