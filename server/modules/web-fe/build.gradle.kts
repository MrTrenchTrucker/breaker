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

// The served noble-curves copy gets exactly four import-path rewrites: a bare
// @noble/hashes path becomes a relative path. The sources are not changed.
// The twin table is REWRITES in tools/assemble_weblibs_test_tree.py, and the
// two must hold the same four lines. The copy task below applies this table.
val nobleCurvesRewrites = listOf(
    Triple("utils.js", "import { abytes as abytes_, anumber as anumber_, bytesToHex as bytesToHex_, concatBytes as concatBytes_, hexToBytes as hexToBytes_, isBytes as isBytes_, randomBytes as randomBytes_, } from '@noble/hashes/utils.js';", "import { abytes as abytes_, anumber as anumber_, bytesToHex as bytesToHex_, concatBytes as concatBytes_, hexToBytes as hexToBytes_, isBytes as isBytes_, randomBytes as randomBytes_, } from '../noble-hashes/utils.js';"),
    Triple("ed25519.js", "import { sha512 } from '@noble/hashes/sha2.js';", "import { sha512 } from '../noble-hashes/sha2.js';"),
    Triple("ed25519.js", "import { abytes, concatBytes, hexToBytes } from '@noble/hashes/utils.js';", "import { abytes, concatBytes, hexToBytes } from '../noble-hashes/utils.js';"),
    Triple("abstract/frost.js", "import { utf8ToBytes } from '@noble/hashes/utils.js';", "import { utf8ToBytes } from '../../noble-hashes/utils.js';")
)

// Captured here, not inside doLast: project state read at execution time is refused by the configuration cache.
val nobleCurvesServedDir = layout.buildDirectory.dir("web/js/vendor/noble-curves")

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
    doLast {
        for ((rel, before, after) in nobleCurvesRewrites) {
            val target = File(nobleCurvesServedDir.get().asFile, rel)
            val text = target.readText(Charsets.UTF_8)
            val count = text.split(before).size - 1
            if (count != 1) {
                throw GradleException("served noble-curves copy of $rel has $count matches for a rewrite line, expected 1")
            }
            target.writeText(text.replace(before, after), Charsets.UTF_8)
        }
    }
}

tasks.named("assemble") {
    dependsOn(copyWebLibs)
}
