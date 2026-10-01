// shared/modules/api-contracts — OpenAPI spec.
//
// Card: shared/modules/api-contracts/AGENTS.md   Registry: modules.toml [module.shared_api_contracts]
// Owns: OpenAPI spec: auth, sync, jobs, updates, admin, training.
// Depends on: shared
// Cross-module calls go through the core ports or the app's own
// dependency wiring; this module never reaches into a sibling's code.
//
// Toolchain versions come from gradle/libs.versions.toml.

// This module ships configuration, specifications or container assets
// rather than JVM code, so the build applies the base plugin. Its own
// tooling is added here by the phase that implements it.

plugins {
    base
}
