package dev.breaker.dictation.overlay

/**
 * The two source-text checks of the window adapter that have to follow code structure
 * rather than look for a word: where the window is added, and where the permission
 * answer comes from.
 *
 * Both are pure functions from source text to a list of violations, with comments
 * blanked and string literals kept. A construct that cannot be found is reported as
 * a violation, never skipped.
 */
internal object AdapterRules {

    private class Guard(val range: IntRange, val catches: List<Pair<String, String>>)

    private val tryOpen = Regex("\\btry\\s*\\{")
    private val catchHead = Regex("^\\s*catch\\s*\\(([^)]*)\\)\\s*\\{")
    private val refusedReturn = Regex("\\breturn\\s+AddOutcome\\.REFUSED\\b")

    /** Every try block of [code] with the catch clauses that directly follow it, as (parameter, body) pairs. */
    private fun guardsIn(code: String): List<Guard> = tryOpen.findAll(code).mapNotNull { match ->
        val open = match.range.last
        val close = SourceText.closeOf(code, open)
        if (close < 0) return@mapNotNull null
        val catches = ArrayList<Pair<String, String>>()
        var next = close + 1
        while (true) {
            val head = catchHead.find(code.substring(next)) ?: break
            val bodyOpen = next + head.range.last
            val bodyClose = SourceText.closeOf(code, bodyOpen)
            if (bodyClose < 0) break
            catches.add(head.groupValues[1] to code.substring(bodyOpen + 1, bodyClose))
            next = bodyClose + 1
        }
        Guard(open..close, catches)
    }.toList()

    private fun refuses(guard: Guard): Boolean = listOf("BadTokenException", "SecurityException").all { type ->
        guard.catches.any { (parameter, body) ->
            Regex("\\b$type\\b").containsMatchIn(parameter) && (refusedReturn.containsMatchIn(body) || body.trim() == "AddOutcome.REFUSED")
        }
    }

    fun guardProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val calls = Regex("\\baddView\\s*\\(").findAll(code).map { it.range.first }.toList()
        if (calls.isEmpty()) return listOf("no addView call was found")
        val guards = guardsIn(code)
        return calls.mapNotNull { at ->
            val enclosing = guards.filter { at in it.range }
            when {
                enclosing.isEmpty() -> "addView at offset $at is not inside a try"
                enclosing.none { refuses(it) } -> "addView at offset $at is in a try that does not catch BadTokenException and SecurityException and return REFUSED"
                else -> null
            }
        }
    }

    private val canDrawHead = Regex("\\bfun\\s+canDrawOverlays\\s*\\(\\s*\\)\\s*(?::\\s*Boolean\\s*)?")

    /** The body of `canDrawOverlays`, block or expression, or null when there is no such function. */
    private fun canDrawBody(code: String): String? {
        val at = canDrawHead.find(code)?.range?.last?.plus(1) ?: return null
        if (at >= code.length) return null
        return when (code[at]) {
            '{' -> SourceText.closeOf(code, at).let { if (it < 0) null else code.substring(at, it + 1) }
            '=' -> expressionFrom(code, at + 1)
            else -> null
        }
    }

    private fun expressionFrom(code: String, start: Int): String {
        var depth = 0
        for (i in start until code.length) {
            if (code[i] == '(') depth++
            if (code[i] == ')') depth--
            if (code[i] == '\n' && depth <= 0) {
                val before = code.substring(start, i).trimEnd().lastOrNull()
                val after = code.substring(i + 1).trimStart().firstOrNull()
                val continued = before == null || before in "&|+-*?:=(," || (after != null && after in ".?&|+-*:")
                if (!continued) return code.substring(start, i)
            }
        }
        return code.substring(start)
    }

    fun permissionAnswerProblems(source: String): List<String> {
        val body = canDrawBody(SourceText.code(source)) ?: return listOf("no canDrawOverlays function with a body was found")
        return listOfNotNull(
            "canDrawOverlays does not call Settings.canDrawOverlays(".takeUnless { Regex("\\bSettings\\.canDrawOverlays\\s*\\(").containsMatchIn(body) },
            "canDrawOverlays contains a literal true".takeIf { Regex("\\btrue\\b").containsMatchIn(body) },
        )
    }
}
