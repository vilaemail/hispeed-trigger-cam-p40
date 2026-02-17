package com.hispeedtriggercam.p40

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.File

// API interfaces — loaded by system classloader, type-compatible across classloaders
import com.huawei.camerakit.api.ModeInterface
import com.huawei.camerakit.api.ModeStateCallback as HwModeStateCallback
import com.huawei.camerakit.api.ActionStateCallback as HwActionStateCallback
import com.huawei.camerakit.api.ModeConfigInterface

/**
 * SSM engine using a patched HwCameraKit loaded via custom classloader.
 *
 * On Huawei devices, the system HwCameraKit.apk is loaded as a shared library
 * whose classloader is the parent of the app classloader. This means the system's
 * unpatched classes always shadow our patched JAR via parent-first delegation.
 *
 * Solution: We bundle the patched HwCameraKit as a dex asset and load it with a
 * ParentLastDexClassLoader that finds `impl.*` classes from our dex first.
 * ModeManager (in impl package) is accessed via reflection through this classloader.
 * API interfaces (ModeInterface, callbacks) remain loaded by the system classloader.
 */
class CustomSDKEngine(
    private val context: Context,
    private val backgroundHandler: Handler,
    private val listener: SSMEngine.Listener
) : SSMEngine {

    companion object {
        private const val TAG = "SSM_Custom"
        private const val SSM_MODE_TYPE = 7  // ModeInterface.Type.SUPER_SLOW_MOTION
        private const val DEX_ASSET = "hwcamerakit_patched.dex.jar"
    }

    override val displayName = "Patched HwCameraKit"

    private var patchedClassLoader: ParentLastDexClassLoader? = null
    private var modeManagerObj: Any? = null       // ModeManager instance (from patched CL)
    private var modeManagerClass: Class<*>? = null // ModeManager class (from patched CL)
    private var currentMode: ModeInterface? = null
    private var cameraId: String? = null
    private var previewSurface: Surface? = null
    private var videoFps = 0
    private var videoWidth = 0
    private var videoHeight = 0

    // ── SSMEngine interface ─────────────────────────────────────

    override fun open(cameraId: String, previewSurface: Surface, fps: Int, width: Int, height: Int) {
        this.cameraId = cameraId
        this.previewSurface = previewSurface
        this.videoFps = fps
        this.videoWidth = width
        this.videoHeight = height

        Log.i(TAG, "=====================================================")
        Log.i(TAG, "  Patched HwCameraKit engine (parent-last classloader)")
        Log.i(TAG, "  camera=$cameraId  ${width}x${height} @ ${fps}fps")
        Log.i(TAG, "  Bitrate: patched in embedded dex")
        Log.i(TAG, "=====================================================")

        try {
            initPatchedClassLoader()
            verifyPatchedClasses()
            enableHwCameraKitLogging()

            // Load ModeManager from patched classloader via reflection
            val cl = patchedClassLoader!!
            modeManagerClass = cl.loadClass("com.huawei.camerakit.impl.ModeManager")
            Log.i(TAG, "ModeManager loaded via: ${modeManagerClass!!.classLoader}")

            val mgr = modeManagerClass!!.getConstructor(Context::class.java).newInstance(context)
            modeManagerObj = mgr

            // getCameraIdList (returns String[])
            @Suppress("UNCHECKED_CAST")
            val cameraIds = modeManagerClass!!.getMethod("getCameraIdList")
                .invoke(mgr) as? Array<String>
            if (cameraIds.isNullOrEmpty()) {
                Log.e(TAG, "No cameras found via ModeManager")
                listener.onError("open", "No cameras found")
                return
            }
            this.cameraId = cameraIds[0]
            Log.i(TAG, "Using camera: ${this.cameraId} (${cameraIds.size} cameras)")

            // getSupportedModes (returns int[])
            val supportedModes = modeManagerClass!!
                .getMethod("getSupportedModes", String::class.java)
                .invoke(mgr, this.cameraId) as? IntArray
            if (supportedModes == null || !supportedModes.contains(SSM_MODE_TYPE)) {
                Log.e(TAG, "SSM not in supported modes: ${supportedModes?.toList()}")
                listener.onError("open", "SSM not supported (modes: ${supportedModes?.toList()})")
                return
            }
            Log.i(TAG, "Supported modes: ${supportedModes.toList()}")

            // createMode(String, int, ModeStateCallback, Handler)
            // ModeStateCallback is an API class → same class object in both classloaders
            Log.i(TAG, "Creating SSM mode (type=$SSM_MODE_TYPE)...")
            modeManagerClass!!.getMethod(
                "createMode",
                String::class.java,
                Int::class.javaPrimitiveType,
                HwModeStateCallback::class.java,
                Handler::class.java
            ).invoke(mgr, this.cameraId, SSM_MODE_TYPE, modeStateCallback, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Patched ModeManager init failed: ${e.message}", e)
            listener.onError("open", "Init failed: ${e.message}")
        }
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
            // SSM mode only supports flash modes {1, 3}:
            //   3 = TORCH (HW_FLASH_ALWAYS_OPEN), 1 = OFF (HW_FLASH_CLOSE)
            val rc = mode.setFlashMode(if (on) 3 else 1)
            Log.i(TAG, "Flash ${if (on) "ON" else "OFF"} rc=$rc")
        } catch (e: Exception) {
            Log.w(TAG, "Flash failed: ${e.message}")
        }
    }

    /**
     * Force the torch off even when the SSM mode blocks setFlashMode (rc=-4)
     * during recording/processing state. Bypasses the recordState!=IDLE check
     * via reflection: sets the flash tag directly on modeTags and resubmits
     * the preview CaptureRequest.
     */
    private fun forceFlashOff() {
        val mode = currentMode ?: return

        // Try the normal API first
        try {
            val rc = mode.setFlashMode(1) // 1 = HW_FLASH_CLOSE
            if (rc == 0) {
                Log.i(TAG, "Flash OFF rc=0")
                return
            }
            Log.i(TAG, "Flash OFF rc=$rc — trying reflection bypass")
        } catch (e: Exception) {
            Log.w(TAG, "setFlashMode exception: ${e.message}")
        }

        // Reflection bypass: SSM mode's setFlashModeInternal rejects during
        // recording state, but we can set the tag directly and update preview.
        try {
            // Walk up to BaseMode which has the 'modeTags' field
            var baseClass: Class<*>? = mode.javaClass
            while (baseClass != null) {
                try { baseClass.getDeclaredField("modeTags"); break }
                catch (_: NoSuchFieldException) { baseClass = baseClass.superclass }
            }
            if (baseClass == null) {
                Log.w(TAG, "forceFlashOff: BaseMode not found")
                return
            }

            // modeTags.setFlashMode(1) — stores flash=OFF in the capture tags
            val modeTagsField = baseClass.getDeclaredField("modeTags")
            modeTagsField.isAccessible = true
            val modeTags = modeTagsField.get(mode) ?: return
            modeTags.javaClass.getMethod("setFlashMode", Int::class.javaPrimitiveType)
                .invoke(modeTags, 1)

            // updatePreview() — rebuilds and submits the CaptureRequest
            val updateMethod = baseClass.getDeclaredMethod("updatePreview")
            updateMethod.isAccessible = true
            updateMethod.invoke(mode)

            Log.i(TAG, "Flash OFF via reflection bypass")
        } catch (e: Exception) {
            Log.w(TAG, "forceFlashOff reflection failed: ${e.message}")
        }
    }

    override fun setFocus(focusRect: android.graphics.Rect) {
        val mode = currentMode ?: return
        try {
            val rc = mode.autoFocus(4, focusRect) // 4 = AF_TOUCH_AUTO
            Log.i(TAG, "Focus rect=$focusRect rc=$rc")
        } catch (e: Exception) {
            Log.w(TAG, "Focus failed: ${e.message}")
        }
    }

    override fun release() {
        Log.i(TAG, "release()")
        try {
            currentMode?.deactive()
        } catch (e: Exception) {
            Log.w(TAG, "deactive: ${e.message}")
        }
        try {
            currentMode?.release()
        } catch (e: Exception) {
            Log.w(TAG, "release mode: ${e.message}")
        }
        currentMode = null
        modeManagerObj = null
        modeManagerClass = null
        patchedClassLoader = null
    }

    // ── Patched classloader setup ───────────────────────────────

    private fun initPatchedClassLoader() {
        val dexFile = File(context.codeCacheDir, DEX_ASSET)

        // Copy dex asset to writable location (codeCacheDir)
        context.assets.open(DEX_ASSET).use { input ->
            dexFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        Log.i(TAG, "Dex asset extracted: ${dexFile.absolutePath} (${dexFile.length()} bytes)")

        patchedClassLoader = ParentLastDexClassLoader(
            dexFile.absolutePath,
            context.classLoader  // app classloader as parent (delegates to system for api/foundation)
        )
        Log.i(TAG, "ParentLastDexClassLoader created")
    }

    /** Verify our classloader actually loads impl classes from the patched dex. */
    private fun verifyPatchedClasses() {
        val cl = patchedClassLoader!!

        // HwMediaRecorder (the patched class)
        val hwRecClass = cl.loadClass("com.huawei.camerakit.impl.c")
        Log.i(TAG, "HwMediaRecorder classLoader: ${hwRecClass.classLoader}")
        Log.i(TAG, "  is from patched CL: ${hwRecClass.classLoader === cl}")

        // Compare with system-loaded version
        val systemClass = context.classLoader.loadClass("com.huawei.camerakit.impl.c")
        Log.i(TAG, "System HwMediaRecorder classLoader: ${systemClass.classLoader}")
        Log.i(TAG, "  classes are different: ${hwRecClass !== systemClass}")

        // API class should be the same from both classloaders
        val apiClass1 = cl.loadClass("com.huawei.camerakit.api.ModeInterface")
        val apiClass2 = context.classLoader.loadClass("com.huawei.camerakit.api.ModeInterface")
        Log.i(TAG, "ModeInterface same class: ${apiClass1 === apiClass2}")
    }

    /**
     * Force-enable HwCameraKit internal logging by flipping the static gate booleans
     * in the obfuscated logging utility class via reflection.
     * Uses the system classloader (foundation package is not overridden).
     */
    private fun enableHwCameraKitLogging() {
        try {
            val logClass = Class.forName("com.huawei.camerakit.foundation.camera2.a.f")
            for (field in logClass.declaredFields) {
                if (field.type == Boolean::class.javaPrimitiveType) {
                    field.isAccessible = true
                    val value = field.getBoolean(null)
                    field.setBoolean(null, true)
                    Log.i(TAG, "  logging '${field.name}': $value -> true")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "enableHwCameraKitLogging failed: ${e.message}")
        }
    }

    // ── Mode State Callback (API class — system classloader) ────

    private val modeStateCallback = object : HwModeStateCallback() {
        override fun onCreated(mode: ModeInterface) {
            Log.i(TAG, "Mode created: type=${mode.type}")
            // ModeInterface is an API type from the system CL — compatible with our code
            // But the mode's concrete class (SuperSlowMotionMode) was loaded by our patched CL
            Log.i(TAG, "  mode classLoader: ${mode.javaClass.classLoader}")
            currentMode = mode
            configureMode(mode)
        }

        override fun onCreateFailed(cameraId: String, errorCode: Int, errorCode2: Int) {
            Log.e(TAG, "Mode creation failed: camera=$cameraId error=$errorCode/$errorCode2")
            listener.onError("createMode", "Creation failed ($errorCode/$errorCode2)")
        }

        override fun onConfigured() {
            Log.i(TAG, "Mode configured - starting preview")
            try {
                currentMode?.startPreview()
                listener.onPreviewStarted()
            } catch (e: Exception) {
                Log.e(TAG, "startPreview failed: ${e.message}", e)
                listener.onError("startPreview", e.message ?: "unknown")
            }
        }

        override fun onConfigureFailed(errorCode: Int) {
            Log.e(TAG, "Mode configuration failed: $errorCode")
            listener.onError("configure", "Configuration failed ($errorCode)")
        }

        override fun onFatalError(errorCode: Int) {
            Log.e(TAG, "Fatal camera error: $errorCode")
            listener.onError("fatal", "Fatal error ($errorCode)")
        }

        override fun onReleased() {
            Log.i(TAG, "Mode released")
        }
    }

    // ── Mode Configuration ──────────────────────────────────────

    private fun configureMode(mode: ModeInterface) {
        try {
            // createModeConfig() is a static method on ModeManager (impl package)
            // Returns ModeConfigInterface (api package) — type-compatible
            val config = modeManagerClass!!.getMethod("createModeConfig")
                .invoke(null) as ModeConfigInterface

            val surface = previewSurface
            if (surface == null) {
                listener.onError("configure", "Preview surface is null")
                return
            }

            config.addPreviewSurface(surface)
            config.addVideoSize(Size(videoWidth, videoHeight))
            config.setVideoFps(videoFps)
            config.setStateCallback(actionStateCallback, backgroundHandler)

            Log.i(TAG, "Configuring: ${videoWidth}x${videoHeight} @ ${videoFps}fps")
            mode.configure(config)
            Log.i(TAG, "mode.configure() called")

        } catch (e: Exception) {
            Log.e(TAG, "Configuration error: ${e.message}", e)
            listener.onError("configure", e.message ?: "unknown")
        }
    }

    // ── Action State Callback (API class — system classloader) ──

    private val actionStateCallback = object : HwActionStateCallback() {
        override fun onRecording(action: Int, result: HwActionStateCallback.RecordingResult?) {
            Log.i(TAG, "onRecording action=$action")
            when (action) {
                1 -> listener.onRecordingReady()       // RECORDING_READY
                2 -> listener.onRecordingStarted()     // RECORDING_STARTED
                3 -> listener.onRecordingStopped()     // RECORDING_STOPPED
                4 -> {
                    forceFlashOff()
                    listener.onRecordingFinished()    // RECORDING_COMPLETED
                }
                5 -> listener.onRecordingSaved(File("")) // RECORDING_FILE_SAVED
                -1 -> listener.onError("recording", "Unknown error")
                -2 -> listener.onError("recording", "File I/O error")
                -3 -> listener.onError("recording", "Not ready")
            }
        }

        override fun onPreview(action: Int, result: HwActionStateCallback.PreviewResult?) {
            Log.i(TAG, "onPreview action=$action")
        }

        override fun onFocus(action: Int, result: HwActionStateCallback.FocusResult?) {
            Log.i(TAG, "onFocus action=$action")
        }
    }
}
