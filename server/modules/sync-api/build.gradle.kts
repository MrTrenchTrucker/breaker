// server/modules/sync-api — AuthApi, SyncApi, AdminApi.
//
// Card: server/modules/sync-api/AGENTS.md   Registry: modules.toml [module.server_sync_api]
// Owns: Auth (users, roles, agent tokens), sync, updates, retention, store clear, log policy.
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
