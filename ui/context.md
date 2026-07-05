# Rudo — Desktop App UI/UX Design Context

> **Purpose of this file.** This is the single source of truth for the design
> agent tasked with redesigning the **Rudo desktop application** UI/UX. It
> describes (1) what the product is, (2) the *exact* visual language we want —
> lifted from our marketing site, (3) the desktop app's real architecture,
> screens, data, and states, and (4) how to translate a scroll-based marketing
> aesthetic into a functional, always-on desktop tool.
>
> **Reference file you MUST study alongside this doc:**
> `/Users/shresth1811/Documents/PROJECTS/brycklabs-tools/public/rudo/index.html`
> — that landing page is the *canonical* look-and-feel. Every color, font,
> border, radius, and motion decision in this doc is extracted from it.
>
> **Golden rule:** the desktop app must feel like it was designed by the same
> hand that made the landing page — warm paper, ink-on-cream, dashed hairlines,
> monospace labels, restrained motion. NOT the current dark "midnight-blue neon"
> theme, which is being retired.

---

## 1. Product: what Rudo is

**Rudo** (product name; the codebase/repo is historically called *ShelfBot*) is
a **privacy-first, on-device document assistant** for desktop (macOS + Windows,
Electron). Tagline: *"Your documents, one honest assistant away."*

The user points Rudo at folders on their computer. Rudo indexes those files
**locally** (embeddings run on-device, nothing is uploaded), then lets the user:

- **Chat** with their whole library in plain English and get answers **with
  citations to the exact file + page**.
- Browse a **Library** of indexed files, see index health, generate one-page
  document summaries.
- Review **Deadlines** — dates/renewals/actions Rudo extracts from documents,
  turned into calendar reminders (`.ics`).
- **Organize** — AI-proposed tidy-up of a messy folder, reviewed and applied by
  the user, with undo.
- Manage **Clients** — optional per-client separation of documents (for
  accountants/freelancers).

**Core brand values to express visually:** *private, honest, calm, on-device,
trustworthy, unhurried.* Rudo shows its work (citations). It is not flashy or
"AI-hype"; it is a quiet, dependable librarian.

**Positioning contrast (from the site):** cloud AI assistants upload your files
and cost $20+/mo; Rudo keeps files on-device and is $5.99/mo flat. Privacy and
honesty are the emotional hooks — the design should feel *safe* and *editorial*,
not *techy* or *neon*.

---

## 2. THE TARGET VISUAL LANGUAGE (extracted from the reference site)

This is the most important section. Match these tokens exactly.

### 2.1 Color palette

Warm paper base, ink text, taupe accent. All values are pulled directly from the
reference stylesheet.

| Role | Value | Usage |
|------|-------|-------|
| **Paper (app bg)** | `rgb(242, 237, 230)` | main app background |
| **Ink (text-1)** | `rgb(20, 19, 19)` | primary text, headings |
| **Ink-soft (text-2)** | `rgb(102, 95, 86)` | body/secondary text, subtitles |
| **Ink-faint (text-3)** | `rgb(150, 140, 128)` / `rgb(128, 119, 108)` | captions, hints, metadata |
| **Taupe (accent)** | `rgb(179, 167, 152)` | the *only* accent — numbers, source labels, the wordmark dot, highlights |
| **Card surface** | `rgb(250, 248, 243)` | cards, panels, form wells |
| **Surface-inset** | `rgb(255, 253, 249)` | inputs, nested wells, inner demos |
| **Ghost/paper-2** | `rgb(247, 244, 239)` | faint stacked "document" shapes |
| **Dark surface** | `rgb(31, 29, 29)` | user chat bubbles, dark CTA blocks, dark buttons |
| **Dark-deepest** | `rgb(20, 19, 19)` | full dark sections (e.g. "What's next") |
| **On-dark text** | `rgb(242, 237, 230)` | text on dark surfaces (= paper color) |
| **Highlight** | `rgba(179, 167, 152, 0.32)` | taupe wash behind a highlighted answer phrase |
| **Error/terracotta** | `rgba(217, 119, 87, 0.9)` | invalid input border, destructive |
| **Success (derive)** | a muted sage/green in the same warmth (no bright greens) | "on calendar", done states |

**Borders (signature):**
- **Dashed hairline (the Rudo signature):** `1px dashed rgba(20, 19, 19, 0.18)`
  — used for section dividers, the outer app frame, chat demo wells, card
  footers. Use this liberally; it is the defining texture of the brand.
- Solid soft border: `1px solid rgba(20, 19, 19, 0.10)` to `0.15` — card edges.
- Focus border: darken to `rgba(20, 19, 19, 0.6)`.

**Never use:** pure black, pure white, neon blue/cyan, midnight navy, or any
saturated tech-blue. The old palette (`--bg: #050816`, `--accent: #5b9dff`,
`--tech: #4ddbff`) is being **removed**.

### 2.2 Typography

Two self-hosted families (already used on the site — fonts live at
`/rudo/fonts/*.woff2` on the marketing side; in the desktop app they should be
bundled locally, NOT loaded from Google Fonts):

- **Manrope** (variable, weights 200–800) — the workhorse. Body, headings, UI
  labels, buttons. Headings use weight **500–600** (never heavy 700+ except the
  wordmark). Tight negative letter-spacing on large text.
- **DM Mono** (weights 300/400/500) — used for *labels, eyebrows, metadata,
  pills, numbers, file paths, source citations, timestamps*. Uppercase +
  letter-spacing `0.08em`–`0.12em` for eyebrows.

**Type scale & treatment (from the site):**

| Element | Font | Size | Weight | Letter-spacing | Notes |
|---------|------|------|--------|----------------|-------|
| Wordmark "rudo." | Manrope | 1.35rem | 700 | -0.03em | the `.` is taupe |
| Hero H1 | Manrope | clamp(3rem, 5.2vw, 5rem) | 500 | -0.05em | line-height 1.05 |
| Section H2 | Manrope | 2.75rem | 550 | -0.04em | (scale down for app density) |
| Card title (h3) | Manrope | 1.4–1.5rem | 600 | -0.025em | |
| Body | Manrope | ~1rem | 500 | -0.003em | ink-soft for secondary |
| **Eyebrow** | DM Mono | 0.78–0.85rem | 500 | 0.08em | UPPERCASE, muted, often bracketed `[ Like this ]` |
| Pill/tag | DM Mono | 0.62rem | 500 | 0.06–0.14em | uppercase, taupe wash bg |
| Metadata/path | DM Mono | 0.6–0.8rem | 400 | — | file names, "p.3", timestamps |
| Numbered step | DM Mono | 0.72rem | 500 | 0.12em | taupe, "01 / 02 / 03" |

**Signature typographic motifs to reuse:**
- **Bracketed mono eyebrows**: `[ How it works ]`, `[ Early access ]`. Use these
  as section/tab/panel labels in the app.
- **Two-digit numbering**: `01 — Email`, step counters.
- **Mono for anything machine-like**: file paths, page numbers, counts,
  timestamps, "On-device" badge, GSTIN/PAN chips.
- **Highlighted answer phrase**: wrap the key fact in a taupe `rgba(179,167,152,0.32)`
  rounded background + bold (see `.rv-hi` in the reference). This is *perfect*
  for chat answers — the cited number/date should get this treatment.

### 2.3 Shape, spacing, elevation

- **Radii:** cards `1rem`–`1.25rem`; icon tiles `0.85rem`; inputs `0.75rem`;
  large hero containers `2rem`; pills/badges `2rem` (fully round); chat bubbles
  `0.9rem` with one corner tightened to `0.2rem` on the "tail" side.
- **Shadows (soft, warm, directional — never harsh):**
  - small lift: `0 16px 32px -20px rgba(20,19,19,0.3)`
  - card lift: `0 20px 40px -24px rgba(20,19,19,0.5)`
  - hero lift: `0 32px 64px -32px rgba(20,19,19,0.45)`
  - Prefer *large-blur, negative-spread* shadows so cards float gently on paper.
- **Icon tiles:** 3rem square, radius 0.85rem, bg `rgba(20,19,19,0.05)`, emoji or
  line-icon centered. The site uses emoji (💬 🔎 🧠 📝 ⏰ 🗂️ 📊 📁 🗓️ ☀️) as
  feature glyphs — we can keep tasteful emoji for feature/empty states, but use
  **stroke line-icons** (1.7–1.9 stroke, rounded caps) for functional UI controls
  (the app already has a consistent Feather-style icon set — keep that geometry,
  just recolor to ink).
- **Density note:** the marketing site is airy (5rem section padding). The
  desktop app must be **denser** — reduce paddings ~40–50% but keep the same
  *ratios* and the dashed-divider rhythm.

### 2.4 Background texture

- **Dashed SVG grid.** The site overlays a faint dashed grid: `88px` cells,
  `stroke-dasharray "3 6"`, opacity ~`0.09` (ink on light sections, paper on
  dark sections). Reuse this as a *very subtle* app-wide backdrop instead of the
  current animated "constellation" canvas (which is being removed).
- The outer content can carry the site's **dashed left/right frame** feel via
  panel borders.
- **Retire** `BackgroundFX.jsx` (neon constellation) and `body::before` neon
  radial pools.

### 2.5 Signature components from the site to reuse in-app

These already exist as patterns in the reference — reuse their exact styling:

1. **Chat bubbles.**
   - *User question* (`.rv-bubble` / `.at-q`): dark `rgb(31,29,29)` fill, paper
     text, right-aligned, radius `0.9rem 0.9rem 0.2rem 0.9rem`.
   - *Rudo answer* (`.at-a` / `.rv-card`): light card, soft border, left-aligned,
     radius `0.9rem 0.9rem 0.9rem 0.2rem`. Key fact gets the taupe highlight.
2. **Answer card with citation** (`.rv-card`): header row = small avatar dot +
   "Rudo" name + `On-device` mono badge; body = answer with highlight; footer =
   dashed top border + a **source chip** (thumbnail of doc bars + `SOURCE` mono
   label + `Lease_2024.pdf · p.3` mono filename). This is the model for how chat
   answers + source chips should look in the app.
3. **Stat tiles** (`.stat-tile`): big number (1.5rem, 600), a hairline divider,
   a small label below. Use for Library/Deadlines stats.
4. **Pill/tag** (`.at-pill`, `.rv-ondevice`, `.wn-soon`): mono, uppercase, small,
   taupe wash or thin outline. Use for statuses ("On-device", "Coming soon",
   "unsure", "monthly", plan tier).
5. **Numbered steps** (`.hiw-step`): top hairline, mono number, short statement
   with a "soft" (muted) continuation. Great for onboarding/empty states.
6. **Comparison rows** (`.cmp-row`): grid with dashed row separators.
7. **Accordion** (`.faq-item`): hairline dividers, `+`→`×` rotate toggle. Reuse
   for any expandable settings/help.
8. **Animated pill button** (`.btn`): a rounded pill with a dark circle that
   **expands to fill on hover**, arrow icon slides right, text color flips. This
   is the hero button personality. In the app, use a calmer version for primary
   actions (keep the expanding-circle idea for the main CTA, but simpler filled
   pills for dense toolbars).
9. **Dark CTA block** (`.cta-window`): rounded `2rem` dark container, centered
   headline, footer callout. Good for upsell/upgrade panels (Deadlines Pro gate,
   plan upgrade).

### 2.6 Motion

The site uses **GSAP** (`gsap`, `ScrollTrigger`, `SplitText`). The desktop app
does **not** need GSAP for everything, but should echo the *feel*:

- **Entrance:** content fades up (`y: 24–36 → 0`, opacity `0 → 1`), short
  stagger (~0.1s), easing `power3.out`/`power4.out`.
- **Buttons:** scale-in with `back.out(1.7)` for the primary CTA; the
  expanding-circle hover on the main button.
- **Line-mask text reveal** for big headings (optional, onboarding/welcome only —
  don't over-animate the working UI).
- **Restraint in the working app:** chat, library, and settings should be
  *calm*. Reserve expressive motion for empty states, onboarding, sign-in, and
  the primary CTA. Respect `prefers-reduced-motion`.
- Standard easings already in the codebase: `cubic-bezier(.16,1,.3,1)` (ease-out),
  `cubic-bezier(.34,1.4,.64,1)` (spring) — keep these.

---

## 3. Desktop app: architecture & technical constraints

The design must be implementable in the *existing* stack. Do not assume Tailwind,
component libraries, or CSS-in-JS.

- **Stack:** Electron 31 + React 18 + Vite (`electron-vite`). Renderer is plain
  React (`.jsx`), no TypeScript, no Tailwind.
- **Styling:** ONE global stylesheet — `ui/src/renderer/src/index.css` (~3,800
  lines) using **CSS custom properties** (design tokens in `:root`) + BEM-ish
  hand-written class names. The redesign = rewrite the tokens + component rules
  in this file. Keep the "tokens in `:root`, components below" structure.
- **Markdown:** answers render via `react-markdown` + `remark-gfm` (tables, bold,
  lists). Raw HTML is disallowed for security; images disallowed; links open
  externally. Design the markdown answer styles (`.md`) accordingly — style
  `h1–h3, p, ul/ol, code, pre, table, a, strong, blockquote`.
- **Icons:** inline SVG, Feather-style (stroke 1.7–2, rounded caps, 24 viewBox).
  A consistent set already exists across components — keep the geometry.
- **Fonts:** bundle **Manrope** + **DM Mono** as local `woff2` (do NOT depend on
  Google Fonts at runtime — offline-first product). Currently the app wrongly
  `@import`s Inter + Space Grotesk from Google — replace this.

### 3.1 Window / chrome constraints (critical for layout)

- **Frameless window** (`frame: false`, `titleBarStyle: 'hidden'`). We draw our
  **own title bar** (`TitleBar.jsx`): a 40px-tall drag region with custom
  minimize/maximize/close buttons on the right (Windows-style; on macOS the
  native traffic lights are hidden too, so our buttons serve both — verify
  padding for macOS traffic-light spacing if we re-enable them).
- **Default window:** 1180 × 760. **Min:** 860 × 580. Design must work from
  860px wide up to large/maximized. It is a resizable desktop window, *not* a
  responsive website — but must reflow gracefully and support a **collapsed
  sidebar**.
- **Backdrop color** on launch: currently `#0b0b14` (dark) — change to the paper
  color `#f2ede6` so there's no dark flash before React mounts.
- `-webkit-app-region: drag` on the title bar; `no-drag` on its buttons and any
  interactive element inside drag regions.

### 3.2 App shell layout (current, keep the structure)

```
┌───────────────────────────────────────────────┐
│  TitleBar (40px, drag region, window buttons)   │
├──────────┬──────────────────────────────────────┤
│          │  UpdateBanner (conditional)           │
│ Sidebar  ├──────────────────────────────────────┤
│ (224px,  │  Content (one active View at a time)  │
│ collaps- │   view-header (title + subtitle +     │
│ ible)    │   header-actions) → view-divider →    │
│          │   scrollable body                     │
└──────────┴──────────────────────────────────────┘
  + global overlays: LoadingOverlay, Toasts,
    SearchModal (⌘K), WelcomeModal, SignInScreen,
    ClientSuggestionModal, DeadlineReviewModal
```

- **Sidebar (`--sidebar-w: 224px`)**, collapsible to an icon rail (persisted in
  `localStorage` as `rudo.sidebar.collapsed`). Contains: brand (mascot + "Rudo"),
  "Workspace" nav (Chat / Library / Deadlines / Organize / Settings), a chat
  thread list w/ search (⌘K) + new-chat, and a footer sync/status area.
- **View header pattern:** every view opens with `view-title` (h1) + `view-subtitle`
  + optional right-aligned `header-actions`, then a `view-divider` (make this the
  dashed hairline), then a scrollable body.

---

## 4. Screens to design (with data, states, and intent)

There are **5 primary views** + **sign-in/onboarding** + **global overlays**.
Each below lists: purpose, key elements, all states, and design notes. File
references point to current implementations to preserve behavior.

### 4.0 Sidebar & App Chrome — `components/Sidebar.jsx`, `TitleBar.jsx`
- **Brand:** the **Rudo mascot** (see §5) + wordmark "Rudo" (apply the "rudo."
  wordmark treatment with a taupe dot).
- **Nav items:** Chat, Library, Deadlines (with a **badge** for open/upcoming
  count, or a spinner while scanning), Organize, Settings. Active state = clear
  but calm (ink text + soft taupe/paper active pill; no neon).
- **Chat thread list:** section label "Chats" (mono eyebrow style), `+` new chat,
  a search trigger row showing `⌘K`, then a scrollable list of thread rows
  (title + hover actions rename/delete, inline rename input, active highlight,
  empty state "No saved chats yet").
- **Footer:** "Synced <time ago>" with a re-index button, and a
  Connected/Offline status dot (backend health). Collapsed mode shows just icons
  + a status dot.
- **States:** expanded / collapsed; connected / offline; indexing (label →
  "Indexing…"); deadline badge count / scanning spinner.

### 4.1 Chat — `views/Chat.jsx`  (the primary, most-used screen)
**Purpose:** ask the library questions; get streamed, cited answers.

- **Empty state:** centered **mascot** (large), "Hi, I'm *Rudo*." headline, a
  short subtitle, and 3 **suggestion chips** (each: icon + prompt text + arrow).
  This is the app's front door — make it warm and inviting (editorial, mascot-led).
- **Active conversation:** `view-header` ("Chat" / "Conversation with your
  library" / new-chat button), then a scrollable **messages** list.
- **Message rows:** avatar + bubble.
  - *User*: dark bubble, right/leading avatar (use the site's user-bubble style).
  - *Rudo*: light answer bubble rendered as **markdown**; below it:
    - **Source chips** (`msg-sources`): each chip = filename + `(page 3)` in
      mono; clickable to open the file; **hover preview tooltip** showing the
      snippet(s) it came from. Model this on the site's `.rv-source`/`.rv-thumb`
      + citation styling. Citations are a core trust feature — make them elegant
      and prominent, not an afterthought.
    - **Action row** (`msg-actions`, on finished answers): Copy, Email, and
      Export-to-Excel (only when the answer contains a table). Show check-state
      feedback ("Copied", "Exported").
    - **Clarify chips** (`clarify-chips`): when Rudo needs to know *which client*
      a question is about, it renders option chips the user taps.
    - **Scope note** (`msg-scope`): "Answering about: <client>".
  - *Variants:* `not-found` (no answer in docs) and `error` bubbles — style
    distinctly but calmly (terracotta accent for error, muted for not-found).
- **Typing indicator:** three animated dots + "Rudo is thinking" while streaming
  begins. The mascot in the input row also reflects state
  (idle/listening/thinking/happy).
- **Input area (`chat-input-area`):** an inline mascot (when in a conversation),
  a text input ("Ask your library anything…"), and a send button. Disabled when
  backend offline.
- **In-chat find (⌘F):** a floating find bar (input + match count `1/3` + prev/
  next/close), highlighting matches (`<mark>` = taupe highlight; active match
  brighter).
- **Design notes:** this screen carries the brand. Streaming should feel alive
  but not jittery. Long answers, tables, code blocks, and multi-source citations
  must all look great. Answers should feel like the site's `.rv-card` — a
  confident, cited response.

### 4.2 Library — `views/Library.jsx`
**Purpose:** manage the indexed knowledge base.

- **Access banner** (conditional): warns when macOS Full Disk Access blocks
  folders; lists the blocked paths + a "Open Privacy Settings" button. Treat as
  an inline warning card (terracotta-tinted, not alarming red).
- **Stats grid (2 tiles):** "Files Indexed" (count), "Failed Files" (count;
  clickable when >0 → opens a modal listing failures + reasons). Use the site's
  **stat-tile** styling (big number, divider, label).
- **Index Control card:** title + "Last: <time ago>" badge; the list of
  configured folder paths (mono, folder icon); actions **"Index Now"**
  (primary) + "Configure folders" (ghost → Settings).
  - **Progress state (while indexing):** a real progress bar (`processed/total`
    files, plus fractional credit for in-flight files), a live list of the top
    active files with their stage ("Extracting text… / Chunking… / Embedding
    12/40 chunks / Saving…"), and a "View all N files" button → status modal.
  - **Result state:** success box (duration + Processed/Skipped/Failed grid) or
    error box.
- **Indexed files panel:** header + refresh; a **search box** (filter by name);
  a list of **file rows** — icon, name, meta (`size · Indexed <time ago>`),
  and per-row actions: **Summarise** (opens a one-page brief modal) and **Remove
  from index** (inline confirm: "Remove from index?" Cancel/Remove).
- **Modals:** Summary modal (title + "Cached · generated…"/"N LLM calls",
  rendered brief with bold headers + bullets), Index-status modal (per-file
  progress bars), Failed-files modal (name + reason tags).
- **States:** empty (no files / no folders configured), loading, indexing,
  result success/error, search-no-match.

### 4.3 Deadlines — `views/Deadlines.jsx`  (a Pro feature)
**Purpose:** surface dates/renewals/actions extracted from docs; make reminders.

- **Gated (free/trial) state:** an upgrade panel — "Deadlines is a Pro feature",
  explanation, "Upgrade to Pro" button. Use the site's **dark CTA block** energy.
- **Enabled state:**
  - Header action: **"Find deadlines"** (scan) — shows live scan progress
    ("Reading 12 of 40 docs · 3 found").
  - **Summary chips:** Due soon / Upcoming / Reminders set (stat-tile style).
  - **"Possibly missing" panel:** inferred gaps in recurring document series
    (e.g. "You usually have a monthly invoice from X — one looks missing"), each
    with a confidence tag ("guess"/cadence). Muted, clearly labeled as inference.
  - **Tabs:** "Open (N)" / "Reminders set (N)".
  - **Deadline cards** grouped by bucket (Overdue / Due soon / Upcoming / No
    date): each card = a **kind tag** (Deadline/Renewal/Action), a relative-due
    label ("in 5 days" / "3 days overdue" / "Today"), absolute date, optional
    "unsure" (low-confidence) tag, optional recurrence tag; title; description;
    a source-file chip. Actions: **"Set reminder"** (primary) + delete; once set,
    shows "On your calendar" (no delete — the calendar owns it).
  - **Reminder modal:** editable before creating — Title, Date, Time, "Remind me"
    lead time, Repeat cadence, Notes → "Add to calendar".
- **States:** gated, empty (no deadlines), scanning (progress), open list,
  reminders list, per-card reminded.

### 4.4 Organize — `views/Organize.jsx`
**Purpose:** AI-proposed folder tidy-up, user-approved, reversible.

State machine: `idle → analyzing → (proposal | scopeError | empty) → executing
→ result`, with undo history.

- **Idle hero:** emoji/mascot, "Pick a folder to organize", explanation
  ("Nothing moves until you approve"), and a **recent reorganizations** list with
  per-batch **Undo**.
- **Controls row:** "Pick a folder" (primary) + shows the chosen target path
  (mono `code`) + Clear.
- **Analyzing / Executing:** spinner + reassuring copy ("this is local, no LLM
  cost yet." / "Moving files…").
- **Proposal panel:** a stats bar (`N proposed moves`, `into existing folders`,
  `new groups`, `left alone`), then **groups by destination folder** — each group
  header has a tri-state checkbox, a `new`/`→` icon, the destination folder name
  (mono), and a checked count; each **row** = checkbox + source filename (mono) +
  the AI's reason + a confidence % on the right. Footer: Cancel / "Apply N moves".
  Collapsible "N skipped (low confidence)" details. Optional "stopped early" note.
- **Scope-error panel:** friendly title + detail + **suggestion chips** to retry
  with a narrower scope.
- **Result panel:** "Moved all N" / "Moved X of Y", a list of any failures/skips
  (reason), then Undo / Done.
- **Design notes:** this is a *trust-heavy, destructive-ish* flow — clarity and
  reversibility must be visually obvious. It should read like a careful diff/
  file-manager, in the paper aesthetic.

### 4.5 Settings — `views/Settings.jsx`
**Purpose:** configure folders, clients, view status, manage plan.

Sections (each a **card** — reuse `scard` pattern: title + sub + content):
- **Indexed folders:** list of folder rows (folder icon + path + remove), "Add
  folder" browse button, "Save & re-index" primary (disabled unless dirty).
- **Clients (advanced/niche):** auto-detected client suggestions (name input +
  GSTIN/PAN chip + file count + Add/Dismiss), existing clients (name + count +
  editable identifier chips + add-identifier input), "Scan for clients" + "Add
  manually". Stays quiet/empty for single-user setups.
- **Status:** service rows with status dots — "Rudo engine" (Connected/Offline),
  "Document storage" (On your device), "Search model" (Runs on your device /
  Cloud), "Scanned-image search" (Tesseract Enabled / Install to enable). Dots
  should be calm (sage=good, amber=attention) — no neon.
- **Plan:** account email, tier (Free / Free trial / Pro), today's query usage
  (`used / limit`), trial state, offline note; "Sign in with Google" or
  "Upgrade to Pro" (coming soon) + "Sign out".
- **About:** logo/mascot + "Rudo v1.0.0" + tagline.

### 4.6 Sign-in gate — `components/SignInScreen.jsx`
Full-screen gate (mandatory Google sign-in before app access): centered **mascot**,
"Welcome to *Rudo*", a subtitle, a **"Sign in with Google"** button (with the
Google glyph), and fine print: "Free trial, no card required · Your files never
leave your device." This is a first-impression moment — make it beautiful and
on-brand (paper, mascot, editorial). Mirror the emotional tone of the landing
page hero.

### 4.7 Onboarding — `components/WelcomeModal.jsx`
3-step modal (dots stepper): (0) welcome + "Get started"/"later"; (1) "Pick
folders to index" (add/remove folder list); (2) "A few things to know" (bullets:
indexing is fast, auto-sync, free trial, files stay local) → "Start indexing".
Reuse the site's numbered-step + bullet + hairline styling.

### 4.8 Global overlays
- **LoadingOverlay:** shown until backend connects + auth resolves ("Starting
  up…" / "Connecting to backend…"). Design a calm branded splash (mascot on
  paper). Set window backdrop to paper so there's no dark flash.
- **Toasts (`components/Toast.jsx`):** transient bottom notifications, types
  info/success/error. Paper card + soft shadow + small mono label; error uses
  terracotta.
- **SearchModal (⌘K):** command-palette-style search across chat threads.
- **UpdateBanner:** auto-update available/downloading/ready bar under the title
  bar.
- **ClientSuggestionModal / DeadlineReviewModal:** auto-surfacing modals (one at
  a time — they share a single "autoModalOpen" slot) prompting the user to
  accept detected clients / review newly found deadlines.

---

## 5. The Rudo mascot — `components/Mascot.jsx`

Rudo has a **mascot character** (pure-SVG, animated via CSS) that is central to
the brand's warmth. Currently it's a neon-blue floating robot with a dark visor,
glasses, and an open book — states: `idle` (bob + blink), `listening` (eyes
dilate), `thinking` (eyes closed, orbiting dots), `happy` (squint smile, bounce),
`sleeping` (closed eyes, floating "z"). Sizes: `sm` (chip), `md` (inline/sidebar),
`lg` (hero/empty/sign-in).

**Design task for the mascot:** re-skin it to fit the **paper/ink/taupe** palette
— the current neon-cyan glow, midnight visor, and blue body must change. Keep the
*silhouette and personality* (friendly librarian-robot holding a book, hovering)
and all five animation states, but recolor to warm ink/taupe/paper tones (e.g.
cream body, ink linework, a soft taupe rather than cyan "engaged" accent). It
appears on: sign-in, chat empty state, chat input row, sidebar brand, welcome,
loading. It is the emotional anchor — get it right.

> Note: `BookshelfIcon.jsx` is a small logomark used in a few places (about,
> welcome, chat avatar) — also re-skin from the current gold `#e8c995` to the
> paper/ink/taupe scheme.

---

## 6. Backend & proxy — what the UI can rely on (so designs match real data)

You don't design these, but the UI's content/states are driven by them.

- **Backend** (Java, local HTTP on port **9876**): does all indexing, retrieval/
  RAG, chat streaming (SSE), summaries, deadline extraction, reorg, clients.
  Local-API calls carry a per-launch token. Key endpoints (see
  `api/client.js`): `/api/health`, `/api/status`, `/api/index` (GET poll / POST
  start), `/api/query/stream` (SSE: `token` / `done` / `error` events),
  `/api/conversations*`, `/api/files*`, `/api/files/summary`, `/api/deadlines*`,
  `/api/clients*`, `/api/reorg/*`, `/api/config`, `/api/missing`.
- **Proxy** (Node, port **8787**): auth + OpenAI passthrough + usage metering.
  Identity is Google account / device; enforces a **daily query cap** (free vs
  pro). This is why the UI shows "Today's queries: used/limit" and 429/upgrade
  states. **No chat content is stored** on the proxy — reinforce the privacy
  story in copy.
- **Data shapes that affect UI:** chat answers return `{ sources:[{fileName,
  absolutePath, snippets[], pages[]}], found, clarify:[{id,name}], scope,
  conversationId }`. Deadlines have `{kind, title, description, dueDate,
  daysUntil, bucket, confidence, recurring, status, reminderSet, fileName,
  path}`. Files have `{name, path, sizeBytes, lastIndexedAt}`. Design the chips/
  cards around these exact fields.

**On-device / privacy is the headline story** — surface it honestly in the UI
(the "On-device" badge on answers, "runs on your device" status rows, "files
never leave your device" copy). This is both product truth and brand.

---

## 7. What to translate — old → new (explicit)

| Aspect | Current (retire) | Target (from reference) |
|--------|------------------|--------------------------|
| Mood | Midnight-blue "Jarvis" neon, dark | Warm paper, ink, editorial, light |
| App bg | `#050816` + radial neon pools | `rgb(242,237,230)` + faint dashed grid |
| Accent | neon blue `#5b9dff` / cyan `#4ddbff` | taupe `rgb(179,167,152)` (only accent) |
| Text | cool blue-whites `#ecf1ff` | ink `rgb(20,19,19)` / warm grays |
| Fonts | Inter + Space Grotesk (Google CDN) | **Manrope + DM Mono** (bundled local) |
| Dividers | solid cool hairlines | **dashed ink hairlines** (signature) |
| Background FX | animated neon constellation canvas | subtle static dashed grid |
| Mascot | neon-cyan robot | same character, paper/ink/taupe reskin |
| Shadows | deep cool/black | soft warm large-blur negative-spread |
| Labels | sentence-case UI text | **mono uppercase bracketed eyebrows** for section/label accents |

---

## 8. Deliverables expected from the design agent

1. **Design token set** (CSS custom properties) for `:root` in `index.css`:
   colors, fonts, radii, shadows, spacing, motion — matching §2.
2. **Full-screen mockups / component specs** for each screen in §4, in both
   expanded and collapsed-sidebar widths (target 1180×760; verify at min 860×580).
   Cover every state listed (empty, loading, error, active, etc.).
3. **Component library** styling: buttons (primary pill w/ expanding circle,
   ghost, icon, static), inputs/selects/textareas, cards (`scard`), pills/tags,
   chat bubbles + source chips + action row, stat tiles, list rows, modals/
   overlays, toasts, progress bars, badges, tabs, accordions, the dashed grid
   backdrop.
4. **Markdown answer styles** (`.md` block) for chat answers (headings, lists,
   tables, code, links, blockquotes, the taupe highlight for key facts).
5. **Mascot + logomark reskin** to the new palette, preserving all states/sizes.
6. **Motion spec**: entrance/hover/stagger timings + `prefers-reduced-motion`.
7. **Local font bundling plan** (Manrope + DM Mono woff2, `@font-face`,
   replacing the Google `@import`).

**Constraints to honor:** plain CSS + CSS variables in one stylesheet; React JSX
components already structured as in §4; frameless Electron window with custom
title bar; offline-first (no runtime CDN); accessibility (focus-visible states,
contrast on paper, reduced-motion); keyboard shortcuts (⌘K search, ⌘F find,
Enter to send).

---

## 9. Quick file map (for the design agent's implementation reference)

```
ui/src/
  main/index.js            Electron main: window (1180×760, frameless), spawns backend, IPC
  main/ics.js              .ics calendar reminder generation
  preload/index.js         window.electron IPC bridge (selectFolder, openPath, window ctrls, auth…)
  renderer/index.html      renderer entry
  renderer/src/
    main.jsx               React mount
    App.jsx                app shell: TitleBar + Sidebar + active View + overlays; ⌘K; sidebar collapse
    index.css              ← ALL styling lives here (tokens + components) — the redesign target
    context/AppContext.jsx global state: api, connected, stats, indexing, deadlines, auth, conversations
    api/client.js          ApiClient — every backend endpoint (source of data shapes)
    utils/tableExport.js   markdown-table → CSV
    components/
      TitleBar.jsx         custom window chrome (drag + min/max/close)
      Sidebar.jsx          nav + chat thread list + sync/status footer (collapsible)
      Mascot.jsx           the Rudo character (5 states, 3 sizes) — reskin
      BookshelfIcon.jsx    small logomark — reskin
      BackgroundFX.jsx     neon constellation — REMOVE / replace with dashed grid
      LoadingOverlay.jsx   startup splash
      Toast.jsx            toasts
      UpdateBanner.jsx     auto-update banner
      WelcomeModal.jsx     3-step onboarding
      SignInScreen.jsx     Google sign-in gate
      SearchModal.jsx      ⌘K chat search
      ClientSuggestionModal.jsx / DeadlineReviewModal.jsx  auto-surfacing prompts
      ErrorBoundary.jsx
    views/
      Chat.jsx             primary chat (streaming, citations, find, actions)
      Library.jsx          index management, files, summaries
      Deadlines.jsx        deadline cards + reminders (Pro-gated)
      Organize.jsx         folder tidy-up proposal/apply/undo
      Settings.jsx         folders, clients, status, plan, about
```

> Reference landing page (canonical look & feel):
> `/Users/shresth1811/Documents/PROJECTS/brycklabs-tools/public/rudo/index.html`
