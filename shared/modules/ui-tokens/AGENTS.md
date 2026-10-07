# AGENTS.md — shared/modules/ui-tokens/

## Purpose

Trucking design tokens: colors (light/dark), type, spacing, breakpoints, CB mic + LED bar spec. The single source of truth for Breaker's **Trucking** design
language (F25). Consumed by the web FE (CSS variables) and the Android app
(plain Kotlin values, no UI-framework dependency) so both look identical.

**Build phase:** Built (on main).

## Palette — white / black / green

**Light mode**
| Token | Value | Use |
|-------|-------|-----|
| `bg` | `#FFFFFF` | app background |
| `surface` | `#F4F6F4` | cards, panels |
| `text` | `#111417` | primary text |
| `text-muted` | `#5A6368` | secondary text |
| `primary` | `#1E7A46` | buttons, active states, links |
| `primary-hover` | `#16603A` | hover/pressed |
| `accent` | `#257F49` | highlights |
| `danger` | `#C0392B` | destructive actions |
| `trim` | `#000000` | borders, stripes, black trim |

**Dark mode**
| Token | Value | Use |
|-------|-------|-----|
| `bg` | `#0E1113` | app background (near-black, green undertone) |
| `surface` | `#161B1E` | cards, panels |
| `text` | `#F2F5F2` | primary text |
| `text-muted` | `#9AA5A0` | secondary text |
| `primary` | `#2E9E5B` | buttons, active states |
| `primary-hover` | `#3BBE6E` | hover/pressed |
| `accent` | `#34A853` | highlights |
| `danger` | `#E74C3C` | destructive actions |
| `trim` | `#FFFFFF` | borders, stripes (white trim on dark) |

## Typography
- **Display/headings:** Anton or Oswald (condensed, bold — truck-lettering
  feel), uppercase with letterspacing for section headers.
- **Body/UI:** Inter (clean, readable at all sizes).
- **Mono:** JetBrains Mono for IDs, timestamps, technical values.
- All open-source (OFL) — safe for self-hosted distribution.

## Style rules
- **Modern but not futuristic:** no glassmorphism, no neon, no sci-fi
  gradients. Strong geometry, hard edges (radius ≤ 4 px), high contrast.
- **Trucking motifs:** horizontal stripe accents (like truck side stripes),
  rectangular badges, bold uppercase labels, black trim lines.
- **Touch targets ≥ 48 px** on mobile; generous spacing; thumb-friendly nav.

## State colors (F36) — the "copy" indicator

| Token | Light | Dark | Meaning |
|-------|-------|------|---------|
| `sent` (green) | `#1E7A46` | `#2E9E5B` | transcription committed — copy confirmed |
| `warning` (orange) | `#A55713` | `#F39C12` | server path failed → local fallback succeeded |
| `danger` (red) | `#C0392B` | `#E74C3C` | complete failure — nothing committed |

## CB mic glyph (F36)

- **Favicon** (browser tab), **floating tile** (Android), **web FE hero card**.
- Flat vector, white/green on transparent + black-outline variant for dark mode.
- Art generated via ComfyUI; one asset, three uses.

## LED bar meter (digital Cobra display, F36)

- 12–16 segment display; fills left→right with input level while recording.
- **Android:** directly above the floating mic when awake and recording.
- Colors follow the state table: green recording/sent · orange fallback · red failure.

## Responsive (F35)
- Breakpoints: **mobile** (< 640 px, portrait) · **tablet** (640–1024 px) ·
  **desktop** (> 1024 px, landscape).
- Mobile: bottom tab bar (Home / History / Settings); desktop: sidebar.
- The Android app renders the same components at phone scale.

## Acceptance
- FE and app render identical tokens; light/dark toggle persists per user.
- Mobile viewport → portrait layout; desktop → landscape layout.
- Contrast passes WCAG AA in both modes.

## Owns
Trucking design tokens: colors (light/dark), type, spacing, breakpoints, CB mic + LED bar spec.

## Public Interface
tokens.css, tokens.kt

## Depends On
- shared (registered in modules.toml)

## Does Not Own
- Screen implementations (android/ui, server/web-fe)

## Test Locations
- Unit (Python): `tests/unit/shared/ui-tokens/`. Run: python3 -m unittest discover -s tests/unit/shared/ui-tokens
- Unit (Kotlin): `shared/modules/ui-tokens/src/test/kotlin/`. Run: ./gradlew :shared:modules:ui-tokens:test
- Contract: `tests/contract/test_ui_tokens_contract.py`. Run: python3 -m unittest discover -s tests/contract -t tests/contract -p test_ui_tokens_contract.py
- Every run must report more than 0 tests. A mistyped path or pattern runs nothing and still prints OK.

## Test Requirement
Every test added or touched for this module must be proven to fail loudly:
break the protected behavior on purpose, confirm the test fails and says why,
then restore the code. A test that only ever passes proves nothing. This
applies to every tier and invariant listed above, not only the ones that seem
fragile.

## How This Module Is Built
One engineer owns this module and delivers it as one pull request, working on
one module at a time. The owner does not write the module's code. The owner:
- splits the work into sub-modules and has sub-agents write each one, code and
  tests;
- coordinates and orchestrates those sub-agents, checks every piece of their
  work, and sends back anything that is wrong until it is right;
- convenes a small council of sub-agents to advise on design, risks and tests
  before and during the build;
- hands the finished, checked module directly to the reviewer as a single pull
  request.
The owner's own work is orchestration, checking and correction, not writing
code.

This is how the project's own team builds modules. It is recommended for AI
agents, not required: an outside contributor may write the code themselves
(`.github/CONTRIBUTING.md`).

## Known Gotchas
- Light + dark tokens must both pass WCAG AA; one-file swap.
- Spacing: no scale defined yet. When a consumer needs one, it is proposed as a card change, and its values go to the project owner (design values are theirs).

## Invariants
- Every token in the palette and state tables above is present in both `tokens.kt`
  and `tokens.css` with exactly the value the table gives. Each table is
  implemented as written, so `sent` and `accent` are separate tokens and are not
  aliased.
- `tokens.kt` and `tokens.css` carry identical values: colors, type families,
  corner radius, touch target, breakpoints and the LED segment range.
- In `tokens.css` the light palette is in `:root`, and the dark palette is
  declared twice with identical values: under the system dark preference unless
  `data-theme="light"` is set, and under `data-theme="dark"`.
- Neither file declares a value this card does not give: no spacing scale, no
  letter-spacing, no chosen LED segment count.
- Corner radius is at most 4, the minimum touch target is at least 48, and the
  breakpoints are exactly 640 and 1024 (mobile below 640, tablet from 640
  through 1024, desktop above 1024).
- Text roles (text, text-muted, primary, primary-hover, danger, warning, accent and
  sent) reach WCAG AA 4.5:1 on bg and on surface, and trim reaches 3:1, in both
  modes. A pair below its minimum is pinned as an expected failure with its
  measured ratio and a floor it may not fall below; the pin fails the run once the
  value is fixed and must then be removed.
- `tokens.kt` is plain Kotlin values with no Android or Compose import and no
  dependency beyond the standard library; colors are opaque ARGB and dimensions
  are dp numbers.
