import { createContext, useContext, useState, useCallback, useMemo, useEffect, useRef } from 'react'
import { ApiClient } from '../api/client'

const AppCtx = createContext(null)

// How often we refresh /api/status while the app is idle. The file watcher
// (item #5) will eventually update lastIndexed on its own, and this poll is
// what surfaces that change to the UI.
const IDLE_POLL_MS  = 30_000
// While an indexing job is running, poll /api/index more aggressively so the
// progress UI in Library and the "Indexing…" label in the Sidebar stay live.
const BUSY_POLL_MS  = 1_200

export function AppProvider({ children }) {
  const [apiBase,   setApiBase]   = useState(null)
  const [connected, setConnected] = useState(false)
  const [toasts,    setToasts]    = useState([])

  // Shared index + status state — consumed by Sidebar and Library
  const [stats,    setStats]    = useState(null)
  const [indexing, setIndexing] = useState(false)
  const [lastJob,  setLastJob]  = useState(null) // { ok, data?, msg? }
  const [progress, setProgress] = useState(null) // { processed, total, failed, currentFile }
  const [activeFiles, setActiveFiles] = useState([]) // [{ name, stage, done, total, path }] in flight

  // Saved chat threads — shared by the Sidebar (list/search/select) and the
  // Chat view (load/send). activeConversationId === null means a fresh,
  // not-yet-persisted chat.
  const [conversations, setConversations] = useState([])
  const [activeConversationId, setActiveConversationId] = useState(null)

  // Device-identity state. Mirrors what /device/bootstrap returns.
  //   checked        — false until bootstrap completes
  //   registered     — does this install have a valid JWT?
  //   plan           — 'free' | 'pro'
  //   usage          — {used, limit, remaining} or null while loading
  //   offline        — couldn't reach the proxy; cached token in use
  const [auth, setAuth] = useState({
    checked: false, registered: false, plan: 'free', usage: null, offline: false,
  })

  const refreshAuth = useCallback(async () => {
    const E = window.electron
    if (!E?.deviceMe) { setAuth(s => ({ ...s, checked: true })); return null }
    const r = await E.deviceMe()
    if (r?.authenticated) {
      setAuth({
        checked: true, registered: true,
        plan: r.plan ?? 'free',
        usage: r.usage ?? null,
        offline: false,
      })
    } else {
      setAuth(s => ({ ...s, checked: true, registered: false }))
    }
    return r
  }, [])

  /**
   * "Sign out" — destructive only locally. Clears the JWT so the next
   * bootstrap re-registers (same device, same identity). Exposed mainly
   * for support / testing; ordinary users never need this.
   */
  const logout = useCallback(async () => {
    const E = window.electron
    if (E?.deviceLogout) await E.deviceLogout()
    setAuth({ checked: true, registered: false, plan: 'free', usage: null, offline: false })
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
        // Surface overall + per-file progress every tick so the bar and the
        // status panel stay live.
        setProgress(s.progress ?? null)
        setActiveFiles(s.activeFiles ?? [])
        if (!s.running) {
          clearInterval(busyTimer.current)
          busyTimer.current = null
          setIndexing(false)
          setProgress(null)
          setActiveFiles([])
          if (s.result) { setLastJob({ ok: true,  data: s.result }); toast('Indexing complete', 's') }
          if (s.error)  { setLastJob({ ok: false, msg:  s.error  }); toast('Indexing failed', 'e') }
          loadStats()
        }
      } catch {
        clearInterval(busyTimer.current)
        busyTimer.current = null
        setIndexing(false)
        setProgress(null)
        setActiveFiles([])
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

  // ── Chat threads ──────────────────────────────────────────────────────────
  const refreshConversations = useCallback(async () => {
    if (!api) return
    try {
      const { conversations: list } = await api.listConversations()
      setConversations(list || [])
    } catch { /* non-fatal: leave the list as-is */ }
  }, [api])

  const newConversation  = useCallback(() => setActiveConversationId(null), [])
  const openConversation = useCallback((id) => setActiveConversationId(id), [])

  const renameConversation = useCallback(async (id, title) => {
    try { await api.renameConversation(id, title); refreshConversations() }
    catch (e) { toast(e.message, 'e') }
  }, [api, refreshConversations, toast])

  const deleteConversation = useCallback(async (id) => {
    try {
      await api.deleteConversation(id)
      setActiveConversationId(cur => (cur === id ? null : cur))
      refreshConversations()
    } catch (e) { toast(e.message, 'e') }
  }, [api, refreshConversations, toast])

  // Load the thread list once the backend is reachable.
  useEffect(() => { if (connected) refreshConversations() }, [connected, refreshConversations])

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

  // Boot: register the device (or revalidate the saved JWT). Runs once
  // after the renderer mounts; the underlying IPC handler auto-registers
  // with the proxy if there's no saved token, so the user sees no login
  // friction whatsoever — just a moment of "Loading…" then they're in.
  useEffect(() => {
    const E = window.electron
    if (!E?.deviceBootstrap) {
      setAuth(s => ({ ...s, checked: true }))
      return
    }
    E.deviceBootstrap().then(r => {
      setAuth({
        checked: true,
        registered: !!r?.authenticated,
        plan: r?.plan ?? 'free',
        usage: r?.usage ?? null,
        offline: !!r?.offline,
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
      stats, indexing, progress, activeFiles, lastJob,
      loadStats, triggerIndex,
      auth, logout, refreshAuth,
      conversations, activeConversationId, setActiveConversationId,
      refreshConversations, newConversation, openConversation,
      renameConversation, deleteConversation,
    }}>
      {children}
    </AppCtx.Provider>
  )
}

export const useApp = () => useContext(AppCtx)
