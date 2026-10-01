// server/modules/training — TrainingApi.
//
// Card: server/modules/training/AGENTS.md   Registry: modules.toml [module.server_training]
// Owns: Per-user voice phrase model training (CPU; GPU via Local Inference).
// Depends on: server
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
