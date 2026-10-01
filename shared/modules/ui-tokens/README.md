# Ui Tokens

The Trucking design tokens for Breaker: one palette, one set of type families
and one set of fixed dimensions, shared by the web front end and the Android app
so both look identical.

Full module card: `AGENTS.md` in this folder.

## Files

| File | What it is |
|------|------------|
| `src/main/resources/tokens.css` | The tokens as CSS custom properties, light and dark. Packaged as a resource of this module's jar. |
| `src/main/kotlin/dev/breaker/shared/tokens/tokens.kt` | The same values as plain Kotlin: opaque ARGB colors, dp numbers and family names. No Android or Compose import, no dependency. |

Both files are written by hand. Nothing generates one from the other. The tests
in `tests/unit/shared/ui-tokens/` compare each file with the tables in the card
and with each other, so a change made to one and not the others fails.

The module carries only the values its card gives. The LED bar is the card's
range of 12 to 16 segments, and the radius and touch target are its bounds (a
corner radius of at most 4, a touch target of at least 48). There is no spacing
scale, no letter-spacing and no LED pixel size, and font files are not bundled:
only family names.

## Using the tokens

### CSS

Load `tokens.css` and reference the values as custom properties, for example
`var(--color-bg)`, `var(--color-text)`, `var(--radius)` and `var(--font-body)`.

The light values are in `:root`. The dark values apply automatically under the
system dark preference unless the root element (`<html>`) has `data-theme="light"`,
and always when it has `data-theme="dark"`. Set the attribute on `<html>`: the
selectors match `:root` only, so it does nothing on any other element. The dark
set is declared once for each of those two cases, with identical values.

Custom properties cannot be used inside a media query condition. A stylesheet
that switches layout at the breakpoints must repeat `640px` and `1024px`
literally in its media queries; `--breakpoint-mobile` and `--breakpoint-tablet`
are there for scripts and other consumers.

### Kotlin

`TruckingTokens.palette(mode)` returns the light or dark `TruckingPalette`, and
`TruckingTokens.toggled(mode)` gives the other mode. `TruckingTokens.metrics`
holds the corner radius, the minimum touch target and the two breakpoints, and
`metrics.bandFor(widthDp)` says which band a width falls in: mobile below 640,
tablet from 640 through 1024, desktop above 1024. `TruckingTokens.type` holds the
family names and `TruckingTokens.ledBar` the range of LED bar segments.

Dimensions are dp numbers. The CSS states the same numbers in px.

`bandFor` takes a width in dp as an `Int`, a `Float` or a `Double` and compares it
exactly, so a fractional width needs no rounding: 639.5 is mobile, 640 and 1024
are tablet, and 1024.5 is desktop. Do not round a width before asking: rounding up
puts 639.5 in tablet and rounding down puts 1024.5 in tablet, so neither direction
is right at both edges. A width that is not a number (NaN) is refused.

Colors are `TokenColor` values: opaque ARGB with an `argb` int and a `hex`
string. `TokenColor` is a Kotlin value class, so the accessors that return one
have mangled names in Java; the token types are meant for Kotlin code.

## Tests

```
python3 -m unittest discover -s tests/unit/shared/ui-tokens -p 'test_*.py'
python3 -m unittest discover -s tests/contract -p 'test_*.py'
./gradlew :shared:modules:ui-tokens:test
```

Only the standard library is needed for the Python tests. Give `discover` the
exact folder as above: the folder name has a hyphen, so it is not a package, and
a wider start directory such as `tests/unit` finds no tests. The contrast tests
are in the Python tier. Every color token except bg, surface and trim is held to
4.5:1 as a text color, on both surfaces and in both modes; trim is held to 3:1.
A pair whose colors fall below its WCAG minimum is pinned as an expected failure
with its measured ratio and a floor it may not fall below.
