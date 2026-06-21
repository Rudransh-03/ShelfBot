export class ApiClient {
  constructor(base, token = null) {
    this.base = base
    // Per-launch secret the Electron main process handed us. The backend
    // rejects any /api/* call without it — this is what keeps a random web
    // page in the user's browser from reading their local documents/chat.
    this.token = token
  }

  /** Headers for every request: JSON + the local-API token when we have one. */
  _headers(extra = {}) {
    return {
      'Content-Type': 'application/json',
      ...(this.token ? { 'X-Shelfbot-Token': this.token } : {}),
      ...extra,
    }
  }

  async _r(path, opts = {}) {
    const res  = await fetch(this.base + path, {
      ...opts,
      headers: this._headers(opts.headers),
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`)
    return data
  }

  health()          { return this._r('/api/health') }
  status()          { return this._r('/api/status') }
  startIndex()      { return this._r('/api/index', { method: 'POST' }) }
  pollIndex()       { return this._r('/api/index') }
  query(q, conversationId) { return this._r('/api/query', { method: 'POST', body: JSON.stringify({ question: q, conversationId }) }) }

  /**
   * Streaming chat query. POSTs to /api/query/stream and pipes the SSE
   * back to the caller via three callbacks:
   *   onToken(text)  — fires per delta; append directly to the AI bubble
   *   onDone(summary) — once at the end with {found, sources}
   *   onError(err)   — once, on any failure (including server-side errors)
   *
   * Returns a function that can be called to abort the stream early.
   */
  queryStream(q, { onToken, onDone, onError, conversationId, clientId }) {
    const ctrl = new AbortController()
    ;(async () => {
      try {
        const res = await fetch(this.base + '/api/query/stream', {
          method:  'POST',
          headers: this._headers({ 'Accept': 'text/event-stream' }),
          body:    JSON.stringify({ question: q, conversationId, clientId }),
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

  // ── Chat threads (local, on-device) ──────────────────────────────────────
  /** All saved chat threads, most-recently-updated first. */
  listConversations()          { return this._r('/api/conversations') }
  /** Threads whose title or any message contains the keywords. */
  searchConversations(q)       { return this._r(`/api/conversations?q=${encodeURIComponent(q)}`) }
  /** Creates an empty thread. Returns { id, title }. */
  createConversation(title)    { return this._r('/api/conversations', { method: 'POST', body: JSON.stringify({ title }) }) }
  /** Full message history for one thread: { id, messages: [{role, content, createdAt}] }. */
  getConversation(id)          { return this._r(`/api/conversations/${id}`) }
  /** Renames a thread. */
  renameConversation(id, title){ return this._r(`/api/conversations/${id}`, { method: 'POST', body: JSON.stringify({ title }) }) }
  /** Deletes a thread and its messages. */
  deleteConversation(id)       { return this._r(`/api/conversations/${id}`, { method: 'DELETE' }) }

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

  /** Files that failed to index, each with a `reason` (error message). */
  listFailedFiles() { return this._r('/api/files?status=failed') }

  /** Removes an indexed file by absolute path. Frees its token budget. */
  deleteFile(path)  { return this._r('/api/files', { method: 'DELETE', body: JSON.stringify({ path }) }) }

  /**
   * Generates (or returns the cached) one-page brief for an indexed file.
   * Returns { path, fileName, summary, llmCalls, generatedAt, cached }.
   * The first call can take several seconds for large docs; subsequent calls
   * are instant unless the file's content has changed.
   */
  summarizeFile(path, { force = false } = {}) {
    return this._r('/api/files/summary', {
      method: 'POST',
      body:   JSON.stringify({ path, force }),
    })
  }

  // ── Deadlines (cross-document intelligence) ──────────────────────────────

  /** Starts the incremental deadline scan (one batched LLM call per group). */
  scanDeadlines()      { return this._r('/api/deadlines/scan', { method: 'POST' }) }
  /** Polls the running/last scan: { running, hasRun, stop?, message?, progress? }. */
  pollDeadlineScan()   { return this._r('/api/deadlines/scan') }
  /**
   * Lists extracted deadlines + counts. status: 'pending' | 'done' | 'dismissed' | 'all'.
   * Each item carries computed { daysUntil, bucket } (OVERDUE|DUE_SOON|UPCOMING|NO_DATE).
   */
  listDeadlines(status = 'all') { return this._r(`/api/deadlines?status=${encodeURIComponent(status)}`) }
  /** Partial update: { status?, reminderSet?, title?, description?, dueDate?, recurring? }. */
  updateDeadline(id, patch)     { return this._r(`/api/deadlines/${id}`, { method: 'POST', body: JSON.stringify(patch) }) }
  /** Removes one deadline. */
  deleteDeadline(id)            { return this._r(`/api/deadlines/${id}`, { method: 'DELETE' }) }
  /**
   * Gaps in recurring document series, computed locally (no LLM): each item is
   * { series, issuer, period, cadence, confidence, presentCount, message }.
   */
  listMissingDocuments()        { return this._r('/api/missing') }

  // ── Per-client workspaces ────────────────────────────────────────────────
  /** [{ id, name, identifiers, fileCount }] */
  listClients()                  { return this._r('/api/clients') }
  /** Create a client with a name and optional identifier strings. */
  createClient(name, identifiers = []) {
    return this._r('/api/clients', { method: 'POST', body: JSON.stringify({ name, identifiers }) })
  }
  editClient(id, patch)          { return this._r(`/api/clients/${id}`, { method: 'POST', body: JSON.stringify(patch) }) }
  deleteClient(id)               { return this._r(`/api/clients/${id}`, { method: 'DELETE' }) }
  /** Re-tag every file against the current client list (local, no LLM). */
  recomputeClients()             { return this._r('/api/clients/recompute', { method: 'POST' }) }
  /** Manually pin a file to a client (clientId null = unassign). */
  assignFileToClient(path, clientId) {
    return this._r('/api/clients/assign', { method: 'POST', body: JSON.stringify({ path, clientId }) })
  }
  /** Auto-detected clients (from the scan) the user can accept: [{ key, name, gstin, pan, fileCount }]. */
  listClientSuggestions()        { return this._r('/api/clients/suggestions') }
  /** Free local scan of indexed files for client identities (GSTIN/PAN). → { found, suggestions }. */
  scanClients()                  { return this._r('/api/clients/scan', { method: 'POST' }) }
  /** Accept a suggestion → creates the client + tags its files. */
  acceptClientSuggestion(s)      { return this._r('/api/clients/accept', { method: 'POST', body: JSON.stringify(s) }) }
  /** Hide a suggestion so it won't reappear. */
  dismissClientSuggestion(key)   { return this._r('/api/clients/dismiss', { method: 'POST', body: JSON.stringify({ key }) }) }

  // ── Reorg pipeline ───────────────────────────────────────────────────────

  /**
   * End-to-end dry-run for a target directory. Returns one of:
   *   { kind: 'scopeError', scopeError: {...}, summary: {...} }
   *   { kind: 'proposal',   proposal: { moves, dropped, leftAlone, ... }, summary: {...} }
   * The proposal can have zero moves — that's the "everything is already
   * tidy, nothing to do" case.
   */
  reorgPreview(targetDir) {
    return this._r('/api/reorg/preview', {
      method: 'POST',
      body:   JSON.stringify({ targetDir }),
    })
  }

  /**
   * Executes a user-approved subset of moves. Returns
   *   { batchId, outcomes, successCount, skippedCount, failedCount }
   * Hand `batchId` to {@link reorgUndo} to reverse the batch.
   */
  reorgExecute(targetDir, moves) {
    return this._r('/api/reorg/execute', {
      method: 'POST',
      body:   JSON.stringify({ targetDir, moves }),
    })
  }

  /** Reverses a previously-executed batch. */
  reorgUndo(batchId) {
    return this._r('/api/reorg/undo', {
      method: 'POST',
      body:   JSON.stringify({ batchId }),
    })
  }

  /** Recent batches the user could choose to undo, newest first. */
  reorgHistory(limit = 20) {
    return this._r(`/api/reorg/history?limit=${limit}`)
  }
}
