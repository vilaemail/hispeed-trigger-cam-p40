package com.hispeedtriggercam.p40

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.huawei.camera.camerakit.ActionStateCallback
import com.huawei.camera.camerakit.CameraKit
import com.huawei.camera.camerakit.Metadata
import com.huawei.camera.camerakit.Mode
import com.huawei.camera.camerakit.ModeStateCallback
import java.io.File

/**
 * SSM engine using the official Huawei CameraKit public SDK (camerakit-1.1.3.aar).
 * Extracted from the original committed MainActivity code.
 *
 * This delegates to the system HwCameraKit APK on the device.
 * Encoding bitrate is controlled by the system (stock 12 Mbps).
 */
class HuaweiSDKEngine(
    private val context: Context,
    private val backgroundHandler: Handler,
    private val listener: SSMEngine.Listener
) : SSMEngine {

    companion object {
        private const val TAG = "SSM_HwSDK"
        private const val MODE_TYPE_SSM = Mode.Type.SUPER_SLOW_MOTION
    }

    override val displayName = "Huawei CameraKit SDK"

    private var cameraKit: CameraKit? = null
    private var currentMode: Mode? = null
    private var cameraId: String? = null
    private var previewSurface: Surface? = null
    private var videoFps = 0
    private var videoWidth = 0
    private var videoHeight = 0

    private var callbackThread: HandlerThread? = null
    private var callbackHandler: Handler? = null

    // ── SSMEngine interface ─────────────────────────────────────

    override fun open(cameraId: String, previewSurface: Surface, fps: Int, width: Int, height: Int) {
        this.cameraId = cameraId
        this.previewSurface = previewSurface
        this.videoFps = fps
        this.videoWidth = width
        this.videoHeight = height

        callbackThread = HandlerThread("HwSDK-Callback").also { it.start() }
        callbackHandler = Handler(callbackThread!!.looper)

        Log.i(TAG, "=====================================================")
        Log.i(TAG, "  Huawei CameraKit SDK engine")
        Log.i(TAG, "  camera=$cameraId  ${width}x${height} @ ${fps}fps")
        Log.i(TAG, "=====================================================")

        cameraKit = CameraKit.getInstance(context)
        if (cameraKit == null) {
            Log.e(TAG, "CameraKit.getInstance() returned null")
            listener.onError("open", "CameraKit not supported on this device")
            return
        }

        val cameraIds = cameraKit!!.cameraIdList
        if (cameraIds.isNullOrEmpty()) {
            Log.e(TAG, "No cameras found")
            listener.onError("open", "No cameras found")
            return
        }
        this.cameraId = cameraIds[0]
        Log.i(TAG, "Using camera: ${this.cameraId}")

        val supportedModes = cameraKit!!.getSupportedModes(this.cameraId)
        if (supportedModes == null || !supportedModes.contains(MODE_TYPE_SSM)) {
            Log.e(TAG, "SSM not in supported modes: ${supportedModes?.toList()}")
            listener.onError("open", "Super slow-mo not supported (modes: ${supportedModes?.toList()})")
            return
        }

        Log.i(TAG, "Creating SSM mode...")
        cameraKit!!.createMode(this.cameraId!!, MODE_TYPE_SSM, modeStateCallback, backgroundHandler)
    }

    override fun startRecording(outputFile: File) {
        val mode = currentMode
        if (mode == null) {
            listener.onError("startRecording", "Mode not ready")
            return
        }
        Log.i(TAG, "startRecording: ${outputFile.name}")
        try {
            mode.startRecording(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed: ${e.message}", e)
            listener.onError("startRecording", e.message ?: "unknown")
        }
    }

    override fun setFlash(on: Boolean) {
        try {
            val mode = currentMode ?: return
            val rc = mode.setFlashMode(
                if (on) Metadata.FlashMode.HW_FLASH_ALWAYS_OPEN.toInt()
                else Metadata.FlashMode.HW_FLASH_CLOSE.toInt()
            )
            Log.i(TAG, "Flash ${if (on) "ON" else "OFF"} rc=$rc")
        } catch (e: Exception) {
            Log.w(TAG, "CameraKit flash failed: ${e.message}")
        }
        if (!on) {
            try {
                val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                camManager.setTorchMode(cameraId ?: "0", false)
            } catch (e: Exception) {
                Log.w(TAG, "Torch off fallback failed: ${e.message}")
            }
        }
    }

    override fun setFocus(focusRect: Rect) {
        val mode = currentMode ?: return
        try {
            val rc = mode.setFocus(Metadata.FocusMode.HW_AF_TOUCH_AUTO.toInt(), focusRect)
            Log.i(TAG, "Focus rect=$focusRect rc=$rc")
        } catch (e: Exception) {
            Log.w(TAG, "Focus failed: ${e.message}")
        }
    }

    override fun release() {
        Log.i(TAG, "release()")
        try { currentMode?.release() } catch (e: Exception) { Log.w(TAG, "Mode release: ${e.message}") }
        currentMode = null
        cameraKit = null
        callbackThread?.quitSafely()
        try { callbackThread?.join() } catch (_: InterruptedException) {}
        callbackThread = null
        callbackHandler = null
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
            listener.onError("createMode", "Mode creation failed ($errorCode/$errorCode2)")
        }

        override fun onConfigured(mode: Mode) {
            Log.i(TAG, "Mode configured - starting preview")
            currentMode?.startPreview()
            listener.onPreviewStarted()
        }

        override fun onConfigureFailed(mode: Mode, errorCode: Int) {
            Log.e(TAG, "Mode configuration failed: $errorCode")
            listener.onError("configure", "Configuration failed ($errorCode)")
        }

        override fun onFatalError(mode: Mode, errorCode: Int) {
            Log.e(TAG, "Fatal camera error: $errorCode")
            listener.onError("fatal", "Fatal error ($errorCode)")
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
            val chars = kit.getModeCharacteristics(camId, MODE_TYPE_SSM)
            val fpsKey = if (videoFps == 1920) Metadata.FpsRange.HW_FPS_1920 else Metadata.FpsRange.HW_FPS_960

            val videoSizeMap = chars.getSupportedVideoSizes(android.media.MediaRecorder::class.java)
            val previewSizes = chars.getSupportedPreviewSizes(SurfaceTexture::class.java)
            Log.i(TAG, "Supported video FPS keys: ${videoSizeMap?.keys}, preview sizes: $previewSizes")

            if (videoSizeMap == null || !videoSizeMap.containsKey(fpsKey)) {
                Log.e(TAG, "Required FPS $fpsKey not supported. Available: ${videoSizeMap?.keys}")
                listener.onError("configure", "${videoFps}fps not supported")
                return
            }

            val sizes = videoSizeMap[fpsKey]!!
            val hasVideoSize = sizes.any { it.width == videoWidth && it.height == videoHeight }
            if (!hasVideoSize) {
                Log.e(TAG, "Required video size ${videoWidth}x${videoHeight} not supported")
                listener.onError("configure", "${videoWidth}x${videoHeight} not supported")
                return
            }

            // Debug dump
            for ((fps, szList) in videoSizeMap) {
                Log.i(TAG, "DEBUG videoSizes[$fps] = ${szList.map { "${it.width}x${it.height}" }}")
            }

            val videoSize = Size(videoWidth, videoHeight)
            Log.i(TAG, "Using video=${videoSize.width}x${videoSize.height} @ ${videoFps}fps")

            val surface = previewSurface ?: return
            val handler = callbackHandler ?: return

            val configBuilder = mode.modeConfigBuilder
            configBuilder.setStateCallback(actionStateCallback, handler)
            configBuilder.setVideoFps(fpsKey)
            configBuilder.addVideoSize(videoSize)
            configBuilder.addPreviewSurface(surface)

            mode.configure()
            Log.i(TAG, "Mode.configure() called")

        } catch (e: Exception) {
            Log.e(TAG, "Configuration error: ${e.message}", e)
            listener.onError("configure", e.message ?: "unknown")
        }
    }

    // ── Action State Callback ───────────────────────────────────

    private val actionStateCallback = object : ActionStateCallback() {
        override fun onFocus(mode: Mode, state: Int, result: ActionStateCallback.FocusResult?) {
            Log.i(TAG, "onFocus state=$state")
        }

        override fun onRecording(mode: Mode, state: Int, result: ActionStateCallback.RecordingResult?) {
            Log.i(TAG, "onRecording state=$state")
            when (state) {
                ActionStateCallback.RecordingResult.State.RECORDING_READY ->
                    listener.onRecordingReady()
                ActionStateCallback.RecordingResult.State.RECORDING_STARTED ->
                    listener.onRecordingStarted()
                ActionStateCallback.RecordingResult.State.RECORDING_STOPPED ->
                    listener.onRecordingStopped()
                ActionStateCallback.RecordingResult.State.RECORDING_COMPLETED ->
                    listener.onRecordingFinished()
                ActionStateCallback.RecordingResult.State.RECORDING_FILE_SAVED ->
                    listener.onRecordingSaved(File("")) // file path not provided by callback
                ActionStateCallback.RecordingResult.State.ERROR_RECORDING_NOT_READY ->
                    listener.onError("recording", "Not ready")
                ActionStateCallback.RecordingResult.State.ERROR_FILE_IO ->
                    listener.onError("recording", "File I/O error")
                ActionStateCallback.RecordingResult.State.ERROR_UNKNOWN ->
                    listener.onError("recording", "Unknown recording error")
            }
        }
    }
}
