// server/modules/deploy — DeployScripts.
//
// Card: server/modules/deploy/AGENTS.md   Registry: modules.toml [module.server_deploy]
// Owns: Git-pull deploy, docker-compose, certs, APK signing, ZT bind, volumes, NOTICE.
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
