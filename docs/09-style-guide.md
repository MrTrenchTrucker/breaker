# 09 — Style guide

> How Breaker looks, in one place: the colours, the lettering, the rules, the logo and how each piece of
> the app is drawn. The exact colour and size values live in `shared/modules/ui-tokens` (its card and
> `tokens.kt`); this guide says how to use them. If this guide and the tokens ever disagree, the tokens
> win and this guide is fixed. Screens and flows are described in `docs/07-ui-ux-look-and-feel.md`.
> The approved logo and icon files are in `docs/brand/`.

Each piece of the app below carries a status:

- **Built**: on the main branch today.
- **Being built**: approved and in a worker's hands.
- **Planned**: approved, not started.
- **Being designed**: the art or the rule is not settled yet.

## 1. The feel

The voice is CB ("Breaker Breaker"), the look is trucking: the logo on the door of an 18-wheeler.
The CB mic is the product. White, black and green, bold slanted capitals, speed stripes.
Modern, not futuristic.

## 2. Colours

Every colour in the app comes from the ui-tokens palette (light and dark). Code never writes a colour
value; the no-colour gate checks that.

| Role | Light | Dark | Used for |
|---|---|---|---|
| `bg` | white | cool near-black | app background |
| `surface` | off-white | dark grey | cards, panels |
| `text` / `text-muted` | near-black / grey | off-white / grey | words |
| `primary` / `primary-hover` | green / darker green | green / brighter green | buttons, active states, links |
| `accent` | green | bright green | highlights |
| `trim` | black | white | borders, stripes, trim lines |
| `sent` | green | green | text delivered |
| `warning` | orange | orange | sending; the phone did it alone after the server failed |
| `danger` | red | red | failure, destructive actions, the microphone-taken sign |

A face that needs to look lit or shaded (the mic's frame and body) is a lighter or darker shade of the
same palette colour, worked out in code from that token. It is not a new colour.

The logo and icon files in `docs/brand/` are finished pictures, not drawn from the tokens at run time.
They use the dark-mode accent green with a darker and a lighter shade of it for the stripes, the
dark-mode background and text colours on the dark version, and white with the light-mode text colour
on the light version.

## 3. Lettering

- **The word BREAKER**, wherever it is drawn (the full-name logo, the word on the mic, the app icon,
  the floating tile): **Oswald Bold**, capitals, **slanted 12 degrees** to the right, letter spacing
  4.5% of the font size. One lettering everywhere, so the brand reads the same at every size.
  The logo and icon files use it today; the floating tile is being built with it.
- **The tagline** under the logo: Oswald SemiBold, capitals, the same 12-degree slant, letter spacing
  4% of the font size, about a quarter of the height of the word, in a muted text colour.
- **Headings in the app:** the tokens' display family, Anton, with Oswald as its alternate; capitals,
  a little letter spacing for section headers. The brand art (logo, icon, the word on the mic) uses
  Oswald only.
- **Body and buttons:** Inter.
- **Times, IDs, numbers:** JetBrains Mono.
- All four are open source (SIL Open Font License). No font file for BREAKER is shipped: the word is
  drawn from outline shapes taken from Oswald, which `NOTICE` credits.

## 4. Rules

- Strong geometry, high contrast, hard edges. Corners round at most 4 px, except where noted below.
- Trucking motifs: speed stripes, rectangular badges, bold capital labels, trim lines.
- Speed stripes are horizontal bars whose ends are cut at the same 12-degree slant as the lettering.
  They are broken into a few segments, and they always sit behind everything else.
- No glass effects, no sci-fi gradients, no neon. **One exception:** the glowing outline of the tile
  mic, wherever that mic appears (the floating tile, the app icon, the dark logo).
- Touch targets at least 48 dp; generous spacing; one-thumb use.

## 5. The CB mic

- One mic shape for everything: the shaded straight-line hand mic (rounded head, frame with lit and
  shaded faces, six grille slots, a ledge, a tapered body with BREAKER on it, a side push-to-talk bar
  and a cord stub). The source of truth is `docs/brand/breaker-mic-tile.svg`.
- The body is dark in both themes: in the light theme it takes the `text` colour, in the dark theme
  the `surface` colour, each with its lit and shaded faces worked out from that one token. The grille
  slots are green; BREAKER on the body follows section 3. **Being built** in the floating tile.

## 6. The floating tile

- **Built:** the tile shows a mic glyph inside a ring whose colour follows the tile's state: `trim`
  when Breaker is switched off; green (`primary`) when ready and while recording; orange (`warning`,
  with the busy pulse) while the take is being sent and turned into text; green (`sent`) when the text
  is delivered; orange (`warning`) when the phone did it alone because the server path failed; red
  (`danger`) on failure. While recording, a 12-segment level bar sits above the tile, with a check mark
  (send) and an X (cancel). When another app has the microphone, a red circle with a slash shows over
  the mic, pulsing softly. A tap on a notice closes it.
- **Being built** (the new tile look): the tile becomes the mic itself on a see-through window, with no
  square or box behind it in any state. A glowing outline runs round the mic, the side bar and the cord
  stub, in the same state colours as today's ring. While recording, the six grille slots become the
  level meter, lighting from left to right with the voice; silence leaves them all unlit; the bar above
  the tile goes away, so the recording window is three tiles wide and one tile high.
- **Planned:** a notice (a sentence from the app) widens the tile for 4 seconds, then the tile returns.
- **Being designed:** the check mark and X as see-through buttons, outlined and glowing like the mic.

## 7. Closing the tile

- **Built:** dragging the tile shows a circle with an X near the bottom of the screen; dropping the tile
  on it hides the tile.
- **Being designed:** the drop area becomes a dark grey, see-through box with rounded corners, about the
  size of the keyboard's space bar (just under half the screen wide, about 6% of its height). Inside
  it, a side-view silhouette of an American 18-wheeler pulling a lowboy trailer, and the load on the
  deck is a big red X.

## 8. Logo

- **Full name.** The tile mic on the left, BREAKER to its right, and speed stripes behind everything:
  five slanted bars run from behind the mic past the end of the word, broken into segments, with short
  dashes after their ends. On the left the bars step out behind the mic like a staircase, with one long
  bar below them. Two thin trim lines sit above the start of the word, with a short green dash after
  the lower one. Under the word, the tagline: **SELF-HOSTED VOICE TO TEXT, HANDS FREE**.
- **Two versions.** Dark: near-black background, white word, green stripes, glowing mic, white trim
  lines. Light: white background, near-black word, green and light-green stripes, black trim lines.
  Files: `docs/brand/breaker-logo-dark.svg` and `docs/brand/breaker-logo-light.svg` (with PNG copies).
- **Square icon** (app icon, website tab): the glowing tile mic with BREAKER on it, on near-black, with
  broken stripes behind it. The mic stays inside the centre two thirds, so a round launcher mask never
  cuts it. File: `docs/brand/breaker-icon.svg`. Using it as the app's launcher icon is **planned**.
- **Do not** redraw the letters in another font, stretch or squash the logo, change the slant, or put
  it on a busy picture. Keep empty space around it at least the height of the word's capitals.

## 9. First start

- **Built:** one scrolling page of setup steps (each permission and the speech model download), with
  the History button and the settings below them.
- **Being built:** the speech model download shows a progress bar.
- **Planned:** a welcome screen with a picture and the logo, for about 3 seconds; then one screen per
  permission (microphone, display over other apps, notifications, accessibility), each with a picture,
  one short sentence on why Breaker needs it, and a button that opens the right setting. Swipe right to
  left or tap Next to move on.
- **Being designed:** the pictures, the buttons and the main screen after setup.

## 10. How art is made

- Pictures are drafted with an image model whose licence allows shipping its output, at their final
  size, with no upscaler.
- Everything the app draws at small sizes (the mic, the icons, the signs) and the logo are redrawn as
  clean vector shapes from exact numbers, so they stay sharp at every size and every letter is exact.
- Every picture is checked for faults before anyone sees it: spelling, loose or floating parts,
  proportions, real-world sense, duplicates, light.
