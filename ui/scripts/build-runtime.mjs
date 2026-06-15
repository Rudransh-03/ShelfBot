// Builds a slim, self-contained Java runtime (via jlink) that ships INSIDE the
// packaged desktop app, so end users never need Java installed. Output goes to
// backend/target/runtime/ and is picked up by electron-builder's extraResources.
//
// IMPORTANT: jlink produces a runtime for THIS machine's OS + CPU arch only.
// The macOS/Windows/Linux installers are each built on their own OS (locally or
// in CI) — you cannot cross-build a Windows runtime from a Mac here.
//
// Requires JDK 17 on PATH (or via JAVA_HOME). Run automatically by `npm run dist`.

import { execFileSync } from 'node:child_process'
import { rmSync, existsSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here  = dirname(fileURLToPath(import.meta.url))
const isWin = process.platform === 'win32'
const exe   = (n) => (isWin ? `${n}.exe` : n)

// Resolve jlink/java from JAVA_HOME when set, else rely on PATH.
const jhome = process.env.JAVA_HOME
const tool  = (n) => (jhome ? join(jhome, 'bin', exe(n)) : exe(n))

const OUT = join(here, '..', '..', 'backend', 'target', 'runtime')

// Module set. java.se is the full Java SE API (java.desktop / java.xml /
// java.sql / java.naming / crypto / … — everything Tika, POI and Lucene touch),
// plus the jdk.* modules a document/RAG app actually hits at runtime:
//   jdk.unsupported  – sun.misc.Unsafe (Lucene's MMap cleaner)
//   jdk.crypto.ec    – EC cipher suites for TLS to OpenAI / the proxy
//   jdk.charsets     – decoding non-Latin text during extraction
//   jdk.zipfs        – zip/jar NIO filesystem (docx/xlsx are zip containers)
//   jdk.localedata   – non-US date / number / locale handling
//
// We deliberately do NOT auto-minimise via `jdeps --print-module-deps`: the
// 179 MB shaded JAR re-bundles javax.xml.* etc., which makes jdeps emit
// "split package" noise and an unreliable list. A known-good broad set that we
// verify with a real run is safer than a fragile minimal one.
const MODULES = [
  'java.se',
  'jdk.httpserver',   // com.sun.net.httpserver.HttpServer — the backend's HTTP server
  'jdk.unsupported',
  'jdk.crypto.ec',
  'jdk.charsets',
  'jdk.zipfs',
  'jdk.localedata',
].join(',')

console.log('[build-runtime] JAVA_HOME =', jhome || '(using PATH)')
try {
  execFileSync(tool('java'), ['-version'], { stdio: 'inherit' })
} catch {
  console.error('[build-runtime] ERROR: JDK 17 not found. Install it or set JAVA_HOME.')
  process.exit(1)
}

if (existsSync(OUT)) rmSync(OUT, { recursive: true, force: true })

console.log(`[build-runtime] jlink → ${OUT}`)
execFileSync(tool('jlink'), [
  '--add-modules', MODULES,
  '--strip-debug',
  '--no-header-files',
  '--no-man-pages',
  '--compress=2',
  '--output', OUT,
], { stdio: 'inherit' })

// Smoke-test the produced runtime so a broken jlink fails the build loudly.
execFileSync(join(OUT, 'bin', exe('java')), ['-version'], { stdio: 'inherit' })
console.log(`[build-runtime] OK — runtime ready at ${OUT}`)
