package dev.breaker.dictation.format

import dev.breaker.dictation.core.port.Formatter
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.CallMethod
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.http.JdkHttpTransport
import dev.breaker.shared.prompts.FormatPrompt
import dev.breaker.shared.prompts.PromptCatalog
import dev.breaker.shared.prompts.PromptParameters
import java.io.IOException

/** Formats a transcript by asking the configured OpenAI-shaped server, falling back on any problem. */
class ServerLlmFormatter(
    private val baseUrl: () -> String,
    private val apiKey: () -> String?,
    private val model: () -> String,
    private val fallback: Formatter,
    private val transport: HttpTransport = JdkHttpTransport(),
    private val prompt: FormatPrompt = PromptCatalog.load(PromptCatalog.CURRENT),
) : Formatter {

    override fun format(rawText: String): String {
        if (rawText.isBlank()) return rawText
        if (baseUrl().isBlank() || model().isBlank()) return fallback.format(rawText)
        return try {
            val candidate = formatViaServer(rawText)
            if (candidate != null) candidate else fallback.format(rawText)
        } catch (e: IOException) {
            fallback.format(rawText)
        } catch (e: RuntimeException) {
            fallback.format(rawText)
        }
    }

    override fun toString(): String = "ServerLlmFormatter"

    private fun formatViaServer(rawText: String): String? {
        val url = baseUrl().removeSuffix("/") + "/v1/chat/completions"
        val headers = mutableListOf<Pair<String, String>>()
        headers += "Content-Type" to "application/json"
        headers += "Accept" to "application/json"
        val key = apiKey()
        if (key != null && key.isNotBlank()) headers += "Authorization" to "Bearer $key"
        val maxTokens = PromptParameters.maxTokensFor((rawText.length + 3) / 4)
        val body = buildBody(rawText, maxTokens)
        val request = CallRequest(
            CallMethod.POST,
            url,
            headers,
            body.toByteArray(),
            CallLimits(1500, 30000, 1_048_576L),
        )
        val reply = transport.send(request)
        if (reply.status != 200) return null
        val text = String(reply.body)
        val parsed = JsonReader.parse(text)
        val content = extractContent(parsed) ?: return null
        val trimmed = content.trim()
        if (!N9Guard.accepts(rawText, trimmed)) return null
        return trimmed
    }

    private fun buildBody(rawText: String, maxTokens: Int): String {
        return "{\"model\":\"${escapeJson(model())}\",\"temperature\":0,\"max_tokens\":$maxTokens,\"stream\":false,\"messages\":[{\"role\":\"system\",\"content\":\"${escapeJson(prompt.systemPrompt)}\"},{\"role\":\"user\",\"content\":\"${escapeJson(rawText)}\"}]}"
    }

    internal fun escapeJson(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun extractContent(parsed: Any?): String? {
        if (parsed !is Map<*, *>) return null
        val choices = parsed["choices"]
        if (choices !is List<*>) return null
        if (choices.isEmpty()) return null
        val first = choices[0]
        if (first !is Map<*, *>) return null
        val message = first["message"]
        if (message !is Map<*, *>) return null
        val content = message["content"]
        if (content !is String) return null
        return content
    }
}
