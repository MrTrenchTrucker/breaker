package dev.breaker.server.syncapi

import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("dev.breaker.server.syncapi.pendingsealed")

// The one line the intake route writes for a sealed copy over MAX_SEALED_BYTES: the account id and the
// decoded size, never the content. It lives here, not in servicecheck/, whose sources may not log.
internal fun logPendingSealedOversize(accountId: Long, size: Int) {
    logger.warning("sync-api: pending-sealed intake refused a sealed copy over the limit: account $accountId, $size bytes")
}
