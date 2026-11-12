package dev.breaker.server.syncapi.accounts

import java.net.Inet4Address
import java.net.InetAddress
import kotlin.experimental.and

private const val ENV_TRUSTED_PROXIES = "BREAKER_TRUSTED_PROXIES"
private const val MAX_ENTRIES = 20
private const val MAX_ENTRY_LENGTH = 45

private val IPV4_LITERAL = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

/**
 * One entry of the trusted-proxy list: an IP literal ([prefixLen] = -1) or a CIDR block ([prefixLen] 1..32 for IPv4,
 * 1..128 for IPv6; a prefix length of 0 matches every address and is refused at parse time). An IPv4-mapped IPv6
 * address is an [Inet4Address] once parsed, so a mapped peer and an IPv4 entry compare as IPv4.
 */
internal data class TrustedProxyEntry(val address: InetAddress, val prefixLen: Int) {

    /** True when [other] is this literal, or inside this block. Addresses of different families never match. */
    fun contains(other: InetAddress): Boolean {
        val network = address.address
        val candidate = other.address
        if (network.size != candidate.size) return false
        if (prefixLen < 0) return network.indices.all { network[it] == candidate[it] }
        var remaining = prefixLen
        for (i in network.indices) {
            if (remaining <= 0) return true
            val bits = if (remaining >= 8) 8 else remaining
            val mask = (0xFF shl (8 - bits)).toByte()
            if ((network[i] and mask) != (candidate[i] and mask)) return false
            remaining -= bits
        }
        return true
    }
}

/** The parsed trusted-proxy list; empty means nothing is trusted. */
internal data class TrustedProxies(val entries: List<TrustedProxyEntry>) {

    /** True when [ip] is a listed proxy: a literal match, or inside a listed block. */
    fun contains(ip: InetAddress): Boolean = entries.any { it.contains(ip) }

    companion object {
        val empty = TrustedProxies(emptyList())
    }
}

/**
 * Parses BREAKER_TRUSTED_PROXIES: entries separated by commas, spaces around an entry ignored, empty entries skipped.
 * An entry is an IPv4 or IPv6 literal, or a literal with /prefix. No host names (nothing here ever asks a resolver).
 * Throws IllegalArgumentException, with a message that starts with sync-api: and names the variable, for more than
 * 20 entries, an entry over 45 characters, an entry that is not a literal or a CIDR, and a prefix length of 0.
 */
internal fun parseTrustedProxies(raw: String?): TrustedProxies {
    if (raw == null) return TrustedProxies.empty
    val texts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (texts.size > MAX_ENTRIES) {
        throw IllegalArgumentException("sync-api: $ENV_TRUSTED_PROXIES lists more than $MAX_ENTRIES entries")
    }
    return TrustedProxies(texts.map { parseEntry(it) })
}

private fun parseEntry(text: String): TrustedProxyEntry {
    if (text.length > MAX_ENTRY_LENGTH) {
        throw IllegalArgumentException("sync-api: $ENV_TRUSTED_PROXIES has an entry over $MAX_ENTRY_LENGTH characters")
    }
    val slash = text.indexOf('/')
    val address = parseIpLiteral(if (slash < 0) text else text.substring(0, slash)) ?: refuseEntry(text)
    if (slash < 0) return TrustedProxyEntry(address, -1)
    val digits = text.substring(slash + 1)
    if (digits.isEmpty() || digits.length > 3 || !digits.all { it in '0'..'9' }) refuseEntry(text)
    val prefixLen = digits.toInt()
    val widest = address.address.size * 8
    if (prefixLen < 1 || prefixLen > widest) refuseEntry(text)
    return TrustedProxyEntry(address, prefixLen)
}

private fun refuseEntry(text: String): Nothing =
    throw IllegalArgumentException("sync-api: $ENV_TRUSTED_PROXIES entry is not an IP literal or a CIDR block: $text")

/** An IPv4 or IPv6 literal, parsed here by hand: never a host name, never a resolver call. Null when it is anything else. */
private fun parseIpLiteral(text: String): InetAddress? {
    val v4 = parseV4Bytes(text)
    if (v4 != null) return InetAddress.getByAddress(v4)
    return parseIpv6(text)
}

private fun parseV4Bytes(text: String): ByteArray? {
    val match = IPV4_LITERAL.matchEntire(text) ?: return null
    val bytes = ByteArray(4)
    for (i in 0..3) {
        val part = match.groupValues[i + 1]
        if (part.length > 1 && part.startsWith('0')) return null
        val value = part.toInt()
        if (value > 255) return null
        bytes[i] = value.toByte()
    }
    return bytes
}

private fun parseIpv6(input: String): InetAddress? {
    if (!input.contains(':')) return null
    var text = input
    if (text.contains('.')) {
        // A dotted IPv4 tail (::ffff:1.2.3.4): turn it into its two hexadecimal groups.
        val cut = text.lastIndexOf(':')
        val tail = parseV4Bytes(text.substring(cut + 1)) ?: return null
        val high = ((tail[0].toInt() and 0xFF) shl 8) or (tail[1].toInt() and 0xFF)
        val low = ((tail[2].toInt() and 0xFF) shl 8) or (tail[3].toInt() and 0xFF)
        text = text.substring(0, cut + 1) + high.toString(16) + ":" + low.toString(16)
    }
    val gap = text.indexOf("::")
    if (gap != text.lastIndexOf("::")) return null
    val head: List<Int>
    val tail: List<Int>
    if (gap >= 0) {
        head = hexGroups(text.substring(0, gap)) ?: return null
        tail = hexGroups(text.substring(gap + 2)) ?: return null
        if (head.size + tail.size > 7) return null
    } else {
        head = hexGroups(text) ?: return null
        tail = emptyList()
        if (head.size != 8) return null
    }
    val groups = head + List(8 - head.size - tail.size) { 0 } + tail
    val bytes = ByteArray(16)
    for (i in 0..7) {
        bytes[i * 2] = (groups[i] shr 8).toByte()
        bytes[i * 2 + 1] = groups[i].toByte()
    }
    return InetAddress.getByAddress(bytes)
}

// Colon separated groups of one to four hexadecimal digits; the empty text is no group; an empty piece is refused.
private fun hexGroups(part: String): List<Int>? {
    if (part.isEmpty()) return emptyList()
    val groups = ArrayList<Int>()
    for (piece in part.split(":")) {
        if (piece.length !in 1..4) return null
        if (!piece.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        groups.add(piece.toInt(16))
    }
    return groups
}

/** The key text of an address: dotted for IPv4, lower case with the longest run of zero groups compressed for IPv6. */
private fun canonicalText(ip: InetAddress): String {
    if (ip is Inet4Address) return ip.hostAddress
    val bytes = ip.address
    val groups = IntArray(8) { ((bytes[it * 2].toInt() and 0xFF) shl 8) or (bytes[it * 2 + 1].toInt() and 0xFF) }
    var bestStart = -1
    var bestLength = 0
    var i = 0
    while (i < 8) {
        if (groups[i] != 0) {
            i++
            continue
        }
        var end = i
        while (end < 8 && groups[end] == 0) end++
        if (end - i > bestLength) {
            bestStart = i
            bestLength = end - i
        }
        i = end
    }
    if (bestLength < 2) return groups.joinToString(":") { it.toString(16) }
    val before = groups.take(bestStart).joinToString(":") { it.toString(16) }
    val after = groups.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
    return "$before::$after"
}

/**
 * The key of a request for the rate limits (ADR-041): the socket [peer], unless the peer is a listed proxy.
 * When it is, the key is the right-most X-Forwarded-For entry that is not itself a listed proxy, written in its
 * canonical form. [xffValues] is every header line, in order; the lines are joined, split on commas and trimmed.
 * The peer is the key when the peer is not listed, when every entry is listed, when the header is absent or empty,
 * and when the scan meets an entry that is not an IP literal, more than 20 entries, or an entry over 45 characters:
 * a header that cannot be read never decides a key. Only X-Forwarded-For is read.
 */
internal fun xffKey(peer: String, xffValues: List<String>, trusted: TrustedProxies): String {
    val peerAddress = parseIpLiteral(peer)
    if (peerAddress == null || !trusted.contains(peerAddress)) return peer
    val entries = xffValues.joinToString(",").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (entries.size > MAX_ENTRIES || entries.any { it.length > MAX_ENTRY_LENGTH }) return peer
    for (entry in entries.asReversed()) {
        val address = parseIpLiteral(entry) ?: return peer
        if (!trusted.contains(address)) return canonicalText(address)
    }
    return peer
}
