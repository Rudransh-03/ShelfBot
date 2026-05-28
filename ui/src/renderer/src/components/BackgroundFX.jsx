/**
 * Constellation background — a drifting field of neon nodes connected by
 * faint lines, both of which brighten near the cursor.
 *
 * Implementation:
 *   • One full-window <canvas> rendered every animation frame.
 *   • N nodes with small random velocities, bouncing off the viewport edges.
 *   • Lines drawn between any two nodes within MAX_LINK_DIST. Line + node
 *     brightness blend two signals: (a) base proximity between the nodes,
 *     and (b) proximity to the cursor. So a still mouse shows a quiet
 *     web; a moving mouse lights up the local neighbourhood like a
 *     spotlight passing over a power grid.
 *   • Pauses the rAF loop while the window is hidden/unfocused so we
 *     don't burn battery on laptops.
 *
 * Performance: ~55 nodes → ~1500 pairwise checks/frame, ~100 lines actually
 * drawn. Trivial for a desktop app even on integrated GPUs.
 */

import { useEffect, useRef } from 'react'

const NODE_COUNT        = 85      // a few more so the field doesn't read as empty
const MAX_LINK_DIST     = 150     // px — link only nodes within this radius
const CURSOR_RADIUS     = 220     // px — proximity field around cursor
const BASE_NODE_ALPHA   = 0.42    // slightly brighter idle stars
const BASE_LINE_ALPHA   = 0.10

export default function BackgroundFX() {
  const canvasRef  = useRef(null)
  const cursorRef  = useRef({ x: -9999, y: -9999 })
  const nodesRef   = useRef([])
  const runningRef = useRef(true)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    let dpr  = window.devicePixelRatio || 1

    function resize() {
      dpr = window.devicePixelRatio || 1
      const w = window.innerWidth
      const h = window.innerHeight
      canvas.width  = w * dpr
      canvas.height = h * dpr
      canvas.style.width  = w + 'px'
      canvas.style.height = h + 'px'
      ctx.setTransform(1, 0, 0, 1, 0, 0)
      ctx.scale(dpr, dpr)
    }

    function initNodes() {
      const w = window.innerWidth
      const h = window.innerHeight
      nodesRef.current = Array.from({ length: NODE_COUNT }, () => ({
        x:  Math.random() * w,
        y:  Math.random() * h,
        vx: (Math.random() - 0.5) * 0.25,   // very slow drift
        vy: (Math.random() - 0.5) * 0.25,
        r:  0.9 + Math.random() * 1.4,
      }))
    }

    function frame() {
      if (!runningRef.current) { rafId = requestAnimationFrame(frame); return }

      const w = window.innerWidth
      const h = window.innerHeight
      ctx.clearRect(0, 0, w, h)

      const nodes  = nodesRef.current
      const cursor = cursorRef.current

      // Advance positions
      for (const n of nodes) {
        n.x += n.vx
        n.y += n.vy
        if (n.x < 0)   { n.x = 0;   n.vx *= -1 }
        if (n.x > w)   { n.x = w;   n.vx *= -1 }
        if (n.y < 0)   { n.y = 0;   n.vy *= -1 }
        if (n.y > h)   { n.y = h;   n.vy *= -1 }
      }

      // Lines first so nodes sit on top
      for (let i = 0; i < nodes.length; i++) {
        const a = nodes[i]
        for (let j = i + 1; j < nodes.length; j++) {
          const b = nodes[j]
          const dx = a.x - b.x
          const dy = a.y - b.y
          const dist2 = dx * dx + dy * dy
          if (dist2 > MAX_LINK_DIST * MAX_LINK_DIST) continue

          const dist = Math.sqrt(dist2)
          // proximity to cursor — use midpoint of the line segment
          const mx = (a.x + b.x) * 0.5
          const my = (a.y + b.y) * 0.5
          const cdx = mx - cursor.x
          const cdy = my - cursor.y
          const cdist = Math.sqrt(cdx * cdx + cdy * cdy)
          const cursorFalloff = Math.max(0, 1 - cdist / CURSOR_RADIUS)

          const baseAlpha   = (1 - dist / MAX_LINK_DIST) * BASE_LINE_ALPHA
          const hover       = cursorFalloff * 0.55
          const alpha       = baseAlpha + hover
          const r = 91, g = 157, blue = 255
          ctx.strokeStyle = `rgba(${r}, ${g}, ${blue}, ${alpha})`
          ctx.lineWidth   = 0.5 + cursorFalloff * 0.9
          ctx.beginPath()
          ctx.moveTo(a.x, a.y)
          ctx.lineTo(b.x, b.y)
          ctx.stroke()
        }
      }

      // Nodes
      for (const n of nodes) {
        const cdx = n.x - cursor.x
        const cdy = n.y - cursor.y
        const cdist = Math.sqrt(cdx * cdx + cdy * cdy)
        const cursorFalloff = Math.max(0, 1 - cdist / CURSOR_RADIUS)

        const r     = n.r + cursorFalloff * 2.2
        const alpha = BASE_NODE_ALPHA + cursorFalloff * 0.5

        ctx.fillStyle = `rgba(143, 188, 255, ${alpha})`
        ctx.beginPath()
        ctx.arc(n.x, n.y, r, 0, Math.PI * 2)
        ctx.fill()

        // Cyan glow halo on close nodes
        if (cursorFalloff > 0.15) {
          const grad = ctx.createRadialGradient(n.x, n.y, 0, n.x, n.y, 14)
          grad.addColorStop(0, `rgba(77, 219, 255, ${cursorFalloff * 0.5})`)
          grad.addColorStop(1, 'rgba(77, 219, 255, 0)')
          ctx.fillStyle = grad
          ctx.beginPath()
          ctx.arc(n.x, n.y, 14, 0, Math.PI * 2)
          ctx.fill()
        }
      }

      rafId = requestAnimationFrame(frame)
    }

    // ── Mouse + lifecycle ────────────────────────────────────────────────
    function onMove(e)   { cursorRef.current.x = e.clientX; cursorRef.current.y = e.clientY }
    function onLeave()   { cursorRef.current.x = -9999;     cursorRef.current.y = -9999 }
    function onResize()  { resize(); initNodes() }
    function onVisible() { runningRef.current = document.visibilityState === 'visible' }

    resize()
    initNodes()
    let rafId = requestAnimationFrame(frame)

    window.addEventListener('mousemove',     onMove,    { passive: true })
    window.addEventListener('mouseleave',    onLeave)
    window.addEventListener('resize',        onResize)
    document.addEventListener('visibilitychange', onVisible)

    return () => {
      cancelAnimationFrame(rafId)
      window.removeEventListener('mousemove',  onMove)
      window.removeEventListener('mouseleave', onLeave)
      window.removeEventListener('resize',     onResize)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [])

  return <canvas ref={canvasRef} className="bg-constellation" aria-hidden="true" />
}
