import { useState, useEffect } from 'react'
import { AppProvider, useApp } from './context/AppContext'
import TitleBar       from './components/TitleBar'
import Sidebar        from './components/Sidebar'
import LoadingOverlay from './components/LoadingOverlay'
import ToastContainer from './components/Toast'
import UpdateBanner   from './components/UpdateBanner'
import WelcomeModal   from './components/WelcomeModal'
import Login          from './components/Login'
import Chat           from './views/Chat'
import Library        from './views/Library'
import Settings       from './views/Settings'

// ─────────────────────────────────────────────────────────────────────────────
// Inner shell (has access to context)
// ─────────────────────────────────────────────────────────────────────────────

function Shell() {
  const { setApiBase, setConnected, connected, auth } = useApp()
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
        <UpdateBanner />
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
      <WelcomeModal />
      {/* Gate the entire UI behind sign-in. Login renders on top of (but
          outside of) the main shell so even if the backend hasn't connected
          yet, the user can already start signing in. */}
      {auth.checked && !auth.authenticated && <Login />}
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
