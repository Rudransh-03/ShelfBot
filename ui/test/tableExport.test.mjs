import { test } from 'node:test'
import assert from 'node:assert/strict'
import { extractTables, tablesToCsv, extractionToCsv } from '../src/renderer/src/utils/tableExport.js'

const cols = [{ name: 'Invoice Date' }, { name: 'Total Amount' }]
const rows = [
  { fileName: 'INV-1.pdf', cells: [
    { name: 'Invoice Date', value: '03/04/2025', status: 'AMBIGUOUS', note: 'ambiguous' },
    { name: 'Total Amount', value: '$15,000', status: 'OK', note: '' },
  ] },
  { fileName: 'INV-2.pdf', cells: [
    { name: 'Invoice Date', value: '', status: 'MISSING', note: '' },
    { name: 'Total Amount', value: '$900', status: 'OK', note: '' },
  ] },
]

test('extractionToCsv: dynamic columns + Source File column', () => {
  const csv = extractionToCsv(cols, rows)
  const lines = csv.replace('﻿', '').trim().split('\r\n')
  assert.equal(lines[0], 'Invoice Date,Total Amount,Source File')
})

test('extractionToCsv: preserves ambiguity metadata inline', () => {
  const csv = extractionToCsv(cols, rows)
  assert.match(csv, /03\/04\/2025 \[AMBIGUOUS\]/)
})

test('extractionToCsv: missing cell is blank, confident cell is plain', () => {
  const csv = extractionToCsv(cols, rows).replace('﻿', '')
  // row 2: blank date, $900 amount, INV-2 source
  assert.match(csv, /\r\n,\$900,INV-2\.pdf/)
})

test('extractionToCsv: UTF-8 BOM present so Excel renders ₹/non-ASCII', () => {
  const csv = extractionToCsv(cols, rows)
  assert.ok(csv.startsWith('﻿'))
})

test('extractionToCsv: arbitrary column count', () => {
  const many = [{ name: 'A' }, { name: 'B' }, { name: 'C' }, { name: 'D' }]
  const r = [{ fileName: 'x.pdf', cells: many.map(c => ({ name: c.name, value: c.name, status: 'OK' })) }]
  const csv = extractionToCsv(many, r).replace('﻿', '')
  assert.equal(csv.trim().split('\r\n')[0], 'A,B,C,D,Source File')
})

test('extractionToCsv: formula-injection guard on a leading =', () => {
  const r = [{ fileName: 'x.pdf', cells: [{ name: 'Invoice Date', value: '=SUM(A1)', status: 'OK' }] }]
  const csv = extractionToCsv([{ name: 'Invoice Date' }], r)
  assert.match(csv, /'=SUM\(A1\)/) // prefixed with a quote so Excel treats it as text
})

test('extractionToCsv: empty inputs → empty string', () => {
  assert.equal(extractionToCsv([], []), '')
  assert.equal(extractionToCsv(null, null), '')
})

test('extractTables + tablesToCsv still parse a markdown pipe table', () => {
  const md = '| A | B |\n|---|---|\n| 1 | 2 |'
  const t = extractTables(md)
  assert.equal(t.length, 1)
  assert.deepEqual(t[0].headers, ['A', 'B'])
  const csv = tablesToCsv(t).replace('﻿', '').trim()
  assert.equal(csv, 'A,B\r\n1,2')
})
