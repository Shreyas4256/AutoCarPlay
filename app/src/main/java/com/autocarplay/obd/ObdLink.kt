package com.autocarplay.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** A connection to an ELM327 adapter over Bluetooth or Wi-Fi. Blocking; use off the main thread. */
class ObdLink private constructor(
    private val socket: Closeable,
    private val input: InputStream,
    private val output: OutputStream,
) : Closeable {

    private val buffer = ByteArray(256)

    /** Sends one command and returns the reply up to the adapter's ">" prompt. */
    fun command(command: String, timeoutMs: Long = TIMEOUT_MS): String {
        // Drop anything left over from an earlier command that timed out.
        while (input.available() > 0) input.read(buffer)
        output.write("$command\r".toByteArray(Charsets.US_ASCII))
        output.flush()
        val reply = StringBuilder()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            // Polling available() gives a timeout on Bluetooth sockets, whose reads can't time out.
            val available = input.available()
            if (available > 0) {
                val count = input.read(buffer, 0, minOf(available, buffer.size))
                if (count < 0) throw IOException("Adapter disconnected")
                for (i in 0 until count) {
                    val c = buffer[i].toInt().toChar()
                    if (c == '>') return reply.toString()
                    reply.append(c)
                }
            } else {
                if (SystemClock.uptimeMillis() > deadline) throw IOException("No reply to $command")
                Thread.sleep(POLL_MS)
            }
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (e: IOException) {
            // Already closed.
        }
    }

    companion object {
        const val TIMEOUT_MS = 3_000L
        private const val POLL_MS = 5L
        private const val CONNECT_TIMEOUT_MS = 8_000

        /** Serial port profile, which Bluetooth ELM327 adapters use. */
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun open(context: Context, adapter: ObdAdapter): ObdLink = when (adapter) {
            is ObdAdapter.Bluetooth -> openBluetooth(context, adapter.address)
            is ObdAdapter.WiFi -> openWiFi(context, adapter)
        }

        @SuppressLint("MissingPermission") // The caller checks ObdAdapters.hasBluetoothAccess.
        private fun openBluetooth(context: Context, address: String): ObdLink {
            val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IOException("This phone has no Bluetooth")
            if (!bluetooth.isEnabled) throw IOException("Bluetooth is off")
            val device = bluetooth.getRemoteDevice(address)
            val socket = try {
                device.createRfcommSocketToServiceRecord(SPP_UUID).apply {
                    try {
                        connect()
                    } catch (e: IOException) {
                        close()
                        throw e
                    }
                }
            } catch (e: IOException) {
                // Some cheap adapters only accept unencrypted connections.
                device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).apply {
                    try {
                        connect()
                    } catch (e2: IOException) {
                        close()
                        throw e2
                    }
                }
            }
            return ObdLink(socket, socket.inputStream, socket.outputStream)
        }

        private fun openWiFi(context: Context, adapter: ObdAdapter.WiFi): ObdLink {
            // The adapter's Wi-Fi has no internet, so Android may route traffic over mobile data
            // instead. Connect through the Wi-Fi network explicitly.
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION") // allNetworks is the simplest way to find the Wi-Fi network.
            val wifi = connectivity?.allNetworks?.firstOrNull {
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            val socket = wifi?.socketFactory?.createSocket() ?: Socket()
            try {
                socket.connect(InetSocketAddress(adapter.host, adapter.port), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
            } catch (e: IOException) {
                socket.close()
                throw e
            }
            return ObdLink(socket, socket.getInputStream(), socket.getOutputStream())
        }
    }
}

/** The chosen adapter (kept in SharedPreferences) and the Bluetooth devices paired with the phone. */
object ObdAdapters {

    /** Runtime permission needed to use paired Bluetooth devices, or null on Android 11 and older. */
    val bluetoothPermission: String? =
        if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else null

    fun hasBluetoothAccess(context: Context): Boolean {
        val permission = bluetoothPermission ?: return true
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun chosen(context: Context): ObdAdapter? =
        ObdAdapter.decode(prefs(context).getString(KEY_ADAPTER, null))

    fun choose(context: Context, adapter: ObdAdapter) =
        prefs(context).edit { putString(KEY_ADAPTER, adapter.encode()) }

    /** Paired Bluetooth devices, likely OBD adapters first. Empty without Bluetooth access. */
    @SuppressLint("MissingPermission") // Checked with hasBluetoothAccess.
    fun pairedDevices(context: Context): List<ObdAdapter.Bluetooth> {
        if (!hasBluetoothAccess(context)) return emptyList()
        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        val devices = try {
            bluetooth.bondedDevices.orEmpty()
        } catch (e: SecurityException) {
            emptySet()
        }
        return devices
            .map { ObdAdapter.Bluetooth(it.address, it.name ?: it.address) }
            .sortedWith(compareBy({ !looksLikeObd(it.name) }, { it.name.lowercase() }))
    }

    private fun looksLikeObd(name: String) =
        OBD_NAMES.any { name.contains(it, ignoreCase = true) }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("obd", Context.MODE_PRIVATE)

    private const val KEY_ADAPTER = "adapter"
    private val OBD_NAMES = listOf("OBD", "ELM", "Vgate", "V-LINK", "Vlink", "iCar", "Konnwei", "Veepeak")
}
