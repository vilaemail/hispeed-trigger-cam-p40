package com.hispeedtriggercam.p40

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.concurrent.Executor

/**
 * Camera2 SSM implementation matching the internal CameraKit SDK flow.
 *
 * Key insight from decompiled HwCameraKit.apk:
 *   - SSM uses a REGULAR capture session (NOT constrained high-speed)
 *   - Session parameters must include cameraSessionSceneMode=33 + hwCameraKitFlag=1
 *   - The ISP SSM mode is driven entirely by vendor tags, not session type
 *   - HAL writes captured frames to our PersistentInputSurface → our encoder
 *
 * Encoding: Uses MediaCodec directly (not MediaRecorder) so we can set
 * capture-rate and operating-rate on the encoder format. This bypasses
 * the setCaptureRate() limitation that blocks non-system apps.
 */
@SuppressLint("MissingPermission")
class Camera2SSMManager(
    private val context: Context,
    private val backgroundHandler: Handler,
    private val listener: Listener
) {

    // ═══════════════════════════════════════════════════════════════════
    //  PUBLIC CONFIGURATION — tweak these to change encoding quality
    // ═══════════════════════════════════════════════════════════════════

    var targetBitrate = 12_000_000          // Match stock exactly first, bump later
    var useHevc = false                      // true = H.265, false = H.264
    var outputFrameRate = 30                 // playback fps

    // ═══════════════════════════════════════════════════════════════════
    //  LISTENER
    // ═══════════════════════════════════════════════════════════════════

    interface Listener {
        fun onPreviewStarted()
        fun onRecordingReady()
        fun onRecordingStarted()
        fun onRecordingStopped()
        fun onRecordingFinished()
        fun onRecordingSaved(file: File)
        fun onError(phase: String, message: String)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  HUAWEI VENDOR TAG NAMES  (from decompiled CaptureRequestEx.java)
    // ═══════════════════════════════════════════════════════════════════

    companion object {
        const val TAG = "SSM_CAM2"

        // CaptureRequest keys (from decompiled CaptureRequestEx field mapping)
        const val TAG_SSM_MODE       = "com.huawei.capture.metadata.superSlowMotionMode"       // bc: Byte
        const val TAG_VIDEO_FPS      = "com.huawei.capture.metadata.hw-video-fps"               // ao: Int
        const val TAG_VIDEO_STATUS   = "com.huawei.capture.metadata.hwVideoStatus"              // ai: Byte
        const val TAG_SCENE_MODE     = "com.huawei.capture.metadata.cameraSceneMode"            // be: Int
        const val TAG_SESSION_SCENE  = "com.huawei.capture.metadata.cameraSessionSceneMode"     // bF: Int
        const val TAG_HW_CAM2_FLAG   = "com.huawei.capture.metadata.hwCamera2Flag"              // t:  Byte
        const val TAG_API_VERSION    = "com.huawei.capture.metadata.apiVersion"                  // ae: Int
        const val TAG_CAMERAKIT_FLAG = "com.huawei.capture.metadata.hwCameraKitFlag"            // bX: Byte
        const val TAG_DUAL_SENSOR    = "com.huawei.capture.metadata.dualSensorMode"             // F:  Byte
        const val TAG_EXT_SCENE      = "com.huawei.capture.metadata.extSceneMode"               // G:  Byte
        const val TAG_MONO_MODE      = "com.huawei.capture.metadata.monoMode"                   // af: Byte
        const val TAG_BURST_SNAP     = "com.huawei.capture.metadata.burstSnapshotMode"          // l:  Byte
        const val TAG_ALL_FOCUS      = "com.huawei.capture.metadata.allFocusMode"               // U:  Byte
        const val TAG_NICE_FOOD      = "com.huawei.capture.metadata.niceFoodMode"               // s:  Byte
        const val TAG_SENSOR_ISO     = "com.huawei.capture.metadata.sensorIso"                  // Z:  Int
        const val TAG_SENSOR_EXP     = "com.huawei.capture.metadata.sensorExposureTime"         // Y:  Int
        const val TAG_EXPOSURE_COMP  = "com.huawei.capture.metadata.exposureCompValue"          // R:  Float
        const val TAG_SENSOR_WB      = "com.huawei.capture.metadata.sensorWbValue"              // X:  Int
        const val TAG_WATERMARK      = "com.huawei.capture.metadata.dmWaterMarkMode"            // aq: Byte
        const val TAG_CHECK_AREA     = "com.huawei.capture.metadata.checkMovingPosition"        // bd: int[]

        // CaptureResult keys
        const val TAG_SSM_STATUS     = "com.huawei.capture.metadata.superSlowMotionStatus"      // C:  Byte

        // CameraCharacteristics keys
        const val TAG_SSM_SUPPORTED  = "com.huawei.device.capabilities.superSlowMotionSupported"
        const val TAG_SSM_CONFIGS    = "com.huawei.device.capabilities.superSlowMotionAvailableConfigurations"

        // SSM scene mode value (mode types 7 & 8 both map to scene 33)
        const val SCENE_MODE_SSM = 33

        // Auto-stop recording if HAL never signals finish
        const val RECORDING_TIMEOUT_MS = 5000L
    }

    // ═══════════════════════════════════════════════════════════════════
    //  INTERNAL STATE
    // ═══════════════════════════════════════════════════════════════════

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraId: String? = null
    private var characteristics: CameraCharacteristics? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null

    // Encoding pipeline: MediaCodec encoder + MediaMuxer (replaces MediaRecorder)
    private var encoder: MediaCodec? = null
    private var persistentSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var encoderOutputFormat: MediaFormat? = null
    private var encodedFrameCount = 0
    private var lastPtsUs = -1L

    private var previewSurface: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoFps = 0

    // Vendor keys discovered at runtime
    private val reqKeys  = mutableMapOf<String, CaptureRequest.Key<*>>()
    private val resKeys  = mutableMapOf<String, CaptureResult.Key<*>>()
    private val charKeys = mutableMapOf<String, CameraCharacteristics.Key<*>>()

    private var ssmBatchCount = 2
    private var currentSsmStatus: Byte = -1
    private var currentOutputFile: File? = null
    private var isRecording = false

    // ═══════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════════

    fun open(cameraId: String, preview: Surface, fps: Int, width: Int, height: Int) {
        this.cameraId = cameraId
        this.previewSurface = preview
        this.videoFps = fps
        this.videoWidth = width
        this.videoHeight = height

        Log.i(TAG, "=====================================================")
        Log.i(TAG, "  Camera2 SSM Direct (MediaCodec encoder)")
        Log.i(TAG, "  camera=$cameraId  ${width}x${height} @ ${fps}fps")
        Log.i(TAG, "  bitrate=${targetBitrate/1_000_000}Mbps  codec=${if (useHevc) "HEVC" else "H264"}")
        Log.i(TAG, "  capture-rate=$fps  operating-rate=$fps")
        Log.i(TAG, "  SESSION TYPE: REGULAR (not high-speed!)")
        Log.i(TAG, "=====================================================")

        probeVendorTags(cameraId)
        if (!setupEncoder()) return
        openCameraDevice(cameraId)
    }

    fun startRecording(outputFile: File) {
        if (isRecording) {
            Log.w(TAG, "startRecording: already recording, ignoring")
            return
        }
        currentOutputFile = outputFile
        encodedFrameCount = 0
        lastPtsUs = -1L
        Log.i(TAG, "--- START RECORDING ---")
        Log.i(TAG, "  file: ${outputFile.name}")
        Log.i(TAG, "  bitrate: ${targetBitrate / 1_000_000} Mbps")
        Log.i(TAG, "  codec: ${if (useHevc) "HEVC" else "H264"}")

        // Step 1: Create MediaMuxer for the output file
        if (!prepareMuxer(outputFile)) {
            listener.onError("startRecording", "Muxer creation failed")
            return
        }

        // Step 2: Send capture burst with recording trigger FIRST (stock order)
        sendRecordingTrigger(start = true)
        isRecording = true
        listener.onRecordingStarted()

        // Auto-stop after timeout if HAL never signals finish (status 3)
        backgroundHandler.postDelayed({
            if (isRecording) {
                Log.w(TAG, "Recording auto-stop after ${RECORDING_TIMEOUT_MS}ms (HAL never signaled finish)")
                stopRecording()
            }
        }, RECORDING_TIMEOUT_MS)
    }

    fun forceStopRecording() {
        Log.i(TAG, "forceStopRecording() called, isRecording=$isRecording")
        if (isRecording) {
            stopRecording()
        }
    }

    fun setFlash(on: Boolean) {
        Log.i(TAG, "setFlash($on)")
        try {
            cameraManager.setTorchMode(cameraId ?: "0", on)
        } catch (e: Exception) {
            Log.w(TAG, "setTorchMode failed: ${e.message}")
        }
    }

    fun setFocus(sensorX: Int, sensorY: Int) {
        val builder = previewBuilder ?: return
        val session = captureSession ?: return
        try {
            val rect = Rect(
                (sensorX - 100).coerceIn(-1000, 1000),
                (sensorY - 100).coerceIn(-1000, 1000),
                (sensorX + 100).coerceIn(-1000, 1000),
                (sensorY + 100).coerceIn(-1000, 1000)
            )
            val afRegion = android.hardware.camera2.params.MeteringRectangle(
                rect, android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX
            )
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(afRegion))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, backgroundHandler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            Log.i(TAG, "Focus triggered at sensor ($sensorX, $sensorY)")
        } catch (e: Exception) {
            Log.w(TAG, "setFocus failed: ${e.message}")
        }
    }

    fun release() {
        Log.i(TAG, "release()")
        isRecording = false
        currentSsmStatus = -1
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        // Stop and release muxer
        try {
            if (muxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
        } catch (_: Exception) {}
        muxer = null
        muxerStarted = false
        videoTrackIndex = -1
        // Stop and release encoder
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        encoderOutputFormat = null
        try { persistentSurface?.release() } catch (_: Exception) {}
        persistentSurface = null
        previewBuilder = null
        reqKeys.clear()
        resKeys.clear()
        charKeys.clear()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PHASE 1 — PROBE VENDOR TAGS
    // ═══════════════════════════════════════════════════════════════════

    private fun probeVendorTags(cameraId: String) {
        Log.i(TAG, "=== PHASE 1: VENDOR TAG PROBE ===")

        characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val chars = characteristics!!

        // Discover Huawei characteristic keys
        var hwCharCount = 0
        for (key in chars.keys) {
            if (key.name.contains("huawei", ignoreCase = true) ||
                key.name.contains("hisi", ignoreCase = true)) {
                hwCharCount++
                charKeys[key.name] = key
                try {
                    val value = chars.get(key)
                    val valueStr = when (value) {
                        is IntArray -> value.toList().toString()
                        is ByteArray -> value.toList().toString()
                        is LongArray -> value.toList().toString()
                        else -> value.toString()
                    }
                    Log.i(TAG, "  CHAR [HW] ${key.name} = $valueStr")
                } catch (e: Exception) {
                    Log.i(TAG, "  CHAR [HW] ${key.name} = <read error>")
                }
            }
        }
        Log.i(TAG, "Huawei characteristic keys: $hwCharCount / ${chars.keys.size}")

        // Try reading SSM support via reflection (hidden from enumerated keys)
        for ((keyName, keyType) in listOf(
            TAG_SSM_SUPPORTED to ByteArray::class.java,
            TAG_SSM_CONFIGS to IntArray::class.java
        )) {
            try {
                val ctor = CameraCharacteristics.Key::class.java.getDeclaredConstructor(
                    String::class.java, Class::class.java
                )
                ctor.isAccessible = true
                val key = ctor.newInstance(keyName, keyType)
                @Suppress("UNCHECKED_CAST")
                val value = chars.get(key as CameraCharacteristics.Key<Any>)
                if (value != null) {
                    val valueStr = when (value) {
                        is IntArray -> value.toList().toString()
                        is ByteArray -> value.toList().toString()
                        else -> value.toString()
                    }
                    Log.i(TAG, "SSM CHAR (reflection): $keyName = $valueStr")

                    // Parse SSM configs to extract batch count
                    if (keyName == TAG_SSM_CONFIGS && value is IntArray && value.size >= 5) {
                        var ci = 0
                        while (ci + 4 < value.size) {
                            val w = value[ci]; val h = value[ci+1]
                            val fps1 = value[ci+2]; val fps2 = value[ci+3]
                            val batch = value[ci+4]
                            Log.i(TAG, "  SSM config: ${w}x${h} @ ${fps1}/${fps2} fps, batch=$batch")
                            if (fps1 == videoFps || fps2 == videoFps) {
                                ssmBatchCount = batch
                                Log.i(TAG, "  -> Matched! batchCount=$ssmBatchCount for ${videoFps}fps")
                            }
                            ci += 5
                        }
                    }

                    // Parse SSM supported
                    if (keyName == TAG_SSM_SUPPORTED && value is ByteArray && value.isNotEmpty()) {
                        Log.i(TAG, "SSM SUPPORTED: ${value[0]} (1=type1, 2=type2/P40)")
                    }
                } else {
                    Log.w(TAG, "SSM CHAR (reflection): $keyName = null")
                }
            } catch (e: Exception) {
                Log.w(TAG, "SSM CHAR (reflection) $keyName FAILED: ${e.message}")
            }
        }

        if (ssmBatchCount <= 2) {
            Log.w(TAG, "batchCount=$ssmBatchCount (default/low) — SSM configs not readable")
        }

        // Discover Huawei request keys
        var hwReqCount = 0
        for (key in chars.availableCaptureRequestKeys) {
            if (key.name.contains("huawei", ignoreCase = true) ||
                key.name.contains("hisi", ignoreCase = true)) {
                hwReqCount++
                reqKeys[key.name] = key
            }
        }
        Log.i(TAG, "Huawei request keys: $hwReqCount / ${chars.availableCaptureRequestKeys.size}")

        // Discover Huawei result keys
        var hwResCount = 0
        for (key in chars.availableCaptureResultKeys) {
            if (key.name.contains("huawei", ignoreCase = true) ||
                key.name.contains("hisi", ignoreCase = true)) {
                hwResCount++
                resKeys[key.name] = key
            }
        }
        Log.i(TAG, "Huawei result keys: $hwResCount / ${chars.availableCaptureResultKeys.size}")

        // Create/replace all vendor keys with correctly-typed reflection keys
        // (enumerated keys may have wrong internal types)
        val vendorKeyTypes = mapOf(
            TAG_SSM_MODE       to Byte::class.javaPrimitiveType!!,
            TAG_VIDEO_FPS      to Int::class.javaPrimitiveType!!,
            TAG_VIDEO_STATUS   to Byte::class.javaPrimitiveType!!,
            TAG_SCENE_MODE     to Int::class.javaPrimitiveType!!,
            TAG_SESSION_SCENE  to Int::class.javaPrimitiveType!!,
            TAG_HW_CAM2_FLAG   to Byte::class.javaPrimitiveType!!,
            TAG_API_VERSION    to Int::class.javaPrimitiveType!!,
            TAG_CAMERAKIT_FLAG to Byte::class.javaPrimitiveType!!,
            TAG_DUAL_SENSOR    to Byte::class.javaPrimitiveType!!,
            TAG_EXT_SCENE      to Byte::class.javaPrimitiveType!!,
            TAG_MONO_MODE      to Byte::class.javaPrimitiveType!!,
            TAG_BURST_SNAP     to Byte::class.javaPrimitiveType!!,
            TAG_ALL_FOCUS      to Byte::class.javaPrimitiveType!!,
            TAG_NICE_FOOD      to Byte::class.javaPrimitiveType!!,
            TAG_SENSOR_ISO     to Int::class.javaPrimitiveType!!,
            TAG_SENSOR_EXP     to Int::class.javaPrimitiveType!!,
            TAG_EXPOSURE_COMP  to Float::class.javaPrimitiveType!!,
            TAG_SENSOR_WB      to Int::class.javaPrimitiveType!!,
            TAG_WATERMARK      to Byte::class.javaPrimitiveType!!
        )

        Log.i(TAG, "Creating correctly-typed reflection keys...")
        for ((name, type) in vendorKeyTypes) {
            createRequestKeyViaReflection(name, type)
        }
        // SSM status comes back as byte[] from the HAL, not Byte
        createResultKeyViaReflection(TAG_SSM_STATUS, ByteArray::class.java)

        // Log camera info
        Log.i(TAG, "Camera: facing=${chars.get(CameraCharacteristics.LENS_FACING)}, " +
                "orientation=${chars.get(CameraCharacteristics.SENSOR_ORIENTATION)}")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PHASE 2 — MEDIACODEC ENCODER SETUP
    //  Replaces MediaRecorder to bypass setCaptureRate() restriction.
    //  We set capture-rate and operating-rate directly on the codec format.
    // ═══════════════════════════════════════════════════════════════════

    private fun setupEncoder(): Boolean {
        Log.i(TAG, "=== PHASE 2: MEDIACODEC ENCODER SETUP ===")
        return try {
            // Create persistent surface (survives encoder reconfigurations)
            persistentSurface = MediaCodec.createPersistentInputSurface()
            Log.i(TAG, "PersistentInputSurface created, valid=${persistentSurface?.isValid}")

            val mimeType = if (useHevc) MediaFormat.MIMETYPE_VIDEO_HEVC
                           else MediaFormat.MIMETYPE_VIDEO_AVC

            // Try with capture-rate first, fall back without if codec rejects it
            for (withCaptureRate in listOf(true, false)) {
                try {
                    val format = MediaFormat.createVideoFormat(mimeType, videoWidth, videoHeight)
                    format.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                    format.setInteger(MediaFormat.KEY_FRAME_RATE, outputFrameRate)
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    format.setInteger(MediaFormat.KEY_PRIORITY, 0)  // realtime

                    if (withCaptureRate) {
                        // capture-rate tells the codec about the input frame rate
                        // This is the MediaCodec equivalent of MediaRecorder.setCaptureRate()
                        format.setFloat("capture-rate", videoFps.toFloat())
                    }

                    Log.i(TAG, "Encoder format (captureRate=$withCaptureRate): $format")

                    val codec = MediaCodec.createEncoderByType(mimeType)
                    Log.i(TAG, "Created encoder: ${codec.name}")

                    codec.setCallback(encoderCallback, backgroundHandler)
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec.setInputSurface(persistentSurface!!)
                    codec.start()

                    encoder = codec
                    Log.i(TAG, "Encoder started: ${codec.name} (captureRate=$withCaptureRate)")
                    Log.i(TAG, "  ${videoWidth}x${videoHeight} ${targetBitrate/1_000_000}Mbps " +
                            "${if(useHevc)"HEVC" else "H264"}")
                    return true
                } catch (e: Exception) {
                    if (withCaptureRate) {
                        Log.w(TAG, "Encoder start failed with capture-rate=$videoFps, retrying without: ${e.message}")
                        // Release the failed codec before retry
                        try { encoder?.release() } catch (_: Exception) {}
                        encoder = null
                    } else {
                        throw e  // No more fallbacks
                    }
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Encoder setup FAILED: ${e.message}", e)
            listener.onError("setupEncoder", e.message ?: "unknown")
            false
        }
    }

    /**
     * Creates a MediaMuxer for the output file.
     * The muxer is started when the encoder delivers the first output format.
     */
    private fun prepareMuxer(outputFile: File): Boolean {
        Log.i(TAG, "prepareMuxer: ${outputFile.name}")
        outputFile.parentFile?.mkdirs()
        return try {
            // Clean up any previous muxer
            try {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}

            val m = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            m.setOrientationHint(90)
            muxer = m
            muxerStarted = false
            videoTrackIndex = -1

            // If we already know the encoder output format (from a previous recording),
            // we can add the track and start immediately
            val fmt = encoderOutputFormat
            if (fmt != null) {
                videoTrackIndex = m.addTrack(fmt)
                m.start()
                muxerStarted = true
                Log.i(TAG, "Muxer started immediately (cached format, track=$videoTrackIndex)")
            } else {
                Log.i(TAG, "Muxer created — waiting for encoder format callback")
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "prepareMuxer FAILED: ${e.message}", e)
            listener.onError("prepareMuxer", e.message ?: "unknown")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ENCODER ASYNC CALLBACK
    //  Receives encoded output from MediaCodec and writes to MediaMuxer
    // ═══════════════════════════════════════════════════════════════════

    private val encoderCallback = object : MediaCodec.Callback() {

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Not used in surface input mode — frames come from PersistentInputSurface
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo
        ) {
            // Skip codec config buffers (SPS/PPS) — muxer gets these from format
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                codec.releaseOutputBuffer(index, false)
                return
            }

            val buf = codec.getOutputBuffer(index)
            if (buf != null && info.size > 0 && isRecording && muxerStarted && videoTrackIndex >= 0) {
                try {
                    muxer?.writeSampleData(videoTrackIndex, buf, info)
                    encodedFrameCount++

                    // Log timestamps to diagnose SSM frame delivery:
                    // Preview frames: ~33ms apart (30fps)
                    // SSM frames: ~0.5ms apart (1920fps) or ~1ms (960fps)
                    val deltaPts = if (lastPtsUs >= 0) info.presentationTimeUs - lastPtsUs else 0
                    lastPtsUs = info.presentationTimeUs

                    if (encodedFrameCount <= 10 || encodedFrameCount % 100 == 0) {
                        Log.d(TAG, "ENC frame #$encodedFrameCount pts=${info.presentationTimeUs}us " +
                                "delta=${deltaPts}us size=${info.size}B " +
                                "flags=${info.flags}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Muxer write failed: ${e.message}")
                }
            } else if (buf != null && info.size > 0) {
                // Frames arriving but not recording or muxer not ready
                if (isRecording && !muxerStarted) {
                    Log.w(TAG, "ENC frame dropped (muxer not started yet) pts=${info.presentationTimeUs}")
                }
            }

            codec.releaseOutputBuffer(index, false)

            // Handle end of stream
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                Log.i(TAG, "Encoder: END_OF_STREAM received")
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Encoder ERROR: ${e.message} diagnostic=${e.diagnosticInfo}")
            listener.onError("encoder", e.message ?: "codec error")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Encoder output format: $format")
            encoderOutputFormat = format

            // If muxer is waiting for the format, start it now
            if (muxer != null && !muxerStarted) {
                try {
                    videoTrackIndex = muxer!!.addTrack(format)
                    muxer!!.start()
                    muxerStarted = true
                    Log.i(TAG, "Muxer started (track=$videoTrackIndex)")
                } catch (e: Exception) {
                    Log.e(TAG, "Muxer start failed in format callback: ${e.message}")
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PHASE 3 — OPEN CAMERA & CREATE SESSION
    //  Key difference: REGULAR session with session parameters
    //  (CameraKit uses SessionConfiguration(SESSION_REGULAR) for SSM)
    // ═══════════════════════════════════════════════════════════════════

    private fun openCameraDevice(cameraId: String) {
        Log.i(TAG, "=== PHASE 3: CAMERA OPEN ===")
        try {
            cameraManager.openCamera(cameraId, cameraDeviceCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera FAILED: ${e.message}", e)
            listener.onError("openCamera", e.message ?: "unknown")
        }
    }

    private val cameraDeviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.i(TAG, "Camera OPENED: ${camera.id}")
            cameraDevice = camera
            createCaptureSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "Camera DISCONNECTED")
            camera.close()
            cameraDevice = null
            listener.onError("cameraDevice", "Camera disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            val errorName = when (error) {
                ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
                ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
                ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
                ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
                ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
                else -> "UNKNOWN($error)"
            }
            Log.e(TAG, "Camera ERROR: $errorName")
            camera.close()
            cameraDevice = null
            listener.onError("cameraDevice", "Camera error: $errorName")
        }
    }

    /**
     * Creates a REGULAR capture session with vendor tags as session parameters.
     *
     * From decompiled CameraController.a() (session creation):
     *   SessionConfiguration(b(), outputConfigs, executor, callback)
     *   b() returns 0 for SSM (SESSION_REGULAR)
     *   Session params: cameraSessionSceneMode=33, hwCameraKitFlag=1
     */
    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val preview = previewSurface ?: return
        val recordSurface = persistentSurface ?: return

        Log.i(TAG, "Creating REGULAR session with SSM session parameters")
        Log.i(TAG, "  preview: $preview")
        Log.i(TAG, "  record:  $recordSurface")

        try {
            // Build OutputConfigurations (matches CameraKit's OutputConfiguration(-1, surface))
            val outputConfigs = listOf(
                OutputConfiguration(preview),
                OutputConfiguration(recordSurface)
            )

            // Create session parameters request with SSM scene mode
            // (from CameraController.h() — sets bF=sessionSceneMode, bX=cameraKitFlag)
            val sessionParamsBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            setVendorInt(sessionParamsBuilder, TAG_SESSION_SCENE, SCENE_MODE_SSM)
            setVendorByte(sessionParamsBuilder, TAG_CAMERAKIT_FLAG, 1)
            Log.i(TAG, "  Session params: sessionSceneMode=$SCENE_MODE_SSM, cameraKitFlag=1")

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                Executor { cmd -> backgroundHandler.post(cmd) },
                sessionCallback
            )
            sessionConfig.sessionParameters = sessionParamsBuilder.build()

            camera.createCaptureSession(sessionConfig)
            Log.i(TAG, "  createCaptureSession(REGULAR) submitted")

        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession FAILED: ${e.message}", e)
            listener.onError("createSession", e.message ?: "unknown")
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            Log.i(TAG, "Session CONFIGURED (REGULAR with SSM params)")
            captureSession = session
            startPreview()
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            Log.e(TAG, "Session CONFIGURE FAILED")
            listener.onError("sessionConfig", "Regular session with SSM params failed")
        }

        override fun onClosed(session: CameraCaptureSession) {
            Log.i(TAG, "Session CLOSED")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PHASE 4 — PREVIEW WITH VENDOR TAGS
    //  Matches HisiSuperSlowMotionTags.enablePreview() exactly
    // ═══════════════════════════════════════════════════════════════════

    private fun startPreview() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val preview = previewSurface ?: return

        Log.i(TAG, "=== PHASE 4: START PREVIEW (SSM vendor tags) ===")

        try {
            // Template: VIDEO_RECORD (3) — from BaseMode.setPreviewTemplateType()
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

            // Only target preview surface (NOT recording surface)
            // Recording surface is in the session but only targeted during recording burst
            builder.addTarget(preview)

            // ─── Standard Camera2 settings (from HisiSuperSlowMotionTags.enablePreview) ───
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, 0)   // off
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, 1)    // on (OIS)
            builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, 2)        // full

            // Do NOT set CONTROL_AE_TARGET_FPS_RANGE for type-2 SSM (P40 Pro)
            // CameraKit only sets this for type-1 hardware; for type-2 it relies on
            // hw-video-fps vendor tag alone (from HisiSuperSlowMotionTags line 55-60)

            // ─── Huawei vendor tags (exact match of enablePreview()) ──────────
            Log.i(TAG, "Setting vendor tags:")
            setVendorByte(builder, TAG_HW_CAM2_FLAG, 1)        // t:  hwCamera2Flag
            setVendorInt(builder, TAG_API_VERSION, 2)           // ae: apiVersion
            setVendorByte(builder, TAG_DUAL_SENSOR, 0)          // F:  dualSensorMode
            setVendorByte(builder, TAG_EXT_SCENE, 0)            // G:  extSceneMode
            setVendorByte(builder, TAG_MONO_MODE, 0)            // af: monoMode
            setVendorByte(builder, TAG_BURST_SNAP, 0)           // l:  burstSnapshotMode
            setVendorByte(builder, TAG_ALL_FOCUS, 0)            // U:  allFocusMode
            setVendorByte(builder, TAG_NICE_FOOD, 0)            // s:  niceFoodMode
            setVendorInt(builder, TAG_SENSOR_ISO, 0)             // Z:  sensorIso
            setVendorInt(builder, TAG_SENSOR_EXP, 0)             // Y:  sensorExposureTime
            setVendorFloat(builder, TAG_EXPOSURE_COMP, 0f)       // R:  exposureCompValue
            setVendorInt(builder, TAG_SENSOR_WB, 0)              // X:  sensorWbValue
            setVendorByte(builder, TAG_WATERMARK, 0)             // aq: dmWaterMarkMode
            setVendorInt(builder, TAG_SCENE_MODE, SCENE_MODE_SSM)   // be: cameraSceneMode = 33
            setVendorInt(builder, TAG_SESSION_SCENE, SCENE_MODE_SSM) // bF: cameraSessionSceneMode = 33
            setVendorByte(builder, TAG_SSM_MODE, 1)              // bc: superSlowMotionMode = 1 (manual)
            setVendorInt(builder, TAG_VIDEO_FPS, videoFps)       // ao: hw-video-fps (960 or 1920)
            setVendorByte(builder, TAG_VIDEO_STATUS, 0)          // ai: hwVideoStatus = 0 (not recording)

            previewBuilder = builder

            // CameraKit SSM uses controller.b() → setRepeatingBurst with 1-element list
            // (SuperSlowPreviewAction.startPreview → controller.b(builder, callback, null))
            session.setRepeatingBurst(
                listOf(builder.build()),
                captureCallback,
                backgroundHandler
            )
            Log.i(TAG, "Repeating burst submitted (1-element, regular session)")

            listener.onPreviewStarted()

            // Enable recording immediately — don't gate on SSM status
            // (SSM status may stay at 0 if HAL doesn't recognize our app as CameraKit)
            // We still monitor it in captureCallback for diagnostics
            listener.onRecordingReady()

        } catch (e: Exception) {
            Log.e(TAG, "startPreview FAILED: ${e.message}", e)
            listener.onError("startPreview", e.message ?: "unknown")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CAPTURE CALLBACK — monitors SSM status from CaptureResult
    // ═══════════════════════════════════════════════════════════════════

    private var resultLogCount = 0

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val statusKey = resKeys[TAG_SSM_STATUS]
            var newStatus: Byte? = null

            if (statusKey != null) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val arr = result.get(statusKey as CaptureResult.Key<ByteArray>)
                    if (arr != null && arr.isNotEmpty()) {
                        newStatus = arr[0]
                    }
                } catch (e: Exception) {
                    if (resultLogCount < 5) {
                        Log.w(TAG, "Failed to read SSM status: ${e.message}")
                    }
                }
            }

            resultLogCount++
            if (resultLogCount % 30 == 1) {
                Log.d(TAG, "CaptureResult #$resultLogCount: ssmStatus=$newStatus " +
                        "af=${result.get(CaptureResult.CONTROL_AF_STATE)} " +
                        "ae=${result.get(CaptureResult.CONTROL_AE_STATE)} " +
                        "frame=${result.frameNumber}")

                if (resultLogCount <= 3) {
                    dumpHuaweiResults(result)
                }
            }

            if (newStatus != null && newStatus != currentSsmStatus) {
                onSsmStatusChanged(newStatus)
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            Log.e(TAG, "Capture FAILED: reason=${failure.reason} frame=${failure.frameNumber}")
        }
    }

    private fun dumpHuaweiResults(result: TotalCaptureResult) {
        Log.d(TAG, "--- Huawei result values ---")
        for ((name, key) in resKeys) {
            try {
                @Suppress("UNCHECKED_CAST")
                val value = result.get(key as CaptureResult.Key<Any>)
                if (value != null) {
                    val valueStr = when (value) {
                        is IntArray -> value.toList().toString()
                        is ByteArray -> value.toList().toString()
                        else -> value.toString()
                    }
                    Log.d(TAG, "  $name = $valueStr")
                }
            } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  SSM STATE MACHINE
    //  Status bytes (from decompiled SuperSlowMotionMode inner class a):
    //    0 = Disabled/Idle
    //    1 = READY (can start recording)
    //    2 = Video Done (HAL finished capturing burst)
    //    3 = Finish (stop MediaRecorder, save file)
    //    4 = Auto-trigger start
    // ═══════════════════════════════════════════════════════════════════

    private fun onSsmStatusChanged(newStatus: Byte) {
        val oldStatus = currentSsmStatus
        currentSsmStatus = newStatus
        Log.i(TAG, "SSM STATUS: $oldStatus -> $newStatus")

        when (newStatus.toInt()) {
            0 -> Log.i(TAG, "  STATUS 0: Disabled/Idle")
            1 -> {
                Log.i(TAG, "  STATUS 1: READY")
                listener.onRecordingReady()
            }
            2 -> {
                Log.i(TAG, "  STATUS 2: Video Done")
                listener.onRecordingStopped()
            }
            3 -> {
                Log.i(TAG, "  STATUS 3: Finish — stopping recording")
                stopRecording()
            }
            4 -> Log.i(TAG, "  STATUS 4: Auto-trigger start")
            else -> Log.i(TAG, "  STATUS $newStatus: Unknown")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  RECORDING TRIGGER & STOP
    //  Matches SuperSlowRecordAction.start()/stop() exactly
    // ═══════════════════════════════════════════════════════════════════

    private fun sendRecordingTrigger(start: Boolean) {
        val session = captureSession ?: return
        val builder = previewBuilder ?: return
        val preview = previewSurface ?: return
        val recordSurface = persistentSurface ?: return

        Log.i(TAG, "sendRecordingTrigger(start=$start) batchCount=$ssmBatchCount")

        try {
            if (start) {
                // SuperSlowRecordAction.start():
                //   builder = controller.e()  (re-use current repeating builder)
                //   builder.set(hwVideoStatus, 1)
                //   builder.addTarget(recordSurface)
                //   list.add(builder.build())              // req 1: preview + record
                //   builder.removeTarget(previewSurface)
                //   list.add(builder.build())              // req 2: record only
                //   add (batchCount - 2) more record-only requests
                //   controller.captureBurst(list)

                val requests = mutableListOf<CaptureRequest>()

                setVendorByte(builder, TAG_VIDEO_STATUS, 1)  // hwVideoStatus = START
                builder.addTarget(recordSurface)

                // Request 1: preview + record
                requests.add(builder.build())

                // Request 2: record only
                builder.removeTarget(preview)
                requests.add(builder.build())

                // Additional batch requests (record only)
                for (i in 0 until (ssmBatchCount - 2).coerceAtLeast(0)) {
                    requests.add(builder.build())
                }

                Log.i(TAG, "Submitting recording burst: ${requests.size} requests")
                session.captureBurst(requests, captureCallback, backgroundHandler)

                // Also switch the repeating request to target BOTH surfaces
                // so the recording surface stays active while the HAL processes
                // the SSM buffer dump (the burst completes instantly but the HAL
                // needs time to output frames)
                builder.addTarget(preview)  // now has: preview + record
                session.setRepeatingBurst(
                    listOf(builder.build()),
                    captureCallback,
                    backgroundHandler
                )
                Log.i(TAG, "Repeating request now targets BOTH surfaces (preview+record)")

            } else {
                // Stop: remove record surface, set video status 0, restore preview-only
                setVendorByte(builder, TAG_VIDEO_STATUS, 0)  // hwVideoStatus = STOP
                builder.removeTarget(recordSurface)
                // builder should now have only preview target

                session.captureBurst(
                    listOf(builder.build()),
                    captureCallback,
                    backgroundHandler
                )

                // Restart repeating preview (preview-only)
                session.setRepeatingBurst(
                    listOf(builder.build()),
                    captureCallback,
                    backgroundHandler
                )
                Log.i(TAG, "Recording stop sent, preview resumed (preview-only)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendRecordingTrigger FAILED: ${e.message}", e)
            listener.onError("recordTrigger", e.message ?: "unknown")
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        val frameCount = encodedFrameCount

        // Stop the HAL recording trigger
        sendRecordingTrigger(start = false)

        // Finalize muxer
        try {
            if (muxerStarted) {
                muxer?.stop()
                Log.i(TAG, "Muxer stopped ($frameCount frames written)")
            }
            muxer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Muxer stop/release FAILED: ${e.message}", e)
        }
        muxer = null
        muxerStarted = false
        videoTrackIndex = -1

        listener.onRecordingFinished()

        val file = currentOutputFile
        if (file != null && file.exists() && file.length() > 0) {
            Log.i(TAG, "Recording saved: ${file.name} (${file.length() / 1024} KB, $frameCount frames)")
            listener.onRecordingSaved(file)
        } else {
            Log.w(TAG, "Recording file missing or empty: ${file?.absolutePath}")
            listener.onError("stopRecording", "Output file missing or empty")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  VENDOR TAG HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun setVendorByte(builder: CaptureRequest.Builder, tagName: String, value: Int) {
        val key = reqKeys[tagName] ?: return
        try {
            @Suppress("UNCHECKED_CAST")
            builder.set(key as CaptureRequest.Key<Byte>, value.toByte())
        } catch (e: Exception) {
            try {
                @Suppress("UNCHECKED_CAST")
                builder.set(key as CaptureRequest.Key<ByteArray>, byteArrayOf(value.toByte()))
            } catch (e2: Exception) {
                Log.w(TAG, "  set $tagName=$value FAILED: ${e.message}")
            }
        }
    }

    private fun setVendorInt(builder: CaptureRequest.Builder, tagName: String, value: Int) {
        val key = reqKeys[tagName] ?: return
        try {
            @Suppress("UNCHECKED_CAST")
            builder.set(key as CaptureRequest.Key<Int>, value)
        } catch (e: Exception) {
            try {
                @Suppress("UNCHECKED_CAST")
                builder.set(key as CaptureRequest.Key<IntArray>, intArrayOf(value))
            } catch (e2: Exception) {
                Log.w(TAG, "  set $tagName=$value FAILED: ${e.message}")
            }
        }
    }

    private fun setVendorFloat(builder: CaptureRequest.Builder, tagName: String, value: Float) {
        val key = reqKeys[tagName] ?: return
        try {
            @Suppress("UNCHECKED_CAST")
            builder.set(key as CaptureRequest.Key<Float>, value)
        } catch (e: Exception) {
            try {
                @Suppress("UNCHECKED_CAST")
                builder.set(key as CaptureRequest.Key<FloatArray>, floatArrayOf(value))
            } catch (e2: Exception) {
                Log.w(TAG, "  set $tagName=$value FAILED: ${e.message}")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun createRequestKeyViaReflection(name: String, type: Class<*>): CaptureRequest.Key<*>? {
        return try {
            val ctor = CaptureRequest.Key::class.java.getDeclaredConstructor(
                String::class.java, Class::class.java
            )
            ctor.isAccessible = true
            val key = ctor.newInstance(name, type) as CaptureRequest.Key<*>
            reqKeys[name] = key
            key
        } catch (e: Exception) {
            Log.w(TAG, "Reflection key failed for $name: ${e.message}")
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun createResultKeyViaReflection(name: String, type: Class<*>): CaptureResult.Key<*>? {
        return try {
            val ctor = CaptureResult.Key::class.java.getDeclaredConstructor(
                String::class.java, Class::class.java
            )
            ctor.isAccessible = true
            val key = ctor.newInstance(name, type) as CaptureResult.Key<*>
            resKeys[name] = key
            key
        } catch (e: Exception) {
            Log.w(TAG, "Reflection result key failed for $name: ${e.message}")
            null
        }
    }
}
