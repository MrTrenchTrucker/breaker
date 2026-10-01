// server/modules/web-fe — WebApp.
//
// Card: server/modules/web-fe/AGENTS.md   Registry: modules.toml [module.server_web_fe]
// Owns: Debian container website (client-side decrypt, Trucking UI), APK/cert/model hosting,...
// Depends on: server, server_sync_api, shared_ui_tokens
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
