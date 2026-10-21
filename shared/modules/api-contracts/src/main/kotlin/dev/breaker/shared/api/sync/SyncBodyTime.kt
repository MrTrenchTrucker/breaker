package dev.breaker.shared.api.sync

/** The largest epoch millisecond a created_at may hold: 9999-12-31T23:59:59.999Z. */
internal const val MAX_CREATED_AT_MS: Long = 253402300799999L

internal const val MS_PER_DAY: Long = 86400000L

/** The only accepted form of created_at: fixed width, UTC, always three millisecond digits. */
internal val CREATED_AT_RE = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})\\.([0-9]{3})Z")

internal fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

internal fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (isLeapYear(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** Days from 1970-01-01 to the given civil date (proleptic Gregorian, integer arithmetic only). */
internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = y / 400
    val yoe = y - era * 400
    val mp = (if (month > 2) month - 3 else month + 9).toLong()
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

/** The civil date (year, month, day) of a day count from 1970-01-01; days must not be negative. */
internal fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + 719468
    val era = z / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
    return Triple(y.toInt(), m.toInt(), d.toInt())
}

internal fun padDigits(value: Long, width: Int): String = value.toString().padStart(width, '0')

/** Formats epoch milliseconds as created_at. Refuses a value outside 0 .. MAX_CREATED_AT_MS. */
internal fun formatCreatedAtMs(epochMs: Long): String {
    if (epochMs < 0L || epochMs > MAX_CREATED_AT_MS) refuse(SyncBodyFamily.CREATED_AT)
    val days = epochMs / MS_PER_DAY
    val rem = epochMs % MS_PER_DAY
    val (y, m, d) = civilFromDays(days)
    val hh = rem / 3600000L
    val mi = (rem / 60000L) % 60
    val ss = (rem / 1000L) % 60
    val ms = rem % 1000
    val date = padDigits(y.toLong(), 4) + "-" + padDigits(m.toLong(), 2) + "-" + padDigits(d.toLong(), 2)
    val time = padDigits(hh, 2) + ":" + padDigits(mi, 2) + ":" + padDigits(ss, 2)
    return date + "T" + time + "." + padDigits(ms, 3) + "Z"
}

/** Parses created_at to epoch milliseconds. Refuses every form except the exact one, and every impossible date. */
internal fun parseCreatedAtMs(text: String): Long {
    val g = CREATED_AT_RE.matchEntire(text) ?: refuse(SyncBodyFamily.CREATED_AT)
    val y = g.groupValues[1].toInt()
    val mo = g.groupValues[2].toInt()
    val d = g.groupValues[3].toInt()
    val h = g.groupValues[4].toInt()
    val mi = g.groupValues[5].toInt()
    val s = g.groupValues[6].toInt()
    val ms = g.groupValues[7].padEnd(3, '0').toInt()
    if (y < 1970) refuse(SyncBodyFamily.CREATED_AT)
    if (mo < 1 || mo > 12) refuse(SyncBodyFamily.CREATED_AT)
    if (d < 1 || d > daysInMonth(y, mo)) refuse(SyncBodyFamily.CREATED_AT)
    if (h > 23) refuse(SyncBodyFamily.CREATED_AT)
    if (mi > 59) refuse(SyncBodyFamily.CREATED_AT)
    if (s > 59) refuse(SyncBodyFamily.CREATED_AT)
    val days = daysFromCivil(y, mo, d)
    return (((days * 24 + h) * 60 + mi) * 60 + s) * 1000 + ms
}
