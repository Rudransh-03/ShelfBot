// OS-agnostic calendar-reminder generation via the iCalendar (.ics) standard.
//
// Rather than wiring per-OS reminder APIs (macOS Reminders, Windows Task
// Scheduler, Linux `at`), we emit a standard .ics event with an alarm and let
// the OS open it with the user's default calendar app — macOS Calendar, Windows
// Outlook/Calendar, Linux GNOME Calendar / Evolution. Once imported the alert
// fires even when Rudo is closed (and syncs to the user's phone if their
// calendar syncs). One codepath, everywhere.
//
// Kept dependency-free (no electron imports) so it can be unit-tested in plain
// Node.

import { randomUUID } from 'crypto'

export function icsEscape(s) {
  return String(s ?? '')
    .replace(/\\/g, '\\\\')
    .replace(/;/g, '\\;')
    .replace(/,/g, '\\,')
    .replace(/\r?\n/g, '\\n')
}

// Fold a single logical line to <=75 octets per RFC 5545 (continuation lines
// start with a space). Calendars are lenient, but correct output avoids edge
// cases with long descriptions.
export function icsFold(line) {
  if (line.length <= 75) return line
  const parts = [line.slice(0, 75)]
  let i = 75
  while (i < line.length) { parts.push(' ' + line.slice(i, i + 74)); i += 74 }
  return parts.join('\r\n')
}

const pad2 = (n) => String(n).padStart(2, '0')
// Floating local time (no Z) so the reminder fires in the user's local zone.
const fmtLocal = (d) =>
  `${d.getFullYear()}${pad2(d.getMonth() + 1)}${pad2(d.getDate())}T${pad2(d.getHours())}${pad2(d.getMinutes())}00`
const fmtUtc = (d) =>
  `${d.getUTCFullYear()}${pad2(d.getUTCMonth() + 1)}${pad2(d.getUTCDate())}T${pad2(d.getUTCHours())}${pad2(d.getUTCMinutes())}${pad2(d.getUTCSeconds())}Z`

export function rruleFor(recurring) {
  switch (String(recurring ?? '').toUpperCase()) {
    case 'WEEKLY':    return 'FREQ=WEEKLY'
    case 'MONTHLY':   return 'FREQ=MONTHLY'
    case 'QUARTERLY': return 'FREQ=MONTHLY;INTERVAL=3'
    case 'YEARLY':    return 'FREQ=YEARLY'
    default:          return null
  }
}

/**
 * One VEVENT with a DISPLAY alarm.
 * @param ev {title, description?, date 'YYYY-MM-DD', time? 'HH:MM',
 *            leadDays? (days before to alert, default 3), recurring?, sourceFile?}
 */
export function buildVevent(ev, now = new Date()) {
  const [y, m, d] = String(ev.date ?? '').split('-').map(Number)
  if (!y || !m || !d) throw new Error('invalid date: ' + ev.date)
  const [hh, mi] = String(ev.time ?? '09:00').split(':').map(Number)
  const start = new Date(y, m - 1, d, hh || 9, mi || 0, 0)
  const end   = new Date(start.getTime() + 60 * 60 * 1000)
  const lead  = Math.max(0, parseInt(ev.leadDays ?? 3, 10) || 0)
  const trigger = lead > 0 ? `-P${lead}D` : 'PT0S'   // N days before, or at event time

  // file:// link to the original document so the reminder links straight to the
  // source PDF. encodeURI keeps the slashes and encodes spaces; the absolute
  // path already starts with "/" so we get the correct file:///Users/... form.
  const fileUrl = ev.sourcePath ? 'file://' + encodeURI(ev.sourcePath) : null

  // Join with real newlines; icsEscape converts them to the ICS "\n" line-break
  // escape. (Joining with a literal "\n" here would get double-escaped to "\\n"
  // and render as a literal backslash-n in the calendar body.)
  const desc = [
    ev.description,
    ev.sourceFile ? `Source: ${ev.sourceFile}` : '',
    fileUrl || '',
    'Added by Rudo',
  ].filter(Boolean).join('\n')

  const lines = [
    'BEGIN:VEVENT',
    `UID:${randomUUID()}@rudo.shelfbot`,
    `DTSTAMP:${fmtUtc(now)}`,
    `DTSTART:${fmtLocal(start)}`,
    `DTEND:${fmtLocal(end)}`,
    `SUMMARY:${icsEscape(ev.title || 'Reminder')}`,
    `DESCRIPTION:${icsEscape(desc)}`,
  ]
  // URL property — macOS Calendar / Outlook surface this as a clickable link
  // that opens the original document. (Not text-escaped; it's a URI value.)
  if (fileUrl) lines.push(`URL:${fileUrl}`)
  const rr = rruleFor(ev.recurring)
  if (rr) lines.push(`RRULE:${rr}`)
  lines.push(
    'BEGIN:VALARM',
    'ACTION:DISPLAY',
    `DESCRIPTION:${icsEscape(ev.title || 'Reminder')}`,
    `TRIGGER:${trigger}`,
    'END:VALARM',
    'END:VEVENT',
  )
  return lines.map(icsFold).join('\r\n')
}

/** A full VCALENDAR wrapping one or more events (so "Set all" is one import). */
export function buildCalendar(events, now = new Date()) {
  if (!Array.isArray(events) || events.length === 0) {
    throw new Error('no events')
  }
  const head = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Rudo//ShelfBot//EN',
    'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH',
  ]
  return [...head, ...events.map((e) => buildVevent(e, now)), 'END:VCALENDAR'].join('\r\n')
}
