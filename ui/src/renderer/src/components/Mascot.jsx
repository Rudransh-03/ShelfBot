/**
 * Rudo — ShelfBot's resident AI assistant.
 *
 * Full-body floating robot character drawn in pure SVG. The brief was "cute
 * but smart" — so the silhouette borrows from friendly robots (Baymax / Wall-E /
 * EVE) but with librarian touches: small rectangular glasses, an open book
 * tucked under one arm, and a faint neon chest-core that pulses when engaged.
 *
 * The character "hovers" — no legs, an oval shadow under the body — to read
 * as an autonomous AI rather than a desktop pet. Everything is animated
 * declaratively via CSS keyframes; the JS only chooses which state we're in.
 *
 * States:
 *   • idle       — gentle bob, periodic blink, chest pulses slow
 *   • listening  — pupils dilate, chest brightens, ears (antenna) glow
 *   • thinking   — eyes close, three dots orbit the head, chest pulses fast
 *   • happy      — squint-smile, brief bounce, chest goes green
 *   • sleeping   — eyes closed, floating "z", chest dim
 *
 * Sizes:
 *   • size="lg"  — empty-state hero (~180 × 220)
 *   • size="md"  — sidebar / inline (~72 × 88)
 *   • size="sm"  — chip / corner peek (~36 × 44)
 */

import { useEffect, useState } from 'react'

// Match aspect ratio of the SVG viewBox (200 × 240) for clean scaling.
const SIZE_PX = {
  sm: { w: 36,  h: 44  },
  md: { w: 72,  h: 88  },
  lg: { w: 180, h: 220 },
}

export default function Mascot({
  size  = 'md',
  state = 'idle',
  className = '',
  label = 'Rudo',
}) {
  const { w, h } = SIZE_PX[size] ?? SIZE_PX.md

  // Idle blink: schedule 4–8 sec random intervals. Only active in resting
  // states; thinking/sleeping/happy already drive eye shape themselves.
  const [blinking, setBlinking] = useState(false)
  useEffect(() => {
    if (state === 'thinking' || state === 'sleeping' || state === 'happy') return
    let timer
    const schedule = () => {
      timer = setTimeout(() => {
        setBlinking(true)
        setTimeout(() => setBlinking(false), 130)
        schedule()
      }, 4000 + Math.random() * 4000)
    }
    schedule()
    return () => clearTimeout(timer)
  }, [state])

  const eyesClosed = blinking || state === 'thinking' || state === 'sleeping'
  const eyesHappy  = state === 'happy'
  const listening  = state === 'listening'

  return (
    <div
      className={`mascot mascot-${size} mascot-${state} ${className}`}
      style={{ width: w, height: h }}
      title={label}
      aria-hidden="true"
    >
      {/* Soft neon halo behind the body, mood-tinted. */}
      <div className="mascot-glow" />

      <svg
        viewBox="0 0 200 240"
        width={w}
        height={h}
        xmlns="http://www.w3.org/2000/svg"
        className="mascot-svg"
      >
        <defs>
          {/* Body — pale ice top to deeper neon-blue bottom for a glossy 3D feel. */}
          <linearGradient id="rudo-body" x1="0%" y1="0%" x2="0%" y2="100%">
            <stop offset="0%"   stopColor="#FFFDF9" />
            <stop offset="40%"  stopColor="#FAF8F3" />
            <stop offset="100%" stopColor="#EDE6DA" />          </linearGradient>
          {/* Visor — dark glass strip across the head for the "smart" read. */}
          <linearGradient id="rudo-visor" x1="0%" y1="0%" x2="0%" y2="100%">
            <stop offset="0%"   stopColor="#2A2727" />
            <stop offset="100%" stopColor="#1F1D1D" />
          </linearGradient>
          {/* Glowing chest core. */}
          <radialGradient id="rudo-core" cx="50%" cy="50%" r="50%">
            <stop offset="0%"   stopColor="#FFFDF9" />
            <stop offset="55%"  stopColor="#B3A798" />
            <stop offset="100%" stopColor="rgba(179, 167, 152, 0)" />
          </radialGradient>
          {/* Book cover. */}
          <linearGradient id="rudo-book" x1="0%" y1="0%" x2="0%" y2="100%">
            <stop offset="0%"   stopColor="#C4B8A9" />
            <stop offset="100%" stopColor="#B3A798" />
          </linearGradient>
        </defs>

        {/* ── Ground shadow (hovering effect) ─────────────────────────── */}
        <ellipse
          cx="100" cy="225" rx="48" ry="6"
          fill="rgba(20, 19, 19, 0.18)"
          className="mascot-shadow"
        />

        {/* ── Whole-character group — bobs gently ─────────────────────── */}
        <g className="mascot-body-group">

          {/* ── Antennae (two short ears with neon tips) ──────────────── */}
          <g className="mascot-ears">
            <line x1="74" y1="38" x2="68" y2="22" stroke="#141313" strokeWidth="3" strokeLinecap="round" />
            <circle cx="68" cy="20" r="4" fill="#B3A798" className="mascot-antenna-tip" />
            <line x1="126" y1="38" x2="132" y2="22" stroke="#141313" strokeWidth="3" strokeLinecap="round" />
            <circle cx="132" cy="20" r="4" fill="#B3A798" className="mascot-antenna-tip" />
          </g>

          {/* ── Head ──────────────────────────────────────────────────── */}
          <g className="mascot-head">
            {/* Head shape — rounded rectangle */}
            <rect x="50" y="36" width="100" height="92" rx="38" ry="38" fill="url(#rudo-body)" stroke="#141313" strokeWidth="2.6" />
            {/* Subtle glossy highlight */}
            <ellipse cx="76" cy="58" rx="16" ry="7" fill="rgba(255, 255, 255, 0.5)" />
            {/* Dark visor strip across the face */}
            <rect x="58" y="68" width="84" height="34" rx="17" ry="17" fill="url(#rudo-visor)" />
            {/* Visor edge highlight */}
            <rect x="58" y="68" width="84" height="2" rx="1" fill="rgba(242, 237, 230, 0.18)" />

            {/* Eyes inside visor */}
            <g className="mascot-eyes">
              {eyesHappy ? (
                <>
                  <path d="M 72 90 Q 80 80 88 90" stroke="#F2EDE6" strokeWidth="3.5" strokeLinecap="round" fill="none" />
                  <path d="M 112 90 Q 120 80 128 90" stroke="#F2EDE6" strokeWidth="3.5" strokeLinecap="round" fill="none" />
                </>
              ) : eyesClosed ? (
                <>
                  <line x1="72" y1="86" x2="88" y2="86" stroke="#F2EDE6" strokeWidth="3.5" strokeLinecap="round" />
                  <line x1="112" y1="86" x2="128" y2="86" stroke="#F2EDE6" strokeWidth="3.5" strokeLinecap="round" />
                </>
              ) : (
                <>
                  {/* Glowing cyan eyes — bigger when listening */}
                  <circle cx="80"  cy="86" r={listening ? 6 : 5} fill="#F2EDE6" className="mascot-eye" />
                  <circle cx="120" cy="86" r={listening ? 6 : 5} fill="#F2EDE6" className="mascot-eye" />
                  {/* Pupils — centered, not offset, so Rudo looks straight at the user */}
                  <circle cx="80"  cy="86" r="1.8" fill="#141313" />
                  <circle cx="120" cy="86" r="1.8" fill="#141313" />
                  {/* Tiny catch-light specular highlight, top-left of each eye */}
                  <circle cx="78"  cy="84" r="0.9" fill="rgba(255,255,255,0.8)" />
                  <circle cx="118" cy="84" r="0.9" fill="rgba(255,255,255,0.8)" />
                </>
              )}
            </g>

            {/* Glasses frame outside the visor for the "smart" silhouette */}
            <g className="mascot-glasses" opacity="0.7">
              <circle cx="80"  cy="86" r="11" fill="none" stroke="#141313" strokeWidth="1.8" />
              <circle cx="120" cy="86" r="11" fill="none" stroke="#141313" strokeWidth="1.8" />
              <line x1="91" y1="86" x2="109" y2="86" stroke="#141313" strokeWidth="1.8" />
            </g>

            {/* Small mouth */}
            <path
              d={eyesHappy ? 'M 88 116 Q 100 124 112 116' : 'M 92 116 Q 100 119 108 116'}
              stroke="#5e564c"
              strokeWidth="2"
              fill="none"
              strokeLinecap="round"
            />
          </g>

          {/* ── Neck connector ────────────────────────────────────────── */}
          <rect x="93" y="128" width="14" height="8" rx="3" fill="#C4B8A9" stroke="#141313" strokeWidth="1.6" />

          {/* ── Body / torso ──────────────────────────────────────────── */}
          <g className="mascot-torso">
            {/* Body shell */}
            <path
              d="M 60 138
                 Q 60 132 70 132
                 L 130 132
                 Q 140 132 140 138
                 L 144 192
                 Q 144 204 132 204
                 L 68 204
                 Q 56 204 56 192 Z"
              fill="url(#rudo-body)"
              stroke="#141313"
              strokeWidth="2.4"
              strokeLinejoin="round"
            />
            {/* Subtle highlight on body */}
            <ellipse cx="80" cy="150" rx="12" ry="5" fill="rgba(255,255,255,0.45)" />

            {/* Chest core — glowing */}
            <circle cx="100" cy="170" r="14" fill="url(#rudo-core)" className="mascot-core-glow" />
            <circle cx="100" cy="170" r="6"  fill="#FFFDF9" className="mascot-core" />
            <circle cx="100" cy="170" r="3"  fill="#fff" />

            {/* A small badge under the core like a "ShelfBot" label hint */}
            <rect x="86" y="190" width="28" height="3" rx="1.5" fill="rgba(20,19,19,0.18)" />
          </g>

          {/* ── Left arm holding an open book ─────────────────────────── */}
          <g className="mascot-arm-left">
            {/* upper arm */}
            <rect x="38" y="148" width="14" height="32" rx="7" fill="url(#rudo-body)" stroke="#141313" strokeWidth="2" transform="rotate(-12 45 164)" />
            {/* hand */}
            <circle cx="34" cy="184" r="9" fill="#FAF8F3" stroke="#141313" strokeWidth="2" />
            {/* open book held in the hand */}
            <g transform="translate(8, 178) rotate(-8)">
              <rect x="0" y="0" width="36" height="26" rx="2" fill="url(#rudo-book)" stroke="#141313" strokeWidth="1.8" />
              {/* page split */}
              <line x1="18" y1="2" x2="18" y2="24" stroke="rgba(255,253,249,0.75)" strokeWidth="1" />
              {/* text lines */}
              <line x1="3"  y1="8"  x2="14" y2="8"  stroke="rgba(20,19,19,0.45)" strokeWidth="1.4" strokeLinecap="round" />
              <line x1="3"  y1="13" x2="14" y2="13" stroke="rgba(20,19,19,0.45)" strokeWidth="1.4" strokeLinecap="round" />
              <line x1="3"  y1="18" x2="11" y2="18" stroke="rgba(20,19,19,0.45)" strokeWidth="1.4" strokeLinecap="round" />
              <line x1="22" y1="8"  x2="33" y2="8"  stroke="rgba(20,19,19,0.45)" strokeWidth="1.4" strokeLinecap="round" />
              <line x1="22" y1="13" x2="33" y2="13" stroke="rgba(20,19,19,0.45)" strokeWidth="1.4" strokeLinecap="round" />
              <line x1="22" y1="18" x2="30" y2="18" stroke="rgba(20,19,19,0.45)" strokeWidth="1.4" strokeLinecap="round" />
            </g>
          </g>

          {/* ── Right arm — small wave that bumps on happy ────────────── */}
          <g className="mascot-arm-right">
            <rect x="148" y="148" width="14" height="32" rx="7" fill="url(#rudo-body)" stroke="#141313" strokeWidth="2" transform="rotate(12 155 164)" />
            <circle cx="166" cy="184" r="9" fill="#FAF8F3" stroke="#141313" strokeWidth="2" />
          </g>
        </g>

        {/* Thinking: three dots orbiting the head */}
        {state === 'thinking' && (
          <g className="mascot-orbit" style={{ transformOrigin: '100px 84px' }}>
            <circle cx="100" cy="20" r="3" fill="#B3A798" />
            <circle cx="100" cy="20" r="3" fill="#B3A798" transform="rotate(120 100 84)" opacity="0.6" />
            <circle cx="100" cy="20" r="3" fill="#B3A798" transform="rotate(240 100 84)" opacity="0.3" />
          </g>
        )}

        {/* Sleeping: floating Z */}
        {state === 'sleeping' && (
          <text
            x="146" y="50"
            fill="#B3A798"
            fontSize="18"
            fontWeight="700"
            fontFamily="Manrope, ui-sans-serif, system-ui"
            className="mascot-zzz"
          >z</text>
        )}
      </svg>
    </div>
  )
}
