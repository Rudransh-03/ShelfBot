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

// TODO(pro-gate): Deadlines is meant to be a Pro feature. Until payments/Pro
// exist, unlock it for every device so it can be used and tested. Flip to
// `false` to gate it — free devices then get an upgrade prompt instead of the
// scan, and the auto-scan only fires for Pro.
const DEADLINES_DEV_UNLOCK = true

// After indexing changes the corpus, wait this long with no further index
// activity before auto-scanning — so a bulk drop of files settles into ONE
// batched scan instead of several small ones.
const DEADLINE_SCAN_DEBOUNCE_MS = 8_000

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

  // Cross-document deadlines — shared by the Sidebar badge and the Deadlines view.
  const [deadlineStats,        setDeadlineStats]        = useState(null) // { pending, overdue, ... }
  const [scanningDeadlines,    setScanningDeadlines]    = useState(false)
  const [deadlineScanProgress, setDeadlineScanProgress] = useState(null) // { processed, total, found }

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

  // Is the Deadlines feature available to this device? (see DEADLINES_DEV_UNLOCK)
  const deadlinesEnabled = DEADLINES_DEV_UNLOCK || auth.plan === 'pro'

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

  const busyTimer        = useRef(null)
  const idleTimer        = useRef(null)
  const deadlineTimer    = useRef(null)
  const prevLastIndexed  = useRef(undefined)
  const scanDebounce     = useRef(null)
  const indexingRef      = useRef(false)
  const scanDeadlinesRef = useRef(null)

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

  // ── Deadlines ───────────────────────────────────────────────────────────────
  const loadDeadlineStats = useCallback(async () => {
    if (!api) return
    try {
      const d = await api.listDeadlines('all')
      const items = d.items ?? []
      // Badge counts only OPEN + UPCOMING items (what the Deadlines list shows):
      // overdue/undated/handled items are hidden there, so they shouldn't inflate it.
      const openUpcoming = items.filter(
        it => it.status === 'PENDING' && (it.bucket === 'DUE_SOON' || it.bucket === 'UPCOMING')
      ).length
      setDeadlineStats({ ...(d.counts ?? {}), openUpcoming })
    } catch { /* non-fatal */ }
  }, [api])

  // Poll the running scan until it finishes, then refresh stats + surface outcome.
  const startDeadlinePolling = useCallback(() => {
    if (deadlineTimer.current) clearInterval(deadlineTimer.current)
    deadlineTimer.current = setInterval(async () => {
      if (!api) return
      try {
        const s = await api.pollDeadlineScan()
        setDeadlineScanProgress(s.progress ?? null)
        if (!s.running) {
          clearInterval(deadlineTimer.current); deadlineTimer.current = null
          setScanningDeadlines(false); setDeadlineScanProgress(null)
          loadDeadlineStats()
          if (s.hasRun && s.message) {
            if (s.stop === 'PAUSED_QUOTA')      toast(s.message, 'i')
            else if (s.stop && s.stop !== 'COMPLETE' && s.stop !== 'NOT_SIGNED_IN') toast(s.message, 'e')
            else if (s.found > 0)               toast(`Found ${s.found} new deadline${s.found === 1 ? '' : 's'}`, 's')
          }
        }
      } catch {
        clearInterval(deadlineTimer.current); deadlineTimer.current = null
        setScanningDeadlines(false); setDeadlineScanProgress(null)
      }
    }, 1500)
  }, [api, loadDeadlineStats, toast])

  const scanDeadlines = useCallback(async () => {
    if (!api || scanningDeadlines) return
    try {
      await api.scanDeadlines()
      setScanningDeadlines(true)
      startDeadlinePolling()
    } catch (e) {
      // 409 = already running (harmless); anything else is worth surfacing.
      if (!String(e.message).toLowerCase().includes('progress')) toast(e.message, 'e')
    }
  }, [api, scanningDeadlines, startDeadlinePolling, toast])

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
    loadDeadlineStats()
    idleTimer.current = setInterval(() => {
      if (!indexing) loadStats()
      loadDeadlineStats()
      refreshAuth()
    }, IDLE_POLL_MS)
    return () => {
      if (idleTimer.current) clearInterval(idleTimer.current)
      idleTimer.current = null
    }
  }, [connected, api, loadStats, loadDeadlineStats, refreshAuth, indexing])

  // Keep refs to the latest indexing flag + scanDeadlines so the debounced
  // timer below always sees current state (no stale closures).
  useEffect(() => { indexingRef.current = indexing }, [indexing])
  useEffect(() => { scanDeadlinesRef.current = scanDeadlines }, [scanDeadlines])

  // Auto-scan for deadlines whenever the corpus changes — fires when
  // stats.lastIndexed moves, which covers BOTH a manual "Index" job and the
  // live watcher silently indexing a dropped-in file (lastIndexed refreshes on
  // the idle poll). Skips the initial load so we don't scan on every launch.
  //
  // Debounced: each index event resets a timer, so a bulk drop of files settles
  // into ONE batched scan. When the timer fires we also wait for any in-flight
  // manual "Index Now" job to finish (indexingRef) before scanning — so we never
  // scan files that are still being (re)indexed; it self-reschedules until the
  // index job is done. The scan itself is incremental + budget-capped + quota-aware.
  useEffect(() => {
    const li = stats?.lastIndexed
    if (!li) return
    if (prevLastIndexed.current === undefined) { prevLastIndexed.current = li; return } // skip first load
    if (prevLastIndexed.current === li) return
    prevLastIndexed.current = li
    if (!deadlinesEnabled) return

    if (scanDebounce.current) clearTimeout(scanDebounce.current)
    const fire = () => {
      if (indexingRef.current) { scanDebounce.current = setTimeout(fire, 3_000); return } // index job running — wait
      scanDebounce.current = null
      scanDeadlinesRef.current?.() // guards against double-run internally (+ backend 409)
    }
    scanDebounce.current = setTimeout(fire, DEADLINE_SCAN_DEBOUNCE_MS)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stats?.lastIndexed])

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

  // On unmount: make sure the busy + deadline timers are also cleared
  useEffect(() => () => {
    if (busyTimer.current) clearInterval(busyTimer.current)
    if (deadlineTimer.current) clearInterval(deadlineTimer.current)
    if (scanDebounce.current) clearTimeout(scanDebounce.current)
  }, [])

  return (
    <AppCtx.Provider value={{
      api, apiBase, setApiBase,
      connected, setConnected,
      toast, toasts,
      stats, indexing, progress, activeFiles, lastJob,
      loadStats, triggerIndex,
      deadlineStats, scanningDeadlines, deadlineScanProgress, deadlinesEnabled,
      scanDeadlines, loadDeadlineStats,
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
