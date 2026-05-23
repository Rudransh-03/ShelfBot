import { useState } from 'react'
import { useApp } from '../context/AppContext'
import BookshelfIcon from './BookshelfIcon'

/**
 * Sign-in screen shown while there's no valid auth token.
 *
 * Today: a stub email login (the proxy issues a JWT to any well-formed
 * address). When Google OAuth credentials are wired, this same component
 * becomes a "Continue with Google" button — the rest of the app doesn't
 * change because the JWT shape is identical.
 */
export default function Login() {
  const { login, toast } = useApp()
  const [email, setEmail] = useState('')
  const [busy, setBusy]   = useState(false)

  const submit = async (e) => {
    e?.preventDefault?.()
    const trimmed = email.trim()
    if (!trimmed.includes('@')) { toast('Enter a valid email address', 'e'); return }
    setBusy(true)
    try {
      const r = await login(trimmed)
      if (!r.ok) toast(r.error || 'Sign-in failed', 'e')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-overlay" role="dialog" aria-modal="true">
      <form className="login-card" onSubmit={submit}>
        <div className="login-brand">
          <BookshelfIcon size={32} color="#e8c995" />
          <h1 className="login-title">ShelfBot</h1>
        </div>
        <div className="login-sub">Sign in to continue. Your usage is bounded to a daily limit; the OpenAI key never leaves our server.</div>

        <label className="form-lbl" htmlFor="login-email">Email</label>
        <input
          id="login-email"
          className="txt-input"
          type="email"
          autoFocus
          autoComplete="email"
          placeholder="you@example.com"
          value={email}
          onChange={e => setEmail(e.target.value)}
          disabled={busy}
        />

        <button type="submit" className="btn-primary login-btn" disabled={busy || !email.includes('@')}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>

        <div className="login-hint">
          Google sign-in is coming soon — for the early beta we use a passwordless email session.
        </div>
      </form>
    </div>
  )
}
