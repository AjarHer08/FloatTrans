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
import android.graphics.Color
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
import com.floattrans.app.core.TextGrouper
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
import kotlin.math.abs

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

    /** Fingerprint of the screen as it looked after the last translation. */
    private var baseline: IntArray? = null

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

    /**
     * Automatic mode watches the screen instead of OCR-ing on a fixed timer:
     * every few hundred milliseconds it fingerprints the page (cheap pixel
     * sampling, no bitmap copy) and starts a translation the moment the page
     * actually changes — a tap to the next chapter, a swipe to the next page or
     * a webtoon scroll. Nothing has to be pressed in between.
     */
    private fun startAutoLoopIfNeeded() {
        loopJob?.cancel()
        loopJob = null
        val auto = Prefs.autoMode
        AppState.setAuto(auto)
        bubble.setAutoMode(auto)
        if (!auto) return
        loopJob = scope.launch {
            translateOnce(force = true)
            val pollMs = Prefs.intervalMs.toLong().coerceIn(MIN_POLL_MS, MAX_POLL_MS)
            while (isActive) {
                delay(pollMs)
                if (panel.isAttached) continue

                val probe = withContext(Dispatchers.Default) { capturer.sampleSignature() }
                    ?: continue
                val reference = baseline
                if (reference == null) {
                    baseline = probe
                    continue
                }
                if (changedRatio(reference, probe) < CHANGE_RATIO) continue

                // New page detected. Give loading artwork a moment to settle so
                // we never OCR a half painted screen.
                var previous = probe
                var rounds = 0
                while (isActive && rounds++ < SETTLE_ROUNDS) {
                    delay(SETTLE_DELAY_MS)
                    val next = withContext(Dispatchers.Default) { capturer.sampleSignature() }
                        ?: break
                    val stillMoving = changedRatio(previous, next) >= SETTLE_RATIO
                    previous = next
                    if (!stillMoving) break
                }

                translateOnce(force = false)
            }
        }
    }

    private fun changedRatio(a: IntArray, b: IntArray): Float {
        if (a.size != b.size || a.isEmpty()) return 1f
        var changed = 0
        for (index in a.indices) {
            if (abs(a[index] - b[index]) > SIGNATURE_TOLERANCE) changed++
        }
        return changed.toFloat() / a.size
    }

    /**
     * Remembers what the screen looks like *with* our translation drawn, so the
     * next poll compares like with like and only a real page change triggers work.
     */
    private suspend fun refreshBaseline() {
        delay(BASELINE_DELAY_MS)
        val signature = withContext(Dispatchers.Default) { capturer.sampleSignature() }
        if (signature != null) baseline = signature
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
        var shot: Bitmap? = null
        try {
            AppState.setBusy(true)
            bubble.setBusy(true)

            shot = captureHidden()
            val frame = shot ?: run {
                AppState.setStatus("Layar tidak dapat ditangkap")
                return
            }

            val captureWidth = frame.width
            val captureHeight = frame.height
            val lines = try {
                withContext(Dispatchers.Default) {
                    ocrEngine.recognize(frame, Prefs.ocrScript)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppState.setStatus("OCR gagal: ${error.message ?: error.javaClass.simpleName}")
                return
            }

            if (lines.isEmpty()) {
                translationOverlay.clear()
                lastKey = ""
                AppState.setStatus("Belum ada teks — menunggu halaman berikutnya")
                return
            }

            val groups = TextGrouper.group(lines)
            val key = groups.joinToString("\n") { it.text }
            if (!force && key == lastKey) return
            lastKey = key

            AppState.setStatus("Menerjemahkan ${groups.size} balon teks…")
            val result = withContext(Dispatchers.Default) {
                translateEngine.translateTexts(
                    groups.map { it.text },
                    Prefs.sourceLang,
                    Prefs.targetLang
                )
            }

            val items = withContext(Dispatchers.Default) {
                groups.mapIndexed { index, group ->
                    val (light, averageColor) = sampleRegion(frame, group.box)
                    TranslationCanvasView.Item(
                        translated = result.lines.getOrNull(index)
                            ?.takeIf { it.isNotBlank() } ?: group.text,
                        original = group.text,
                        box = RectF(group.box),
                        lightBackground = light,
                        background = averageColor
                    )
                }
            }
            translationOverlay.render(items, captureWidth, captureHeight)
            AppState.setResult(result.lines.joinToString("\n"))
            AppState.setStatus(
                "${groups.size} balon — ${Languages.label(result.sourceLang)} → " +
                    Languages.label(Prefs.targetLang)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppState.setStatus("Gagal: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            runCatching { shot?.recycle() }
            captureLock.unlock()
            AppState.setBusy(false)
            bubble.setBusy(false)
            refreshBaseline()
        }
    }

    /**
     * Hides our own windows, grabs a frame, then restores them.
     *
     * The window calls must stay on the main thread: hiding/showing overlay
     * views from a worker thread throws "Only the original thread that created
     * a view hierarchy can touch its views". Only the pixel copy is offloaded.
     */
    private suspend fun captureHidden(): Bitmap? {
        capturer.drain()
        panel.hide()
        bubble.setWindowVisible(false)
        translationOverlay.setWindowVisible(false)
        delay(HIDE_DELAY_MS)
        val bitmap = withContext(Dispatchers.Default) { capturer.captureNow() }
        bubble.setWindowVisible(true)
        translationOverlay.setWindowVisible(true)
        return bitmap
    }

    /** Average colour of the OCR box: picks black-on-light or white-on-dark lettering. */
    private fun sampleRegion(bitmap: Bitmap, box: RectF): Pair<Boolean, Int> {
        val left = box.left.toInt().coerceIn(0, bitmap.width - 1)
        val right = box.right.toInt().coerceIn(left, bitmap.width - 1)
        val top = box.top.toInt().coerceIn(0, bitmap.height - 1)
        val bottom = box.bottom.toInt().coerceIn(top, bitmap.height - 1)
        val stepX = ((right - left) / 12).coerceAtLeast(1)
        val stepY = ((bottom - top) / 12).coerceAtLeast(1)

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                val pixel = bitmap.getPixel(x, y)
                sumR += (pixel shr 16) and 0xFF
                sumG += (pixel shr 8) and 0xFF
                sumB += pixel and 0xFF
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return false to Color.BLACK

        val r = (sumR / count).toInt()
        val g = (sumG / count).toInt()
        val b = (sumB / count).toInt()
        val luminance = (r * 299 + g * 587 + b * 114) / 1000
        return (luminance >= LIGHT_BACKGROUND) to Color.rgb(r, g, b)
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
        baseline = null
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
        private const val BASELINE_DELAY_MS = 180L

        /** Page watcher tuning. */
        private const val MIN_POLL_MS = 500L
        private const val MAX_POLL_MS = 3000L
        private const val CHANGE_RATIO = 0.04f
        private const val SETTLE_RATIO = 0.01f
        private const val SETTLE_ROUNDS = 6
        private const val SETTLE_DELAY_MS = 170L
        private const val SIGNATURE_TOLERANCE = 24
        private const val LIGHT_BACKGROUND = 145

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun actionIntent(context: Context, action: String): Intent =
            Intent(context, CaptureService::class.java).setAction(action)
    }
}
