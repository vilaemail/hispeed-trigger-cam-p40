package com.hispeedtriggercam.p40

import android.content.Context

class AppSettings(context: Context) {

    enum class EngineType(val label: String) {
        HUAWEI_SDK("Huawei SDK"),
        CAMERA2_DIRECT("Camera2 Direct"),
        CUSTOM_SDK("Custom SDK");
    }

    private val prefs = context.getSharedPreferences("hst_camera_prefs", Context.MODE_PRIVATE)

    var engineType: EngineType
        get() = try {
            val type = EngineType.valueOf(prefs.getString("engine_type", EngineType.CUSTOM_SDK.name)!!)
            if (type == EngineType.CAMERA2_DIRECT) EngineType.CUSTOM_SDK else type
        } catch (_: Exception) {
            EngineType.CUSTOM_SDK
        }
        set(value) = prefs.edit().putString("engine_type", value.name).apply()

    var use1920fps: Boolean
        get() = prefs.getBoolean("use_1920fps", true)
        set(value) = prefs.edit().putBoolean("use_1920fps", value).apply()

    var serverEnabled: Boolean
        get() = prefs.getBoolean("http_server_enabled", false)
        set(value) = prefs.edit().putBoolean("http_server_enabled", value).apply()

    var externalDriveUri: String?
        get() = prefs.getString("external_drive_uri", null)
        set(value) = prefs.edit().putString("external_drive_uri", value).apply()

    var remoteTriggerEnabled: Boolean
        get() = prefs.getBoolean("remote_trigger_enabled", false)
        set(value) = prefs.edit().putBoolean("remote_trigger_enabled", value).apply()

    var espLedPin: Int
        get() = prefs.getInt("esp_led_pin", 2)
        set(value) = prefs.edit().putInt("esp_led_pin", value).apply()

    /** LED on duration in milliseconds (displayed as ms, sent as µs) */
    var espLedOnMs: Int
        get() = prefs.getInt("esp_led_on_ms", 10)
        set(value) = prefs.edit().putInt("esp_led_on_ms", value).apply()

    /** Cooldown in milliseconds (displayed as seconds, sent as µs) */
    var espCooldownMs: Int
        get() = prefs.getInt("esp_cooldown_ms", 6000)
        set(value) = prefs.edit().putInt("esp_cooldown_ms", value).apply()

    /** Trigger delay in milliseconds (displayed as ms, sent as µs) */
    var espTriggerDelayMs: Int
        get() = prefs.getInt("esp_trigger_delay_ms", 200)
        set(value) = prefs.edit().putInt("esp_trigger_delay_ms", value).apply()

    var espTriggers: String
        get() = prefs.getString("esp_triggers", "4/") ?: "4/"
        set(value) = prefs.edit().putString("esp_triggers", value).apply()

    val videoFps get() = if (use1920fps) 1920 else 960
    val videoWidth get() = if (use1920fps) 1280 else 1920
    val videoHeight get() = if (use1920fps) 720 else 1080
    val fpsLabel get() = if (use1920fps) "1920fps 720p" else "960fps 1080p"
}
