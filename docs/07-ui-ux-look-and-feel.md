# 07 — UI/UX: Look & Feel (Trucking)

> The complete architecture of how Breaker looks and feels — web FE (container)
> and Android app. Both share one design language and one token set
> (`shared/ui-tokens`), so they look identical.

## 1. Brand story

The **voice is CB** ("Breaker Breaker", "And I'm Gone"); the **look is
trucking** — the logo on the door of an 18-wheeler. The **CB mic is the
product**: tap the mic, talk. Every screen tells that story in white, black,
and green.

## 2. Design DNA

- **Palette:** white/black/green (light mode); near-black/green (dark mode);
  black trim. Full token tables in `shared/ui-tokens`.
- **Type:** Anton/Oswald display (condensed, uppercase, truck-lettering) ·
  Inter body · JetBrains Mono for timestamps/IDs (logbook feel).
- **Style rules:** hard edges (radius ≤ 4 px), stripe accents, rectangular
  badges, high contrast. **Modern but not futuristic** — no glassmorphism, no
  neon, no sci-fi gradients.
- **Motifs:** horizontal stripes (truck side stripes), black trim lines, green
  active-state stripes, logbook-style mono timestamps.

## 3. The CB mic motif

The CB mic glyph is used in three places:
1. **Favicon** (browser tab) and app icon.
2. **Floating tile** (Android) — the tile IS the mic. Tap to talk.
3. **Hero element** on the dictation screen.

**Art:** generated via ComfyUI — flat vector style, white/green on transparent,
with a black-outline variant for dark mode. One asset, three uses.

## 4. State system (the "copy" indicator)

The mic glyph + **LED bar meter** show transmission state:

| State | Visual | Meaning |
|-------|--------|---------|
| Idle | black mic on white ring (light) / white mic on green-tinted ring (dark) | waiting |
| Armed | thin green ring pulses slowly | listening for "Breaker Breaker" — "on the air" |
| Recording | **LED bar meter fills** with audio level (digital Cobra-style segments) | transmitting |
| Queued / transcribing | green dot blinks / mono `TX` tag | job in the FIFO queue |
| **Sent (copy)** | **GREEN** | transcription committed (pasted or copied) |
| **Server failed → local fallback** | **ORANGE** | server path failed; local phone model succeeded |
| **Complete failure** | **RED** | nothing committed |

- **CP = copy:** the indicator turns **green once the text is sent/committed**.
- **Orange** only appears when the server path failed and the on-device model
  took over (the no-silent-fallthrough rule [1] — the user always knows which
  path produced the text).
- **Red** = complete failure (e.g., no local model installed — a clear error,
  never a silent cloud attempt [1]).

## 5. LED bar meter (digital Cobra display)

- A **segment display** (12–16 segments) that fills left→right with input
  level while recording — like a classic Cobra CB signal/modulation meter.
- **Android:** sits **directly above the floating mic** when the mic is awake
  and recording.
- **Color:** green segments while recording; the bar turns green on sent,
  orange on fallback, red on complete failure.
- Web FE: the same meter appears on the dictation panel where relevant (it is
  primarily an Android element).

## 6. Docker FE screens

### Landing — the fleet gate (NOT logged in)
- **Header:** black band, CB mic badge, **BREAKER** wordmark (white condensed
  caps), green underline stripe. **Log In button top-right** (black outline,
  green on hover) — **the login path is always visible, no scrolling**.
- **Hero card:** "Your words, your rig." On first visit, the **registration
  card** sits beside it (first account = admin).
- **Download badges** (rectangular, hard-edged): **Get the App** (primary
  green) · **Install Certificate** (black outline) · **Get the Model** (white
  outline).
- Every visitor sees four clear paths: **Log In · Register · Get the App ·
  Get the Cert**.

### Auth
- Login form + registration card (stacked on mobile). First registration = admin.

### Dashboard — the dispatch board
- **Sidebar (desktop):** black panel, wordmark, nav (Dashboard / History /
  Settings / Admin), green active stripe on the left edge, user block at the
  bottom (initials in a green square badge, name, logout).
- **Floating tiles:** every transcription is a card — black trim left edge,
  mono timestamp ("09:41 · 12.4s"), rectangular source tag (LOCAL = green
  outline, SERVER = solid green), text in Inter. Hover: green accent stripe +
  copy button. Slight stagger, soft shadow — dispatch slips on a desk.
- **Search** at top with a green focus ring.

### Settings — the cab
- Light/dark **rocker toggle** (black trim, green when on). Profile card,
  password change. **Danger zone** (red): logout, delete account — **blocked
  for the last remaining admin** with the note: "You're the last admin. Demote
  or remove another admin, or delete the container." Destructive actions demand
  typing **DELETE**.

### Admin panel — the fleet office
- Hard-edged tables, black headers in white condensed caps, green row
  highlights. Transcription service config styled as a **route card** (name,
  URL, API key) with a big green **Test Connection** button (green check / red
  X). Agent tokens, users/roles, store clear — same language throughout.

## 7. Android app screens

### Floating tile
- The **CB mic glyph**, draggable, tap-only (overlay windows cannot take focus
  [1] — the dictation UI opens in a normal window). Permission set stays
  minimal: display over other apps (the tile), mic, internet, foreground
  service [1].
- **LED bar meter above the mic** when awake and recording (F36).
- State colors per section 4.

### Dictation screen
- **BREAKER** wordmark top with green underline stripe. Center: the **big mic**
  hero. **LED bar meter / waveform strip** beneath (green bars on white, black
  bars on dark — a CB signal meter). Status line in mono: `LISTENING…` /
  `QUEUED…` / `TRANSMITTING…` with a green status dot. Last transcription
  previews as a floating tile at the bottom — the same tile as the web.

### History — the dispatch board in your pocket
- Same floating tiles: mono timestamp, source tag, text, copy + delete.
  **Delete All** in the header (type-to-confirm). Search on top.

### Settings
- Theme toggle, model size, server URL, voice phrases on/off, shake on/off,
  formatting on/off, tile position, language. Danger zone: logout, delete
  account (blocked for the last admin).

## 8. Navigation

- **Desktop (> 1024 px):** persistent sidebar — the cab is always visible.
- **Tablet (640–1024 px):** sidebar collapses to a **hamburger**.
- **Mobile (< 640 px):** **bottom tab bar** — Home (mic) / History / Settings;
  Admin tab for admins. Thumb-friendly, no hidden nav.

## 9. Responsive

- Breakpoints per `shared/ui-tokens`. The container FE renders the mobile
  layout on phones automatically — it looks like the Android app. The Android
  app consumes the same tokens at phone scale.

## 10. Acceptance

- Login path visible on the landing page without scrolling; download badges
  adjacent (F35).
- State colors correct: sent = green, server-fail→local fallback = orange,
  complete failure = red (F36).
- LED bar fills while recording, directly above the floating mic on Android (F36).
- FE + app render identical tokens; light/dark toggle persists per user.
- Contrast passes WCAG AA in both modes.
