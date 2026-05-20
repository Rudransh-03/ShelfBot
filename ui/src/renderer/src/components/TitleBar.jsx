export default function TitleBar({ connected }) {
  // intentional: connected prop no longer needed visually
  // (status moved into the sidebar) but kept for API compat.
  const E = window.electron

  return (
    <div className="titlebar">
      <div className="tb-left" />

      {E && (
        <div className="tb-right">
          <button className="win-btn" onClick={E.minimizeWindow} title="Minimize">
            <svg width="10" height="2" viewBox="0 0 10 2">
              <rect width="10" height="2" rx="1" fill="currentColor"/>
            </svg>
          </button>
          <button className="win-btn" onClick={E.maximizeWindow} title="Maximize">
            <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.4">
              <rect x=".7" y=".7" width="8.6" height="8.6" rx="1.4"/>
            </svg>
          </button>
          <button className="win-btn close" onClick={E.closeWindow} title="Close">
            <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.8">
              <line x1="1" y1="1" x2="9" y2="9"/>
              <line x1="9" y1="1" x2="1" y2="9"/>
            </svg>
          </button>
        </div>
      )}
    </div>
  )
}
