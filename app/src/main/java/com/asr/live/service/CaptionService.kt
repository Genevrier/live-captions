package com.asr.live.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.asr.live.MainActivity
import com.asr.live.model.ModelCatalog
import com.asr.live.pipeline.*
import java.util.concurrent.atomic.AtomicLong
import android.provider.Settings
import android.net.Uri
import com.asr.live.overlay.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CaptionService : Service() {
    @Volatile private var displayedGeneration = 0L
    @Volatile private var current: CaptionSession? = null
    @Volatile private var destroyed = false
    @Volatile private var latestStartId = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val terminationWaiters = mutableMapOf<CaptionSession, MutableList<() -> Unit>>()
    private lateinit var overlayPrefs: OverlayPreferences
    private lateinit var overlay: FloatingCaptions
    private var notificationModel = "Live captions"
    override fun onCreate() {
        super.onCreate()
        overlayPrefs = OverlayPreferences(this)
        overlay = FloatingCaptions(this, overlayPrefs)
        scope.launch {
            combine(CaptionState.lines, CaptionState.lifecycle, overlayPrefs.state) { lines, lifecycle, options -> Triple(lines, lifecycle, options) }
                .collect { (lines, lifecycle, _) ->
                    overlay.render(lines, lifecycle, if (CaptionState.metrics.value.profile == Profile.ENGLISH_FRENCH.label) "French" else "English")
                }
        }
        scope.launch {
            overlayPrefs.state.collect {
                if (CaptionState.running.value) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(notificationModel))
            }
        }
        scope.launch {
            while (isActive) {
                delay(5000)
                val generation = displayedGeneration
                if (CaptionState.running.value) {
                    val memory = withContext(Dispatchers.IO) { MemoryUsage.sample(this@CaptionService) }
                    CaptionState.metrics(generation) { it.copy(appPssKb = memory.appPssKb, rssKb = memory.rssKb,
                        nativeHeapKb = memory.nativeHeapKb, javaHeapKb = memory.javaHeapKb, availableKb = memory.availableKb,
                        thermalStatus = memory.thermalStatus) }
                    // Recheck permission and lock-screen visibility even during a silent phrase.
                    overlay.render(CaptionState.lines.value, CaptionState.lifecycle.value,
                        if (CaptionState.metrics.value.profile == Profile.ENGLISH_FRENCH.label) "French" else "English")
                    if (CaptionState.running.value) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(notificationModel))
                }
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_OVERLAY) {
            if (CaptionState.running.value && Settings.canDrawOverlays(this))
                overlayPrefs.update(overlayPrefs.state.value.copy(enabled = !overlayPrefs.state.value.enabled))
            else if (!CaptionState.running.value) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val generation = requests.incrementAndGet()
        if (intent?.action == ACTION_STOP || intent == null) {
            val stoppedGeneration = displayedGeneration
            val session = current
            if (CaptionState.lifecycle.value == ListeningState.LISTENING) {
                CaptionState.stopping(stoppedGeneration)
                session?.stop()
            } else {
                CaptionState.cancel(stoppedGeneration)
                session?.cancel()
            }
            if (session == null) {
                finishStop(stoppedGeneration, generation)
            } else {
                afterTermination(session) {
                    if (current === session) current = null
                    finishStop(stoppedGeneration, generation)
                }
            }
            return START_NOT_STICKY
        }
        val config = SessionConfig(
            profile = Profile.fromId(intent.getStringExtra("profile")),
            performanceMode = PerformanceMode.entries.firstOrNull { it.name == intent.getStringExtra("performanceMode") } ?: PerformanceMode.BALANCED,
            modelId = intent.getStringExtra("model") ?: ModelCatalog.DEFAULT.id,
            threads = intent.getIntExtra("threads", 6).coerceIn(1, 8),
            correctionThreads = intent.getIntExtra("correctionThreads", 4).let { if (it in setOf(2, 4, 6, 8)) it else 4 },
            quality = TranslationQuality.entries.firstOrNull { it.name == intent.getStringExtra("quality") } ?: TranslationQuality.HY_Q8,
            qnn = intent.getBooleanExtra("qnn", false),
            gpuTranslation = intent.getBooleanExtra("gpuTranslation", com.asr.live.BuildConfig.OPENCL_ENABLED),
            translationBatch = intent.getIntExtra("translationBatch", 256).let { if (it in setOf(128, 256, 512)) it else 256 },
            translationUbatch = intent.getIntExtra("translationUbatch", 128).let { if (it in setOf(64, 128, 256)) it else 128 },
            correction = intent.getBooleanExtra("correction", false),
            glossary = intent.getStringExtra("glossary") ?: "",
        )
        startForegroundNotification(ModelCatalog.byId(config.modelId)?.displayName ?: "Loading captions")
        displayedGeneration = generation
        CaptionState.begin(generation, config, ModelCatalog.byId(config.modelId)?.shortName ?: "Nemotron")
        val previous = current
        if (previous == null) {
            createAndStartSession(generation, config)
        } else {
            previous.cancel()
            afterTermination(previous) {
                if (current === previous) current = null
                if (!destroyed && generation == requests.get()) createAndStartSession(generation, config)
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        destroyed = true; requests.incrementAndGet()
        scope.cancel(); shutdownScope.cancel(); terminationWaiters.clear()
        overlay.close(); overlayPrefs.close()
        current?.let { CaptionState.cancel(it.generation); it.cancel() }
        current = null
        super.onDestroy()
    }

    private fun createAndStartSession(generation: Long, config: SessionConfig) {
        if (destroyed || generation != requests.get()) return
        lateinit var session: CaptionSession
        session = CaptionSession(applicationContext, generation, config) { message ->
            CaptionState.error(generation, message)
            CaptionState.cancel(generation)
            scope.launch {
                if (!destroyed && generation == requests.get() && current === session) {
                    afterTermination(session) {
                        if (current === session) current = null
                        finishStop(generation, generation)
                    }
                }
            }
        }
        current = session
        try {
            session.start()
        } catch (t: Throwable) {
            CaptionState.error(generation, "Could not start workers: ${t.message}")
            CaptionState.cancel(generation)
            session.cancel()
            afterTermination(session) {
                if (current === session) current = null
                finishStop(generation, generation)
            }
        }
    }

    /** Wait for worker-owned native cleanup off the main and service control threads. */
    private fun afterTermination(session: CaptionSession, action: () -> Unit) {
        terminationWaiters[session]?.let { it += action; return }
        terminationWaiters[session] = mutableListOf(action)
        shutdownScope.launch {
            try {
                while (isActive && !session.join(250)) { }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@launch
            }
            if (!isActive) return@launch
            withContext(Dispatchers.Main.immediate) {
                val actions = terminationWaiters.remove(session).orEmpty()
                actions.forEach { it() }
            }
        }
    }

    private fun finishStop(stoppedGeneration: Long, requestGeneration: Long) {
        if (destroyed || requestGeneration != requests.get()) return
        CaptionState.stopped(stoppedGeneration)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(latestStartId)
    }

    private fun startForegroundNotification(modelName: String) {
        notificationModel = modelName
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Live captions", NotificationManager.IMPORTANCE_LOW)
            )
        }

        ServiceCompat.startForeground(this, NOTIF_ID, notification(modelName), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
    private fun notification(modelName: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val allowed = Settings.canDrawOverlays(this)
        val toggle = if (allowed) PendingIntent.getService(this, 2,
            Intent(this, CaptionService::class.java).setAction(ACTION_OVERLAY), PendingIntent.FLAG_IMMUTABLE)
        else PendingIntent.getActivity(this, 3,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION") // int-icon Action.Builder keeps us off the icons dependency
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Listening")
            .setContentText(modelName)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_view,
                if (!allowed) "Overlay permission" else if (overlayPrefs.state.value.enabled) "Hide captions" else "Show captions", toggle).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_delete, "Stop", stop).build())
            .build()

        return notification
    }

    companion object {
        private val requests = AtomicLong()
        private const val ACTION_STOP = "com.asr.live.action.STOP"
        private const val ACTION_OVERLAY = "com.asr.live.action.OVERLAY"
        private const val CHANNEL_ID = "captions"
        private const val NOTIF_ID = 1
        fun start(ctx: Context, config: SessionConfig) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CaptionService::class.java)
                .putExtra("profile", config.profile.name).putExtra("performanceMode", config.performanceMode.name).putExtra("model", config.modelId)
                .putExtra("threads", config.threads).putExtra("correctionThreads", config.correctionThreads)
                .putExtra("gpuTranslation", config.gpuTranslation).putExtra("quality", config.quality.name)
                .putExtra("translationBatch", config.translationBatch).putExtra("translationUbatch", config.translationUbatch)
                .putExtra("qnn", config.qnn).putExtra("correction", config.correction).putExtra("glossary", config.glossary))
        }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, CaptionService::class.java).setAction(ACTION_STOP)) }
    }
}
