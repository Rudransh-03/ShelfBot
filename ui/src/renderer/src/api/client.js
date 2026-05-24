export class ApiClient {
  constructor(base) {
    this.base = base
  }

  async _r(path, opts = {}) {
    const res  = await fetch(this.base + path, {
      headers: { 'Content-Type': 'application/json' },
      ...opts,
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`)
    return data
  }

  health()          { return this._r('/api/health') }
  status()          { return this._r('/api/status') }
  startIndex()      { return this._r('/api/index', { method: 'POST' }) }
  pollIndex()       { return this._r('/api/index') }
  query(q)          { return this._r('/api/query', { method: 'POST', body: JSON.stringify({ question: q }) }) }

  /**
   * Streaming chat query. POSTs to /api/query/stream and pipes the SSE
   * back to the caller via three callbacks:
   *   onToken(text)  — fires per delta; append directly to the AI bubble
   *   onDone(summary) — once at the end with {found, sources}
   *   onError(err)   — once, on any failure (including server-side errors)
   *
   * Returns a function that can be called to abort the stream early.
   */
  queryStream(q, { onToken, onDone, onError }) {
    const ctrl = new AbortController()
    ;(async () => {
      try {
        const res = await fetch(this.base + '/api/query/stream', {
          method:  'POST',
          headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
          body:    JSON.stringify({ question: q }),
          signal:  ctrl.signal,
        })
        if (!res.ok || !res.body) {
          // Non-stream error path — parse JSON if we can.
          let msg = `HTTP ${res.status}`
          try { const d = await res.json(); msg = d.error || msg } catch {}
          onError?.(new Error(msg))
          return
        }
        const reader  = res.body.getReader()
        const decoder = new TextDecoder('utf-8')
        let buffer = ''
        let currentEvent = 'message'
        let currentData  = []

        // Helper to dispatch one fully-collected event.
        const flush = () => {
          if (currentData.length === 0) { currentEvent = 'message'; return }
          const data = currentData.join('\n')
          if (currentEvent === 'token') {
            onToken?.(data)
          } else if (currentEvent === 'done') {
            try { onDone?.(JSON.parse(data)) } catch { onDone?.({}) }
          } else if (currentEvent === 'error') {
            onError?.(new Error(data))
          }
          currentEvent = 'message'
          currentData  = []
        }

        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          // SSE events are separated by a blank line; lines within an
          // event by single newlines.
          let nl
          while ((nl = buffer.indexOf('\n')) !== -1) {
            const line = buffer.slice(0, nl)
            buffer = buffer.slice(nl + 1)
            if (line === '') { flush(); continue }
            if (line.startsWith('event:')) currentEvent = line.slice(6).trim()
            else if (line.startsWith('data:')) currentData.push(line.slice(5).trim().length === 0 ? '' : line.slice(line.startsWith('data: ') ? 6 : 5))
            // Note: 'data:' with no space is still valid SSE; the slice above handles both.
          }
        }
        flush()
      } catch (e) {
        if (e.name !== 'AbortError') onError?.(e)
      }
    })()
    return () => ctrl.abort()
  }
  clearConvo()      { return this._r('/api/conversation', { method: 'DELETE' }) }
  getConfig()       { return this._r('/api/config') }
  /**
   * Persists the indexed roots.
   * Accepts either a single string (legacy) or an array of strings (multi-root).
   * The backend understands both shapes.
   */
  saveConfig(paths) {
    const body = Array.isArray(paths)
      ? { rootPaths: paths }
      : { rootPath:  paths }
    return this._r('/api/config', { method: 'POST', body: JSON.stringify(body) })
  }

  /** Returns indexed files sorted by size desc. */
  listFiles()       { return this._r('/api/files') }

  /** Removes an indexed file by absolute path. Frees its token budget. */
  deleteFile(path)  { return this._r('/api/files', { method: 'DELETE', body: JSON.stringify({ path }) }) }
}
