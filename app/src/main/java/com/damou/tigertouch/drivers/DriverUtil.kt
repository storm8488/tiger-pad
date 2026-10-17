package com.damou.tigertouch.drivers

import com.damou.tigertouch.model.COLOR_UNKNOWN
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/*
 * The small things every driver does with a parsed report, written once: the
 * firmware's drivers/driver_util.h. A fix to one of these is a fix to all of
 * them; a driver that needs a variation writes its own and says why.
 */

/** The number at [key] when it is one; null otherwise, so a report that leaves a field out keeps the previous value. */
fun JSONObject.numOrNull(key: String): Double? {
    val v = opt(key)
    return if (v is Number) v.toDouble() else null
}

/** Like [numOrNull], but also reads numbers sent as strings ("22.930000"), as Creality does. */
fun JSONObject.looseNumOrNull(key: String): Double? = when (val v = opt(key)) {
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull()
    else -> null
}

/** The string at [key], or "" - never null. */
fun JSONObject.str(key: String): String {
    val v = opt(key)
    return if (v is String) v else ""
}

fun JSONArray.strAt(i: Int): String {
    val v = opt(i)
    return if (v is String) v else ""
}

/** Parses [text] as a JSON object, or null when it is not one. */
fun parseObject(text: String): JSONObject? = try {
    JSONObject(text)
} catch (e: Exception) {
    null
}

/**
 * A filament colour as the makers write it, to 0xRRGGBB.
 *
 *   "#RRGGBB" / "RRGGBB"        the plain form
 *   "#0RRGGBB"                  Creality's, with a leading 0 where an alpha would sit
 *   "RRGGBBAA" / "#RRGGBBAA"    Snapmaker's and Bambu's: the colour is the first six digits
 *
 * An empty or missing string is "unknown": a grey that reads as "no data", not
 * black, which would read as a real spool.
 */
fun colorFromHex(src: String?): Int {
    if (src.isNullOrEmpty()) return COLOR_UNKNOWN
    var s = if (src[0] == '#') src.substring(1) else src
    if (s.length == 7) s = s.substring(1)
    else if (s.length >= 8) s = s.substring(0, 6)
    return (s.toLongOrNull(16) ?: return COLOR_UNKNOWN).toInt() and 0xffffff
}

/**
 * Text for the screen in the device's language. Drivers say why a printer
 * shows nothing (`notice`) through this, French or English for now, so each
 * driver carries its own words and no shared table has to be edited.
 */
object Tr {
    fun t(en: String, fr: String): String =
        if (Locale.getDefault().language == "fr") fr else en
}

/** `host` or `host:port` as the account gives it: the firmware's net/addr.h. */
object Addr {
    /** Keeps a written port and adds [defaultPort] otherwise. IPv6 literals are bracketed. */
    fun withPort(addr: String, defaultPort: Int): String {
        val a = addr.trim()
        if (a.startsWith("[")) return if (a.contains("]:")) a else "$a:$defaultPort"
        val colons = a.count { it == ':' }
        return when {
            colons == 1 -> a
            colons > 1 -> "[$a]:$defaultPort" // a bare IPv6 address
            else -> "$a:$defaultPort"
        }
    }

    /** The host alone, port dropped. */
    fun hostOnly(addr: String): String {
        val a = addr.trim()
        if (a.startsWith("[")) return a.substringAfter('[').substringBefore(']')
        return if (a.count { it == ':' } == 1) a.substringBefore(':') else a
    }
}
