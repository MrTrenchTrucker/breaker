// server/modules/whisper-server — TranscriptionApi, QueueWorker.
//
// Card: server/modules/whisper-server/AGENTS.md   Registry: modules.toml [module.server_whisper]
// Owns: /v1/audio/transcriptions + FIFO queue worker + job API, forwarding to the...
// Depends on: server, shared_api_contracts
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
