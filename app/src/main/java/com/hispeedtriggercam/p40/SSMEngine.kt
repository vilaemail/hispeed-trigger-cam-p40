package com.hispeedtriggercam.p40

import android.graphics.Rect
import android.view.Surface
import java.io.File

interface SSMEngine {

    interface Listener {
        fun onPreviewStarted()
        fun onRecordingReady()
        fun onRecordingStarted()
        fun onRecordingStopped()
        fun onRecordingFinished()
        fun onRecordingSaved(file: File)
        fun onError(phase: String, message: String)
    }

    val displayName: String

    fun open(cameraId: String, previewSurface: Surface, fps: Int, width: Int, height: Int)
    fun startRecording(outputFile: File)
    fun setFlash(on: Boolean)
    fun setFocus(focusRect: Rect)
    fun release()
}
