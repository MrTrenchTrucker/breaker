// shared — Contracts, registries.
//
// Card: shared/AGENTS.md   Registry: modules.toml [module.shared]
// Owns: Contracts + registry shared by app and server.
// Depends on: nothing (leaf)
// Cross-module calls go through the core ports or the app's own
// dependency wiring; this module never reaches into a sibling's code.
//
// Toolchain versions come from gradle/libs.versions.toml.

// Registered aggregate: this folder groups the modules listed in its
// card. It owns no sources of its own, so the build applies the base
// plugin and stops there.

plugins {
    base
}
