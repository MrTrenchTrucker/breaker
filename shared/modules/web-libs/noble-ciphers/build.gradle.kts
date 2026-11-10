// shared/modules/web-libs/noble-ciphers — vendored noble-ciphers files.
//
// Card: shared/modules/web-libs/noble-ciphers/AGENTS.md   Registry: modules.toml [module.shared_web_libs_noble_ciphers]
// Owns: Vendored noble-ciphers files (unmodified copies, pinned by hash).
// Depends on: shared_web_libs
// Cross-module calls go through the core ports or the app's own
// dependency wiring; this module never reaches into a sibling's code.
//
// Toolchain versions come from gradle/libs.versions.toml.
//
// Data-only vendored module: publishes no artifact, applies base only.
plugins {
    base
}
