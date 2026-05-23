import Mascot from './Mascot'

export default function LoadingOverlay({ visible, msg }) {
  return (
    <div className={`loading-overlay${visible ? '' : ' out'}`}>
      <div className="load-logo">
        <Mascot size="md" state="thinking" />
      </div>
      <div className="load-title">Rudo</div>
      <div className="load-sub">{msg}</div>
      <div className="load-spin" />
    </div>
  )
}
