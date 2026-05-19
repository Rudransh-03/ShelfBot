import BookshelfIcon from './BookshelfIcon'

export default function LoadingOverlay({ visible, msg }) {
  return (
    <div className={`loading-overlay${visible ? '' : ' out'}`}>
      <div className="load-logo">
        <BookshelfIcon size={38} />
      </div>
      <div className="load-title">ShelfBot</div>
      <div className="load-sub">{msg}</div>
      <div className="load-spin" />
    </div>
  )
}
