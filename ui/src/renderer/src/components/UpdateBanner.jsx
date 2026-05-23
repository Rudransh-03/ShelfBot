import { useEffect, useState } from 'react'

const DISMISS_KEY = 'shelfbot.updateDismissed'

const SparkIcon = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="1 4 1 10 7 10"/>
    <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/>
  </svg>
)
const CloseIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
    <line x1="18" y1="6" x2="6"  y2="18"/>
    <line x1="6"  y1="6" x2="18" y2="18"/>
  </svg>
)

/**
 * Shows a small banner when a newer ShelfBot has been downloaded in the
 * background. Click "Restart" → main process calls autoUpdater.quitAndInstall
 * and the app relaunches on the new version.
 *
 * State machine:
 *   • idle / none / error    → hidden
 *   • available / downloading → hidden (we let it download silently)
 *   • downloaded             → visible until user dismisses or installs
 *
 * "Dismissed" persists by version, so a fresh download of a *newer* version
 * shows the banner again even if the previous one was dismissed.
 */
export default function UpdateBanner() {
  const [state, setState] = useState(null) // { state, version?, percent? }
  const [installing, setInstalling] = useState(false)

  useEffect(() => {
    const E = window.electron
    if (!E?.onUpdateStatus) return
    E.onUpdateStatus(setState)
  }, [])

  if (!state || state.state !== 'downloaded') return null

  const dismissedVer = localStorage.getItem(DISMISS_KEY)
  if (dismissedVer && dismissedVer === state.version) return null

  const restart = async () => {
    const E = window.electron
    if (!E?.installUpdate) return
    setInstalling(true)
    const r = await E.installUpdate()
    if (!r?.ok) setInstalling(false)
    // On success the app will quit + relaunch — no further UI work needed.
  }

  const dismiss = () => {
    if (state.version) localStorage.setItem(DISMISS_KEY, state.version)
    setState(null)
  }

  return (
    <div className="update-banner" role="status" aria-live="polite">
      <div className="update-banner-icon"><SparkIcon /></div>
      <div className="update-banner-body">
        <div className="update-banner-title">
          Rudo {state.version ? `v${state.version}` : 'update'} is ready
        </div>
        <div className="update-banner-sub">Restart to install — takes a few seconds.</div>
      </div>
      <button
        className="update-banner-btn"
        onClick={restart}
        disabled={installing}
      >
        {installing ? 'Restarting…' : 'Restart'}
      </button>
      <button
        className="update-banner-close"
        onClick={dismiss}
        title="Hide until next update"
        aria-label="Dismiss update banner"
      >
        <CloseIcon />
      </button>
    </div>
  )
}
