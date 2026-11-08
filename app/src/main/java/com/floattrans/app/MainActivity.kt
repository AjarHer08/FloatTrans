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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.floattrans.app.core.AppState
import com.floattrans.app.core.Languages
import com.floattrans.app.core.OcrScript
import com.floattrans.app.core.Prefs
import com.floattrans.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val intervalOptions = listOf(
        1000 to "1 detik",
        2000 to "2 detik",
        3000 to "3 detik",
        5000 to "5 detik",
        8000 to "8 detik",
        12000 to "12 detik"
    )

    private val styleOptions = listOf(
        Prefs.STYLE_SOLID to "Padat (kotak gelap)",
        Prefs.STYLE_OUTLINE to "Garis tepi",
        Prefs.STYLE_NONE to "Tanpa latar (teks saja)"
    )

    private val sourceOptions by lazy { Languages.sourceEntries() }
    private val targetOptions by lazy { Languages.targetEntries() }

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
        observeState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionLabel()
        refreshButtons()
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
