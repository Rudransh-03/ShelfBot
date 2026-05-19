import { createContext, useContext, useState, useCallback, useMemo } from 'react'
import { ApiClient } from '../api/client'

const AppCtx = createContext(null)

export function AppProvider({ children }) {
  const [apiBase,    setApiBase]    = useState(null)
  const [connected,  setConnected]  = useState(false)
  const [toasts,     setToasts]     = useState([])

  const toast = useCallback((msg, type = 'i', ms = 3600) => {
    const id = Date.now() + Math.random()
    setToasts(t => [...t, { id, msg, type }])
    setTimeout(() => setToasts(t => t.filter(x => x.id !== id)), ms)
  }, [])

  // Fresh ApiClient any time the base URL changes
  const api = useMemo(() => (apiBase ? new ApiClient(apiBase) : null), [apiBase])

  return (
    <AppCtx.Provider value={{ api, apiBase, setApiBase, connected, setConnected, toast, toasts }}>
      {children}
    </AppCtx.Provider>
  )
}

export const useApp = () => useContext(AppCtx)
