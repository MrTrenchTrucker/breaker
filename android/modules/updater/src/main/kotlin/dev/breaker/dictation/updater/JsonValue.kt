package dev.breaker.dictation.updater

/** One parsed JSON value. Objects keep their fields, numbers keep the text they had, and null is [Null]. */
internal sealed interface JsonValue {
    /** A JSON object; its keys are unique because a repeated key is refused while reading. */
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue

    /** A JSON array, items in the order they were written. */
    data class Arr(val items: List<JsonValue>) : JsonValue

    /** A JSON string, after its escapes are decoded. */
    data class Str(val value: String) : JsonValue

    /** A JSON number, kept as the text it had. */
    data class Num(val text: String) : JsonValue

    /** A JSON true or false. */
    data class Bool(val value: Boolean) : JsonValue

    /** A JSON null. */
    object Null : JsonValue
}
