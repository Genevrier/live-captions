package com.asr.live.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.asr.live.MainActivity
import com.asr.live.asr.AsrEngine
import com.asr.live.asr.EngineFactory
import com.asr.live.i18n.Languages
import com.asr.live.i18n.MlKitTranslator
import com.asr.live.model.EngineKind
import com.asr.live.model.ModelCatalog
import com.asr.live.model.ModelInfo
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground (type=microphone) service that owns the mic + recognizer (+ optional translator).
 * Audio is captured on one thread and decoded on another via a queue, so a slow decode never
 * stalls capture.
 */
class CaptionService : Service() {

    private val queue = ArrayBlockingQueue<FloatArray>(64)
    private val translationQueue = ArrayBlockingQueue<Pair<Long, String>>(16)
    private val poison = FloatArray(0)

    private var audio: com.asr.live.audio.AudioCapture? = null
    private var worker: Thread? = null
    private var translationWorker: Thread? = null
    private val droppedAudio = AtomicLong()

    @Volatile private var engine: AsrEngine? = null
    @Volatile private var translator: MlKitTranslator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        val info = ModelCatalog.byId(intent?.getStringExtra(EXTRA_MODEL_ID)) ?: ModelCatalog.DEFAULT
        val spoken = intent?.getStringExtra(EXTRA_SPOKEN) ?: "nl"
        val target = intent?.getStringExtra(EXTRA_TARGET) ?: "en"

        startForegroundNotification(info.displayName)
        startEngine(info, spoken, target)
        return START_NOT_STICKY
    }

    private fun startEngine(info: ModelInfo, spoken: String, target: String) {
        if (worker != null) return
        CaptionState.setError(null)

        // Whisper can translate straight to English itself; for any other target we transcribe
        // in the source language and translate the text with ML Kit.
        val whisperDirectEnglish = info.kind == EngineKind.WHISPER && target == "en"
        val whisperTask = if (whisperDirectEnglish) "translate" else "transcribe"
        val sourceForMlKit = if (info.isMultilingual) spoken else "en"
        val needMlKit = target != Languages.OFF && !whisperDirectEnglish && target != sourceForMlKit

        translator = if (needMlKit) MlKitTranslator(sourceForMlKit, target).also {
            if (!it.supported) {
                CaptionState.setError("Unsupported translation: $sourceForMlKit → $target")
                stopSelf()
                return
            }
        } else null

        engine = try {
            EngineFactory.create(
                applicationContext, info, language = spoken, whisperTask = whisperTask,
                onPartial = CaptionState::setPartial,
                onFinal = ::handleFinal,
            )
        } catch (t: Throwable) {
            CaptionState.setError("Couldn't load ${info.displayName}: ${t.message}")
            stopSelf()
            return
        }

        CaptionState.setRunning(true)
        if (translator != null) translationWorker = Thread(::translationLoop, "translation").also { it.start() }
        worker = Thread(::decodeLoop, "asr-decode").also { it.start() }

        audio = com.asr.live.audio.AudioCapture { chunk ->
            if (!queue.offer(chunk)) {
                queue.poll()
                queue.offer(chunk)
                droppedAudio.incrementAndGet()
                CaptionState.setStatus("Audio backlog: ${queue.size} chunks · dropped: ${droppedAudio.get()}")
            }
        }
        try {
            audio?.start()
        } catch (t: Throwable) {
            CaptionState.setError("Microphone error: ${t.message}")
            shutdown()
            stopSelf()
        }
    }

    /** Recognition never waits for translation. */
    private fun handleFinal(text: String) {
        if (translator != null) {
            val id = CaptionState.appendSource(text)
            if (!translationQueue.offer(id to text)) {
                translationQueue.poll()
                if (!translationQueue.offer(id to text)) CaptionState.setError("Translation backlog full")
            }
        } else {
            CaptionState.appendFinal(text)
        }
    }

    private fun translationLoop() {
        val tr = translator ?: return
        try {
            CaptionState.setStatus("Preparing on-device ML Kit translator…")
            tr.prepare()
            CaptionState.setStatus(null)
            while (!Thread.currentThread().isInterrupted) {
                val (id, text) = translationQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                try { CaptionState.applyTranslation(id, 1, tr.translate(text)) }
                catch (t: Throwable) { CaptionState.setError("Translation failed: ${t.message}") }
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            CaptionState.setError("Translation model unavailable: ${t.message}")
        }
    }

    private fun decodeLoop() {
        val e = engine ?: return
        try {
            while (true) {
                val chunk = queue.take()
                if (chunk === poison) break
                e.accept(chunk)
            }
            e.finish()
        } catch (_: InterruptedException) {
            // stopping
        } catch (t: Throwable) {
            CaptionState.setError("Recognition error: ${t.message}")
        }
    }

    private fun shutdown() {
        audio?.stop()
        audio = null
        queue.clear()
        queue.offer(poison)
        worker?.join(3000)
        if (worker?.isAlive == true) worker?.interrupt()
        worker = null
        engine?.release()
        engine = null
        translationWorker?.interrupt()
        translationWorker?.join(3000)
        translationWorker = null
        translationQueue.clear()
        translator?.close()
        translator = null
        CaptionState.setRunning(false)
        CaptionState.setPartial("")
        CaptionState.setStatus(null)
    }

    override fun onDestroy() {
        shutdown()
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
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_SPOKEN = "spoken"
        const val EXTRA_TARGET = "target"
        private const val ACTION_STOP = "com.asr.live.action.STOP"
        private const val CHANNEL_ID = "captions"
        private const val NOTIF_ID = 1

        fun start(ctx: Context, modelId: String, spoken: String, target: String) {
            val intent = Intent(ctx, CaptionService::class.java)
                .putExtra(EXTRA_MODEL_ID, modelId)
                .putExtra(EXTRA_SPOKEN, spoken)
                .putExtra(EXTRA_TARGET, target)
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptionService::class.java).setAction(ACTION_STOP))
        }
    }
}
