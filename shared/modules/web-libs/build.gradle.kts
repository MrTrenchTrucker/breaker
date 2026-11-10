// shared/modules/web-libs — grouping module for vendored web libraries.
//
// Card: shared/modules/web-libs/AGENTS.md   Registry: modules.toml [module.shared_web_libs]
// Owns: nothing — grouping module only; each library lives in its own sub-module.
// Depends on: shared
// Cross-module calls go through the core ports or the app's own
// dependency wiring; this module never reaches into a sibling's code.
//
// Toolchain versions come from gradle/libs.versions.toml.
//
// Registered aggregate: groups the web-libs sub-modules. It owns no sources
// of its own, so the build applies the base plugin and stops there.
plugins {
    base
}
