# ShelfBot proxy

Auth + rate-limit + OpenAI passthrough server. Identity is the **device**,
not an email or user account.

## Architecture

```
ShelfBot (Electron + Java)  ──JWT──>  this proxy  ──OPENAI_API_KEY──>  OpenAI
```

* On first launch the desktop app generates a stable device fingerprint and
  hits `POST /device/register`. The proxy upserts a row in `devices` and
  returns a JWT scoped to that device.
* The desktop app persists the JWT via Electron's `safeStorage` (OS
  keychain). Subsequent launches reuse it; the proxy never sees the device
  fingerprint again.
* Every OpenAI call goes through the proxy with `Authorization: Bearer <jwt>`.
  The proxy adds the shared `OPENAI_API_KEY` and forwards.
* Per-device daily cap on chat completions. Free tier and Pro tier have
  independent caps (defaults: 5 and 25).

There is **no email-based identity** anywhere. Abuse via "create another
account" is impossible because there are no user accounts — sharing a free
quota across humans requires physically owning a second device. Pro
subscriptions (added in #12) bind a license key to up to N devices.

## Running locally

```bash
cd proxy
cp .env.example .env       # then edit .env
npm install
npm test                   # runs the integration suite
npm run dev                # node --watch
```

Defaults to `http://localhost:8787`.

## Endpoints

| Method | Path                          | Auth | Description                                       |
|--------|-------------------------------|------|---------------------------------------------------|
| GET    | `/health`                     | no   | Liveness + per-plan caps                          |
| POST   | `/device/register`            | no   | Idempotent registration. `{deviceId}` → `{token, device, usage}` |
| GET    | `/me`                         | yes  | `{device:{plan}, usage:{used,limit,remaining}}`   |
| POST   | `/proxy/embeddings`           | yes  | Forwards to OpenAI embeddings (NOT rate-limited)  |
| POST   | `/proxy/chat/completions`     | yes  | Forwards to OpenAI chat. Counted against the cap. |

JWT goes in `Authorization: Bearer <token>` on every authenticated call.

## Daily limit

- `FREE_DAILY` / `PRO_DAILY` env vars control the per-tier caps.
- Only chat completions count. Embeddings are unmetered.
- Counter resets at UTC midnight (date key is `YYYY-MM-DD`).
- 429 body includes `plan`, `used`, `limit`, `remaining`, `upgradeHint`.
- The counter is **not** incremented for a rejected (429) call.
- If OpenAI itself fails on a call we already counted, we automatically
  refund the slot so the user isn't charged for our outage.

## Schema

```sql
CREATE TABLE devices (
  id INTEGER PRIMARY KEY,
  device_id TEXT UNIQUE NOT NULL,   -- the machine fingerprint
  plan TEXT DEFAULT 'free',          -- 'free' | 'pro'
  license_id INTEGER NULL,           -- FK to licenses (used in #12)
  created_at TEXT, last_seen TEXT
);

CREATE TABLE usage (
  device_id INTEGER, date TEXT, query_count INTEGER,
  PRIMARY KEY (device_id, date)
);

CREATE TABLE licenses (
  id INTEGER PRIMARY KEY,
  key TEXT UNIQUE,
  max_devices INTEGER DEFAULT 3,
  created_at TEXT
);
```

The `licenses` table is created empty by this build; #12 populates it
when a Stripe checkout completes.

## Tests

`npm test` spawns a real proxy + a local OpenAI stub and exercises:

* unauthenticated `/me` → 401
* malformed `deviceId` → 400
* idempotent `/device/register`
* `/me` returns plan + usage
* invalid JWT → 401
* daily cap enforced; 429 body includes plan + upgrade hint
* counter does NOT increment on 429
* different deviceIds get independent quotas
* `/health` exposes per-plan caps

All 8 pass in ~1 second.

## Deploying

Anywhere that runs Node 18+: Render, Fly.io, Railway, Cloud Run, a $5 VPS.

Required:
* Persistent disk for `shelfbot-proxy.db` (or swap to Postgres before
  deploying to a stateless platform).
* `OPENAI_API_KEY` + `JWT_SECRET` set securely.
* HTTPS in front of it.

## What's NOT here (intentional)

* **Google OAuth**: there's no email, so no OAuth. Identity is purely the
  device fingerprint.
* **User accounts**: no `users` table, no signup, no password reset, no
  "forgot my email" flow. Subscriptions (#12) bind to license keys, not
  to email accounts.
