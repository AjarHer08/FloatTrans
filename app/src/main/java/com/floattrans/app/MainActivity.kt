package com.floattrans.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.floattrans.app.core.AppState
import com.floattrans.app.core.Languages
import com.floattrans.app.core.OcrScript
import com.floattrans.app.core.Prefs
import com.floattrans.app.databinding.ActivityMainBinding
import com.floattrans.app.update.UpdateChecker
import com.floattrans.app.update.UpdateInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val intervalOptions = listOf(
        600 to "Cepat (0,6 detik)",
        900 to "Normal (0,9 detik)",
        1500 to "Hemat baterai (1,5 detik)",
        2500 to "Sangat hemat (2,5 detik)"
    )

    private val styleOptions = listOf(
        Prefs.STYLE_AUTO to "Otomatis (menyatu dengan balon)",
        Prefs.STYLE_SOLID to "Kotak warna balon",
        Prefs.STYLE_OUTLINE to "Kotak + garis tepi",
        Prefs.STYLE_NONE to "Tanpa latar (teks saja)"
    )

    private val engineOptions = listOf(
        Prefs.ENGINE_SMART to "Otomatis (Google, cadangan offline)",
        Prefs.ENGINE_ONLINE to "Google online (paling akurat)",
        Prefs.ENGINE_OFFLINE to "Offline ML Kit (tanpa internet)"
    )

    private val sourceOptions by lazy { Languages.sourceEntries() }
    private val targetOptions by lazy { Languages.targetEntries() }

    private var updateChecked = false
    private var updateJob: Job? = null
    private var pendingUpdate: UpdateInfo? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            launchService(CaptureService.startIntent(this, result.resultCode, data))
        } else {
            AppState.setStatus("Izin tangkap layar ditolak")
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startProjectionRequest() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLanguagePickers()
        setupDisplayPickers()
        setupActions()
        setupUpdateCard()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionLabel()
        refreshButtons()

        if (!updateChecked) {
            updateChecked = true
            checkForUpdates(manual = false)
        } else {
            // Returning from the "install unknown apps" settings: finish the
            // update the user had already approved.
            val pending = pendingUpdate
            pendingUpdate = null
            if (pending != null && canInstallPackages()) downloadAndInstall(pending)
        }
    }

    // ------------------------------------------------------------------ settings

    private fun setupLanguagePickers() {
        bindPicker(
            binding.spOcr,
            OcrScript.entries.map { it.second },
            OcrScript.entries.map { it.first }.indexOf(Prefs.ocrScript)
        ) { index -> Prefs.ocrScript = OcrScript.entries[index].first }

        bindPicker(
            binding.spSource,
            sourceOptions.map { it.second },
            sourceOptions.map { it.first }.indexOf(Prefs.sourceLang)
        ) { index -> Prefs.sourceLang = sourceOptions[index].first }

        bindPicker(
            binding.spTarget,
            targetOptions.map { it.second },
            targetOptions.map { it.first }.indexOf(Prefs.targetLang)
        ) { index -> Prefs.targetLang = targetOptions[index].first }

        bindPicker(
            binding.spEngine,
            engineOptions.map { it.second },
            engineOptions.map { it.first }.indexOf(Prefs.translationEngine)
        ) { index -> Prefs.translationEngine = engineOptions[index].first }
    }

    private fun setupDisplayPickers() {
        binding.sbTextSize.max = 16
        binding.sbTextSize.progress = (Prefs.textSizeSp - 10).coerceIn(0, 16)
        updateTextSizeLabel(Prefs.textSizeSp)
        binding.sbTextSize.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val size = 10 + progress
                    Prefs.textSizeSp = size
                    updateTextSizeLabel(size)
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            }
        )

        bindPicker(
            binding.spInterval,
            intervalOptions.map { it.second },
            intervalOptions.map { it.first }.indexOf(Prefs.intervalMs)
        ) { index -> Prefs.intervalMs = intervalOptions[index].first }

        bindPicker(
            binding.spStyle,
            styleOptions.map { it.second },
            styleOptions.map { it.first }.indexOf(Prefs.backgroundStyle)
        ) { index -> Prefs.backgroundStyle = styleOptions[index].first }

        binding.swShowOriginal.isChecked = Prefs.showOriginal
        binding.swShowOriginal.setOnCheckedChangeListener { _, checked ->
            Prefs.showOriginal = checked
        }
    }

    private fun updateTextSizeLabel(size: Int) {
        binding.tvTextSize.text = getString(R.string.text_size, size)
    }

    private inline fun bindPicker(
        spinner: android.widget.Spinner,
        labels: List<String>,
        initialIndex: Int,
        crossinline onSelected: (Int) -> Unit
    ) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter
        spinner.setSelection(initialIndex.coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                onSelected(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    // ------------------------------------------------------------------- actions

    private fun setupActions() {
        binding.btnCheckUpdate.setOnClickListener { checkForUpdates(manual = true) }
        binding.btnOverlay.setOnClickListener { openOverlaySettings() }
        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener {
            startService(CaptureService.actionIntent(this, CaptureService.ACTION_STOP))
        }
    }

    private fun onStartClicked() {
        if (AppState.running.value) {
            toast("Sesi sudah berjalan")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Aktifkan izin overlay dulu")
            openOverlaySettings()
            return
        }
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startProjectionRequest()
        }
    }

    private fun startProjectionRequest() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            AppState.setStatus("Perangkat tidak mendukung tangkap layar")
            return
        }
        runCatching { projectionLauncher.launch(manager.createScreenCaptureIntent()) }
            .onFailure { AppState.setStatus("Tidak dapat membuka izin tangkap layar") }
    }

    private fun launchService(intent: Intent) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }.onFailure { AppState.setStatus("Gagal memulai layanan: ${it.message}") }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } }
    }

    // --------------------------------------------------------------------- state

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AppState.status.collect { status ->
                        binding.tvStatus.text = status.ifBlank { getString(R.string.status_idle) }
                    }
                }
                launch {
                    AppState.result.collect { result ->
                        binding.tvResult.text = result.ifBlank { getString(R.string.result_empty) }
                    }
                }
                launch {
                    AppState.running.collect {
                        refreshButtons()
                        refreshPermissionLabel()
                    }
                }
                launch {
                    AppState.busy.collect { busy ->
                        binding.btnStart.isEnabled = !busy && !AppState.running.value
                    }
                }
            }
        }
    }

    private fun refreshButtons() {
        val running = AppState.running.value
        binding.btnStart.isEnabled = !running && !AppState.busy.value
        binding.btnStop.isEnabled = running
        binding.btnStop.alpha = if (running) 1f else 0.5f
    }

    private fun refreshPermissionLabel() {
        val granted = Settings.canDrawOverlays(this)
        binding.tvPermission.text = getString(
            if (granted) R.string.permission_overlay_granted else R.string.permission_overlay_missing
        )
        binding.btnOverlay.visibility = if (granted) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------- updates

    private fun setupUpdateCard() {
        binding.tvUpdateInfo.text = getString(R.string.update_installed, installedVersion())
    }

    private fun installedVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "?"

    private fun checkForUpdates(manual: Boolean) {
        if (updateJob?.isActive == true) return
        updateJob = lifecycleScope.launch {
            binding.btnCheckUpdate.isEnabled = false
            binding.tvUpdateInfo.text = getString(R.string.update_checking)

            val info = UpdateChecker.check()
            binding.btnCheckUpdate.isEnabled = true
            val current = installedVersion()

            if (info == null) {
                binding.tvUpdateInfo.text = getString(R.string.update_installed, current)
                if (manual) toast(getString(R.string.update_check_failed))
                return@launch
            }
            if (!UpdateChecker.isNewer(info.versionName, current)) {
                val text = getString(R.string.update_latest, current)
                binding.tvUpdateInfo.text = text
                if (manual) toast(text)
                return@launch
            }
            binding.tvUpdateInfo.text = getString(R.string.update_available, info.versionName)
            showUpdateDialog(info)
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val notes = info.notes.trim()
            .replace("**", "")
            .ifBlank { getString(R.string.update_notes_empty) }
            .take(1500)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_dialog_title, info.versionName))
            .setMessage(notes)
            .setPositiveButton(R.string.update_download_install) { _, _ -> downloadAndInstall(info) }
            .setNeutralButton(R.string.update_open_browser) { _, _ -> openUrl(info.pageUrl) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun downloadAndInstall(info: UpdateInfo) {
        if (info.apkUrl.isNullOrBlank()) {
            toast(getString(R.string.update_no_apk))
            openUrl(info.pageUrl)
            return
        }
        if (!canInstallPackages()) {
            pendingUpdate = info
            requestInstallPermission()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_update_progress, null)
        val bar = view.findViewById<ProgressBar>(R.id.pbUpdate)
        val percent = view.findViewById<TextView>(R.id.tvUpdatePercent)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_downloading)
            .setView(view)
            .setCancelable(true)
            .create()

        val job = lifecycleScope.launch {
            val file = UpdateChecker.download(this@MainActivity, info) { progress ->
                runOnUiThread {
                    bar.progress = progress
                    percent.text = getString(R.string.update_percent, progress)
                }
            }
            runCatching { dialog.dismiss() }
            if (file == null) {
                toast(getString(R.string.update_download_failed))
                return@launch
            }
            installApk(file)
        }
        dialog.setOnCancelListener { job.cancel() }
        dialog.show()
    }

    private fun installApk(file: File) {
        if (signatureMismatch(file)) {
            showReinstallDialog()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { toast(getString(R.string.update_install_failed)) }
    }

    /**
     * APKs built by CI are signed with a fresh debug key each time, so Android
     * refuses to install them over an existing copy. Detect that up front and
     * explain it instead of letting the installer show a cryptic error.
     */
    @Suppress("DEPRECATION")
    private fun signatureMismatch(file: File): Boolean = runCatching {
        val archive = packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_SIGNATURES
        ) ?: return@runCatching false
        val installed = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        val newSignature = archive.signatures?.firstOrNull()?.toCharsString()
        val oldSignature = installed.signatures?.firstOrNull()?.toCharsString()
        newSignature != null && oldSignature != null && newSignature != oldSignature
    }.getOrDefault(false)

    private fun showReinstallDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_reinstall_title)
            .setMessage(R.string.update_reinstall_message)
            .setPositiveButton(R.string.update_open_browser) { _, _ -> openUrl(RELEASES_URL) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun requestInstallPermission() {
        toast(getString(R.string.update_allow_install))
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure { toast(getString(R.string.update_install_failed)) }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast(getString(R.string.update_open_failed)) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val RELEASES_URL = "https://github.com/AjarHer08/FloatTrans/releases/latest"
    }
}
