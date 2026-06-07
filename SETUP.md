# Setup & Contributor Guide

How to get Rudo (ShelfBot) running locally for development, using **your own
OpenAI API key**. Takes ~10 minutes.

The app has three parts — the **backend** (Java), the **proxy** (Node), and the
**desktop app** (Electron/React). For local dev you run all three. Your OpenAI
key lives **only in the proxy's local `.env`** (which is git-ignored); the
desktop app and backend never see it directly.

---

## 1. Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK  | **17+** | `java -version` should report 17 or higher |
| Maven| 3.6+    | `mvn -v` |
| Node | **18+** (20 recommended) | `node -v` |
| npm  | 9+      | ships with Node |
| Git  | any     | |
| Tesseract | optional | only needed to OCR images (scanned PDFs/photos). macOS: `brew install tesseract` |

You also need an **OpenAI API key** (`sk-...`) — get one at
<https://platform.openai.com/api-keys>. You pay OpenAI directly for what you use
in development.

---

## 2. Clone

```bash
git clone https://github.com/Rudransh-03/ShelfBot.git
cd ShelfBot
```

Layout:

```
backend/   Java RAG/indexing server
ui/        Electron + React desktop app
proxy/     Node auth/usage proxy (holds the OpenAI key)
```

---

## 3. Build the backend (Java)

The desktop app runs a **built JAR**, not the Java source — so you must build it
once up front (and rebuild after any Java change).

```bash
cd backend
mvn package -DskipTests        # produces target/local-file-brain-1.0.0.jar
```

Run the test suite any time with `mvn test`.

> **Important:** after editing any Java file, re-run `mvn package -DskipTests`.
> The UI launches the JAR, so unbuilt changes silently run stale code.

---

## 4. Run the proxy (with your OpenAI key)

The proxy is the only place your OpenAI key lives. It forwards the app's
requests to OpenAI and enforces simple per-day usage caps.

```bash
cd ../proxy
npm install
cp .env.example .env
```

Edit `.env`:

```ini
OPENAI_API_KEY=sk-your-own-key-here
# Generate a secret: node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
JWT_SECRET=paste-a-random-32+-char-string
FREE_DAILY=50            # daily chat/summary/deadline AI-call cap per device (dev: set generously)
PRO_DAILY=200
JWT_TTL_SECONDS=86400
PORT=8787
DB_PATH=./shelfbot-proxy.db
```

Start it:

```bash
npm start                 # listens on http://localhost:8787
```

Leave this running in its own terminal. `.env` and the proxy SQLite DB are
git-ignored, so your key never gets committed.

---

## 5. Run the desktop app

```bash
cd ../ui
npm install
npm run dev               # spawns the backend JAR + opens the Electron window
```

On first launch the app auto-registers this device with the proxy and gets a
token — no login screen. JS/JSX/CSS **hot-reload**; after a Java change, rebuild
the JAR (step 3) and restart the app.

Then, in the app:

1. **Settings** → add the folder(s) you want indexed → save.
2. **Library** → **Index Now** (or it indexes automatically as files change).
3. Use **Chat**, generate **summaries** in Library, try **Organize**, and open
   **Deadlines**.

---

## 6. Configuration reference

### `backend/config.properties` (optional, git-ignored)

The backend works with sensible defaults (proxy mode at `localhost:8787`, local
embeddings, indexing your Desktop/Downloads/Documents). Create
`backend/config.properties` only to customize:

```ini
# Folders to index (comma-separated for multiple)
files.root.paths=/Users/you/Documents,/Users/you/Desktop/work

# LLM routing: "proxy" (default, uses the proxy above) or "direct"
api.mode=proxy
api.proxy.url=http://localhost:8787

# Embeddings: "local" (default, on-device, no key) or "openai"
embedding.provider=local

# Deadline auto-scan: max LLM calls per UTC day (paces big library scans)
deadline.daily.call.budget=25
```

> `direct` mode talks to OpenAI straight from the backend using a local
> `openai.api.key` (handy for quick backend-only testing of chat). For the full
> feature set (summaries, deadlines), use the default **proxy** mode as above.

### Ports

- Backend HTTP API: **9876**
- Proxy: **8787**

---

## 7. Dev workflow & gotchas

- **Rebuild the JAR after Java changes**: `cd backend && mvn package -DskipTests`,
  then restart the app. UI changes hot-reload on their own.
- **Lucene single-writer lock**: only one backend can open
  `backend/shelfbot-vector-index/` at a time. If a previous run was force-quit
  and the new one fails with a lock error, clear orphans:
  ```bash
  lsof -ti :9876 | xargs kill
  ```
  (The app also sweeps orphaned JVMs on startup.)
- **Never delete** `shelfbot-vector-index/` or `shelfbot-metadata.db` casually —
  re-indexing a large library is slow.
- **Backend tests**: `cd backend && mvn test`. Live tests that hit OpenAI are
  skipped unless explicitly enabled.
- **Deadlines is gated to Pro** in product terms, but is dev-unlocked for now via
  the `DEADLINES_DEV_UNLOCK` flag in
  `ui/src/renderer/src/context/AppContext.jsx` (set it to `false` to see the
  upgrade-gated behavior).

## 8. Backend API (quick reference)

`GET/POST /api/{health,status,index,query,query/stream,config,auth,files,files/summary}`
· `/api/conversations`, `/api/conversations/{id}`
· `/api/reorg/{preview,execute,undo,history}`
· `/api/deadlines` (list / `{id}` patch / `{id}` delete), `/api/deadlines/scan` (start / poll)

---

Questions or something not working? Open an issue with your OS, Java/Node
versions, and the relevant logs from the terminal running `npm run dev`.
