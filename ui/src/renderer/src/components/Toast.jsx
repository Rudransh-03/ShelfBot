import { useApp } from '../context/AppContext'

const TYPE_COLOR = { s: 'var(--green)', e: 'var(--red)', i: 'var(--purple)' }
const TYPE_ICON  = { s: '✓', e: '✕', i: '●' }

export default function ToastContainer() {
  const { toasts } = useApp()

  return (
    <div className="toast-container">
      {toasts.map(t => (
        <div key={t.id} className={`toast ${t.type}`}>
          <span style={{ color: TYPE_COLOR[t.type] ?? TYPE_COLOR.i }}>
            {TYPE_ICON[t.type] ?? '●'}
          </span>
          <span>{t.msg}</span>
        </div>
      ))}
    </div>
  )
}
