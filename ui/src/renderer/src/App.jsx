import { useState, useEffect } from 'react'
import { AppProvider, useApp } from './context/AppContext'
import TitleBar       from './components/TitleBar'
import Sidebar        from './components/Sidebar'
import LoadingOverlay from './components/LoadingOverlay'
import ToastContainer from './components/Toast'
import UpdateBanner   from './components/UpdateBanner'
import WelcomeModal   from './components/WelcomeModal'
import SignInScreen   from './components/SignInScreen'
import SearchModal    from './components/SearchModal'
import ClientSuggestionModal from './components/ClientSuggestionModal'
import DeadlineReviewModal from './components/DeadlineReviewModal'
import AttentionModal  from './components/AttentionModal'
import Chat           from './views/Chat'
import Extract        from './views/Extract'
import BulkQA         from './views/BulkQA'
import Library        from './views/Library'
import Deadlines      from './views/Deadlines'
import Organize       from './views/Organize'
import Settings       from './views/Settings'

// ─────────────────────────────────────────────────────────────────────────────
// Inner shell (has access to context)
// ─────────────────────────────────────────────────────────────────────────────

function Shell() {
  const { setApiBase, setApiToken, setConnected, connected, auth } = useApp()
  const [loadMsg,   setLoadMsg]   = useState('Starting up…')
  const [loaded,    setLoaded]    = useState(false)
  const [view,      setView]      = useState('chat')
  const [searchOpen, setSearchOpen] = useState(false)
  const [attnOpen,  setAttnOpen]  = useState(false)
  const [collapsed, setCollapsed] = useState(
    // Remember the user's preference between sessions
    () => localStorage.getItem('rudo.sidebar.collapsed') === 'true'
  )

  // Non-blocking backend-supervisor notice (restarting / restarted / down).
  const [backendNotice, setBackendNotice] = useState(null)

  // ⌘K / Ctrl+K → global chat search palette.
  useEffect(() => {
    const onKey = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setSearchOpen(true)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  // Backend supervisor lifecycle. The supervisor re-sends `api-port` after a
  // successful restart, so the ApiClient reconnects automatically (see the
  // onApiPort effect below); here we only surface a non-blocking notice and
  // reflect connectivity in the header dot.
  useEffect(() => {
    const E = window.electron
    if (!E?.onBackendStatus) return
    let clearTimer = null
    const dispose = E.onBackendStatus((s) => {
      if (clearTimer) { clearTimeout(clearTimer); clearTimer = null }
      switch (s?.state) {
        case 'restarting':
          setConnected(false)
          setBackendNotice({ tone: 'warn', text: 'The backend stopped — reconnecting…' })
          break
        case 'restarted':
        case 'ready':
          setBackendNotice({ tone: 'ok', text: 'Backend reconnected.' })
          clearTimer = setTimeout(() => setBackendNotice(null), 4000)
          break
        case 'failed':
          setConnected(false)
          setBackendNotice({ tone: 'error', text: s.message || 'The backend is unavailable.' })
          break
        case 'stopped':
          setBackendNotice(null)
          break
        default:
          break
      }
    })
    return () => { if (clearTimer) clearTimeout(clearTimer); dispose?.() }
  }, [setConnected])

  function toggleCollapsed() {
    setCollapsed(c => {
      const next = !c
      try { localStorage.setItem('rudo.sidebar.collapsed', String(next)) } catch {}
      return next
    })
  }

  async function initBackend({ port, token }) {
    const base = `http://localhost:${port}`
    setApiToken(token || null)
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
    if (!E) {
      // Dev fallback — open the HTML directly in a browser (no token; the
      // backend only enforces one when launched by the Electron main process).
      initBackend({ port: 9876 })
      return
    }
    // Payload is { port, token } sent by the Electron main process.
    return E.onApiPort(payload => initBackend(payload || {})) // disposer removes the listener
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  // Gate: until auth is checked AND the user is signed in, don't show the app.
  // (auth bootstrap runs in AppContext; auth.checked flips once it resolves.)
  const showSignIn = auth.checked && !auth.registered

  return (
    <>
      {/* BackgroundFX (neon constellation) retired — the paper aesthetic uses
          a static dashed-grid backdrop painted by body::before in index.css. */}
      <LoadingOverlay visible={!loaded || !auth.checked} msg={loadMsg} />

      {showSignIn ? (
        <div className="app">
          <TitleBar connected={connected} />
          <SignInScreen />
        </div>
      ) : (
        <div className="app">
          <TitleBar connected={connected} />
          <UpdateBanner />
          <div className={`main-area${collapsed ? ' sidebar-collapsed' : ''}`}>
            <Sidebar
              active={view}
              onNav={setView}
              connected={connected}
              collapsed={collapsed}
              onToggle={toggleCollapsed}
              onOpenSearch={() => setSearchOpen(true)}
              onOpenAttention={() => setAttnOpen(true)}
            />
            <div className="content">
              <Chat      active={view === 'chat'} />
              <Extract   active={view === 'extract'} />
              <BulkQA    active={view === 'bulkqa'} />
              <Library   active={view === 'lib'}  onGoSettings={() => setView('set')} />
              <Deadlines active={view === 'due'} />
              <Organize  active={view === 'org'} />
              <Settings active={view === 'set'} onGoLibrary={() => setView('lib')} />
            </div>
          </div>
        </div>
      )}

      {!showSignIn && searchOpen && (
        <SearchModal
          onClose={() => setSearchOpen(false)}
          onNavigate={() => setView('chat')}
        />
      )}

      {!showSignIn && (
        <AttentionModal
          open={attnOpen}
          onClose={() => setAttnOpen(false)}
          onGoDeadlines={() => setView('due')}
        />
      )}

      {backendNotice && (
        <div
          role="status"
          style={{
            position: 'fixed', left: '50%', bottom: 20, transform: 'translateX(-50%)',
            zIndex: 9999, maxWidth: '90vw', padding: '10px 16px', borderRadius: 10,
            font: '500 13px system-ui, sans-serif', color: '#2a2118',
            boxShadow: '0 6px 24px rgba(0,0,0,0.18)',
            border: '1px solid rgba(0,0,0,0.08)',
            background: backendNotice.tone === 'error' ? '#f6d4cf'
                      : backendNotice.tone === 'warn'  ? '#f3e6c8'
                      : '#d7ead2',
          }}
        >
          {backendNotice.text}
        </div>
      )}

      <ToastContainer />
      {!showSignIn && <WelcomeModal />}
      {!showSignIn && <ClientSuggestionModal />}
      {!showSignIn && <DeadlineReviewModal onNavigate={() => setView('due')} />}
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
