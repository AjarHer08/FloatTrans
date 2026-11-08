package com.floattrans.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.floattrans.app.capture.ScreenCapturer
import com.floattrans.app.core.AppState
import com.floattrans.app.core.Languages
import com.floattrans.app.core.OcrEngine
import com.floattrans.app.core.Prefs
import com.floattrans.app.core.TranslateEngine
import com.floattrans.app.overlay.BubbleOverlay
import com.floattrans.app.overlay.ControlPanel
import com.floattrans.app.overlay.TranslationCanvasView
import com.floattrans.app.overlay.TranslationOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class CaptureService : Service(), BubbleOverlay.Callbacks, ControlPanel.Callbacks {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var capturer: ScreenCapturer
    private val ocrEngine = OcrEngine()
    private val translateEngine = TranslateEngine()
    private lateinit var translationOverlay: TranslationOverlay
    private lateinit var bubble: BubbleOverlay
    private lateinit var panel: ControlPanel

    private var loopJob: Job? = null
    private var stopping = false
    private var lastKey = ""

    private val captureLock = Mutex()

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        capturer = ScreenCapturer(this)
        translationOverlay = TranslationOverlay(this)
        bubble = BubbleOverlay(this, this)
        panel = ControlPanel(this, this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_TRANSLATE -> requestTranslate()
            ACTION_TOGGLE_AUTO -> toggleAuto()
            ACTION_STOP -> stopEverything()
            else -> if (!AppState.running.value) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- lifecycle

    private fun handleStart(intent: Intent) {
        if (AppState.running.value) {
            AppState.setStatus("Sesi sudah berjalan")
            return
        }
        startForegroundSafely()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.parcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            AppState.setStatus("Izin tangkap layar ditolak")
            stopEverything()
            return
        }

        val started = capturer.start(resultCode, resultData) { onProjectionStopped() }
        if (!started) {
            AppState.setStatus("Gagal memulai tangkap layar")
            stopEverything()
            return
        }

        translationOverlay.show()
        bubble.setAutoMode(Prefs.autoMode)
        bubble.show()

        AppState.setRunning(true)
        AppState.setStatus("Siap — buka aplikasi baca komik Anda")
        updateNotification()
        startAutoLoopIfNeeded()
    }

    private fun stopEverything() {
        if (stopping) return
        stopping = true
        loopJob?.cancel()
        loopJob = null
        scope.launch {
            runCatching { capturer.stop() }
            panel.hide()
            translationOverlay.clear()
            translationOverlay.hide()
            bubble.hide()
            AppState.setRunning(false)
            AppState.setAuto(false)
            AppState.setBusy(false)
            AppState.setStatus("Berhenti")
            runCatching { translateEngine.close() }
            ServiceCompat.stopForeground(this@CaptureService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            stopping = false
        }
    }

    private fun onProjectionStopped() {
        if (stopping) return
        AppState.setStatus("Tangkap layar dihentikan")
        stopEverything()
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        runCatching { capturer.stop() }
        runCatching { panel.hide() }
        runCatching { translationOverlay.hide() }
        runCatching { bubble.hide() }
        runCatching { ocrEngine.close() }
        runCatching { translateEngine.close() }
        AppState.setRunning(false)
        AppState.setAuto(false)
        AppState.setBusy(false)
        super.onDestroy()
    }

    // ------------------------------------------------------------- translate flow

    private fun startAutoLoopIfNeeded() {
        loopJob?.cancel()
        loopJob = null
        val auto = Prefs.autoMode
        AppState.setAuto(auto)
        bubble.setAutoMode(auto)
        if (!auto) return
        loopJob = scope.launch {
            while (isActive) {
                translateOnce(force = false)
                delay(Prefs.intervalMs.toLong().coerceIn(800L, 20_000L))
            }
        }
    }

    private fun requestTranslate() {
        scope.launch { translateOnce(force = true) }
    }

    private fun toggleAuto() {
        Prefs.autoMode = !Prefs.autoMode
        panel.updateAuto(Prefs.autoMode)
        startAutoLoopIfNeeded()
        AppState.setStatus(if (Prefs.autoMode) "Mode otomatis aktif" else "Mode otomatis mati")
    }

    private suspend fun translateOnce(force: Boolean) {
        if (!captureLock.tryLock()) return
        try {
            AppState.setBusy(true)
            bubble.setBusy(true)

            val bitmap = withContext(Dispatchers.Default) { captureHidden() }
            if (bitmap == null) {
                AppState.setStatus("Layar tidak dapat ditangkap")
                return
            }

            val captureWidth = bitmap.width
            val captureHeight = bitmap.height
            val lines = try {
                withContext(Dispatchers.Default) {
                    ocrEngine.recognize(bitmap, Prefs.ocrScript)
                }
            } finally {
                bitmap.recycle()
            }

            if (lines.isEmpty()) {
                translationOverlay.clear()
                AppState.setStatus("Tidak ada teks terdeteksi")
                return
            }

            val key = lines.joinToString("\n") { it.text }
            if (!force && key == lastKey) return
            lastKey = key

            AppState.setStatus("Menerjemahkan ${lines.size} baris…")
            val result = withContext(Dispatchers.Default) {
                translateEngine.translate(lines, Prefs.sourceLang, Prefs.targetLang)
            }

            val items = lines.mapIndexed { index, line ->
                TranslationCanvasView.Item(
                    translated = result.lines.getOrNull(index) ?: line.text,
                    original = line.text,
                    box = RectF(
                        line.left.toFloat(),
                        line.top.toFloat(),
                        line.right.toFloat(),
                        line.bottom.toFloat()
                    )
                )
            }
            translationOverlay.render(items, captureWidth, captureHeight)
            AppState.setResult(result.lines.joinToString("\n"))
            AppState.setStatus(
                "${lines.size} baris — ${Languages.label(result.sourceLang)} → " +
                    Languages.label(Prefs.targetLang)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppState.setStatus("Gagal: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            captureLock.unlock()
            AppState.setBusy(false)
            bubble.setBusy(false)
        }
    }

    /** Hides our own windows, grabs a frame, then restores them. */
    private suspend fun captureHidden(): Bitmap? {
        capturer.drain()
        panel.hide()
        bubble.setWindowVisible(false)
        translationOverlay.setWindowVisible(false)
        delay(HIDE_DELAY_MS)
        val bitmap = capturer.captureNow()
        bubble.setWindowVisible(true)
        translationOverlay.setWindowVisible(true)
        return bitmap
    }

    // ------------------------------------------------------------- overlay events

    override fun onTranslateNow() {
        requestTranslate()
    }

    override fun onOpenMenu() {
        panel.show(Prefs.autoMode)
    }

    override fun onToggleAuto() {
        toggleAuto()
    }

    override fun onClear() {
        translationOverlay.clear()
        lastKey = ""
        AppState.setStatus("Terjemahan dibersihkan")
    }

    override fun onOpenSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    override fun onStop() {
        stopEverything()
    }

    // --------------------------------------------------------------- notification

    private fun startForegroundSafely() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            pendingFlags()
        )
        val translate = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_TRANSLATE),
            pendingFlags()
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            pendingFlags()
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_translate)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notification_action_translate), translate)
            .addAction(0, getString(R.string.notification_action_stop), stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            description = getString(R.string.notification_text)
        }
        manager.createNotificationChannel(channel)
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Intent.parcelableExtra(key: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, T::class.java)
        } else {
            getParcelableExtra(key)
        }

    companion object {
        const val ACTION_START = "com.floattrans.app.action.START"
        const val ACTION_STOP = "com.floattrans.app.action.STOP"
        const val ACTION_TRANSLATE = "com.floattrans.app.action.TRANSLATE"
        const val ACTION_TOGGLE_AUTO = "com.floattrans.app.action.TOGGLE_AUTO"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "floattrans_capture"
        private const val NOTIFICATION_ID = 0x5452
        private const val HIDE_DELAY_MS = 140L

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun actionIntent(context: Context, action: String): Intent =
            Intent(context, CaptureService::class.java).setAction(action)
    }
}
