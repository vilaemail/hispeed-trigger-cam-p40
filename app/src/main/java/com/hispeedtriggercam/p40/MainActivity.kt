package com.hispeedtriggercam.p40

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hispeedtriggercam.p40.databinding.ActivityMainBinding
import com.huawei.camera.camerakit.ActionStateCallback
import com.huawei.camera.camerakit.CameraKit
import com.huawei.camera.camerakit.Metadata
import com.huawei.camera.camerakit.Mode
import com.huawei.camera.camerakit.ModeStateCallback
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HiSpeedTriggerCam"
        private const val CURRENT_MODE_TYPE = Mode.Type.SUPER_SLOW_MOTION
        private const val PREFS_NAME = "hst_camera_prefs"
        private const val PREF_SERVER_ENABLED = "http_server_enabled"
        private const val PREF_USE_1920FPS = "use_1920fps"
    }

    private var use1920fps = true
    private val videoFps get() = if (use1920fps) Metadata.FpsRange.HW_FPS_1920 else Metadata.FpsRange.HW_FPS_960
    private val videoWidth get() = if (use1920fps) 1280 else 1920
    private val videoHeight get() = if (use1920fps) 720 else 1080
    private val fpsLabel get() = if (use1920fps) "1920" else "960"

    private lateinit var binding: ActivityMainBinding

    private var cameraKit: CameraKit? = null
    private var currentMode: Mode? = null
    private var cameraId: String? = null
    private var previewSurface: Surface? = null
    private var isRecordingReady = false
    private var isArmed = false
    private var recordedFromArmedMode = false
    private var currentOutputFile: File? = null
    private var captureStartMs = 0L

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var callbackThread: HandlerThread? = null
    private var callbackHandler: Handler? = null

    private var captureServer: CaptureServer? = null

    // ── Lifecycle ───────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        use1920fps = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PREF_USE_1920FPS, true)

        binding.captureButton.setOnClickListener { onCapturePressed() }
        binding.armButton.setOnClickListener { onArmPressed() }
        binding.moreButton.setOnClickListener { showMoreMenu() }
        binding.textureView.surfaceTextureListener = surfaceTextureListener
        setupTapToFocus()
        if (isServerEnabled()) startCaptureServer()
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

    // ── USB HID Key Handling ────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_R &&
            isArmed && isRecordingReady
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
        callbackThread = HandlerThread("CameraCallback").also { it.start() }
        callbackHandler = Handler(callbackThread!!.looper)
    }

    private fun stopBackgroundThreads() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null

        callbackThread?.quitSafely()
        try { callbackThread?.join() } catch (_: InterruptedException) {}
        callbackThread = null
        callbackHandler = null
    }

    private fun releaseCamera() {
        try {
            currentMode?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing mode: ${e.message}")
        }
        currentMode = null
        isRecordingReady = false
        isArmed = false
        previewSurface?.release()
        previewSurface = null
        runOnUiThread {
            binding.armButton.text = getString(R.string.arm)
            binding.armButton.isEnabled = false
            binding.captureButton.isEnabled = false
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

    // ── CameraKit Mode Creation ─────────────────────────────────

    private fun createMode() {
        if (currentMode != null) return

        cameraKit = CameraKit.getInstance(applicationContext)
        if (cameraKit == null) {
            Log.e(TAG, "CameraKit.getInstance() returned null - not supported on this device")
            updateStatus("ERROR: CameraKit not supported")
            return
        }

        val cameraIds = cameraKit!!.cameraIdList
        if (cameraIds.isNullOrEmpty()) {
            Log.e(TAG, "No cameras found")
            updateStatus("ERROR: No cameras")
            return
        }
        cameraId = cameraIds[0]
        Log.i(TAG, "Using camera: $cameraId")

        val supportedModes = cameraKit!!.getSupportedModes(cameraId)
        if (supportedModes == null || !supportedModes.contains(CURRENT_MODE_TYPE)) {
            Log.e(TAG, "SUPER_SLOW_MOTION not in supported modes: ${supportedModes?.toList()}")
            updateStatus("ERROR: Super slow-mo not supported")
            return
        }

        updateStatus("Creating super slow-mo mode...")
        val handler = backgroundHandler ?: return
        cameraKit!!.createMode(cameraId!!, CURRENT_MODE_TYPE, modeStateCallback, handler)
    }

    // ── Mode State Callback ─────────────────────────────────────

    private val modeStateCallback = object : ModeStateCallback() {
        override fun onCreated(mode: Mode) {
            Log.i(TAG, "Mode created")
            currentMode = mode
            configureMode()
        }

        override fun onCreateFailed(cameraId: String, errorCode: Int, errorCode2: Int) {
            Log.e(TAG, "Mode creation failed: camera=$cameraId error=$errorCode/$errorCode2")
            updateStatus("ERROR: Mode creation failed ($errorCode)")
        }

        override fun onConfigured(mode: Mode) {
            Log.i(TAG, "Mode configured - starting preview")
            currentMode?.startPreview()
            updateStatus("Preview active - waiting for ready...")
        }

        override fun onConfigureFailed(mode: Mode, errorCode: Int) {
            Log.e(TAG, "Mode configuration failed: $errorCode — retrying in 3s")
            updateStatus("Configuration failed ($errorCode) — retrying...")
            backgroundHandler?.postDelayed({
                Log.i(TAG, "Retrying after configuration failure — full recreate")
                releaseCamera()
                createMode()
            }, 3000)
        }

        override fun onFatalError(mode: Mode, errorCode: Int) {
            Log.e(TAG, "Fatal camera error: $errorCode")
            updateStatus("ERROR: Fatal ($errorCode)")
            releaseCamera()
        }

        override fun onReleased(mode: Mode) {
            Log.i(TAG, "Mode released")
        }
    }

    // ── Mode Configuration ──────────────────────────────────────

    private fun configureMode() {
        val mode = currentMode ?: return
        val kit = cameraKit ?: return
        val camId = cameraId ?: return

        try {
            val chars = kit.getModeCharacteristics(camId, CURRENT_MODE_TYPE)

            val videoSizeMap = chars.getSupportedVideoSizes(android.media.MediaRecorder::class.java)
            val previewSizes = chars.getSupportedPreviewSizes(SurfaceTexture::class.java)
            Log.i(TAG, "Supported video FPS keys: ${videoSizeMap?.keys}, preview sizes: $previewSizes")

            if (videoSizeMap == null || !videoSizeMap.containsKey(videoFps)) {
                Log.e(TAG, "Required FPS $videoFps not supported. Available: ${videoSizeMap?.keys}")
                updateStatus("ERROR: ${fpsLabel}fps not supported")
                return
            }

            val sizes = videoSizeMap[videoFps]!!
            val hasVideoSize = sizes.any { it.width == videoWidth && it.height == videoHeight }
            if (!hasVideoSize) {
                Log.e(TAG, "Required video size ${videoWidth}x${videoHeight} not supported. Available: $sizes")
                updateStatus("ERROR: ${videoWidth}x${videoHeight} not supported")
                return
            }

            // ── Debug dump: all FPS → sizes ──
            for ((fps, szList) in videoSizeMap) {
                Log.i(TAG, "DEBUG videoSizes[$fps] = ${szList.map { "${it.width}x${it.height}" }}")
            }
            Log.i(TAG, "DEBUG previewSizes = ${previewSizes?.map { "${it.width}x${it.height}" }}")

            // ── Debug dump: supported CaptureRequest parameters ──
            val supportedParams = chars.getSupportedParameters()
            if (supportedParams.isNullOrEmpty()) {
                Log.i(TAG, "DEBUG supportedParameters: NONE")
            } else {
                for (key in supportedParams) {
                    Log.i(TAG, "DEBUG supportedParam: ${key.name}")
                }
            }

            // ── Debug dump: supported flash, focus, color, etc. ──
            Log.i(TAG, "DEBUG supportedFlash: ${chars.getSupportedFlashMode()?.toList()}")
            Log.i(TAG, "DEBUG supportedAutoFocus: ${chars.getSupportedAutoFocus()?.toList()}")
            Log.i(TAG, "DEBUG supportedZoom: ${chars.getSupportedZoom()?.toList()}")
            Log.i(TAG, "DEBUG supportedColorMode: ${chars.getSupportedColorMode()?.toList()}")
            Log.i(TAG, "DEBUG supportedFaceDetection: ${chars.getSupportedFaceDetection()?.toList()}")
            Log.i(TAG, "DEBUG supportedSceneDetection: ${chars.getSupportedSceneDetection()}")
            Log.i(TAG, "DEBUG conflictActions: ${chars.getConflictActions()}")
            Log.i(TAG, "DEBUG isVideoSupported: ${chars.isVideoSupported}")
            Log.i(TAG, "DEBUG isCaptureSupported: ${chars.isCaptureSupported}")
            Log.i(TAG, "DEBUG isBurstSupported: ${chars.isBurstSupported}")

            // ── Debug dump: parameter ranges for known RequestKeys ──
            try {
                val requestKeys = listOf(
                    com.huawei.camera.camerakit.RequestKey.HW_SUPER_SLOW_CHECK_AREA,
                    com.huawei.camera.camerakit.RequestKey.HW_VIDEO_STABILIZATION,
                    com.huawei.camera.camerakit.RequestKey.HW_AI_MOVIE,
                    com.huawei.camera.camerakit.RequestKey.HW_SENSOR_HDR,
                    com.huawei.camera.camerakit.RequestKey.HW_FILTER_EFFECT,
                    com.huawei.camera.camerakit.RequestKey.HW_SCENE_EFFECT_ENABLE,
                    com.huawei.camera.camerakit.RequestKey.HW_PRO_SENSOR_ISO_VALUE,
                    com.huawei.camera.camerakit.RequestKey.HW_PRO_SENSOR_EXPOSURE_TIME_VALUE,
                    com.huawei.camera.camerakit.RequestKey.HW_EXPOSURE_COMPENSATION_VALUE,
                )
                for (key in requestKeys) {
                    try {
                        val range = chars.getParameterRange(key)
                        Log.i(TAG, "DEBUG paramRange[${key.name}] = $range")
                    } catch (e: Exception) {
                        Log.i(TAG, "DEBUG paramRange[${key.name}] = ERROR: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DEBUG RequestKey dump failed: ${e.message}")
            }

            val videoSize = Size(videoWidth, videoHeight)
            val previewSize = Size(videoWidth, videoHeight)
            Log.i(TAG, "Using video=${videoSize.width}x${videoSize.height} preview=${previewSize.width}x${previewSize.height} @ ${fpsLabel}fps")
            updateStatus("Configuring ${videoSize.width}x${videoSize.height} @ ${fpsLabel}fps...")

            val surfaceTexture = binding.textureView.surfaceTexture
            if (surfaceTexture == null) {
                Log.e(TAG, "SurfaceTexture is null")
                updateStatus("ERROR: No surface")
                return
            }
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            configureTransform(previewSize)
            previewSurface = Surface(surfaceTexture)

            val configBuilder = mode.modeConfigBuilder
            val handler = callbackHandler ?: return
            val surface = previewSurface ?: return
            configBuilder.setStateCallback(actionStateCallback, handler)
            configBuilder.setVideoFps(videoFps)
            configBuilder.addVideoSize(videoSize)
            configBuilder.addPreviewSurface(surface)

            mode.configure()
            Log.i(TAG, "Mode.configure() called")

        } catch (e: Exception) {
            Log.e(TAG, "Configuration error: ${e.message}", e)
            updateStatus("ERROR: ${e.message}")
        }
    }

    // ── Action State Callback (Recording Lifecycle) ─────────────

    private val actionStateCallback = object : ActionStateCallback() {
        override fun onFocus(
            mode: Mode,
            state: Int,
            result: ActionStateCallback.FocusResult?
        ) {
            Log.i(TAG, "onFocus state=$state result=$result")
            when (state) {
                FocusResult.State.FOCUS_SUCCEED -> Log.i(TAG, "Focus: SUCCEED")
                FocusResult.State.FOCUS_FAILED -> Log.i(TAG, "Focus: FAILED")
                FocusResult.State.FOCUS_MOVING -> Log.i(TAG, "Focus: MOVING")
                FocusResult.State.FOCUS_LOCKED -> Log.i(TAG, "Focus: LOCKED")
                FocusResult.State.FOCUS_MODE_CHANGED -> Log.i(TAG, "Focus: MODE_CHANGED")
                FocusResult.State.ERROR_UNKNOWN -> Log.i(TAG, "Focus: ERROR_UNKNOWN")
                else -> Log.i(TAG, "Focus: unknown state=$state")
            }
        }

        override fun onRecording(
            mode: Mode,
            state: Int,
            result: ActionStateCallback.RecordingResult?
        ) {
            Log.i(TAG, "onRecording state=$state result=$result")

            when (state) {
                ActionStateCallback.RecordingResult.State.RECORDING_READY -> {
                    Log.i(TAG, "RECORDING_READY +${elapsedMs()}ms")
                    if (!recordedFromArmedMode) {
                        setFlash(false)
                    }
                    isRecordingReady = true
                    runOnUiThread {
                        binding.moreButton.isEnabled = true
                        if (isArmed) {
                            binding.captureButton.isEnabled = false
                            binding.armButton.isEnabled = true
                            updateStatus("Armed - waiting for trigger... +${elapsedMs()}ms")
                        } else {
                            binding.captureButton.isEnabled = true
                            binding.armButton.isEnabled = true
                            updateStatus("Ready - press CAPTURE +${elapsedMs()}ms")
                        }
                    }
                }

                ActionStateCallback.RecordingResult.State.RECORDING_STARTED -> {
                    Log.i(TAG, "RECORDING_STARTED +${elapsedMs()}ms")
                    updateStatus("Recording... +${elapsedMs()}ms")
                    runOnUiThread {
                        binding.captureButton.isEnabled = false
                        binding.armButton.isEnabled = false
                        binding.moreButton.isEnabled = false
                    }
                }

                ActionStateCallback.RecordingResult.State.RECORDING_STOPPED -> {
                    Log.i(TAG, "RECORDING_STOPPED +${elapsedMs()}ms")
                    updateStatus("Processing... +${elapsedMs()}ms")
                }

                ActionStateCallback.RecordingResult.State.RECORDING_COMPLETED -> {
                    Log.i(TAG, "RECORDING_COMPLETED +${elapsedMs()}ms")
                    updateStatus("Saving... +${elapsedMs()}ms")
                }

                ActionStateCallback.RecordingResult.State.RECORDING_FILE_SAVED -> {
                    val file = currentOutputFile
                    Log.i(TAG, "RECORDING_FILE_SAVED +${elapsedMs()}ms: ${file?.absolutePath}")
                    updateStatus("Saved: ${file?.name ?: "unknown"} +${elapsedMs()}ms")
                }

                ActionStateCallback.RecordingResult.State.ERROR_RECORDING_NOT_READY -> {
                    Log.w(TAG, "ERROR_RECORDING_NOT_READY")
                    updateStatus("Not ready yet...")
                }

                ActionStateCallback.RecordingResult.State.ERROR_FILE_IO -> {
                    Log.e(TAG, "ERROR_FILE_IO")
                    updateStatus("ERROR: File I/O error")
                    isRecordingReady = true
                    runOnUiThread {
                        if (isArmed) {
                            binding.armButton.isEnabled = true
                        } else {
                            binding.captureButton.isEnabled = true
                            binding.armButton.isEnabled = true
                        }
                    }
                }

                ActionStateCallback.RecordingResult.State.ERROR_UNKNOWN -> {
                    Log.e(TAG, "ERROR_UNKNOWN")
                    updateStatus("ERROR: Unknown recording error")
                    isRecordingReady = true
                    runOnUiThread {
                        if (isArmed) {
                            binding.armButton.isEnabled = true
                        } else {
                            binding.captureButton.isEnabled = true
                            binding.armButton.isEnabled = true
                        }
                    }
                }

                else -> {
                    Log.d(TAG, "Unknown recording state: $state")
                }
            }
        }
    }

    // ── Tap to Focus ────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        binding.textureView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP && currentMode != null) {
                val viewW = v.width.toFloat()
                val viewH = v.height.toFloat()
                // Sensor is landscape, phone is portrait (90° rotation)
                // Touch X (left→right) maps to sensor Y (-1000→1000)
                // Touch Y (top→bottom) maps to sensor X (1000→-1000, inverted)
                val sensorX = (1000 - (event.y / viewH) * 2000).toInt()
                val sensorY = ((event.x / viewW) * 2000 - 1000).toInt()
                val halfSide = 100
                val rect = Rect(
                    (sensorX - halfSide).coerceIn(-1000, 1000),
                    (sensorY - halfSide).coerceIn(-1000, 1000),
                    (sensorX + halfSide).coerceIn(-1000, 1000),
                    (sensorY + halfSide).coerceIn(-1000, 1000)
                )
                try {
                    val rc = currentMode!!.setFocus(
                        Metadata.FocusMode.HW_AF_TOUCH_AUTO.toInt(), rect
                    )
                    Log.i(TAG, "Tap-to-focus touch=(${event.x.toInt()},${event.y.toInt()}) sensor=($sensorX,$sensorY) rect=$rect rc=$rc")
                } catch (e: Exception) {
                    Log.w(TAG, "Tap-to-focus failed: ${e.message}")
                }
            }
            true
        }
    }

    // ── Arm / Unarm ─────────────────────────────────────────────

    private fun onArmPressed() {
        if (isArmed) {
            // Unarm
            isArmed = false
            setFlash(false)
            binding.armButton.text = getString(R.string.arm)
            if (isRecordingReady) {
                binding.captureButton.isEnabled = true
            }
            updateStatus(if (isRecordingReady) "Ready - press CAPTURE" else "Waiting for ready...")
        } else {
            // Arm
            isArmed = true
            setFlash(true)
            binding.armButton.text = getString(R.string.unarm)
            binding.captureButton.isEnabled = false
            updateStatus("Armed - waiting for trigger...")
        }
    }

    // ── More Menu ────────────────────────────────────────────

    private fun showMoreMenu() {
        val popup = PopupMenu(this, binding.moreButton)
        val fpsText = if (use1920fps) getString(R.string.fps_960) else getString(R.string.fps_1920)
        val serverOn = captureServer != null
        popup.menu.add(0, 1, 0, "Switch to $fpsText")
        popup.menu.add(0, 3, 1, if (serverOn) "HTTP Server: ON" else "HTTP Server: OFF")
        if (serverOn) popup.menu.add(0, 4, 2, "HTTP Server Info")
        popup.menu.add(0, 2, 3, "About")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { toggleFps(); true }
                2 -> { showAbout(); true }
                3 -> { toggleServer(); true }
                4 -> { showServerInfo(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun toggleFps() {
        if (!isRecordingReady && currentMode != null) {
            Toast.makeText(this, "Wait for camera to be ready", Toast.LENGTH_SHORT).show()
            return
        }
        use1920fps = !use1920fps
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(PREF_USE_1920FPS, use1920fps).apply()
        Log.i(TAG, "FPS toggled to $fpsLabel — recreating mode")
        releaseCamera()
        if (binding.textureView.isAvailable) {
            createMode()
        }
    }

    private fun showAbout() {
        val repoUrl = "https://github.com/vilaemail/hispeed-trigger-cam-p40"
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        AlertDialog.Builder(this)
            .setTitle("HST Camera v$version")
            .setMessage("High-speed trigger camera for Huawei P40.\n\n$repoUrl")
            .setPositiveButton("Open GitHub") { _, _ ->
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(repoUrl)))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ── Flash Control ───────────────────────────────────────────

    private fun setFlash(on: Boolean) {
        try {
            val mode = currentMode ?: return
            val rc = mode.setFlashMode(
                if (on) Metadata.FlashMode.HW_FLASH_ALWAYS_OPEN.toInt()
                else Metadata.FlashMode.HW_FLASH_CLOSE.toInt()
            )
            Log.i(TAG, "Flash ${if (on) "ON" else "OFF"} (CameraKit rc=$rc)")
        } catch (e: Exception) {
            Log.w(TAG, "CameraKit flash failed: ${e.message}")
        }
        if (!on) {
            try {
                val camManager = getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                camManager.setTorchMode(cameraId ?: "0", false)
                Log.i(TAG, "Flash OFF (CameraManager fallback)")
            } catch (e: Exception) {
                Log.w(TAG, "CameraManager torch off failed: ${e.message}")
            }
        }
    }

    // ── Capture ─────────────────────────────────────────────────

    private fun onCapturePressed() {
        startCapture()
    }

    private fun startCapture() {
        if (!isRecordingReady || currentMode == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        recordedFromArmedMode = isArmed
        isRecordingReady = false
        captureStartMs = android.os.SystemClock.elapsedRealtime()

        if (!recordedFromArmedMode) {
            setFlash(true)
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(Date())
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "hispeed-trigger-cam"
        )
        dir.mkdirs()
        currentOutputFile = File(dir, "${timestamp}_${fpsLabel}fps.mp4")

        Log.i(TAG, "Starting capture to ${currentOutputFile!!.absolutePath}")
        updateStatus("Capturing...")

        runOnUiThread {
            binding.captureButton.isEnabled = false
            binding.armButton.isEnabled = false
        }

        try {
            currentMode!!.startRecording(currentOutputFile!!)
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed: ${e.message}", e)
            updateStatus("ERROR: ${e.message}")
            isRecordingReady = true
            runOnUiThread {
                if (isArmed) {
                    binding.armButton.isEnabled = true
                } else {
                    binding.captureButton.isEnabled = true
                    binding.armButton.isEnabled = true
                }
            }
        }
    }

    // ── HTTP Server ────────────────────────────────────────────

    private fun isServerEnabled(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(PREF_SERVER_ENABLED, false)
    }

    private fun setServerEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(PREF_SERVER_ENABLED, enabled).apply()
    }

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

    private fun toggleServer() {
        if (captureServer != null) {
            stopCaptureServer()
            setServerEnabled(false)
            Toast.makeText(this, "HTTP server stopped", Toast.LENGTH_SHORT).show()
        } else {
            startCaptureServer()
            setServerEnabled(true)
            if (captureServer != null) {
                Toast.makeText(this, "HTTP server started", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to start server", Toast.LENGTH_SHORT).show()
                setServerEnabled(false)
            }
        }
    }

    private fun showServerInfo() {
        val url = captureServer?.getServerUrl()
        if (url == null) {
            Toast.makeText(this, "Server not running or no WiFi", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("HTTP Server")
            .setMessage("Base URL: $url\n\nEndpoints:\n  GET /captures - list captures\n  GET /capture/<filename> - download a capture")
            .setPositiveButton("Close", null)
            .show()
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
