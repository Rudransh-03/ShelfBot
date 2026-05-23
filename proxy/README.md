# ShelfBot proxy

A tiny auth + rate-limit + OpenAI-passthrough server. It exists so the desktop
app never has to ship your OpenAI API key.

## Architecture

```
ShelfBot (Electron + Java)  ──JWT──>  this proxy  ──OPENAI_API_KEY──>  OpenAI
```

* The desktop app holds only a short-lived **JWT** issued by this proxy.
* The proxy holds the real **OpenAI key** as a server-side env var.
* Each user is rate-limited to `DAILY_QUERY_LIMIT` chat-completion calls per day.

If a user reverse-engineers the desktop binary, they get a JWT scoped to their
own account — nothing that lets them burn your OpenAI budget.

## Running locally

```bash
cd proxy
cp .env.example .env       # then edit .env to set real values
npm install
npm run dev                # node --watch, auto-restarts on edits
```

Defaults to `http://localhost:8787`. The desktop app picks that up from
`api.proxy.url` in `backend/config.properties`.

## Endpoints

| Method | Path                          | Auth | Description                                 |
|--------|-------------------------------|------|---------------------------------------------|
| GET    | `/health`                     | no   | Liveness check                              |
| POST   | `/auth/login`                 | no   | Stub email login → returns JWT (replace with Google OAuth in prod) |
| GET    | `/me`                         | yes  | Current user + today's usage                |
| POST   | `/proxy/embeddings`           | yes  | Forwards to OpenAI embeddings               |
| POST   | `/proxy/chat/completions`     | yes  | Forwards to OpenAI chat. **Counted against the daily limit.** |

JWT goes in `Authorization: Bearer <token>` on every authenticated call.

## Daily limit

- Configured by `DAILY_QUERY_LIMIT` (default 15).
- Counts **chat completion** calls only. Embeddings are unmetered.
- The counter is reset implicitly at the start of each UTC day (the date key
  is just `YYYY-MM-DD`).
- A 429 response includes `{used, limit, remaining}`. The counter is **not**
  incremented for rejected calls.

## Storage

Single SQLite file at `DB_PATH` (default `./shelfbot-proxy.db`). Two tables:

* `users (id, email, created_at, last_login)` — one row per signed-up user
* `usage (user_id, date, query_count)` — one row per user per day

Sub-millisecond lookups; fine until you have tens of thousands of concurrent
users. When you do, swap `openDb()` for a Postgres pool.

## Swapping the stub email login for Google OAuth

When you're ready:

1. Create a Google Cloud Console OAuth 2.0 Client ID (type: Web application).
2. Set the authorized redirect URI to `https://your-proxy-host/auth/google/callback`.
3. Save the client id + secret as `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` env vars.
4. Add a route that runs the OAuth dance with Google, verifies the
   `id_token`, extracts `email`, and calls the same `auth.issueToken(email)`
   used by `/auth/login`.

Everything downstream of `issueToken` is identical — the rest of the system
won't change.

## Deploying

Anywhere that runs Node 18+: Render, Fly.io, Railway, Cloud Run, a $5 VPS.

Required:
* Persistent disk for `shelfbot-proxy.db` (or swap to Postgres before
  deploying to a stateless platform).
* `OPENAI_API_KEY` + `JWT_SECRET` env vars set securely (Render Secrets,
  Fly secrets, etc.).
* HTTPS in front of it (the desktop app's `api.proxy.url` should use `https://`).
