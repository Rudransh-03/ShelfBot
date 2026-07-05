import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildExtractionReport, buildBulkQaReport, buildAnswerReport } from '../src/renderer/src/utils/reportTemplates.js'

const cols = [{ name: 'Invoice Date' }, { name: 'Total Amount' }]
const rows = [
  { fileName: 'INV-1.pdf', cells: [
    { name: 'Invoice Date', value: '03/04/2025', status: 'AMBIGUOUS', note: 'could be DD/MM or MM/DD' },
    { name: 'Total Amount', value: '$15,000', status: 'OK', note: '' },
  ] },
  { fileName: 'INV-2.pdf', cells: [
    { name: 'Invoice Date', value: '', status: 'MISSING', note: '' },
    { name: 'Total Amount', value: '$900', status: 'OK', note: '' },
  ] },
]

test('extraction report: ambiguity fidelity — flagged field styled distinctly', () => {
  const html = buildExtractionReport({ title: 'Extraction Report', columns: cols, rows })
  assert.ok(html.startsWith('<!doctype html>'))
  assert.match(html, /td class="ambiguous"/)
  assert.ok(html.includes('⚠ ambiguous'))
  assert.match(html, /could be DD\/MM/)                 // note preserved
  assert.ok(html.includes('>$15,000</td>'))             // confident cell NOT ambiguous-styled
  assert.match(html, /td class="missing">—/)            // missing muted
  assert.ok(html.includes('review before relying'))     // legend appears with ambiguity
  assert.ok(html.includes('INV-1.pdf'))                 // source preserved
})

test('extraction report: no legend when nothing is ambiguous', () => {
  const clean = [{ fileName: 'a.pdf', cells: [{ name: 'Invoice Date', value: '2025-01-01', status: 'OK' }] }]
  const html = buildExtractionReport({ title: 'X', columns: [{ name: 'Invoice Date' }], rows: clean })
  assert.ok(!html.includes('review before relying'))
})

test('bulk q&a report: Document/Answer/Source + question subtitle', () => {
  const html = buildBulkQaReport({ title: 'Bulk Q&A Report', question: 'Auto-renewal?',
    rows: [{ fileName: 'c.pdf', cells: [{ name: 'Answer', value: 'Yes', status: 'OK' }] }] })
  assert.ok(html.includes('<th>Document</th>') && html.includes('<th>Answer</th>'))
  assert.ok(html.includes('Auto-renewal?'))
})

test('answer report: bold + citations with page preserved', () => {
  const html = buildAnswerReport({ title: 'Contract Summary',
    answerText: 'The lease renews **March 1, 2027**.',
    sources: [{ fileName: 'Lease.pdf', pages: [3] }] })
  assert.ok(html.includes('<strong>March 1, 2027</strong>'))
  assert.ok(html.includes('Lease.pdf') && html.includes('p. 3'))
})

test('reports escape untrusted content (no HTML injection from document text)', () => {
  const evil = [{ fileName: '<img src=x onerror=alert(1)>.pdf',
    cells: [{ name: 'Invoice Date', value: '<script>alert(1)</script>', status: 'OK' }] }]
  const html = buildExtractionReport({ title: 'X', columns: [{ name: 'Invoice Date' }], rows: evil })
  assert.ok(!html.includes('<script>alert(1)</script>'))
  assert.ok(!html.includes('<img src=x'))
  assert.ok(html.includes('&lt;script&gt;'))
})

test('answer report escapes malicious answer text', () => {
  const html = buildAnswerReport({ title: 'X', answerText: '<script>bad()</script>', sources: [] })
  assert.ok(!html.includes('<script>bad()</script>'))
})
