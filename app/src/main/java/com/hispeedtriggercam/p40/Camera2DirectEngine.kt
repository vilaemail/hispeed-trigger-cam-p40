package com.hispeedtriggercam.p40

import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * SSM engine using direct Camera2 API with Huawei vendor tags.
 * Thin wrapper around Camera2SSMManager (which stays unchanged).
 *
 * Uses MediaCodec encoder directly — configurable bitrate and codec.
 */
class Camera2DirectEngine(
    private val context: Context,
    private val backgroundHandler: Handler,
    private val listener: SSMEngine.Listener
) : SSMEngine {

    companion object {
        private const val TAG = "SSM_Cam2"
    }

    override val displayName = "Camera2 Direct (vendor tags)"

    private var manager: Camera2SSMManager? = null

    // Encoding settings — expose for configuration from MainActivity
    var targetBitrate = 12_000_000
    var useHevc = false
    var outputFrameRate = 30

    // ── SSMEngine interface ─────────────────────────────────────

    override fun open(cameraId: String, previewSurface: Surface, fps: Int, width: Int, height: Int) {
        Log.i(TAG, "=====================================================")
        Log.i(TAG, "  Camera2 Direct engine (MediaCodec)")
        Log.i(TAG, "  camera=$cameraId  ${width}x${height} @ ${fps}fps")
        Log.i(TAG, "  bitrate=${targetBitrate / 1_000_000}Mbps  codec=${if (useHevc) "HEVC" else "H264"}")
        Log.i(TAG, "=====================================================")

        val mgr = Camera2SSMManager(context, backgroundHandler, managerListener)
        mgr.targetBitrate = targetBitrate
        mgr.useHevc = useHevc
        mgr.outputFrameRate = outputFrameRate
        manager = mgr

        mgr.open(cameraId, previewSurface, fps, width, height)
    }

    override fun startRecording(outputFile: File) {
        val mgr = manager
        if (mgr == null) {
            listener.onError("startRecording", "Manager not ready")
            return
        }
        mgr.startRecording(outputFile)
    }

    override fun setFlash(on: Boolean) {
        manager?.setFlash(on)
    }

    override fun setFocus(focusRect: android.graphics.Rect) {
        // TODO: convert center-coordinate Rect to Camera2 MeteringRectangle
        manager?.setFocus(focusRect.centerX(), focusRect.centerY())
    }

    override fun release() {
        Log.i(TAG, "release()")
        manager?.release()
        manager = null
    }

    // ── Adapter: Camera2SSMManager.Listener → SSMEngine.Listener ──

    private val managerListener = object : Camera2SSMManager.Listener {
        override fun onPreviewStarted() = listener.onPreviewStarted()
        override fun onRecordingReady() = listener.onRecordingReady()
        override fun onRecordingStarted() = listener.onRecordingStarted()
        override fun onRecordingStopped() = listener.onRecordingStopped()
        override fun onRecordingFinished() = listener.onRecordingFinished()
        override fun onRecordingSaved(file: File) = listener.onRecordingSaved(file)
        override fun onError(phase: String, message: String) = listener.onError(phase, message)
    }
}
