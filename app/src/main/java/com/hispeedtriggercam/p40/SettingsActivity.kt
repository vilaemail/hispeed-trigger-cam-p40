package com.hispeedtriggercam.p40

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.net.Inet4Address

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Settings"
        private const val CAPTURES_DIR = "hispeed-trigger-cam"
    }

    private lateinit var settings: AppSettings
    private var pendingPushButton: Button? = null
    private var pendingPushStatus: TextView? = null

    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val button = pendingPushButton
        val statusView = pendingPushStatus
        if (result.resultCode == RESULT_OK && result.data?.data != null) {
            val uri = result.data!!.data!!
            // Take persistable permission so we don't need the picker next time
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            settings.externalDriveUri = uri.toString()
            if (button != null && statusView != null) {
                Thread { pushToExternal(statusView, button, uri) }.start()
            }
        } else {
            if (button != null) {
                button.isEnabled = true
                statusView?.text = "Folder selection cancelled"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        settings = AppSettings(this)

        setupEngine()
        setupFps()
        setupServer()
        setupPushExternal()
        setupAbout()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupEngine() {
        val radioHuawei = findViewById<RadioButton>(R.id.radioHuaweiSdk)
        val radioCamera2 = findViewById<RadioButton>(R.id.radioCamera2Direct)
        val radioCustom = findViewById<RadioButton>(R.id.radioCustomSdk)

        when (settings.engineType) {
            AppSettings.EngineType.HUAWEI_SDK -> radioHuawei.isChecked = true
            AppSettings.EngineType.CAMERA2_DIRECT -> radioCamera2.isChecked = true
            AppSettings.EngineType.CUSTOM_SDK -> radioCustom.isChecked = true
        }

        radioHuawei.setOnCheckedChangeListener { _, checked ->
            if (checked) settings.engineType = AppSettings.EngineType.HUAWEI_SDK
        }
        radioCamera2.setOnCheckedChangeListener { _, checked ->
            if (checked) settings.engineType = AppSettings.EngineType.CAMERA2_DIRECT
        }
        radioCustom.setOnCheckedChangeListener { _, checked ->
            if (checked) settings.engineType = AppSettings.EngineType.CUSTOM_SDK
        }
    }

    private fun setupFps() {
        val radio1920 = findViewById<RadioButton>(R.id.radio1920fps)
        val radio960 = findViewById<RadioButton>(R.id.radio960fps)

        if (settings.use1920fps) radio1920.isChecked = true else radio960.isChecked = true

        radio1920.setOnCheckedChangeListener { _, checked ->
            if (checked) settings.use1920fps = true
        }
        radio960.setOnCheckedChangeListener { _, checked ->
            if (checked) settings.use1920fps = false
        }
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun setupServer() {
        val serverSwitch = findViewById<Switch>(R.id.serverSwitch)
        val serverInfo = findViewById<TextView>(R.id.serverInfoText)

        serverSwitch.isChecked = settings.serverEnabled
        updateServerInfo(serverInfo, settings.serverEnabled)

        serverSwitch.setOnCheckedChangeListener { _, checked ->
            settings.serverEnabled = checked
            updateServerInfo(serverInfo, checked)
        }
    }

    private fun updateServerInfo(infoView: TextView, enabled: Boolean) {
        if (enabled) {
            val ip = getDeviceIp()
            if (ip != null) {
                infoView.text = "Endpoints:\n  GET http://$ip/captures\n  GET http://$ip/capture/<file>"
                infoView.visibility = View.VISIBLE
            } else {
                infoView.text = "No network — connect to WiFi"
                infoView.visibility = View.VISIBLE
            }
        } else {
            infoView.visibility = View.GONE
        }
    }

    private fun getDeviceIp(): String? {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val linkProps = cm.getLinkProperties(network) ?: return null
        return linkProps.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    private fun setupPushExternal() {
        val pushButton = findViewById<Button>(R.id.pushExternalButton)
        val pushStatus = findViewById<TextView>(R.id.pushStatusText)

        pushButton.setOnClickListener {
            pushButton.isEnabled = false
            pushStatus.visibility = View.VISIBLE
            pushStatus.text = "Preparing..."

            pendingPushButton = pushButton
            pendingPushStatus = pushStatus

            // Check if we have a saved URI with valid permissions
            val savedUri = settings.externalDriveUri
            if (savedUri != null) {
                val uri = Uri.parse(savedUri)
                val hasPermission = contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isWritePermission
                }
                if (hasPermission) {
                    pushStatus.text = "Copying to drive..."
                    Thread { pushToExternal(pushStatus, pushButton, uri) }.start()
                    return@setOnClickListener
                }
                // Permission lost, clear saved URI
                settings.externalDriveUri = null
            }

            // Launch folder picker, pre-navigated to the removable volume
            pushStatus.text = "Select folder on external drive..."
            launchFolderPicker()
        }
    }

    private fun launchFolderPicker() {
        val sm = getSystemService(STORAGE_SERVICE) as StorageManager
        val removable = sm.storageVolumes.firstOrNull { it.isRemovable }

        val intent = if (removable != null) {
            removable.createOpenDocumentTreeIntent()
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        }

        pickFolderLauncher.launch(intent)
    }

    private fun pushToExternal(statusView: TextView, button: Button, treeUri: Uri) {
        fun status(msg: String) {
            Log.i(TAG, "Push: $msg")
            runOnUiThread { statusView.text = msg }
        }

        try {
            // Source: DCIM/hispeed-trigger-cam
            val srcDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                CAPTURES_DIR
            )
            if (!srcDir.exists() || !srcDir.isDirectory) {
                status("No captures found in ${srcDir.absolutePath}")
                runOnUiThread { button.isEnabled = true }
                return
            }

            val srcFiles = srcDir.listFiles { f -> f.isFile && f.length() > 0 } ?: emptyArray()
            if (srcFiles.isEmpty()) {
                status("No capture files to push")
                runOnUiThread { button.isEnabled = true }
                return
            }

            val treeDoc = DocumentFile.fromTreeUri(this, treeUri)
            if (treeDoc == null || !treeDoc.canWrite()) {
                settings.externalDriveUri = null
                status("Drive access lost — tap Push again to re-select")
                runOnUiThread { button.isEnabled = true }
                return
            }

            // Find or create hispeed-trigger-cam subfolder
            var destDir = treeDoc.findFile(CAPTURES_DIR)
            if (destDir == null) {
                destDir = treeDoc.createDirectory(CAPTURES_DIR)
            }
            if (destDir == null) {
                status("Cannot create folder on drive")
                runOnUiThread { button.isEnabled = true }
                return
            }

            // Get existing file names on dest
            val existingNames = destDir.listFiles().mapNotNull { it.name }.toSet()
            val toCopy = srcFiles.filter { it.name !in existingNames }

            if (toCopy.isEmpty()) {
                status("All ${srcFiles.size} files already on drive")
                runOnUiThread { button.isEnabled = true }
                return
            }

            status("Copying ${toCopy.size} of ${srcFiles.size} files...")

            var copied = 0
            var totalBytes = 0L
            for (file in toCopy) {
                val mimeType = when (file.extension.lowercase()) {
                    "mp4" -> "video/mp4"
                    "h265", "hevc" -> "video/hevc"
                    else -> "application/octet-stream"
                }
                val destFile = destDir.createFile(mimeType, file.nameWithoutExtension)
                if (destFile == null) {
                    Log.w(TAG, "Cannot create ${file.name} on drive, skipping")
                    continue
                }

                contentResolver.openFileDescriptor(destFile.uri, "w")?.use { pfd ->
                    java.io.FileOutputStream(pfd.fileDescriptor).use { output ->
                        file.inputStream().use { input ->
                            input.copyTo(output)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }

                copied++
                totalBytes += file.length()
                status("Copied $copied/${toCopy.size}: ${file.name} (${totalBytes / 1024 / 1024} MB total)")
            }

            // System-wide sync to flush USB drive hardware write cache
            status("Syncing to drive...")
            try {
                val proc = Runtime.getRuntime().exec("sync")
                proc.waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "sync command failed: ${e.message}")
            }

            status("Done — $copied files copied (${totalBytes / 1024 / 1024} MB). Safe to unplug.")

        } catch (e: Exception) {
            Log.e(TAG, "Push failed: ${e.message}", e)
            status("Error: ${e.message}")
        }

        runOnUiThread { button.isEnabled = true }
    }

    private fun setupAbout() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val aboutText = findViewById<TextView>(R.id.aboutText)
        aboutText.text = "HST Camera v$version\nHigh-speed trigger camera for Huawei P40 Pro"

        val githubLink = findViewById<TextView>(R.id.githubLink)
        githubLink.setOnClickListener {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/vilaemail/hispeed-trigger-cam-p40")
                )
            )
        }
    }
}
