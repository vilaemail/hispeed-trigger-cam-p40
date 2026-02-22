package com.hispeedtriggercam.p40

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
        setupRemoteTrigger()
        setupPushExternal()
        setupClearRecordings()
        setupClearExternal()
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

    // Edge characters for the ESP trigger protocol
    private val edgeLabels = arrayOf("Rising (/)", "Falling (\\)", "Both (X)")
    private val edgeChars = charArrayOf('/', '\\', 'X')

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun setupRemoteTrigger() {
        val triggerSwitch = findViewById<Switch>(R.id.remoteTriggerSwitch)
        val deviceSection = findViewById<LinearLayout>(R.id.serialDeviceSection)
        val deviceInfoText = findViewById<TextView>(R.id.serialDeviceInfoText)

        val ledPinEdit = findViewById<EditText>(R.id.espLedPinEdit)
        val ledOnMsEdit = findViewById<EditText>(R.id.espLedOnMsEdit)
        val cooldownSEdit = findViewById<EditText>(R.id.espCooldownSEdit)
        val triggerDelayMsEdit = findViewById<EditText>(R.id.espTriggerDelayMsEdit)
        val triggersContainer = findViewById<LinearLayout>(R.id.triggersContainer)
        val addTriggerButton = findViewById<Button>(R.id.addTriggerButton)
        val validationText = findViewById<TextView>(R.id.espValidationText)

        triggerSwitch.isChecked = settings.remoteTriggerEnabled
        deviceSection.visibility = if (settings.remoteTriggerEnabled) View.VISIBLE else View.GONE

        triggerSwitch.setOnCheckedChangeListener { _, checked ->
            settings.remoteTriggerEnabled = checked
            deviceSection.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) refreshSerialDeviceInfo(deviceInfoText)
        }

        // Populate ESP params from settings
        ledPinEdit.setText(settings.espLedPin.toString())
        ledOnMsEdit.setText(settings.espLedOnMs.toString())
        triggerDelayMsEdit.setText(settings.espTriggerDelayMs.toString())
        // Display cooldown in seconds (stored as ms)
        val cooldownS = settings.espCooldownMs / 1000.0
        cooldownSEdit.setText(if (cooldownS == cooldownS.toLong().toDouble()) {
            cooldownS.toLong().toString()
        } else {
            "%.1f".format(cooldownS)
        })

        // Validation + save for LED Pin
        ledPinEdit.addTextChangedListener(validatingIntWatcher(
            validationText, 0, 100, "LED Pin"
        ) { settings.espLedPin = it })

        // Validation + save for LED On (ms)
        ledOnMsEdit.addTextChangedListener(validatingIntWatcher(
            validationText, 0, 10000, "LED On"
        ) { settings.espLedOnMs = it })

        // Validation + save for Trigger Delay (ms, max 10000ms = 10s)
        triggerDelayMsEdit.addTextChangedListener(validatingIntWatcher(
            validationText, 0, 10000, "Trigger Delay"
        ) { settings.espTriggerDelayMs = it })

        // Validation + save for Cooldown (seconds → stored as ms)
        cooldownSEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: return
                val seconds = text.toDoubleOrNull()
                if (seconds == null || seconds < 0 || seconds > 10) {
                    validationText.text = "Cooldown must be 0–10 seconds"
                    validationText.visibility = View.VISIBLE
                    return
                }
                validationText.visibility = View.GONE
                settings.espCooldownMs = (seconds * 1000).toInt()
            }
        })

        // Populate trigger list from settings
        parseTriggers(settings.espTriggers).forEach { (gpio, edge) ->
            addTriggerRow(triggersContainer, gpio, edge)
        }

        addTriggerButton.setOnClickListener {
            addTriggerRow(triggersContainer, 4, '/')
            saveTriggers(triggersContainer)
        }

        if (settings.remoteTriggerEnabled) {
            refreshSerialDeviceInfo(deviceInfoText)
        }
    }

    private fun validatingIntWatcher(
        validationText: TextView,
        min: Int,
        max: Int,
        label: String,
        setter: (Int) -> Unit
    ): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.toIntOrNull()
                if (value == null || value < min || value > max) {
                    validationText.text = "$label must be $min–$max"
                    validationText.visibility = View.VISIBLE
                    return
                }
                validationText.visibility = View.GONE
                setter(value)
            }
        }
    }

    private fun parseTriggers(str: String): List<Pair<Int, Char>> {
        val result = mutableListOf<Pair<Int, Char>>()
        for (token in str.trim().split("\\s+".toRegex())) {
            if (token.isEmpty()) continue
            val edge = token.last()
            if (edge !in charArrayOf('/', '\\', 'X')) continue
            val gpio = token.dropLast(1).toIntOrNull() ?: continue
            result.add(gpio to edge)
        }
        return result.ifEmpty { listOf(4 to '/') }
    }

    private fun serializeTriggers(container: LinearLayout): String {
        val parts = mutableListOf<String>()
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as? LinearLayout ?: continue
            val gpioEdit = row.getChildAt(0) as? EditText ?: continue
            val edgeSpinner = row.getChildAt(1) as? Spinner ?: continue
            val gpio = gpioEdit.text.toString().toIntOrNull() ?: continue
            val edgeIdx = edgeSpinner.selectedItemPosition
            if (edgeIdx in edgeChars.indices) {
                parts.add("$gpio${edgeChars[edgeIdx]}")
            }
        }
        return parts.joinToString(" ").ifEmpty { "4/" }
    }

    private fun saveTriggers(container: LinearLayout) {
        settings.espTriggers = serializeTriggers(container)
    }

    private fun addTriggerRow(container: LinearLayout, gpio: Int, edge: Char) {
        val dp8 = dpToPx(8)
        val validationText = findViewById<TextView>(R.id.espValidationText)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(4) }
        }

        // GPIO number input
        val gpioEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(gpio.toString())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            hint = "GPIO"
            setHintTextColor(Color.parseColor("#FF666666"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp8 }
        }
        gpioEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.toIntOrNull()
                if (value != null && (value < 0 || value > 100)) {
                    validationText.text = "GPIO must be 0–100"
                    validationText.visibility = View.VISIBLE
                } else {
                    validationText.visibility = View.GONE
                    saveTriggers(container)
                }
            }
        })

        // Edge spinner
        val edgeSpinner = Spinner(this).apply {
            adapter = darkSpinnerAdapter(edgeLabels.toList())
            val edgeIdx = edgeChars.indexOf(edge)
            setSelection(if (edgeIdx >= 0) edgeIdx else 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f)
                .apply { marginEnd = dp8 }
            setPopupBackgroundDrawable(ColorDrawable(Color.parseColor("#FF424242")))
        }
        edgeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                saveTriggers(container)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Remove button
        val removeBtn = Button(this).apply {
            text = "X"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(Color.parseColor("#FFD32F2F"))
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
            setPadding(0, 0, 0, 0)
        }
        removeBtn.setOnClickListener {
            if (container.childCount > 1) {
                container.removeView(row)
                saveTriggers(container)
            }
        }

        row.addView(gpioEdit)
        row.addView(edgeSpinner)
        row.addView(removeBtn)
        container.addView(row)
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private fun <T> darkSpinnerAdapter(items: List<T>): ArrayAdapter<T> {
        return object : ArrayAdapter<T>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getView(position, convertView, parent).also {
                    (it as? TextView)?.setTextColor(Color.WHITE)
                }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getDropDownView(position, convertView, parent).also {
                    (it as? TextView)?.apply {
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#FF424242"))
                    }
                }
            }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun refreshSerialDeviceInfo(infoText: TextView) {
        val tempManager = SerialTriggerManager(this, object : SerialTriggerManager.Listener {
            override fun onArmAcknowledged() {}
            override fun onArmTimeout() {}
            override fun onTriggerReceived() {}
            override fun onSerialError(message: String) {}
        })
        val drivers = tempManager.getAvailableDevices()

        if (drivers.isEmpty()) {
            infoText.text = "No USB serial devices connected.\nWill auto-connect when a device is plugged in."
        } else {
            val names = drivers.joinToString("\n") { drv ->
                val dev = drv.device
                "  \u2022 ${dev.productName ?: "Unknown"} (${dev.deviceName})"
            }
            infoText.text = "Available devices (auto-connects to first):\n$names"
        }
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

            val allowedExtensions = setOf("mp4", "json")
            val srcFiles = srcDir.listFiles { f ->
                f.isFile && f.length() > 0 && f.extension.lowercase() in allowedExtensions
            } ?: emptyArray()
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
                    "json" -> "application/json"
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

            status("Done — $copied files copied (${totalBytes / 1024 / 1024} MB). Wait for drive light to stop flashing before unplugging.")

        } catch (e: Exception) {
            Log.e(TAG, "Push failed: ${e.message}", e)
            status("Error: ${e.message}")
        }

        runOnUiThread { button.isEnabled = true }
    }

    private fun setupClearRecordings() {
        val clearButton = findViewById<Button>(R.id.clearRecordingsButton)
        val clearStatus = findViewById<TextView>(R.id.clearRecordingsStatus)

        clearButton.setOnClickListener {
            val capturesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                CAPTURES_DIR
            )
            val allowedExtensions = setOf("mp4", "json")
            val files = capturesDir.listFiles { f ->
                f.isFile && f.extension.lowercase() in allowedExtensions
            } ?: emptyArray()

            if (files.isEmpty()) {
                clearStatus.visibility = View.VISIBLE
                clearStatus.text = "No recordings to delete"
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("Clear All Recordings")
                .setMessage("Delete ${files.size} file(s) from ${capturesDir.absolutePath}?\n\nThis cannot be undone.")
                .setPositiveButton("Delete All") { _, _ ->
                    var deleted = 0
                    for (file in files) {
                        if (file.delete()) deleted++
                    }
                    clearStatus.visibility = View.VISIBLE
                    clearStatus.text = "Deleted $deleted of ${files.size} files"
                    Log.i(TAG, "Cleared recordings: $deleted/${files.size} files deleted")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupClearExternal() {
        val clearButton = findViewById<Button>(R.id.clearExternalButton)
        val clearStatus = findViewById<TextView>(R.id.clearExternalStatus)

        clearButton.setOnClickListener {
            val savedUri = settings.externalDriveUri
            if (savedUri == null) {
                clearStatus.visibility = View.VISIBLE
                clearStatus.text = "No external drive configured — push files first"
                return@setOnClickListener
            }

            val uri = Uri.parse(savedUri)
            val hasPermission = contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
            if (!hasPermission) {
                settings.externalDriveUri = null
                clearStatus.visibility = View.VISIBLE
                clearStatus.text = "Drive access lost — push files first to re-select"
                return@setOnClickListener
            }

            val treeDoc = DocumentFile.fromTreeUri(this, uri)
            val destDir = treeDoc?.findFile(CAPTURES_DIR)
            if (destDir == null || !destDir.canWrite()) {
                clearStatus.visibility = View.VISIBLE
                clearStatus.text = "No recordings folder found on external drive"
                return@setOnClickListener
            }

            val allowedExtensions = setOf("mp4", "json")
            val files = destDir.listFiles().filter { f ->
                f.isFile && f.name?.substringAfterLast('.')?.lowercase() in allowedExtensions
            }

            if (files.isEmpty()) {
                clearStatus.visibility = View.VISIBLE
                clearStatus.text = "No recordings on external drive"
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("Clear External Drive Recordings")
                .setMessage("Delete ${files.size} file(s) from external drive?\n\nThis cannot be undone.")
                .setPositiveButton("Delete All") { _, _ ->
                    clearButton.isEnabled = false
                    clearStatus.visibility = View.VISIBLE
                    clearStatus.text = "Deleting..."
                    Thread {
                        var deleted = 0
                        for (file in files) {
                            if (file.delete()) deleted++
                        }
                        Log.i(TAG, "Cleared external: $deleted/${files.size} files deleted")
                        runOnUiThread {
                            clearStatus.text = "Deleted $deleted of ${files.size} files from external drive"
                            clearButton.isEnabled = true
                        }
                    }.start()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
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
