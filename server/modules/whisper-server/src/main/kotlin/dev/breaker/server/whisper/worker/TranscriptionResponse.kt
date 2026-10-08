package dev.breaker.server.whisper.worker

// The service returns verbose_json with a "text" field, or plain text.
internal object TranscriptionResponse {

    fun parse(responseBody: String): ParsedTranscription? {
        val trimmed = responseBody.trim()
        if (trimmed.isEmpty()) return null

        return if (trimmed.startsWith("{")) {
            parseVerboseJson(trimmed)
        } else {
            ParsedTranscription(trimmed)
        }
    }

    private fun parseVerboseJson(json: String): ParsedTranscription? {
        val root = fields(json, 0) ?: return null
        val textIdx = root["text"] ?: return null
        val textResult = readString(json, textIdx) ?: return null
        val text = textResult.first
        if (text.isBlank()) return null

        val languageIdx = root["language"]
        val language = if (languageIdx != null) {
            val langResult = readString(json, languageIdx)
            langResult?.first
        } else null

        val segmentsIdx = root["segments"]
        val segments = if (segmentsIdx != null && json[segmentsIdx] == '[') {
            parseSegments(json, segmentsIdx)
        } else emptyList()

        return ParsedTranscription(text, segments, language)
    }

    private fun parseSegments(json: String, startIdx: Int): List<TranscriptionSegment> {
        val segments = mutableListOf<TranscriptionSegment>()
        var i = startIdx + 1
        while (i < json.length) {
            i = skipWs(json, i)
            if (i >= json.length) break
            val c = json[i]
            if (c == ']') break
            if (c == '{') {
                val segFields = fields(json, i)
                if (segFields != null) {
                    val startIdx = segFields["start"]
                    val endIdx = segFields["end"]
                    val textIdx = segFields["text"]
                    if (startIdx != null && endIdx != null && textIdx != null) {
                        val start = readNumber(json, startIdx)
                        val end = readNumber(json, endIdx)
                        val textResult = readString(json, textIdx)
                        if (start != null && end != null && textResult != null) {
                            segments.add(TranscriptionSegment(start, end, textResult.first))
                        }
                    }
                }
                val after = skipValue(json, i)
                i = if (after != null) after else break
            } else {
                i++
            }
        }
        return segments
    }

    private fun readNumber(s: String, i: Int): Double? {
        var j = skipWs(s, i)
        val start = j
        while (j < s.length && (s[j].isDigit() || s[j] == '.' || s[j] == '-' || s[j] == 'e' || s[j] == 'E' || s[j] == '+')) j++
        if (j == start) return null
        return s.substring(start, j).toDoubleOrNull()
    }

    private fun readString(s: String, i: Int): Pair<String, Int>? {
        if (i >= s.length || s[i] != '"') return null
        val sb = StringBuilder()
        var j = i + 1
        while (j < s.length) {
            val c = s[j]
            if (c == '"') return Pair(sb.toString(), j + 1)
            if (c == '\\') {
                if (j + 1 >= s.length) return null
                val next = s[j + 1]
                when (next) {
                    '"' -> { sb.append('"'); j += 2 }
                    '\\' -> { sb.append('\\'); j += 2 }
                    '/' -> { sb.append('/'); j += 2 }
                    'b' -> { sb.append('\b'); j += 2 }
                    'f' -> { sb.append('\u000C'); j += 2 }
                    'n' -> { sb.append('\n'); j += 2 }
                    'r' -> { sb.append('\r'); j += 2 }
                    't' -> { sb.append('\t'); j += 2 }
                    'u' -> {
                        if (j + 5 >= s.length) return null
                        val hex = s.substring(j + 2, j + 6)
                        if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
                        val code = hex.toIntOrNull(16) ?: return null
                        sb.append(code.toChar())
                        j += 6
                    }
                    else -> return null
                }
            } else {
                sb.append(c)
                j++
            }
        }
        return null
    }

    private fun skipWs(s: String, i: Int): Int {
        var j = i
        while (j < s.length) {
            val c = s[j]
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') j++
            else break
        }
        return j
    }

    private fun skipValue(s: String, i: Int): Int? {
        val j = skipWs(s, i)
        if (j >= s.length) return null
        return when (s[j]) {
            '"' -> {
                val result = readString(s, j)
                result?.second
            }
            '{', '[' -> {
                var depth = 0
                var k = j
                while (k < s.length) {
                    val c = s[k]
                    if (c == '"') {
                        val result = readString(s, k)
                        if (result == null) return null
                        k = result.second
                    } else {
                        if (c == '{' || c == '[') depth++
                        if (c == '}' || c == ']') {
                            depth--
                            if (depth == 0) return k + 1
                        }
                        k++
                    }
                }
                null
            }
            else -> {
                var k = j
                while (k < s.length) {
                    val c = s[k]
                    if (c == ',' || c == '}' || c == ']') break
                    k++
                }
                k
            }
        }
    }

    private fun fields(s: String, i: Int): Map<String, Int>? {
        if (i >= s.length || s[i] != '{') return null
        val map = mutableMapOf<String, Int>()
        var j = i + 1
        while (true) {
            j = skipWs(s, j)
            if (j >= s.length) return null
            if (s[j] == '}') return map
            val keyResult = readString(s, j) ?: return null
            val key = keyResult.first
            j = skipWs(s, keyResult.second)
            if (j >= s.length || s[j] != ':') return null
            j = skipWs(s, j + 1)
            if (j >= s.length) return null
            map[key] = j
            val after = skipValue(s, j) ?: return null
            j = skipWs(s, after)
            if (j >= s.length) return null
            if (s[j] == ',') {
                j++
                continue
            }
            if (s[j] == '}') return map
            return null
        }
    }
}

data class TranscriptionSegment(val start: Double, val end: Double, val text: String)

data class ParsedTranscription(
    val text: String,
    val segments: List<TranscriptionSegment> = emptyList(),
    val language: String? = null
)
