package dev.breaker.server.syncapi.servicecheck

import java.nio.file.Files
import java.nio.file.Path

// Adds a second directory entry for an existing file (a hard link). The link fails when the
// target name already exists. The production linker is FILES; a test passes its own linker.
internal fun interface KeyLinker {
    fun link(target: Path, existing: Path)

    companion object {
        val FILES: KeyLinker = KeyLinker { target, existing -> Files.createLink(target, existing) }
    }
}
