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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class CaptionService : Service() {
    private val control = Executors.newSingleThreadExecutor()
    @Volatile private var displayedGeneration = 0L
    @Volatile private var current: CaptionSession? = null
    @Volatile private var destroyed = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val generation = requests.incrementAndGet()
        if (intent?.action == ACTION_STOP || intent == null) {
            val stoppedGeneration = displayedGeneration
            CaptionState.cancel(stoppedGeneration)
            current?.cancel()
            control.execute {
                current?.join(); current = null
                if (generation == requests.get()) {
                    CaptionState.stopped(stoppedGeneration)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }
            return START_NOT_STICKY
        }
        val config = SessionConfig(
            profile = Profile.fromId(intent.getStringExtra("profile")),
            modelId = intent.getStringExtra("model") ?: ModelCatalog.DEFAULT.id,
            threads = intent.getIntExtra("threads", 6).coerceIn(1, 8),
            quality = TranslationQuality.entries.firstOrNull { it.name == intent.getStringExtra("quality") } ?: TranslationQuality.HY_Q8,
            correction = intent.getBooleanExtra("correction", false),
            glossary = intent.getStringExtra("glossary") ?: "",
        )
        startForegroundNotification(ModelCatalog.byId(config.modelId)?.displayName ?: "Loading captions")
        displayedGeneration = generation
        CaptionState.begin(generation, config, ModelCatalog.byId(config.modelId)?.shortName ?: "Nemotron")
        current?.cancel()
        control.execute {
            current?.join(); current = null
            if (destroyed || generation != requests.get()) return@execute
            val session = CaptionSession(applicationContext, generation, config) { message ->
                CaptionState.error(generation, message)
                CaptionState.cancel(generation)
                if (!destroyed) control.execute {
                    if (generation == requests.get()) {
                        current?.cancel(); current?.join(); current = null
                        CaptionState.stopped(generation)
                        stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId)
                    }
                }
            }
            current = session
            if (destroyed || generation != requests.get()) session.cancel() else session.start()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        destroyed = true; requests.incrementAndGet()
        current?.let { CaptionState.cancel(it.generation); it.cancel() }
        control.execute { current?.let { it.join(); CaptionState.stopped(it.generation) }; current = null }
        control.shutdown()
        super.onDestroy()
    }
    private fun startForegroundNotification(modelName: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Live captions", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        @Suppress("DEPRECATION") // int-icon Action.Builder keeps us off the icons dependency
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Listening")
            .setContentText(modelName)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_delete, "Stop", stop).build())
            .build()

        ServiceCompat.startForeground(
            this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    companion object {
        private val requests = AtomicLong()
        private const val ACTION_STOP = "com.asr.live.action.STOP"
        private const val CHANNEL_ID = "captions"
        private const val NOTIF_ID = 1
        fun start(ctx: Context, config: SessionConfig) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CaptionService::class.java)
                .putExtra("profile", config.profile.name).putExtra("model", config.modelId)
                .putExtra("threads", config.threads).putExtra("quality", config.quality.name)
                .putExtra("correction", config.correction).putExtra("glossary", config.glossary))
        }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, CaptionService::class.java).setAction(ACTION_STOP)) }
    }
}
