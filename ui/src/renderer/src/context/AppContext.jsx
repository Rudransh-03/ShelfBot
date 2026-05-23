import { createContext, useContext, useState, useCallback, useMemo, useEffect, useRef } from 'react'
import { ApiClient } from '../api/client'

const AppCtx = createContext(null)

// How often we refresh /api/status while the app is idle. The file watcher
// (item #5) will eventually update lastIndexed on its own, and this poll is
// what surfaces that change to the UI.
const IDLE_POLL_MS  = 30_000
// While an indexing job is running, poll /api/index more aggressively so the
// progress UI in Library and the "Indexing…" label in the Sidebar stay live.
const BUSY_POLL_MS  = 2_200

export function AppProvider({ children }) {
  const [apiBase,   setApiBase]   = useState(null)
  const [connected, setConnected] = useState(false)
  const [toasts,    setToasts]    = useState([])

  // Shared index + status state — consumed by Sidebar and Library
  const [stats,    setStats]    = useState(null)
  const [indexing, setIndexing] = useState(false)
  const [lastJob,  setLastJob]  = useState(null) // { ok, data?, msg? }
  const [progress, setProgress] = useState(null) // { processed, total, failed, currentFile }

  // Auth state
  const [auth, setAuth] = useState({ checked: false, authenticated: false, email: null, usage: null })

  const refreshAuth = useCallback(async () => {
    const E = window.electron
    if (!E?.authMe) { setAuth({ checked: true, authenticated: false }); return null }
    const r = await E.authMe()
    setAuth({
      checked: true,
      authenticated: !!r?.authenticated,
      email: r?.email ?? null,
      usage: r?.usage ?? null,
    })
    return r
  }, [])

  const login = useCallback(async (email) => {
    const E = window.electron
    if (!E?.authLogin) return { ok: false, error: 'Desktop app required' }
    const r = await E.authLogin(email)
    if (r.ok) await refreshAuth()
    return r
  }, [refreshAuth])

  const logout = useCallback(async () => {
    const E = window.electron
    if (E?.authLogout) await E.authLogout()
    setAuth({ checked: true, authenticated: false, email: null, usage: null })
  }, [])

  const busyTimer = useRef(null)
  const idleTimer = useRef(null)

  const toast = useCallback((msg, type = 'i', ms = 3600) => {
    const id = Date.now() + Math.random()
    setToasts(t => [...t, { id, msg, type }])
    setTimeout(() => setToasts(t => t.filter(x => x.id !== id)), ms)
  }, [])

  // Fresh ApiClient any time the base URL changes
  const api = useMemo(() => (apiBase ? new ApiClient(apiBase) : null), [apiBase])

  const loadStats = useCallback(async () => {
    if (!api) return null
    try {
      const d = await api.status()
      setStats(d)
      return d
    } catch {
      return null
    }
  }, [api])

  // Busy poll: while an indexing job is running, hit /api/index every couple
  // of seconds to detect completion. Cleared automatically when the job ends.
  const startBusyPolling = useCallback(() => {
    if (busyTimer.current) clearInterval(busyTimer.current)
    busyTimer.current = setInterval(async () => {
      if (!api) return
      try {
        const s = await api.pollIndex()
        // Surface per-file progress every tick so the bar moves smoothly.
        setProgress(s.progress ?? null)
        if (!s.running) {
          clearInterval(busyTimer.current)
          busyTimer.current = null
          setIndexing(false)
          setProgress(null)
          if (s.result) { setLastJob({ ok: true,  data: s.result }); toast('Indexing complete', 's') }
          if (s.error)  { setLastJob({ ok: false, msg:  s.error  }); toast('Indexing failed', 'e') }
          loadStats()
        }
      } catch {
        clearInterval(busyTimer.current)
        busyTimer.current = null
        setIndexing(false)
        setProgress(null)
      }
    }, BUSY_POLL_MS)
  }, [api, loadStats, toast])

  const triggerIndex = useCallback(async () => {
    if (!api || indexing) return
    try {
      setLastJob(null)
      await api.startIndex()
      setIndexing(true)
      toast('Indexing started…', 'i')
      startBusyPolling()
    } catch (e) {
      toast(e.message, 'e')
    }
  }, [api, indexing, startBusyPolling, toast])

  // On connect: fetch status immediately, then keep a slow background refresh
  // running so lastIndexed stays current (this is what the future file watcher
  // will piggyback on for free). The same tick also refreshes /me so the
  // daily-usage counter in Settings stays accurate without polling per query.
  useEffect(() => {
    if (!connected || !api) return
    loadStats()
    idleTimer.current = setInterval(() => {
      if (!indexing) loadStats()
      refreshAuth()
    }, IDLE_POLL_MS)
    return () => {
      if (idleTimer.current) clearInterval(idleTimer.current)
      idleTimer.current = null
    }
  }, [connected, api, loadStats, refreshAuth, indexing])

  // Boot: check whether we have a valid saved token before showing the UI.
  // Runs once after the API base is set so the bootstrap can also push the
  // active JWT into the Java backend before any query goes through.
  useEffect(() => {
    const E = window.electron
    if (!E?.authBootstrap) {
      setAuth({ checked: true, authenticated: false })
      return
    }
    E.authBootstrap().then(r => {
      setAuth({
        checked: true,
        authenticated: !!r?.authenticated,
        email: r?.email ?? null,
        usage: r?.usage ?? null,
      })
    })
  }, [])

  // On unmount: make sure the busy timer is also cleared
  useEffect(() => () => {
    if (busyTimer.current) clearInterval(busyTimer.current)
  }, [])

  return (
    <AppCtx.Provider value={{
      api, apiBase, setApiBase,
      connected, setConnected,
      toast, toasts,
      stats, indexing, progress, lastJob,
      loadStats, triggerIndex,
      auth, login, logout, refreshAuth,
    }}>
      {children}
    </AppCtx.Provider>
  )
}

export const useApp = () => useContext(AppCtx)
