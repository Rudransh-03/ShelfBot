/** Reusable inline SVG bookshelf logo used throughout the app.
    Defaults to the brand taupe; pass `currentColor` to inherit. */
export default function BookshelfIcon({ size = 24, color = '#B3A798' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <rect x="3"    y="3"    width="2.5" height="16"   rx=".8" fill={color} opacity=".9"/>
      <rect x="7.5"  y="5.5"  width="2.5" height="13.5" rx=".8" fill={color} opacity=".7"/>
      <rect x="12"   y="4"    width="2.5" height="15.5" rx=".8" fill={color} opacity=".85"/>
      <rect x="16.5" y="6.5"  width="2.5" height="13"   rx=".8" fill={color} opacity=".6"/>
      <rect x="2"    y="19"   width="19"  height="1.5"  rx=".5" fill={color}/>
    </svg>
  )
}
