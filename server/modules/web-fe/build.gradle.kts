// server/modules/web-fe -- the website.
//
// Card: server/modules/web-fe/AGENTS.md   Registry: modules.toml [module.server_web_fe]
// Owns: Debian container website (client-side decrypt, Trucking UI), APK/model hosting,...
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

// Vendored web libraries (libsodium pair vendored + pinned but NOT copied to
// any served path — CSP script-src 'self' has no wasm-unsafe-eval, ADR-040;
// serving libsodium needs a new ADR). Copy exactly the three noble sub-modules
// from shared/modules/web-libs into build/web/js/vendor/. Hooked to assemble
// so the copy runs at assemble time, not processResources (web-fe applies only
// the base plugin and has no processResources of its own).
val webLibs = rootProject.layout.projectDirectory.dir("shared/modules/web-libs")

val copyWebLibs = tasks.register<Copy>("copyWebLibs") {
    group = "build"
    description = "Copy the vendored noble libraries into the served vendor tree"
    into(layout.buildDirectory.dir("web/js/vendor"))
    for (lib in listOf("noble-ciphers", "noble-hashes", "noble-curves")) {
        from(webLibs.dir(lib)) {
            exclude("VERSION", "README.md", "AGENTS.md", "build.gradle.kts")
            into(lib)
        }
    }
}

tasks.named("assemble") {
    dependsOn(copyWebLibs)
}
