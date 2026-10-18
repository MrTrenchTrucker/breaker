package dev.breaker.dictation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The scope the start-up purge runs on: an app-lifetime scope on the dispatcher for blocking
 * input and output, because the purge talks to the database. Its job is a supervisor, so a failing
 * child cannot cancel the scope.
 */
fun createPurgeScope(): CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
