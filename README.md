# Rudo (ShelfBot)

**A local-first, private desktop assistant that understands your documents.**

Rudo indexes the files already on your machine, answers questions about them,
organizes them, and proactively surfaces the deadlines buried inside them — all
while keeping your file contents on your device. It's a Retrieval-Augmented
Generation (RAG) app wrapped in a native desktop UI.

---

## What it does

- **Chat with your files** — ask questions in natural language and get answers
  grounded in your own documents, with citations to the source files. Streaming
  responses, multi-chat history, and full-text search across past chats.
- **Library** — index folders, monitor what's indexed, generate one-page
  document summaries, and manage the knowledge base.
- **Organize** — AI-assisted file reorganization: preview a proposed tidy-up of
  a messy folder, approve it, and undo it if needed.
- **Deadlines** — scans your documents for deadlines, renewals, and required
  actions, then turns the ones you choose into **calendar reminders** (via the
  cross-platform `.ics` standard, so they fire even when the app is closed).

## Privacy / local-first

- File **indexing and embeddings run entirely on-device** (a local
  `bge-small-en-v1.5` ONNX model) — your file contents are never uploaded just
  to be searchable.
- Only the text needed to **answer a question / summarize / extract deadlines**
  is sent to the language model, and that goes through a thin proxy that
  **stores no chat content**.

---

## Architecture

Three independent components:

| Component   | Stack                              | Role |
|-------------|------------------------------------|------|
| `backend/`  | Java 17, Maven, Lucene, Apache Tika, ONNX Runtime | The brain. HTTP server on **port 9876**. Indexing, retrieval/RAG, chat, summaries, reorg, deadline extraction. Runs as a built JAR. |
| `ui/`       | Electron, React, Vite              | The desktop app. Spawns the backend JAR and talks to it over HTTP. |
| `proxy/`    | Node.js, SQLite                    | Auth'd OpenAI passthrough + licensing/usage metering. Keyed by `device_id` (no user accounts). Default port **8787**. Holds the OpenAI key server-side; **stores no chat content**. |

```
                ┌────────────┐   spawns    ┌─────────────┐
                │   ui/      │────────────▶│  backend/   │
                │ (Electron) │   HTTP 9876 │ (Java JAR)  │
                └─────┬──────┘             └──────┬──────┘
                      │ device register / JWT     │ chat / embeddings
                      ▼                           ▼
                ┌──────────────────────────────────────┐
                │              proxy/ (Node)            │
                │   auth + usage caps + OpenAI passthru │
                └───────────────────┬──────────────────┘
                                    ▼
                              OpenAI API
```

### How it works (data flow)

1. **Index** — `backend` scans your folders → extracts text (Tika, with OCR for
   images when Tesseract is installed) → chunks it → embeds each chunk **locally**
   → stores vectors + text in an embedded Lucene HNSW index.
2. **Ask** — your question is embedded, the most relevant chunks are retrieved,
   and an LLM (a `gpt-4o-mini`-class model) writes a grounded answer with
   citations.
3. **Deadlines** — a local regex prefilter narrows documents to date-bearing
   passages (free), then a single batched LLM call per group of documents
   extracts structured deadlines; you review and turn them into `.ics` reminders.

## Tech highlights

- **Embedded vector store**: Apache Lucene HNSW (`shelfbot-vector-index/`), no
  external DB.
- **Local embeddings**: `bge-small-en-v1.5` (384-dim) via ONNX Runtime — zero
  per-token cost, fully offline indexing.
- **Metadata**: local SQLite (`shelfbot-metadata.db` for the index,
  `shelfbot-chats.db` for chats).
- **LLM**: OpenAI (`gpt-4o-mini` class) routed through the proxy.

---

## Repository layout

```
backend/   Java RAG/indexing server (Maven project)
ui/        Electron + React desktop app
proxy/     Node auth/usage proxy + OpenAI passthrough
```

## Getting started

See **[SETUP.md](SETUP.md)** for full prerequisites and step-by-step
instructions (build the backend, run the proxy with your own OpenAI key, launch
the desktop app).

Quick version:

```bash
# 1. Backend — build the JAR
cd backend && mvn package -DskipTests

# 2. Proxy — install, configure your key, run
cd ../proxy && npm install && cp .env.example .env   # then edit .env
npm start

# 3. Desktop app — install and run (spawns the backend JAR)
cd ../ui && npm install && npm run dev
```

## Ports

- Backend HTTP API: **9876**
- Proxy: **8787**

---

_Private project — all rights reserved._
