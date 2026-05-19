import { useState, useEffect } from 'react'
import { AppProvider, useApp } from './context/AppContext'
import TitleBar       from './components/TitleBar'
import Sidebar        from './components/Sidebar'
import LoadingOverlay from './components/LoadingOverlay'
import ToastContainer from './components/Toast'
import Chat           from './views/Chat'
import Library        from './views/Library'
import Settings       from './views/Settings'

// ─────────────────────────────────────────────────────────────────────────────
// Inner shell (has access to context)
// ─────────────────────────────────────────────────────────────────────────────

function Shell() {
  const { setApiBase, setConnected, connected } = useApp()
  const [loadMsg, setLoadMsg] = useState('Starting up…')
  const [loaded,  setLoaded]  = useState(false)
  const [view,    setView]    = useState('chat')

  async function initBackend(port) {
    const base = `http://localhost:${port}`
    setApiBase(base)
    setLoadMsg('Connecting to backend…')

    for (let i = 0; i < 50; i++) {
      try {
        await fetch(base + '/api/health')
        setConnected(true)
        setLoaded(true)
        return
      } catch {}
      await new Promise(r => setTimeout(r, 400))
    }
    setLoadMsg('⚠️  Backend unreachable. Make sure `mvn package` has been run inside backend/.')
    setLoaded(true) // show UI anyway so user can see settings
  }

  useEffect(() => {
    const E = window.electron
    if (E) {
      E.onApiPort(port => initBackend(port))
    } else {
      // Dev fallback — open the HTML directly in a browser
      initBackend(9876)
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <>
      <LoadingOverlay visible={!loaded} msg={loadMsg} />

      <div className="app">
        <TitleBar connected={connected} />
        <div className="main-area">
          <Sidebar
            active={view}
            onNav={setView}
            connected={connected}
          />
          <div className="content">
            <Chat    active={view === 'chat'} />
            <Library active={view === 'lib'}  onGoSettings={() => setView('set')} />
            <Settings active={view === 'set'} />
          </div>
        </div>
      </div>

      <ToastContainer />
    </>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Root — wraps everything in the provider
// ─────────────────────────────────────────────────────────────────────────────

export default function App() {
  return (
    <AppProvider>
      <Shell />
    </AppProvider>
  )
}
