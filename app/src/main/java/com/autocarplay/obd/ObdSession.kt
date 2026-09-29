package com.autocarplay.obd

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.autocarplay.R
import java.io.IOException

/**
 * Talks to an ELM327 adapter on a background thread while the car dashboard is open: connects,
 * reads the gauges in a loop and reconnects when the link drops. Results arrive on the main thread.
 */
class ObdSession(
    context: Context,
    private val adapter: ObdAdapter,
    private val listener: Listener,
) {

    interface Listener {
        fun onObdStatus(status: String, connected: Boolean)
        fun onObdReading(gauge: Gauge, value: Double?)

        /** Stored trouble codes, or null and the reason if they could not be read. */
        fun onTroubleCodes(codes: List<String>?, error: String?)
    }

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val thread = Thread({ loop() }, "obd")

    @Volatile private var running = false
    @Volatile private var link: ObdLink? = null
    @Volatile private var codesRequested = false

    fun start() {
        running = true
        thread.start()
    }

    fun stop() {
        running = false
        link?.close()
        thread.interrupt()
    }

    /** Asks for the stored trouble codes; the answer arrives in [Listener.onTroubleCodes]. */
    fun readTroubleCodes() {
        codesRequested = true
    }

    private fun loop() {
        while (running) {
            status(context.getString(R.string.obd_connecting, adapter.name), connected = false)
            try {
                ObdLink.open(context, adapter).use { opened ->
                    link = opened
                    if (running) talk(opened)
                }
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "OBD connection failed", e)
                val reason = if (e is SecurityException) context.getString(R.string.obd_bluetooth_permission) else e.message
                status(context.getString(R.string.obd_failed, reason ?: e.javaClass.simpleName), connected = false)
                clearReadings()
            } finally {
                link = null
            }
            if (codesRequested) {
                codesRequested = false
                post { listener.onTroubleCodes(null, context.getString(R.string.obd_not_connected)) }
            }
            try {
                Thread.sleep(RETRY_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun talk(link: ObdLink) {
        status(context.getString(R.string.obd_initializing), connected = false)
        for (command in Elm327.INIT_COMMANDS) {
            link.command(command, if (command == "ATZ") RESET_TIMEOUT_MS else ObdLink.TIMEOUT_MS)
        }
        // The first request also makes the adapter find the car's protocol, which can take a while.
        status(context.getString(R.string.obd_searching), connected = false)
        val supported = supportedPids(link)
        val can = Elm327.isCan(link.command("ATDPN"))
        val protocol = Elm327.lines(link.command("ATDP")).firstOrNull().orEmpty().removePrefix("AUTO,")
        status(context.getString(R.string.obd_connected, protocol), connected = true)

        val gauges = Gauge.entries.filter { it.pid == null || supported == null || it.pid in supported }
        (Gauge.entries - gauges.toSet()).forEach { reading(it, null) }
        var round = 0
        while (running) {
            if (codesRequested) {
                codesRequested = false
                readTroubleCodes(link, can)
            }
            for (gauge in gauges) {
                if (!gauge.fast && round % SLOW_EVERY != 0) continue
                val reply = link.command(gauge.command)
                val pid = gauge.pid
                reading(gauge, if (pid == null) Elm327.voltage(reply) else Elm327.pidData(reply, pid)?.let(gauge::decode))
            }
            round++
            Thread.sleep(ROUND_PAUSE_MS)
        }
    }

    /** Mode 01 PIDs the car supports, or null if it did not say (then every gauge is tried). */
    private fun supportedPids(link: ObdLink): Set<Int>? {
        val reply = link.command("0100", SEARCH_TIMEOUT_MS)
        val error = Elm327.error(reply)
        if (error == "UNABLE TO CONNECT" || error == "CAN ERROR" || error == "BUS ERROR") {
            throw IOException(context.getString(R.string.obd_no_car))
        }
        val supported = Elm327.supportedPids(reply, 0x00).toMutableSet()
        if (supported.isEmpty()) return null
        if (0x20 in supported) supported += Elm327.supportedPids(link.command("0120"), 0x20)
        return supported
    }

    private fun readTroubleCodes(link: ObdLink, can: Boolean) {
        val reply = link.command("03", CODES_TIMEOUT_MS)
        val error = Elm327.error(reply)
        when {
            // Many cars answer "NO DATA" when nothing is stored.
            error == null || error == "NO DATA" -> {
                val codes = Elm327.troubleCodes(reply, can)
                post { listener.onTroubleCodes(codes, null) }
            }
            else -> post { listener.onTroubleCodes(null, error) }
        }
    }

    private fun status(text: String, connected: Boolean) = post { listener.onObdStatus(text, connected) }

    private fun reading(gauge: Gauge, value: Double?) = post { listener.onObdReading(gauge, value) }

    private fun clearReadings() = Gauge.entries.forEach { reading(it, null) }

    /** Runs on the main thread, unless the session was stopped in the meantime. */
    private fun post(action: () -> Unit) {
        main.post { if (running) action() }
    }

    private companion object {
        const val TAG = "ObdSession"
        const val RESET_TIMEOUT_MS = 5_000L
        const val SEARCH_TIMEOUT_MS = 20_000L
        const val CODES_TIMEOUT_MS = 8_000L
        const val RETRY_MS = 5_000L
        const val ROUND_PAUSE_MS = 100L

        /** Slow gauges (temperatures, fuel, battery) are read every this many rounds. */
        const val SLOW_EVERY = 5
    }
}
