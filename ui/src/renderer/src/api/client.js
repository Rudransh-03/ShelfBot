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
}
