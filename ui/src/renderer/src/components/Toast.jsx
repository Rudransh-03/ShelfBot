import { useApp } from '../context/AppContext'

const TYPE_COLOR = { s: 'var(--green)', e: 'var(--red)', i: 'var(--purple)' }

const ICONS = {
  s: (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="20 6 9 17 4 12"/>
    </svg>
  ),
  e: (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <line x1="18" y1="6" x2="6" y2="18"/>
      <line x1="6" y1="6" x2="18" y2="18"/>
    </svg>
  ),
  i: (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="9"/>
      <line x1="12" y1="8" x2="12" y2="12"/>
      <line x1="12" y1="16" x2="12.01" y2="16"/>
    </svg>
  ),
}

export default function ToastContainer() {
  const { toasts } = useApp()

  return (
    <div className="toast-container">
      {toasts.map(t => (
        <div key={t.id} className={`toast ${t.type}`}>
          <span style={{ color: TYPE_COLOR[t.type] ?? TYPE_COLOR.i, display: 'flex', alignItems: 'center' }}>
            {ICONS[t.type] ?? ICONS.i}
          </span>
          <span>{t.msg}</span>
        </div>
      ))}
    </div>
  )
}
