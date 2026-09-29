package com.autocarplay.obd

import java.util.Locale

/** A value shown on the car dashboard, read with one ELM327 command. */
enum class Gauge(
    /** The ELM327 command: an OBD-II mode 01 request, or an adapter command. */
    val command: String,
    /** The mode 01 PID, or null for adapter commands. */
    val pid: Int?,
    val unit: String,
    val decimals: Int,
    /** Read on every polling round; the others change slowly and are read less often. */
    val fast: Boolean,
) {
    RPM("010C", 0x0C, "rpm", 0, fast = true),
    SPEED("010D", 0x0D, "km/h", 0, fast = true),
    ENGINE_LOAD("0104", 0x04, "%", 0, fast = true),
    THROTTLE("0111", 0x11, "%", 0, fast = true),
    COOLANT("0105", 0x05, "°C", 0, fast = false),
    INTAKE_AIR("010F", 0x0F, "°C", 0, fast = false),
    FUEL_LEVEL("012F", 0x2F, "%", 0, fast = false),

    /** Battery voltage as measured by the adapter (works on every car). */
    BATTERY("ATRV", null, "V", 1, fast = false);

    /** Converts the data bytes of a mode 01 reply (after "41 <pid>") to the value. */
    fun decode(data: IntArray): Double? {
        fun byte(i: Int) = data.getOrNull(i)?.toDouble()
        return when (this) {
            RPM -> byte(1)?.let { (byte(0)!! * 256 + it) / 4 }
            SPEED -> byte(0)
            ENGINE_LOAD, THROTTLE, FUEL_LEVEL -> byte(0)?.let { it * 100 / 255 }
            COOLANT, INTAKE_AIR -> byte(0)?.let { it - 40 }
            BATTERY -> null
        }
    }

    fun format(value: Double?): String =
        if (value == null) "–" else String.format(Locale.US, "%.${decimals}f", value)
}

/** Parses replies from an ELM327 OBD-II adapter (sent with echo, spaces and headers off). */
object Elm327 {

    /** Commands sent after connecting: reset, no echo/linefeeds/spaces/headers, auto protocol. */
    val INIT_COMMANDS = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")

    private val ERRORS = listOf("NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "STOPPED", "ERROR", "?")

    /** Reply lines, upper case without spaces, with progress messages and the prompt removed. */
    fun lines(reply: String): List<String> = reply
        .split('\r', '\n', '>')
        .map { it.replace(" ", "").uppercase(Locale.US) }
        .filter { it.isNotEmpty() && it != "SEARCHING..." && !it.startsWith("BUSINIT") }

    /** A short reason if the adapter or car could not answer, else null. */
    fun error(reply: String): String? {
        val text = reply.uppercase(Locale.US)
        return ERRORS.firstOrNull { text.contains(it) }
    }

    /** The data bytes of the reply to mode 01 [pid], or null if the car did not answer it. */
    fun pidData(reply: String, pid: Int): IntArray? {
        val prefix = "41" + hex(pid)
        val line = lines(reply).firstOrNull { it.startsWith(prefix) } ?: return null
        return bytes(line.substring(prefix.length))
    }

    /** Battery voltage from an `ATRV` reply such as "12.6V". */
    fun voltage(reply: String): Double? =
        Regex("""(\d{1,2}(?:\.\d+)?)V""").find(lines(reply).joinToString(""))?.groupValues?.get(1)?.toDoubleOrNull()

    /**
     * The mode 01 PIDs from [base]+1 to [base]+32 that the car supports, from the reply to
     * "01<base>" (base 0x00, 0x20, 0x40...). Replies from several control units are combined.
     */
    fun supportedPids(reply: String, base: Int): Set<Int> {
        val result = HashSet<Int>()
        val prefix = "41" + hex(base)
        for (line in lines(reply)) {
            if (!line.startsWith(prefix)) continue
            val mask = line.substring(prefix.length).take(8).toLongOrNull(16) ?: continue
            for (bit in 0 until 32) {
                if (mask and (1L shl (31 - bit)) != 0L) result += base + bit + 1
            }
        }
        return result
    }

    /** True if the reply to `ATDPN` (for example "A6") names a CAN protocol. */
    fun isCan(protocolReply: String): Boolean {
        val code = lines(protocolReply).firstOrNull()?.removePrefix("A") ?: return false
        return code.length == 1 && code[0] in "6789ABC"
    }

    /**
     * Stored trouble codes (such as "P0301") from the reply to mode 03. On CAN cars each reply
     * starts with the number of codes; long replies arrive as numbered frames ("0:", "1:"...).
     */
    fun troubleCodes(reply: String, can: Boolean): List<String> {
        val lines = lines(reply)
        val framed = lines.any { FRAME.matches(it) }
        val messages = if (framed) {
            // Multi-frame reply: a length line, then "0:<data>", "1:<data>"...
            listOf(lines.filter { FRAME.matches(it) }.joinToString("") { it.substringAfter(':') })
        } else {
            lines
        }
        val codes = LinkedHashSet<String>()
        for (message in messages) {
            if (!message.startsWith("43")) continue
            var data = message.substring(2)
            var count = Int.MAX_VALUE
            if (can && data.length >= 2) {
                count = data.substring(0, 2).toIntOrNull(16) ?: continue
                data = data.substring(2)
            }
            data.chunked(4).filter { it.length == 4 }.take(count).forEach { raw ->
                if (raw != "0000") decodeTroubleCode(raw)?.let { codes += it }
            }
        }
        return codes.toList()
    }

    /** "0301" → "P0301", "C123" → "U0123"... (the first two bits pick the system). */
    fun decodeTroubleCode(raw: String): String? {
        val first = raw.firstOrNull()?.digitToIntOrNull(16) ?: return null
        if (raw.length != 4 || raw.any { it.digitToIntOrNull(16) == null }) return null
        val system = "PCBU"[first shr 2]
        return "$system${first and 3}${raw.substring(1)}"
    }

    private val FRAME = Regex("^[0-9A-F]:[0-9A-F]*$")

    private fun hex(value: Int) = String.format(Locale.US, "%02X", value)

    private fun bytes(hexText: String): IntArray? {
        val pairs = hexText.chunked(2).filter { it.length == 2 }
        if (pairs.isEmpty()) return null
        return IntArray(pairs.size) { pairs[it].toIntOrNull(16) ?: return null }
    }
}

/** An ELM327 adapter the dashboard connects to. */
sealed class ObdAdapter {
    abstract val name: String

    /** A Bluetooth (classic, SPP) adapter paired with the phone. */
    data class Bluetooth(val address: String, override val name: String) : ObdAdapter()

    /** A Wi-Fi adapter; the phone joins the adapter's own Wi-Fi network. */
    data class WiFi(val host: String = DEFAULT_HOST, val port: Int = DEFAULT_PORT) : ObdAdapter() {
        override val name: String get() = "Wi-Fi $host:$port"
    }

    fun encode(): String = when (this) {
        is Bluetooth -> "bt|$address|$name"
        is WiFi -> "wifi|$host|$port"
    }

    companion object {
        /** The address almost every Wi-Fi ELM327 uses. */
        const val DEFAULT_HOST = "192.168.0.10"
        const val DEFAULT_PORT = 35000

        fun decode(text: String?): ObdAdapter? {
            val parts = text?.split('|', limit = 3) ?: return null
            if (parts.size != 3) return null
            return when (parts[0]) {
                "bt" -> Bluetooth(parts[1], parts[2])
                "wifi" -> parts[2].toIntOrNull()?.let { WiFi(parts[1], it) }
                else -> null
            }
        }
    }
}
