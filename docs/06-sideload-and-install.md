# 06 — Sideload & Install How-To (Android)

> For the user and their friends/family. Everything happens over the local IP or
> a VPN — no public internet exposure.

## Prerequisites
- Phone on the same VPN (or local Wi-Fi as Local Server).
- Local Server reachable at `https://<breaker-host>` (VPN address or local IP).

## Step 1 — Trust the server (one time)

> This step is changing for ADR-034. Breaker serves plain HTTP; TLS is the admin's choice via any reverse proxy. There is no certificate authority to set up and no one-time sign-in pairs the phone. The steps below are the route that applies until that is built, and they will then go away.

1. On the phone, open the server's web FE: `https://<breaker-host>/`
2. Download the **Breaker CA certificate** (link on the FE home page).
3. Install it: Settings → Security & privacy → Other security settings →
   **Install from device storage** → select the cert → name it "Breaker CA".
4. Verify: revisit `https://<breaker-host>/` — it now shows **secured** (no
   "connection not private" warning).

> Why: this makes HTTPS to Local Server trusted on the local IP/VPN. Without it,
> the phone treats the server's certificate as untrusted.

## Step 2 — Enable sideloading (one time)
1. Settings → Security & privacy → **Install unknown apps**.
2. Select your browser / file manager → **Allow from this source**.

> Honest note: Android still shows a standard "unknown app — are you sure?"
> prompt the first time you install a sideloaded APK. That is normal for
> self-hosted apps and is not a virus warning. Verify the APK's SHA-256 (published
> on the FE) matches what you downloaded.

## Step 3 — Download & install the APK
1. On the FE, go to **Downloads**.
2. Download `Breaker.apk` (latest tagged release, built from the local GIT server).
3. Tap the downloaded file → **Install** → confirm the unknown-app prompt.
4. Open Breaker.

## Step 4 — First-run onboarding
1. **Register** (username + password) — one time. You stay signed in while you use
   the app: after 30 days without using it the session ends and you sign in
   again (ADR-031). The app keeps working offline either way, and Home shows a
   quiet notice with a way to sign in when you are signed out (ADR-033).
2. **Grant permissions** (F11): after a short welcome, the app asks for the
   microphone, display over other apps, notifications and the accessibility
   service, one screen each — approve all.
3. **Enable high-power mode** (F12): if Android power-saving is on, the app prompts
   you to switch to high-performance mode.
4. **Train your phrases** (F16, optional but recommended): record the wake phrase
   ("Breaker Breaker") and end phrase ("And I'm Gone") 10–20 times each in your
   environment (car, home, etc.). The server trains your personal model; the phone
   downloads it.
5. **Enable Breaker's accessibility service** (one time): Settings →
   Accessibility → Installed apps → **Breaker** → turn it on. This is what
   lets Breaker put your dictated text into whatever app you're typing in; it
   never reads, stores, or sends anywhere any screen content beyond what one
   insert needs in the moment (ADR-022). On Android 13+, a sideloaded app's
   accessibility entry shows as a **restricted setting** at first: open
   Settings → Apps → Breaker → the three-dot menu → **Allow restricted
   settings**, then go back and turn the service on.

> **Privacy:** your transcriptions are encrypted with a key derived from your
> password — the server stores them as ciphertext, so a passive admin
> browsing the database sees ciphertext, not your notes. Don't forget your
> password: it's the only way to decrypt them on your own devices. This is
> not a claim that nothing can ever expose a note — an admin can force an
> account reset, and on the web login page specifically, the key-handling
> code is served by this server, so a compromised server is a different
> threat than a passive one; see ADR-006 and ADR-018 for the honest limits.
>
> **Retention:** transcriptions are kept for **3 months**, then auto-deleted.
> Audio files are deleted as soon as transcription finishes. Admins can clear
> the store per-user or for everyone.

## Step 5 — Go
- Say **"Breaker Breaker"** (or shake to bring up the tile, then tap it) → dictate → **"And I'm Gone"** (or tap send).
- Transcriptions appear in the focused field (or clipboard) AND in the app history.
- Everything syncs to the server; view/search/copy from the web FE on any device.

## For admins: transcription service + agent tokens

- **Transcription service** (Admin panel → Transcription Services): point
  Breaker at your transcription service of choice — a container on the same
  Docker network, a service by IP, or an external URL. Add an API key if the
  service needs one, then hit **Test connection** — Breaker confirms it can
  reach the service before you rely on it.
- **Agent tokens** (Admin panel → Agent Tokens): generate a scoped token and
  give it to an AI agent. The agent calls the Breaker API directly (no Android
  app) and its transcriptions flow through the same FIFO queue, landing in your
  account and showing up on this site. Tokens are revocable at any time.

## Updates

- Breaker checks for updates **once a day** automatically. When a new tagged
  release is available you'll get a notification: **"Breaker X.Y.Z is available —
  update?"** → tap to download and install. Your data and settings are preserved.
- **Admins:** the first account to register is admin by default and has a
  **force-check** button (Home menu → About → Check for updates) for immediate checks.
- Updates are **signed APKs** verified against the published SHA-256 before
  install — if the signature or hash doesn't match, the update is refused.

## Troubleshooting
| Symptom | Fix |
|---------|-----|
| "Connection not private" | Trust the server (Step 1) |
| Home shows a quiet notice with a way to sign in | You have not signed in yet, or your session ended (30 days without use, or an admin ended it). Sign in again from the notice; the app still works offline |
| APK won't install | Enable "Install unknown apps" for your browser (Step 2) |
| Wake phrase not responding | Train your phrases (Step 4.4); check mic permission |
| Text goes to the clipboard instead of the field | The accessibility service is off or restricted (Step 4.5), or the field is a password field (never filled, by design) |
| Sync stuck | Confirm VPN connectivity; sync retries automatically |
