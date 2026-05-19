import BookshelfIcon from './BookshelfIcon'

export default function TitleBar({ connected }) {
  const E = window.electron

  return (
    <div className="titlebar">
      <div className="tb-left">
        <svg className="tb-logo" viewBox="0 0 24 24" fill="none">
          <rect x="3"    y="3"    width="2.4" height="16"   rx=".75" fill="#8b5cf6" opacity=".9"/>
          <rect x="7.4"  y="5.5"  width="2.4" height="13.5" rx=".75" fill="#8b5cf6" opacity=".68"/>
          <rect x="11.8" y="4"    width="2.4" height="15.5" rx=".75" fill="#8b5cf6" opacity=".85"/>
          <rect x="16.2" y="6.5"  width="2.4" height="13"   rx=".75" fill="#8b5cf6" opacity=".58"/>
          <rect x="2"    y="19"   width="18.6" height="1.4" rx=".5"  fill="#6d28d9"/>
        </svg>
        <span className="tb-name">ShelfBot</span>
        <div className={`tb-dot${connected ? ' on' : ''}`} />
      </div>

      {/* Only show custom controls when we have no native ones (non-mac or frame:false) */}
      {E && (
        <div className="tb-right">
          <button className="win-btn"       onClick={E.minimizeWindow} title="Minimize">
            <svg width="10" height="2" viewBox="0 0 10 2">
              <rect width="10" height="2" rx="1" fill="currentColor"/>
            </svg>
          </button>
          <button className="win-btn"       onClick={E.maximizeWindow} title="Maximize">
            <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.4">
              <rect x=".7" y=".7" width="8.6" height="8.6" rx="1.4"/>
            </svg>
          </button>
          <button className="win-btn close" onClick={E.closeWindow}    title="Close">
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
