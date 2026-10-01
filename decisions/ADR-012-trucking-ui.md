# ADR-012: Trucking UI + CB mic motif (supersedes CB Radio palette)

**Status:** accepted
**Date:** 2026-09-29

## Context
The user's visual direction: white/black/green, modern but not futuristic,
trucking-company / 18-wheeler door-logo aesthetic, light + dark modes,
responsive web FE, and a CB mic as the floating-tile icon with a digital
Cobra-style LED bar meter for state.

## Decision
A **Trucking** design language shared by the web FE and Android app via
`shared/ui-tokens` (CSS variables / plain Kotlin values). State colors:
green = sent (copy confirmed), orange = server-fail → local fallback, red =
complete failure. The LED bar fills above the floating mic while recording.
Responsive breakpoints: mobile bottom tabs, tablet hamburger, desktop sidebar.

## Reasons
- One token set = identical look across platforms; the CB mic is the product
  affordance (tap the mic, talk); the LED meter reads at a glance.

## Consequences
Rules in: `shared/ui-tokens`, `docs/07-ui-ux-look-and-feel.md`, ComfyUI art
(flat vector, white/green + black outline). Rules out: the amber CB Radio
palette, glassmorphism/neon/sci-fi styling.
