package com.hispeedtriggercam.p40

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.concurrent.Executors

class SerialTriggerManager(
    private val context: Context,
    private val listener: Listener
) : SerialInputOutputManager.Listener {

    companion object {
        private const val TAG = "SerialTrigger"
        private const val ACTION_USB_PERMISSION = "com.hispeedtriggercam.p40.USB_PERMISSION"
        private const val BAUD_RATE = 115200
        private const val BOOT_DELAY_MS = 1500L      // ESP8266 resets on DTR toggle at port open
        private const val ARM_TIMEOUT_MS = 5000L      // Total deadline for arm acknowledgement
        private const val ARM_RETRY_INTERVAL_MS = 1000L // Re-send arm command every 1s
    }

    interface Listener {
        fun onArmAcknowledged()
        fun onArmTimeout()
        fun onTriggerReceived()
        fun onSerialError(message: String)
    }

    private var port: UsbSerialPort? = null
    private var connection: UsbDeviceConnection? = null
    private var ioManager: SerialInputOutputManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lineBuffer = StringBuilder()
    private var waitingForAck = false
    private var armTimeoutRunnable: Runnable? = null
    private var pendingDriver: UsbSerialDriver? = null
    private var pendingArmCommand: (() -> Unit)? = null
    private var receiverRegistered = false
    private var armRetryRunnable: Runnable? = null
    private var pendingArmPayload: String? = null

    // ── Public API ──────────────────────────────────────────

    fun getAvailableDevices(): List<UsbSerialDriver> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    }

    fun open(driver: UsbSerialDriver, onReady: (() -> Unit)? = null) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        if (!usbManager.hasPermission(driver.device)) {
            pendingDriver = driver
            pendingArmCommand = onReady
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_MUTABLE
            )
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            context.registerReceiver(usbPermissionReceiver, filter)
            receiverRegistered = true
            usbManager.requestPermission(driver.device, permissionIntent)
            return
        }

        openInternal(driver, usbManager, onReady)
    }

    fun close() {
        cancelArmTimeout()
        cancelArmRetries()
        waitingForAck = false
        pendingArmCommand = null
        pendingArmPayload = null
        ioManager?.listener = null
        ioManager?.stop()
        ioManager = null
        try { port?.close() } catch (_: Exception) {}
        port = null
        try { connection?.close() } catch (_: Exception) {}
        connection = null
        lineBuffer.clear()
        unregisterReceiver()
        Log.i(TAG, "Serial port closed")
    }

    val isOpen: Boolean get() = port != null

    fun sendArm(ledPin: Int, ledOnUs: Int, delayUs: Int, triggerDelayUs: Int, triggers: String) {
        val payload = "hispeed-trigger $ledPin $ledOnUs $delayUs $triggerDelayUs $triggers"
        pendingArmPayload = payload
        waitingForAck = true

        // Send immediately, then retry every ARM_RETRY_INTERVAL_MS
        sendWithCrc(payload)
        scheduleArmRetries()
        scheduleArmTimeout()
    }

    fun sendReset() {
        val payload = "hispeed-reset"
        sendWithCrc(payload)
    }

    // ── USB Permission ──────────────────────────────────────

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                unregisterReceiver()
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val drv = pendingDriver
                val onReady = pendingArmCommand
                pendingDriver = null
                pendingArmCommand = null

                if (granted && drv != null) {
                    val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
                    openInternal(drv, mgr, onReady)
                } else {
                    mainHandler.post { listener.onSerialError("USB permission denied") }
                }
            }
        }
    }

    private fun unregisterReceiver() {
        if (receiverRegistered) {
            try { context.unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
    }

    private fun openInternal(driver: UsbSerialDriver, usbManager: UsbManager, onReady: (() -> Unit)? = null) {
        try {
            val conn = usbManager.openDevice(driver.device)
                ?: throw IOException("Failed to open USB device")
            val p = driver.ports[0]
            p.open(conn)
            p.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            // Try to set DTR/RTS — ESP8266 resets when DTR toggles at port open.
            // We can't prevent the reset, so we wait for it to boot.
            try { p.dtr = true } catch (_: Exception) {}
            try { p.rts = true } catch (_: Exception) {}

            connection = conn
            port = p

            val mgr = SerialInputOutputManager(p, this)
            ioManager = mgr
            Executors.newSingleThreadExecutor().submit(mgr)

            Log.i(TAG, "Serial port opened: ${driver.device.deviceName}, waiting ${BOOT_DELAY_MS}ms for ESP boot...")

            // ESP8266/ESP32 resets when DTR toggles on port open; wait for boot
            // then flush any boot garbage from input buffer before sending commands
            mainHandler.postDelayed({
                lineBuffer.clear()
                Log.i(TAG, "Boot delay done, input buffer flushed — ready")
                onReady?.invoke()
            }, BOOT_DELAY_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open serial: ${e.message}", e)
            mainHandler.post { listener.onSerialError("Open failed: ${e.message}") }
        }
    }

    // ── Serial I/O Callbacks ────────────────────────────────

    override fun onNewData(data: ByteArray) {
        val text = String(data)
        lineBuffer.append(text)

        while (true) {
            val nlIndex = lineBuffer.indexOf('\n')
            if (nlIndex < 0) break
            val line = lineBuffer.substring(0, nlIndex).trim()
            lineBuffer.delete(0, nlIndex + 1)
            if (line.isNotEmpty()) {
                processLine(line)
            }
        }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "Serial I/O error: ${e.message}", e)
        mainHandler.post { listener.onSerialError("Serial disconnected: ${e.message}") }
    }

    // ── Line Processing ─────────────────────────────────────

    private fun processLine(line: String) {
        Log.d(TAG, "Received: $line")

        // Split into body + CRC hex
        val lastSpace = line.lastIndexOf(' ')
        if (lastSpace < 0) {
            Log.w(TAG, "Malformed message (no space): $line")
            return
        }

        val body = line.substring(0, lastSpace)
        val receivedCrcHex = line.substring(lastSpace + 1)

        // The CRC covers "body " (body + the trailing space)
        val crcInput = line.substring(0, lastSpace + 1)
        val expectedCrc = crc8(crcInput.toByteArray())
        val receivedCrc = try {
            receivedCrcHex.toInt(16)
        } catch (_: NumberFormatException) {
            Log.w(TAG, "Invalid CRC hex: $receivedCrcHex")
            return
        }

        if (receivedCrc != expectedCrc) {
            Log.w(TAG, "CRC mismatch: expected %02X, got %02X for '%s'"
                .format(expectedCrc, receivedCrc, crcInput))
            mainHandler.post { listener.onSerialError("CRC error on received message") }
            return
        }

        when (body) {
            "OK" -> {
                Log.i(TAG, "Received ACK: OK")
                if (waitingForAck) {
                    waitingForAck = false
                    cancelArmTimeout()
                    cancelArmRetries()
                    mainHandler.post { listener.onArmAcknowledged() }
                }
            }
            "r" -> {
                Log.i(TAG, "Received TRIGGER: r")
                mainHandler.post { listener.onTriggerReceived() }
            }
            else -> {
                Log.w(TAG, "Unknown response: $body")
            }
        }
    }

    // ── CRC8 (polynomial 0x07, init 0x00, no final XOR) ────

    private fun crc8(data: ByteArray): Int {
        var crc = 0x00
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (bit in 0 until 8) {
                crc = if (crc and 0x80 != 0) {
                    (crc shl 1) xor 0x07
                } else {
                    crc shl 1
                }
                crc = crc and 0xFF
            }
        }
        return crc
    }

    // ── Helpers ─────────────────────────────────────────────

    private fun sendWithCrc(payload: String) {
        // CRC covers all bytes before the CRC, including the trailing space
        val withSpace = "$payload "
        val crc = crc8(withSpace.toByteArray())
        val message = "$payload ${"%02X".format(crc)}\n"

        Log.i(TAG, "Sending: ${message.trim()}")
        try {
            port?.write(message.toByteArray(), 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Write failed: ${e.message}", e)
            mainHandler.post { listener.onSerialError("Write failed: ${e.message}") }
        }
    }

    private fun scheduleArmTimeout() {
        cancelArmTimeout()
        val runnable = Runnable {
            if (waitingForAck) {
                waitingForAck = false
                Log.w(TAG, "Arm timeout — no OK received within ${ARM_TIMEOUT_MS}ms")
                listener.onArmTimeout()
            }
        }
        armTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, ARM_TIMEOUT_MS)
    }

    private fun cancelArmTimeout() {
        armTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        armTimeoutRunnable = null
    }

    private fun scheduleArmRetries() {
        cancelArmRetries()
        val runnable = object : Runnable {
            override fun run() {
                val payload = pendingArmPayload
                if (waitingForAck && payload != null) {
                    Log.i(TAG, "Re-sending arm command (device may still be booting)")
                    sendWithCrc(payload)
                    mainHandler.postDelayed(this, ARM_RETRY_INTERVAL_MS)
                }
            }
        }
        armRetryRunnable = runnable
        mainHandler.postDelayed(runnable, ARM_RETRY_INTERVAL_MS)
    }

    private fun cancelArmRetries() {
        armRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        armRetryRunnable = null
        pendingArmPayload = null
    }
}
