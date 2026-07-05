import { useState, useEffect } from 'react'

/* Platform-aware title bar (canonical Rudo design):
   - mac:     traffic lights left (functional close/min/max), theme toggle right
   - windows: brand chip left, theme toggle + divider + min/max/close right
   Center: mono uppercase app title. The whole bar is a drag region. */
export default function TitleBar({ connected }) {
  // connected prop kept for API compat (status lives in the sidebar)
  const E = window.electron
  const isMac = (E?.platform || navigator.platform).toLowerCase().includes('mac') ||
                (E?.platform === 'darwin')

  const [theme, setTheme] = useState(() => {
    try { return localStorage.getItem('rudo-theme') || 'light' } catch { return 'light' }
  })

  useEffect(() => {
    document.body.dataset.theme = theme
    try { localStorage.setItem('rudo-theme', theme) } catch { /* ignore */ }
  }, [theme])

  const toggleTheme = () => setTheme(t => (t === 'dark' ? 'light' : 'dark'))

  return (
    <div className={`titlebar ${isMac ? 'mac' : 'win'}`}>
      <div className="tb-left">
        {isMac ? (
          <div className="traffic-lights">
            <button className="tl tl-close" onClick={E?.closeWindow}    title="Close" aria-label="Close" />
            <button className="tl tl-min"   onClick={E?.minimizeWindow} title="Minimize" aria-label="Minimize" />
            <button className="tl tl-max"   onClick={E?.maximizeWindow} title="Zoom" aria-label="Zoom" />
          </div>
        ) : (
          <div className="tb-brand-chip"><span className="tb-brand-dot" /></div>
        )}
      </div>

      <div className="tb-title">rudo — on-device document assistant</div>

      <div className="tb-right">
        <button
          className="tb-theme"
          onClick={toggleTheme}
          title={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
        >
          {theme === 'dark' ? (
            /* sun */
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="4" />
              <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
            </svg>
          ) : (
            /* moon */
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
              <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
            </svg>
          )}
        </button>

        {!isMac && E && (
          <>
            <div className="tb-divider" />
            <button className="win-btn" onClick={E.minimizeWindow} title="Minimize">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M5 12h14" /></svg>
            </button>
            <button className="win-btn" onClick={E.maximizeWindow} title="Maximize">
              <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="4" y="4" width="16" height="16" rx="2" /></svg>
            </button>
            <button className="win-btn close" onClick={E.closeWindow} title="Close">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M6 6l12 12M18 6L6 18" /></svg>
            </button>
          </>
        )}
      </div>
    </div>
  )
}
