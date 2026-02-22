package com.hispeedtriggercam.p40

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbManager
import android.view.MotionEvent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hispeedtriggercam.p40.databinding.ActivityMainBinding
import android.os.Environment
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HiSpeedTriggerCam"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        private val RETRYABLE_PHASES = setOf(
            "configure", "createMode", "fatal", "sessionConfig",
            "createSession", "cameraDevice", "openCamera", "startPreview"
        )
    }

    // ── State ───────────────────────────────────────────────────

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings

    private var engineType = AppSettings.EngineType.CAMERA2_DIRECT
    private var use1920fps = true
    private val videoFps get() = if (use1920fps) 1920 else 960
    private val videoWidth get() = if (use1920fps) 1280 else 1920
    private val videoHeight get() = if (use1920fps) 720 else 1080
    private val fpsLabel get() = if (use1920fps) "1920" else "960"

    private var engine: SSMEngine? = null
    private var previewSurface: Surface? = null
    private var isRecordingReady = false
    private var isArmed = false
    private var recordedFromArmedMode = false
    private var currentOutputFile: File? = null
    private var captureStartMs = 0L

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var retryCount = 0

    private var captureServer: CaptureServer? = null
    private var serialManager: SerialTriggerManager? = null
    private val useRemoteTrigger get() = settings.remoteTriggerEnabled
    private var usbReceiverRegistered = false

    private val usbHotplugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // Check if the attached device is a serial device
                    val tempMgr = SerialTriggerManager(context, object : SerialTriggerManager.Listener {
                        override fun onArmAcknowledged() {}
                        override fun onArmTimeout() {}
                        override fun onTriggerReceived() {}
                        override fun onSerialError(message: String) {}
                    })
                    val drivers = tempMgr.getAvailableDevices()
                    if (drivers.isNotEmpty()) {
                        val dev = drivers.first().device
                        val name = dev.productName ?: dev.deviceName
                        Toast.makeText(context, "USB serial device connected: $name", Toast.LENGTH_SHORT).show()
                        Log.i(TAG, "USB serial attached: $name (${drivers.size} device(s))")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val tempMgr = SerialTriggerManager(context, object : SerialTriggerManager.Listener {
                        override fun onArmAcknowledged() {}
                        override fun onArmTimeout() {}
                        override fun onTriggerReceived() {}
                        override fun onSerialError(message: String) {}
                    })
                    val drivers = tempMgr.getAvailableDevices()
                    if (drivers.isEmpty()) {
                        Toast.makeText(context, "USB serial device disconnected", Toast.LENGTH_SHORT).show()
                        Log.i(TAG, "USB serial detached — no devices remaining")
                    } else {
                        Toast.makeText(context, "USB device disconnected (${drivers.size} still connected)", Toast.LENGTH_SHORT).show()
                        Log.i(TAG, "USB detached — ${drivers.size} device(s) remaining")
                    }
                }
            }
        }
    }

    private var preSettingsServer = false

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        onSettingsResult()
    }

    // ── Lifecycle ───────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = AppSettings(this)
        loadSettings()

        binding.captureButton.setOnClickListener { onCapturePressed() }
        binding.armButton.setOnClickListener { onArmPressed() }
        binding.moreButton.setOnClickListener { openSettings() }
        binding.textureView.surfaceTextureListener = surfaceTextureListener
        setupTapToFocus()
        if (settings.serverEnabled) startCaptureServer()

        // Listen for USB hotplug events
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbHotplugReceiver, usbFilter)
        usbReceiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThreads()
        if (PermissionHelper.hasAllPermissions(this)) {
            if (binding.textureView.isAvailable) {
                createMode()
            }
        } else {
            PermissionHelper.requestPermissions(this)
        }
    }

    override fun onPause() {
        releaseCamera()
        stopBackgroundThreads()
        super.onPause()
    }

    override fun onDestroy() {
        if (usbReceiverRegistered) {
            try { unregisterReceiver(usbHotplugReceiver) } catch (_: Exception) {}
            usbReceiverRegistered = false
        }
        closeSerial()
        captureServer?.stop()
        captureServer = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionHelper.REQUEST_CODE) {
            if (PermissionHelper.allGranted(grantResults)) {
                if (binding.textureView.isAvailable) {
                    createMode()
                }
            } else {
                Toast.makeText(this, "Camera permissions are required", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // ── Settings ─────────────────────────────────────────────────

    private fun loadSettings() {
        engineType = settings.engineType
        use1920fps = settings.use1920fps
    }

    private fun openSettings() {
        preSettingsServer = settings.serverEnabled
        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
    }

    private fun onSettingsResult() {
        val newServer = settings.serverEnabled

        // Handle server toggle
        if (newServer != preSettingsServer) {
            if (newServer) startCaptureServer() else stopCaptureServer()
        }

        // If remote trigger was disabled while serial is open, clean up
        if (!settings.remoteTriggerEnabled && serialManager != null) {
            if (isArmed) {
                serialManager?.sendReset()
                isArmed = false
                binding.armButton.text = getString(R.string.arm)
            }
            closeSerial()
        }

        // Update local fields — camera will reinitialize in onResume()
        engineType = settings.engineType
        use1920fps = settings.use1920fps
        retryCount = 0
    }

    // ── USB HID Key Handling ────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_R &&
            isArmed && isRecordingReady &&
            !useRemoteTrigger
        ) {
            Log.i(TAG, "USB HID trigger 'r' received - starting capture")
            startCapture()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ── Background Threads ──────────────────────────────────────

    private fun startBackgroundThreads() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThreads() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
    }

    // ── Engine Lifecycle ────────────────────────────────────────

    private fun releaseCamera() {
        try {
            engine?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing engine: ${e.message}")
        }
        engine = null
        isRecordingReady = false
        if (isArmed && useRemoteTrigger) {
            serialManager?.sendReset()
            closeSerial()
        }
        isArmed = false
        previewSurface?.release()
        previewSurface = null
        runOnUiThread {
            binding.armButton.text = getString(R.string.arm)
            binding.armButton.isEnabled = false
            binding.captureButton.isEnabled = false
        }
    }

    private fun createMode() {
        if (engine != null) return
        val handler = backgroundHandler ?: return

        Log.i(TAG, "Creating engine: ${engineType.label}")
        updateStatus("Starting ${engineType.label}...")

        val videoSize = Size(videoWidth, videoHeight)
        val surfaceTexture = binding.textureView.surfaceTexture
        if (surfaceTexture == null) {
            Log.e(TAG, "SurfaceTexture is null")
            updateStatus("ERROR: No surface")
            return
        }
        surfaceTexture.setDefaultBufferSize(videoSize.width, videoSize.height)
        configureTransform(videoSize)
        previewSurface = Surface(surfaceTexture)

        val camManager = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val cameraIds = camManager.cameraIdList
        if (cameraIds.isEmpty()) {
            Log.e(TAG, "No cameras found")
            updateStatus("ERROR: No cameras")
            return
        }
        val cameraId = cameraIds[0]

        val newEngine = when (engineType) {
            AppSettings.EngineType.HUAWEI_SDK ->
                HuaweiSDKEngine(applicationContext, handler, engineListener)
            AppSettings.EngineType.CAMERA2_DIRECT -> {
                val e = Camera2DirectEngine(applicationContext, handler, engineListener)
                e.targetBitrate = 12_000_000
                e.useHevc = false
                e.outputFrameRate = 30
                e
            }
            AppSettings.EngineType.CUSTOM_SDK ->
                CustomSDKEngine(applicationContext, handler, engineListener)
        }
        engine = newEngine

        handler.post {
            newEngine.open(cameraId, previewSurface!!, videoFps, videoWidth, videoHeight)
        }
    }

    // ── Engine Listener (routes callbacks to UI) ────────────────

    private val engineListener = object : SSMEngine.Listener {
        override fun onPreviewStarted() {
            Log.i(TAG, "[${engineType.label}] Preview started")
            updateStatus("Preview active — waiting for ready...")
        }

        override fun onRecordingReady() {
            Log.i(TAG, "[${engineType.label}] RECORDING_READY +${elapsedMs()}ms isArmed=$isArmed recordedFromArmed=$recordedFromArmedMode")
            retryCount = 0
            if (isArmed) {
                // Re-apply torch — mode may cycle back to READY and reset flash
                engine?.setFlash(true)
            } else if (!recordedFromArmedMode) {
                engine?.setFlash(false)
            }
            isRecordingReady = true
            runOnUiThread {
                binding.moreButton.isEnabled = true
                if (isArmed) {
                    binding.captureButton.isEnabled = false
                    binding.armButton.isEnabled = true
                    updateStatus("Armed — waiting for trigger... +${elapsedMs()}ms")
                } else {
                    binding.captureButton.isEnabled = true
                    binding.armButton.isEnabled = true
                    updateStatus("Ready — press CAPTURE +${elapsedMs()}ms")
                }
            }
        }

        override fun onRecordingStarted() {
            Log.i(TAG, "[${engineType.label}] RECORDING_STARTED +${elapsedMs()}ms")
            updateStatus("Recording... +${elapsedMs()}ms")
            runOnUiThread {
                binding.captureButton.isEnabled = false
                binding.armButton.isEnabled = false
                binding.moreButton.isEnabled = false
            }
        }

        override fun onRecordingStopped() {
            Log.i(TAG, "[${engineType.label}] RECORDING_STOPPED +${elapsedMs()}ms")
            updateStatus("Processing... +${elapsedMs()}ms")
        }

        override fun onRecordingFinished() {
            Log.i(TAG, "[${engineType.label}] RECORDING_FINISHED +${elapsedMs()}ms")
            updateStatus("Saving... +${elapsedMs()}ms")
        }

        override fun onRecordingSaved(file: File) {
            val outputFile = if (file.exists() && file.length() > 0) file else currentOutputFile
            Log.i(TAG, "[${engineType.label}] RECORDING_SAVED +${elapsedMs()}ms: ${outputFile?.absolutePath}")
            updateStatus("Saved: ${outputFile?.name ?: "?"} (${(outputFile?.length() ?: 0) / 1024} KB) +${elapsedMs()}ms")

            if (outputFile != null && outputFile.exists() && outputFile.length() > 0) {
                backgroundHandler?.post { writeMetadataJson(outputFile) }
            }
        }

        override fun onError(phase: String, message: String) {
            Log.e(TAG, "[${engineType.label}] ERROR [$phase]: $message")

            if (phase in RETRYABLE_PHASES && retryCount < MAX_RETRIES) {
                retryCount++
                Log.i(TAG, "Retrying engine (attempt $retryCount/$MAX_RETRIES) after $phase failure...")
                updateStatus("Retry $retryCount/$MAX_RETRIES after $phase error...")
                releaseCamera()
                backgroundHandler?.postDelayed({
                    runOnUiThread {
                        if (binding.textureView.isAvailable) {
                            createMode()
                        }
                    }
                }, RETRY_DELAY_MS)
            } else {
                updateStatus("ERROR [$phase]: $message")
                runOnUiThread {
                    binding.captureButton.isEnabled = isRecordingReady
                    binding.armButton.isEnabled = true
                    binding.moreButton.isEnabled = true
                }
            }
        }
    }

    // ── TextureView Listener ────────────────────────────────────

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            if (PermissionHelper.hasAllPermissions(this@MainActivity)) {
                createMode()
            }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    // ── Tap to Focus ────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        binding.textureView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP && engine != null) {
                val focusRect = touchToFocusRect(event.x, event.y, v.width, v.height)
                Log.i(TAG, "Tap-to-focus touch=(${event.x.toInt()},${event.y.toInt()}) rect=$focusRect")
                engine?.setFocus(focusRect)
            }
            true
        }
    }

    /**
     * Convert a touch point in preview (screen) coordinates to a focus Rect
     * in the CameraKit center coordinate system: [-1000, 1000] on both axes.
     *
     * The camera sensor is landscape while the phone is portrait, so there's a 90° rotation:
     *   center_x = preview_y * 2000 / previewHeight - 1000
     *   center_y = 1000 - preview_x * 2000 / previewWidth
     * (Matches the transformation in the Huawei CameraKit SSM example.)
     */
    private fun touchToFocusRect(touchX: Float, touchY: Float, viewW: Int, viewH: Int): Rect {
        val halfSide = 100
        val centerX = (touchY * 2000f / viewH - 1000f).toInt()
        val centerY = (1000f - touchX * 2000f / viewW).toInt()
        return Rect(
            (centerX - halfSide).coerceIn(-1000, 1000),
            (centerY - halfSide).coerceIn(-1000, 1000),
            (centerX + halfSide).coerceIn(-1000, 1000),
            (centerY + halfSide).coerceIn(-1000, 1000)
        )
    }

    // ── Serial Trigger ─────────────────────────────────────────

    private val serialListener = object : SerialTriggerManager.Listener {
        override fun onArmAcknowledged() {
            Log.i(TAG, "Serial arm acknowledged — entering armed mode")
            enterArmedState()
        }

        override fun onArmTimeout() {
            Log.w(TAG, "Serial arm timeout — no OK received")
            Toast.makeText(this@MainActivity,
                getString(R.string.serial_arm_timeout), Toast.LENGTH_LONG).show()
            exitArmingState()
            closeSerial()
        }

        override fun onTriggerReceived() {
            Log.i(TAG, "Serial trigger 'r' received — starting capture")
            if (isArmed && isRecordingReady) {
                startCapture()
            }
        }

        override fun onSerialError(message: String) {
            Log.e(TAG, "Serial error: $message")
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            if (isArmed) {
                isArmed = false
                engine?.setFlash(false)
                binding.armButton.text = getString(R.string.arm)
                if (isRecordingReady) {
                    binding.captureButton.isEnabled = true
                    binding.armButton.isEnabled = true
                }
                binding.moreButton.isEnabled = true
                updateStatus("Serial error — unarmed")
            } else {
                exitArmingState()
            }
            closeSerial()
        }
    }

    private fun openSerial(): Boolean {
        val mgr = SerialTriggerManager(this, serialListener)
        val drivers = mgr.getAvailableDevices()
        val driver = drivers.firstOrNull()

        if (driver == null) {
            Toast.makeText(this, "No USB serial device found", Toast.LENGTH_LONG).show()
            return false
        }

        val devName = driver.device.productName ?: driver.device.deviceName
        Toast.makeText(this, "Connecting to $devName...", Toast.LENGTH_SHORT).show()

        serialManager = mgr
        mgr.open(driver) {
            // Called when port is ready (after permission grant + ESP boot delay)
            // Send reset first to clear any stale state on the ESP
            mgr.sendReset()
            // Small delay to let the ESP process the reset before arming
            Handler(Looper.getMainLooper()).postDelayed({
                // Convert display units to microseconds for the ESP protocol
                mgr.sendArm(
                    settings.espLedPin,
                    settings.espLedOnMs * 1000,          // ms → µs
                    settings.espCooldownMs * 1000,        // ms → µs
                    settings.espTriggerDelayMs * 1000,    // ms → µs
                    settings.espTriggers
                )
            }, 200)
        }
        return true
    }

    private fun closeSerial() {
        serialManager?.close()
        serialManager = null
    }

    private fun enterArmingState() {
        binding.captureButton.isEnabled = false
        binding.armButton.isEnabled = false
        binding.moreButton.isEnabled = false
        updateStatus(getString(R.string.serial_arming))
    }

    private fun exitArmingState() {
        binding.armButton.isEnabled = true
        binding.moreButton.isEnabled = true
        if (isRecordingReady) {
            binding.captureButton.isEnabled = true
        }
        updateStatus(if (isRecordingReady) "Ready — press CAPTURE" else "Waiting for ready...")
    }

    private fun enterArmedState() {
        isArmed = true
        engine?.setFlash(true)
        binding.armButton.text = getString(R.string.unarm)
        binding.armButton.isEnabled = true
        binding.captureButton.isEnabled = false
        binding.moreButton.isEnabled = true
        updateStatus("Armed — waiting for trigger...")
    }

    // ── Arm / Unarm ─────────────────────────────────────────────

    private fun onArmPressed() {
        if (isArmed) {
            // ── UNARM ──
            isArmed = false
            engine?.setFlash(false)
            binding.armButton.text = getString(R.string.arm)

            if (useRemoteTrigger) {
                serialManager?.sendReset()
                closeSerial()
            }

            if (isRecordingReady) {
                binding.captureButton.isEnabled = true
            }
            updateStatus(if (isRecordingReady) "Ready — press CAPTURE" else "Waiting for ready...")
        } else {
            // ── ARM ──
            if (useRemoteTrigger) {
                enterArmingState()
                if (!openSerial()) {
                    exitArmingState()
                }
            } else {
                enterArmedState()
            }
        }
    }

    // ── Capture ─────────────────────────────────────────────────

    private fun onCapturePressed() {
        startCapture()
    }

    private fun startCapture() {
        if (!isRecordingReady || engine == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        recordedFromArmedMode = isArmed
        isRecordingReady = false
        captureStartMs = android.os.SystemClock.elapsedRealtime()

        if (!recordedFromArmedMode) {
            engine?.setFlash(true)
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(Date())
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "hispeed-trigger-cam"
        )
        dir.mkdirs()
        currentOutputFile = File(dir, "${timestamp}.mp4")

        Log.i(TAG, "Starting capture to ${currentOutputFile!!.absolutePath}")
        updateStatus("Capturing...")

        runOnUiThread {
            binding.captureButton.isEnabled = false
            binding.armButton.isEnabled = false
        }

        backgroundHandler?.post {
            engine?.startRecording(currentOutputFile!!)
        }
    }

    // ── HTTP Server ────────────────────────────────────────────

    private fun startCaptureServer() {
        if (captureServer != null) return
        try {
            val server = CaptureServer(applicationContext, 80)
            server.start()
            captureServer = server
            Log.i(TAG, "HTTP server started on port 80")
        } catch (e: Exception) {
            Log.w(TAG, "Port 80 failed (${e.message}), trying 8080")
            try {
                val server = CaptureServer(applicationContext, 8080)
                server.start()
                captureServer = server
                Log.i(TAG, "HTTP server started on port 8080")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start HTTP server: ${e2.message}", e2)
            }
        }
    }

    private fun stopCaptureServer() {
        captureServer?.stop()
        captureServer = null
        Log.i(TAG, "HTTP server stopped")
    }

    // ── Recording Metadata ─────────────────────────────────────

    private fun writeMetadataJson(mp4File: File) {
        try {
            val sha256 = MessageDigest.getInstance("SHA-256")
            mp4File.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    sha256.update(buffer, 0, read)
                }
            }
            val hash = sha256.digest().joinToString("") { "%02x".format(it) }

            // Frame layout: 30 ramp-up @ 30fps, SSM middle, 30 ramp-down @ 30fps
            // 1920fps: 960 middle frames, 960fps: 480 middle frames
            val middleFrames = if (use1920fps) 960 else 480
            val totalFrames = 30 + middleFrames + 30
            val frames = JSONArray().apply {
                put(JSONObject().put("count", 30).put("fps", 30))
                put(JSONObject().put("count", middleFrames).put("fps", videoFps))
                put(JSONObject().put("count", 30).put("fps", 30))
            }

            val json = JSONObject().apply {
                put("engine", engineType.label)
                put("fps", videoFps)
                put("width", videoWidth)
                put("height", videoHeight)
                put("resolution", "${videoWidth}x${videoHeight}")
                put("totalFrames", totalFrames)
                put("frames", frames)
                put("file", mp4File.name)
                put("size", mp4File.length())
                put("sha256", hash)
            }

            val jsonFile = File(mp4File.parent, mp4File.nameWithoutExtension + ".json")
            jsonFile.writeText(json.toString(2))
            Log.i(TAG, "Metadata written: ${jsonFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write metadata JSON: ${e.message}", e)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun configureTransform(previewSize: Size) {
        val viewWidth = binding.textureView.width.toFloat()
        val viewHeight = binding.textureView.height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f) return

        @Suppress("DEPRECATION")
        val displayRotation = windowManager.defaultDisplay.rotation
        Log.i(TAG, "configureTransform: view=${viewWidth}x${viewHeight}, buffer=${previewSize.width}x${previewSize.height}, displayRotation=$displayRotation")

        val matrix = Matrix()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f

        val bufW = previewSize.height.toFloat()
        val bufH = previewSize.width.toFloat()
        val fitScale = minOf(viewWidth / bufW, viewHeight / bufH)
        matrix.setScale(fitScale * bufW / viewWidth, fitScale * bufH / viewHeight, centerX, centerY)

        val rotateDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> -90f
            Surface.ROTATION_270 -> 90f
            Surface.ROTATION_180 -> 180f
            else -> 0f
        }
        if (rotateDegrees != 0f) {
            matrix.postRotate(rotateDegrees, centerX, centerY)
        }

        runOnUiThread { binding.textureView.setTransform(matrix) }
    }

    private fun elapsedMs(): Long =
        android.os.SystemClock.elapsedRealtime() - captureStartMs

    private fun updateStatus(message: String) {
        Log.d(TAG, "Status: $message")
        runOnUiThread { binding.statusText.text = message }
    }
}
