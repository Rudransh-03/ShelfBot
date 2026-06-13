// Markdown-table → CSV extraction for the chat "Export to Excel" action.
//
// Pure, dependency-free, framework-agnostic (no React) so it can be unit-tested
// in isolation. It pulls GFM pipe tables out of an assistant answer and renders
// them as RFC-4180 CSV that opens cleanly in Excel / Numbers / Google Sheets.
//
// Why CSV (not .xlsx): zero dependency, opens natively in Excel, and finance
// users' real workflow is "answer → spreadsheet". The two classic CSV gotchas
// are handled below — a UTF-8 BOM (so ₹/é render instead of mojibake) and a
// formula-injection guard (so a cell like "=cmd()" can't run in Excel).

// Splits one table line into raw cell strings. Honours backslash-escaped pipes
// (`\|` is a literal pipe, not a column break) and drops the optional leading /
// trailing pipe that GFM tables usually carry.
function splitRow(line) {
  let s = line.trim()
  if (s.startsWith('|')) s = s.slice(1)
  // Only strip a trailing pipe that isn't itself escaped.
  if (s.endsWith('|') && !s.endsWith('\\|')) s = s.slice(0, -1)

  const cells = []
  let cur = ''
  for (let i = 0; i < s.length; i++) {
    const ch = s[i]
    if (ch === '\\' && i + 1 < s.length) { cur += s[i + 1]; i++; continue }
    if (ch === '|') { cells.push(cur); cur = ''; continue }
    cur += ch
  }
  cells.push(cur)
  return cells
}

// The `|---|:--:|` line that separates a table header from its body: every
// cell is one-or-more hyphens with optional leading/trailing colon (alignment).
function isDelimiterRow(line) {
  const cells = splitRow(line)
  if (cells.length === 0) return false
  return cells.every(c => /^:?-+:?$/.test(c.trim()))
}

// Reduces a cell's inline markdown to the plain text a spreadsheet wants:
// drops bold/italic/strike markers, link/image syntax (keeps visible text),
// inline-code backticks, and turns <br> into a space.
function cleanCell(raw) {
  let s = raw.trim()
  s = s.replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')   // image → alt text
  s = s.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')     // link  → label
  s = s.replace(/(\*\*|__)(.*?)\1/g, '$2')          // bold
  s = s.replace(/(\*|_)(.*?)\1/g, '$2')             // italic
  s = s.replace(/~~(.*?)~~/g, '$1')                 // strikethrough
  s = s.replace(/`([^`]*)`/g, '$1')                 // inline code
  s = s.replace(/<br\s*\/?>/gi, ' ')                // soft break inside a cell
  return s.trim()
}

/**
 * Extracts every GFM pipe table from a markdown string.
 * @returns {{headers: string[], rows: string[][]}[]} one entry per table, in
 *          document order. Body rows are padded/truncated to the header width.
 */
export function extractTables(markdown) {
  if (!markdown || typeof markdown !== 'string') return []
  const rawLines = markdown.split(/\r?\n/)

  // Mask fenced code blocks (``` … ``` / ~~~ … ~~~) to blank lines so a code
  // sample that happens to contain pipes isn't mistaken for a real table.
  const lines = []
  let fenceChar = null
  for (const line of rawLines) {
    const fm = line.match(/^\s*(`{3,}|~{3,})/)
    if (fenceChar) {
      if (fm && fm[1][0] === fenceChar) fenceChar = null
      lines.push('')
      continue
    }
    if (fm) { fenceChar = fm[1][0]; lines.push(''); continue }
    lines.push(line)
  }

  const tables = []
  for (let i = 0; i < lines.length - 1; i++) {
    const header = lines[i]
    const delim  = lines[i + 1]
    if (!header.trim()) continue
    if (!isDelimiterRow(delim)) continue
    // A genuine table has pipes; this rejects a Setext "heading\n---" pair.
    if (!header.includes('|') && !delim.includes('|')) continue

    const headers = splitRow(header).map(cleanCell)
    const cols = headers.length
    if (cols === 0) continue

    const rows = []
    let j = i + 2
    for (; j < lines.length; j++) {
      const ln = lines[j]
      if (!ln.trim() || !ln.includes('|')) break  // blank / non-row ends the table
      if (isDelimiterRow(ln)) break
      const cells = splitRow(ln).map(cleanCell)
      while (cells.length < cols) cells.push('')   // pad short rows
      if (cells.length > cols) cells.length = cols  // truncate long rows
      rows.push(cells)
    }

    tables.push({ headers, rows })
    i = j - 1  // resume scanning after this table
  }
  return tables
}

// A bare number/percentage we should NOT prefix-guard (so "-500" stays a number
// in Excel, not text). Allows sign, thousands separators, decimals, percent.
const NUMERIC = /^[-+]?[\d,]*\.?\d+%?$/

// Neutralises CSV/formula injection: a cell starting with = + - @ (or tab/CR)
// can execute in Excel. Prefix a single quote to force text — but skip plain
// numbers so finance figures aren't mangled.
function guardFormula(v) {
  if (v && /^[=+\-@\t\r]/.test(v) && !NUMERIC.test(v)) return "'" + v
  return v
}

// RFC-4180 field: quote when it contains a quote/comma/newline or edge spaces.
function csvCell(v) {
  const g = guardFormula(String(v ?? ''))
  if (/[",\r\n]/.test(g) || /^\s|\s$/.test(g)) {
    return '"' + g.replace(/"/g, '""') + '"'
  }
  return g
}

/**
 * Renders extracted tables as a single CSV document.
 * Multiple tables are separated by a blank line. Prepends a UTF-8 BOM so Excel
 * decodes non-ASCII (₹, é, …) correctly. Returns '' when there's nothing.
 */
export function tablesToCsv(tables) {
  if (!Array.isArray(tables) || tables.length === 0) return ''
  const blocks = tables.map(t =>
    [t.headers, ...t.rows].map(r => r.map(csvCell).join(',')).join('\r\n')
  )
  return '\uFEFF' + blocks.join('\r\n\r\n') + '\r\n'
}
