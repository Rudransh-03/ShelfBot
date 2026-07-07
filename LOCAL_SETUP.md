# ShelfBot (Rudo) — Local Development Setup on macOS (Beginner Guide)

This guide walks you through running ShelfBot on **macOS** from scratch.
It assumes you have never set up a project like this before.

**Platform:** macOS only (Apple Silicon or Intel). All paths and commands below are for Mac.

**Time needed:** about 15–30 minutes (mostly waiting for downloads and the first build).

**What you are building:** a desktop app that indexes your local files, lets you chat
with them, summarizes documents, finds deadlines, and helps reorganize folders — using
AI for answers while keeping your file contents on your machine.

---

## Table of contents

1. [What runs on your machine](#1-what-runs-on-your-machine)
2. [Where each secret / key goes](#2-where-each-secret--key-goes)
3. [Prerequisites — install these first](#3-prerequisites--install-these-first)
4. [Clone the repository](#4-clone-the-repository)
5. [Terminal layout (you need three)](#5-terminal-layout-you-need-three)
6. [Step A — Build the backend (Java)](#6-step-a--build-the-backend-java)
7. [Step B — Configure and run the proxy (Node)](#7-step-b--configure-and-run-the-proxy-node)
8. [Step C — Install and run the desktop app (Electron)](#8-step-c--install-and-run-the-desktop-app-electron)
9. [First launch — what to do in the app](#9-first-launch--what-to-do-in-the-app)
10. [Optional backend config (`config.properties`)](#10-optional-backend-config-configproperties)
11. [Optional: OCR for scanned PDFs and images](#11-optional-ocr-for-scanned-pdfs-and-images)
12. [Where data is stored on disk](#12-where-data-is-stored-on-disk)
13. [How the pieces talk to each other](#13-how-the-pieces-talk-to-each-other)
14. [Daily dev workflow](#14-daily-dev-workflow)
15. [Troubleshooting](#15-troubleshooting)
16. [Quick reference cheat sheet](#16-quick-reference-cheat-sheet)

---

## 1. What runs on your machine

ShelfBot is **not** one program. It is **three separate processes** that work together:

| # | Folder   | What it is              | Port  | You start it with        |
|---|----------|---------------------------|-------|--------------------------|
| 1 | `backend/` | Java “brain” — indexing, search, RAG | **9876** | Automatically when you run the UI (or manually for debugging) |
| 2 | `proxy/`   | Node server — holds your OpenAI key, auth, usage limits | **8787** | `npm start` in `proxy/` |
| 3 | `ui/`      | Electron desktop app — the window you click around in | —     | `npm run dev` in `ui/` |

```
┌─────────────────┐     spawns      ┌──────────────────┐
│  ui/ (Electron) │ ──────────────▶ │ backend/ (Java)  │
│  Desktop window │   HTTP :9876    │ Index + RAG      │
└────────┬────────┘                 └────────┬─────────┘
         │                                   │
         │ register device / JWT               │ chat + (optional) embeddings
         ▼                                   ▼
┌─────────────────────────────────────────────────────┐
│              proxy/ (Node)  :8787                    │
│  Your OpenAI key lives HERE only                     │
└────────────────────────┬────────────────────────────┘
                         ▼
                   OpenAI API (gpt-4o-mini, etc.)
```

**Important for beginners:** you must have the **proxy running** before (or when) you
use Chat, Summaries, Deadlines, or Organize. Indexing alone can work without the proxy
because embeddings run **locally** on your CPU by default.

---

## 2. Where each secret / key goes

This is the most common source of confusion. Read this section carefully.

### The one key you need: OpenAI API key

| Secret | Where it goes | Who reads it | Never put it in |
|--------|---------------|--------------|-----------------|
| **`OPENAI_API_KEY`** (`sk-...`) | `proxy/.env` | Proxy server only | UI code, backend code, git commits, Slack, screenshots |

Get a key at: https://platform.openai.com/api-keys

You pay OpenAI directly for usage during development.

### Other proxy secrets (also in `proxy/.env`)

| Variable | What it is | Example |
|----------|------------|---------|
| **`JWT_SECRET`** | Random string used to sign login tokens for your device. **Not** your OpenAI key. | Generate with: `node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"` |
| **`FREE_DAILY`** / **`PRO_DAILY`** | How many AI chat calls per device per day (dev: set high, e.g. 50/200) | `50` / `200` |
| **`PORT`** | Proxy HTTP port | `8787` |
| **`DB_PATH`** | Where proxy stores device/usage data | `./shelfbot-proxy.db` |

### What the desktop app stores (automatic — you don't edit these)

| Item | Location | Purpose |
|------|----------|---------|
| Device ID | `~/Library/Application Support/shelfbot/device.id` | Identifies your Mac to the proxy (no email login) |
| Auth JWT | `~/Library/Application Support/shelfbot/auth.token` (encrypted via **macOS Keychain**) | Proves to proxy/backend that you're registered |

Full app data folder:

```
~/Library/Application Support/shelfbot/
├── device.id      ← stable device fingerprint
└── auth.token     ← encrypted JWT (do not edit by hand)
```

### What the Java backend needs (usually **no** OpenAI key)

| Setting | Default | Where |
|---------|---------|-------|
| `api.mode` | `proxy` | `backend/config.properties` (optional) |
| `api.proxy.url` | `http://localhost:8787` | same |
| `embedding.provider` | `local` (on-device, free) | same |
| `openai.api.key` | *(empty)* | Only if you use `api.mode=direct` (advanced) |

**Default setup:** backend talks to proxy; proxy talks to OpenAI. Your `sk-...` key
never touches the Java process or the React UI.

### Optional UI environment variable

| Variable | Default | When to change |
|----------|---------|----------------|
| `SHELFBOT_PROXY_URL` | `http://localhost:8787` | If proxy runs on another host/port |

Example:

```bash
SHELFBOT_PROXY_URL=http://localhost:8787 npm run dev
```

---

## 3. Prerequisites — install these first (macOS)

Open **Terminal** (`Applications → Utilities → Terminal`, or search Spotlight for
"Terminal") and run the check commands below. If any command fails, install that tool
before continuing.

### 3.1 Install Homebrew (if you don't have it)

Homebrew is the standard way to install dev tools on Mac. Check:

```bash
brew --version
```

If that fails, install Homebrew from https://brew.sh:

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

Follow the post-install instructions Homebrew prints (adding `brew` to your `PATH` —
especially important on Apple Silicon Macs).

### 3.2 Required tools

| Tool | Minimum version | Check | Install on Mac |
|------|-----------------|-------|----------------|
| **Git** | any | `git --version` | `xcode-select --install` (includes Git) or `brew install git` |
| **Java (JDK)** | **17+** | `java -version` | See [Java setup](#33-java-setup-on-mac) below |
| **Maven** | 3.6+ | `mvn -v` | `brew install maven` |
| **Node.js** | **18+** (20 recommended) | `node -v` | `brew install node@20` |
| **npm** | 9+ | `npm -v` | Bundled with Node |

**Java tip:** `java -version` should say `openjdk version "17"` or `"21"`.
If you only have Java 8 (common on older Macs), the backend will not build.

### 3.3 Java setup on Mac

Install JDK 17 via Homebrew:

```bash
brew install openjdk@17
```

Homebrew will print a `export PATH=...` line — **run that command** (or add it to
`~/.zshrc` so it persists). Typical Apple Silicon path:

```bash
echo 'export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

Intel Macs often use `/usr/local/opt/openjdk@17/bin` instead — use whatever `brew`
prints after install.

Verify:

```bash
java -version
javac -version
```

Both should report version 17 or higher.

### 3.4 Optional but recommended

| Tool | Why | Install on Mac |
|------|-----|----------------|
| **Tesseract OCR** | Read text from scanned PDFs and images (IDs, receipts) | `brew install tesseract` |
| **OpenAI account + API key** | Required for Chat, summaries, deadlines, organize | https://platform.openai.com |

After installing Tesseract:

```bash
tesseract --version
```

---

## 4. Clone the repository

```bash
git clone https://github.com/Rudransh-03/ShelfBot.git
cd ShelfBot
```

You should see three main folders:

```
ShelfBot/
├── backend/    ← Java server (Maven project)
├── proxy/      ← Node auth + OpenAI proxy
└── ui/         ← Electron + React desktop app
```

---

## 5. Terminal layout (you need three)

On Mac, use **Terminal** or **iTerm2**. For local dev, keep **three tabs** open
(⌘T opens a new tab in Terminal):

| Terminal | Directory | Command | Leave running? |
|----------|-----------|---------|----------------|
| **1 — Proxy** | `ShelfBot/proxy` | `npm start` | Yes |
| **2 — UI** | `ShelfBot/ui` | `npm run dev` | Yes (spawns backend automatically) |
| **3 — Work** | anywhere | build/tests/editing | As needed |

You only run `mvn package` in terminal 3 when you change Java code (see below).

---

## 6. Step A — Build the backend (Java)

The desktop app does **not** run `.java` source files. It runs a **compiled JAR file**.
You must build it at least once.

```bash
cd backend
mvn package -DskipTests
```

**What this does:**

- Downloads Java dependencies (first run can take several minutes)
- Compiles all Java code
- Creates: `backend/target/local-file-brain-1.0.0.jar`

**Verify it worked:**

```bash
ls -la target/local-file-brain-1.0.0.jar
```

You should see a `.jar` file (roughly 100MB+ depending on bundled deps).

**Optional — run tests:**

```bash
mvn test
```

**When to rebuild:** every time you change any file under `backend/src/`. Then restart
the desktop app (press **Ctrl+C** in the UI terminal tab, then `npm run dev` again).

> **Gotcha:** If you edit Java but forget to rebuild, the app silently runs **old code**
> from the previous JAR. Always rebuild after Java changes.

### (Optional) Run backend alone for debugging

```bash
cd backend
java -Djava.awt.headless=true -Xmx512m -jar target/local-file-brain-1.0.0.jar --server --port 9876
```

Wait for this line in the output:

```
SHELFBOT_SERVER_READY:9876
```

Test in another terminal:

```bash
curl http://localhost:9876/api/health
```

Expected: JSON with `"status"` and `"app"` fields.

---

## 7. Step B — Configure and run the proxy (Node)

The proxy is the **only** place your OpenAI API key should live.

### 7.1 Install dependencies

```bash
cd proxy
npm install
```

### 7.2 Create your `.env` file

```bash
cp .env.example .env
```

Open `proxy/.env` in TextEdit, VS Code, Cursor, or `nano`:

```bash
nano .env
# save with Ctrl+O, exit with Ctrl+X
```

### 7.3 Fill in every value (explained line by line)

```ini
# ── REQUIRED ─────────────────────────────────────────────────────────────

# Your OpenAI secret key. Starts with sk-
# This is the ONLY copy of this key you need for normal dev.
OPENAI_API_KEY=sk-paste-your-real-key-here

# Random secret for signing JWTs — NOT your OpenAI key.
# Generate one in terminal:
#   node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
JWT_SECRET=paste-the-long-random-hex-string-here

# ── USAGE LIMITS (raise these for local dev) ───────────────────────────────

# Max AI chat/completion calls per device per calendar day (UTC)
FREE_DAILY=50
PRO_DAILY=200

# How long each device token stays valid (seconds). 86400 = 1 day, 2592000 = 30 days
JWT_TTL_SECONDS=86400

# ── SERVER ─────────────────────────────────────────────────────────────────

PORT=8787
DB_PATH=./shelfbot-proxy.db

# ── OPTIONAL (reorg / organize feature) ───────────────────────────────────
# These exist in server.js but are missing from .env.example — add them if needed:

# FREE_REORG_DAILY=20
# PRO_REORG_DAILY=20
# REORG_LLM_BUDGET=50
# REORG_SESSION_TTL_MIN=30
# OPENAI_BASE_URL=https://api.openai.com/v1
```

**Security rules:**

- `proxy/.env` is in `.gitignore` — it will **not** be committed to git
- Never paste your `sk-...` key into Discord, GitHub issues, or `backend/config.properties`
- If you leak a key, revoke it at https://platform.openai.com/api-keys and create a new one

### 7.4 Start the proxy

```bash
npm start
```

**Success looks like:**

```
[proxy] listening on http://localhost:8787
```

(no error about `JWT_SECRET` or `OPENAI_API_KEY`)

**Verify:**

```bash
curl http://localhost:8787/health
```

Expected: JSON with `"ok": true` and plan limits.

**Leave this terminal tab open.** Stopping the proxy (**Ctrl+C**) breaks Chat and other AI features.

### 7.5 (Optional) Auto-restart on file changes

```bash
npm run dev
```

Uses Node's `--watch` to restart when you edit proxy code.

---

## 8. Step C — Install and run the desktop app (Electron)

Make sure:

1. Backend JAR exists (`backend/target/local-file-brain-1.0.0.jar`)
2. Proxy is running on port 8787

Then:

```bash
cd ui
npm install
npm run dev
```

**What happens on `npm run dev`:**

1. Vite starts the React dev server (hot reload for UI code)
2. Electron opens a desktop window
3. Electron **automatically spawns** the Java backend JAR
4. Electron registers your device with the proxy and saves a JWT
5. The UI connects to `http://localhost:<backend-port>` (usually 9876)

**First launch may take 1–2 minutes** while the local embedding model downloads to
`~/.shelfbot/models/bge-small-en-v1.5/` (one-time, ~100MB).

### What you should see

- A ShelfBot/Rudo window with sidebar: Chat, Library, Deadlines, Organize, Settings
- Terminal logs like `[ShelfBot] Starting Java on port 9876`
- `[Java] SHELFBOT_SERVER_READY:9876`
- `[ShelfBot] proxy url: http://localhost:8787`

### If the JAR is missing

```
[ShelfBot] JAR not found — run `mvn package` inside backend/
```

Go back to [Step A](#6-step-a--build-the-backend-java).

---

## 9. First launch — what to do in the app

Follow these in order:

### 9.1 Welcome wizard (first time only)

1. Intro screen → Next
2. **Pick folders** to index (Documents, a project folder, etc.)
3. Privacy info → Finish

This saves folder paths to the backend via the Settings API.

### 9.2 Index your files

1. Open **Library** in the sidebar
2. Click **Index Now**
3. Watch progress (extracting → chunking → embedding → saving)

Indexing uses **local embeddings** by default — no OpenAI calls for this step.

### 9.3 Try Chat

1. Open **Chat**
2. Ask something about a file you indexed, e.g. *"What documents mention invoices?"*
3. You should see a streaming answer with source citations

If Chat says "session expired" or quota errors:

- Confirm proxy is running (`curl http://localhost:8787/health`)
- Check `OPENAI_API_KEY` in `proxy/.env`
- Restart the app

### 9.4 Other features (all need proxy + valid OpenAI key)

| Feature | Tab | What it does |
|---------|-----|--------------|
| Summaries | Library → Summarise on a file | One-page AI summary |
| Deadlines | Deadlines | Scans docs for due dates (dev-unlocked by default) |
| Organize | Organize | AI folder cleanup proposal |

---

## 10. Optional backend config (`config.properties`)

You usually **don't** need this file. Defaults work for local dev.

Create it only if you want custom folders, direct OpenAI mode, or OpenAI embeddings:

**File path:** `backend/config.properties`  
(git-ignored — safe for local paths; still **don't** put your OpenAI key here unless using direct mode)

```ini
# ── Folders to index (comma-separated macOS absolute paths) ──
# Replace YOUR_USERNAME with your Mac login name (run `whoami` in Terminal)
files.root.paths=/Users/YOUR_USERNAME/Documents,/Users/YOUR_USERNAME/Desktop

# ── How the backend reaches OpenAI ──
# "proxy" = normal (recommended). Backend → proxy :8787 → OpenAI
api.mode=proxy
api.proxy.url=http://localhost:8787

# ── Embeddings ──
# "local" = free on-device model (default, recommended)
# "openai" = cloud embeddings via proxy (costs money, 1536-dim)
embedding.provider=local

# ── Deadline scanning pace ──
deadline.daily.call.budget=25

# ── ADVANCED: direct mode (skip proxy — NOT recommended for full app dev) ──
# api.mode=direct
# openai.api.key=sk-...
```

**Note:** The backend reads `config.properties` from its **working directory**, which is
`backend/` when launched by Electron (`cwd: backend/` in the spawn command).

You can also change indexed folders from the app: **Settings → Indexed folders**.

---

## 11. Optional: OCR for scanned PDFs and images (macOS)

Without Tesseract, digital PDFs and text files still work. Scanned PDFs and photos
(JPG, PNG, etc.) won't extract text.

```bash
brew install tesseract
tesseract --version
```

Restart the app. Check **Settings → Services** for OCR availability.

---

## 12. Where data is stored on your Mac

All paths below use `~` = your home folder (e.g. `/Users/shresth1811`).

| Data | Location on Mac | Safe to delete? |
|------|-----------------|-----------------|
| Vector search index | `ShelfBot/backend/shelfbot-vector-index/` | Forces full re-index (slow) |
| File metadata, deadlines, summaries | `ShelfBot/backend/shelfbot-metadata.db` | Loses index metadata |
| Chat history | `ShelfBot/backend/shelfbot-chats.db` | Loses conversations |
| Local embedding model | `~/.shelfbot/models/bge-small-en-v1.5/` | Re-downloads on next run |
| Proxy devices/usage | `ShelfBot/proxy/shelfbot-proxy.db` | Device re-registers; usage resets |
| App auth token + device ID | `~/Library/Application Support/shelfbot/` | Re-register on next launch |

**Finder tip:** press **⌘⇧G** (Go to Folder) and paste a path to jump there quickly.

**Never delete** `shelfbot-vector-index/` or `shelfbot-metadata.db` casually on a large library.

---

## 13. How the pieces talk to each other

### Auth flow (happens automatically)

```
1. Electron generates/loads device ID
2. POST http://localhost:8787/device/register  { deviceId: "..." }
3. Proxy returns JWT
4. Electron saves JWT (macOS Keychain via `safeStorage`)
5. Electron POST http://localhost:9876/api/auth  { token: "..." }
6. Java backend stores JWT in memory
7. All AI requests: Java → proxy (Bearer JWT) → OpenAI
```

### A Chat question (simplified)

```
UI (React)
  → POST /api/query/stream  (localhost:9876, SSE)
    → QueryEngine embeds question locally
    → VectorStore finds relevant chunks (Lucene)
    → GPT4oMiniClient → proxy /proxy/chat/completions → OpenAI
  ← streamed tokens back to UI
```

### Ports summary

| Service | URL | Used by |
|---------|-----|---------|
| Backend | `http://localhost:9876` | UI (all features) |
| Proxy | `http://localhost:8787` | UI (device register) + Backend (AI calls) |

---

## 14. Daily dev workflow

| What you changed | What to do |
|------------------|------------|
| React/CSS/JS in `ui/` | Save file — hot reload (no restart) |
| Java in `backend/` | `cd backend && mvn package -DskipTests` → restart `npm run dev` |
| Proxy in `proxy/` | Restart `npm start` (or use `npm run dev`) |
| `proxy/.env` | Restart proxy only |
| `backend/config.properties` | Restart app (restart Java backend) |

### Run backend tests

```bash
cd backend
mvn test
```

### Run proxy tests

```bash
cd proxy
npm test
```

---

## 15. Troubleshooting

### "JAR not found"

```bash
cd backend && mvn package -DskipTests
```

### Proxy won't start: `JWT_SECRET missing` or `OPENAI_API_KEY missing`

Edit `proxy/.env` — both must be set. `JWT_SECRET` must be at least 16 characters.

### Port already in use (9876 or 8787)

**Find what's using the port:**

```bash
lsof -i :9876
lsof -i :8787
```

**Kill stale backend process:**

```bash
lsof -ti :9876 | xargs kill
```

The Electron app also sweeps orphaned Java processes on startup (via `pgrep` on Mac).

### Lucene `write.lock` / backend won't start after force-quit

```bash
rm -f backend/shelfbot-vector-index/write.lock
lsof -ti :9876 | xargs kill
```

Then restart `npm run dev`.

### Chat works but always says quota / session expired

- Check proxy logs for errors
- Verify `OPENAI_API_KEY` is valid (not expired/revoked)
- Try **Settings → Log out** (if available) or delete `~/Library/Application Support/shelfbot/auth.token` and restart

### Indexing works but Chat doesn't

- Proxy must be running
- Backend `api.mode` should be `proxy` (default)
- Check backend can reach proxy: look for 401/502 errors in `[Java/err]` logs

### "Full Disk Access" warning in Library

macOS blocks apps from reading certain folders (Desktop, Documents, Downloads, iCloud)
unless you grant permission.

1. Open **System Settings → Privacy & Security → Full Disk Access**
2. Click **+** and add **Terminal** (if you run `npm run dev` from Terminal) and/or
   the built ShelfBot app once packaged
3. Toggle access **on**, then restart the app

For dev, start by indexing a folder you own outright (e.g. `~/Projects`) before
pointing at Desktop/Documents.

### First index is very slow

Normal. The app downloads the embedding model and processes every file. Large PDFs and
OCR take longer.

### Changed Java code but behavior didn't change

You forgot to rebuild the JAR. Run `mvn package -DskipTests` again.

### `java: command not found` or wrong Java version

Your JDK 17 PATH is not set. Re-run the Homebrew `export PATH=...` line from
[§3.3](#33-java-setup-on-mac) and open a **new** Terminal tab.

### Homebrew `node` vs nvm conflicts

If `node -v` shows an old version, check what Terminal is using:

```bash
which node
which java
```

Fix by using Homebrew's Node (`brew install node@20`) or ensuring your version manager
(nvm, fnm) loads in `~/.zshrc` before running `npm install`.

---

## 16. Quick reference cheat sheet

```bash
# One-time / after clone
cd backend && mvn package -DskipTests
cd ../proxy && npm install && cp .env.example .env   # then edit .env
cd ../ui && npm install

# Every dev session (two terminals)
# Terminal 1:
cd proxy && npm start

# Terminal 2:
cd ui && npm run dev

# After Java edits:
cd backend && mvn package -DskipTests
# then restart ui (Ctrl+C, npm run dev)
```

| Key / setting | File |
|---------------|------|
| `OPENAI_API_KEY` | `proxy/.env` |
| `JWT_SECRET` | `proxy/.env` |
| Indexed folders | App Settings UI, or `backend/config.properties` |
| Proxy URL | Default `http://localhost:8787`; override with `SHELFBOT_PROXY_URL` for UI |
| Backend API URL | Auto — Electron picks port and sends to renderer |

---

## Related docs

- **[SETUP.md](SETUP.md)** — shorter contributor-focused guide
- **[functional-breakdown.md](functional-breakdown.md)** — full system architecture for engineers
- **[README.md](README.md)** — product overview

If something still fails, open an issue with your **macOS version** (Apple menu → About
This Mac), `java -version`, `node -v`, and the full Terminal output from `npm start`
(proxy) and `npm run dev` (ui).
