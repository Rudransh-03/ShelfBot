import { useState } from 'react'
import { useApp } from '../context/AppContext'
import Mascot from './Mascot'

// Full-screen gate shown until the user signs in with Google. Mandatory sign-in
// is what binds usage to an account (and its trial), so there's no way past this
// without authenticating — no anonymous access.
export default function SignInScreen() {
  const { signInWithGoogle, toast } = useApp()
  const [busy, setBusy] = useState(false)

  const onSignIn = async () => {
    if (busy) return
    setBusy(true)
    try {
      const r = await signInWithGoogle()
      if (!r?.ok && r?.error) toast(r.error, 'e')
      // On success, auth state flips to registered and the app replaces this screen.
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="signin-screen">
      <div className="signin-card">
        <Mascot size="lg" state={busy ? 'thinking' : 'idle'} label="Rudo" />
        <div className="signin-title">Welcome to <em>Rudo</em></div>
        <div className="signin-sub">
          Your private assistant for the documents on your computer. Sign in to
          start your free trial.
        </div>
        <button className="signin-btn" onClick={onSignIn} disabled={busy}>
          <span className="g-circle"><GoogleGlyph /></span>
          {busy ? 'Opening browser…' : 'Sign in with Google'}
        </button>
        <div className="signin-fine">
          Free trial, no card required · Your files never leave your device
        </div>
      </div>
    </div>
  )
}

function GoogleGlyph() {
  return (
    <svg width="16" height="16" viewBox="0 0 48 48" aria-hidden="true">
      <path fill="#FFC107" d="M43.6 20.5H42V20H24v8h11.3c-1.6 4.7-6.1 8-11.3 8a12 12 0 1 1 7.9-21l5.7-5.7A20 20 0 1 0 24 44c11 0 20-8 20-20 0-1.2-.1-2.3-.4-3.5z"/>
      <path fill="#FF3D00" d="M6.3 14.7l6.6 4.8A12 12 0 0 1 24 12c3.1 0 5.9 1.2 8 3.1l5.7-5.7A20 20 0 0 0 6.3 14.7z"/>
      <path fill="#4CAF50" d="M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2A12 12 0 0 1 24 36c-5.2 0-9.6-3.3-11.3-7.9l-6.5 5C9.5 39.6 16.2 44 24 44z"/>
      <path fill="#1976D2" d="M43.6 20.5H42V20H24v8h11.3a12 12 0 0 1-4.1 5.6l6.2 5.2C39.9 36 44 30.6 44 24c0-1.2-.1-2.3-.4-3.5z"/>
    </svg>
  )
}
