package com.didyar.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class BatchDescriptionStatus(
    val running: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val lastSceneIndex: Int = -1,
    val message: String = "",
    val error: String = "",
    val updateToken: Long = 0L
)

object BatchStatusStore {
    private const val PREFS = "didyar_batch_status"

    fun load(context: Context): BatchDescriptionStatus {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BatchDescriptionStatus(
            running = p.getBoolean("running", false),
            completed = p.getInt("completed", 0),
            total = p.getInt("total", 0),
            lastSceneIndex = p.getInt("lastSceneIndex", -1),
            message = p.getString("message", "").orEmpty(),
            error = p.getString("error", "").orEmpty(),
            updateToken = p.getLong("updateToken", 0L)
        )
    }

    fun write(
        context: Context,
        running: Boolean,
        completed: Int,
        total: Int,
        lastSceneIndex: Int,
        message: String,
        error: String = ""
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("running", running)
            .putInt("completed", completed)
            .putInt("total", total)
            .putInt("lastSceneIndex", lastSceneIndex)
            .putString("message", message)
            .putString("error", error)
            .putLong("updateToken", System.currentTimeMillis())
            .apply()
    }
}

class BatchDescriptionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var stopRequested = false
    @Volatile private var processing = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            val current = BatchStatusStore.load(this)
            BatchStatusStore.write(
                context = this,
                running = current.running,
                completed = current.completed,
                total = current.total,
                lastSceneIndex = current.lastSceneIndex,
                message = "پس از پایان درخواست جاری، پردازش متوقف می‌شود."
            )
            updateNotification(
                "در حال توقف پس از درخواست جاری",
                current.completed,
                current.total
            )
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification("در حال آماده‌سازی توضیحات", 0, 0)
        )

        if (!processing) {
            processing = true
            stopRequested = false
            serviceScope.launch {
                processPendingScenes()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun processPendingScenes() {
        val project = ProjectStore.load(this)
        val apiKey = SecureSecretStore.load(this, "avalai_api_key").orEmpty().trim()

        if (project == null) {
            finishWithError("پروژهٔ ذخیره‌شده‌ای برای پردازش پیدا نشد.")
            return
        }

        if (apiKey.isBlank()) {
            finishWithError("کلید AvalAI پیدا نشد.")
            return
        }

        val scenes = project.scenes.toMutableList()
        val pendingIndices = scenes.indices.filter { scenes[it].description.isBlank() }

        if (pendingIndices.isEmpty()) {
            BatchStatusStore.write(
                this,
                running = false,
                completed = 0,
                total = 0,
                lastSceneIndex = -1,
                message = "همهٔ صحنه‌ها از قبل توضیح دارند."
            )
            updateNotification("همهٔ صحنه‌ها توضیح دارند", 0, 0, finished = true)
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }

        val total = pendingIndices.size
        BatchStatusStore.write(
            this,
            running = true,
            completed = 0,
            total = total,
            lastSceneIndex = -1,
            message = "توضیح‌دار کردن $total صحنه در پس‌زمینه آغاز شد."
        )
        updateNotification("۰ از $total صحنه آماده شده", 0, total)

        var completed = 0
        var lastSceneIndex = -1

        try {
            for ((position, sceneIndex) in pendingIndices.withIndex()) {
                if (stopRequested) break

                val scene = scenes[sceneIndex]
                val nextSceneStart =
                    scenes.getOrNull(sceneIndex + 1)?.timeMs ?: project.durationMs

                val workingMessage =
                    "در حال توضیح صحنه ${scene.index}؛ ${position + 1} از $total"
                BatchStatusStore.write(
                    this,
                    running = true,
                    completed = completed,
                    total = total,
                    lastSceneIndex = lastSceneIndex,
                    message = workingMessage
                )
                updateNotification(workingMessage, completed, total)

                val frames = SceneFrameExtractor.extractBase64Jpegs(
                    context = this,
                    uri = project.uri,
                    startMs = scene.timeMs,
                    endMs = nextSceneStart
                )

                val description = AvalAiVisionProvider.describeScene(
                    apiKey = apiKey,
                    framesBase64 = frames,
                    sceneIndex = scene.index,
                    sceneTotal = scenes.size,
                    timecode = formatTime(scene.timeMs)
                )

                scenes[sceneIndex] = scene.copy(description = description)
                ProjectStore.save(
                    context = this,
                    uri = project.uri,
                    fileName = project.fileName,
                    durationMs = project.durationMs,
                    scenes = scenes
                )

                completed = position + 1
                lastSceneIndex = sceneIndex
                val progressMessage =
                    "صحنه ${scene.index} آماده شد؛ $completed از $total"
                BatchStatusStore.write(
                    this,
                    running = true,
                    completed = completed,
                    total = total,
                    lastSceneIndex = lastSceneIndex,
                    message = progressMessage
                )
                updateNotification(progressMessage, completed, total)

                if (position < pendingIndices.lastIndex && !stopRequested) {
                    delay(12_000)
                }
            }

            val finalMessage = if (stopRequested) {
                "پردازش متوقف شد؛ $completed از $total صحنه آماده شد."
            } else {
                "توضیح‌دار کردن کامل شد؛ $completed صحنه آماده شد."
            }

            BatchStatusStore.write(
                this,
                running = false,
                completed = completed,
                total = total,
                lastSceneIndex = lastSceneIndex,
                message = finalMessage
            )
            updateNotification(finalMessage, completed, total, finished = true)
        } catch (e: Exception) {
            val sceneText = if (lastSceneIndex >= 0) {
                "آخرین صحنهٔ کامل‌شده: ${lastSceneIndex + 1}\n"
            } else {
                ""
            }
            val details =
                "نسخه دیدیار: ۰٫۵\n" +
                    sceneText +
                    "نوع خطا: ${e::class.java.simpleName}\n" +
                    "پیام: ${e.message ?: "خطای نامشخص"}"

            BatchStatusStore.write(
                this,
                running = false,
                completed = completed,
                total = total,
                lastSceneIndex = lastSceneIndex,
                message = "توضیح‌دار کردن متوقف شد. جزئیات خطا داخل برنامه ذخیره شده است.",
                error = details
            )
            updateNotification(
                "پردازش با خطا متوقف شد؛ $completed از $total",
                completed,
                total,
                finished = true
            )
        } finally {
            processing = false
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun finishWithError(message: String) {
        val details =
            "نسخه دیدیار: ۰٫۵\n" +
                "نوع خطا: ProjectState\n" +
                "پیام: $message"
        BatchStatusStore.write(
            this,
            running = false,
            completed = 0,
            total = 0,
            lastSceneIndex = -1,
            message = message,
            error = details
        )
        updateNotification(message, 0, 0, finished = true)
        processing = false
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "پردازش توضیح صوتی دیدیار",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "پیشرفت توضیح‌دار کردن صحنه‌های فیلم"
            }
        )
    }

    private fun buildNotification(
        text: String,
        completed: Int,
        total: Int,
        finished: Boolean = false
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("دیدیار")
            .setContentText(text)
            .setContentIntent(openPendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished)
            .setAutoCancel(finished)

        if (total > 0) {
            builder.setProgress(total, completed.coerceIn(0, total), false)
        }

        if (!finished) {
            val stopIntent = Intent(this, BatchDescriptionService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                this,
                2,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "توقف پس از درخواست جاری",
                    stopPendingIntent
                ).build()
            )
        }

        return builder.build()
    }

    private fun updateNotification(
        text: String,
        completed: Int,
        total: Int,
        finished: Boolean = false
    ) {
        getSystemService(NotificationManager::class.java)
            .notify(
                NOTIFICATION_ID,
                buildNotification(text, completed, total, finished)
            )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "didyar_batch_descriptions"
        private const val NOTIFICATION_ID = 4105
        private const val ACTION_START = "com.didyar.app.action.START_BATCH"
        private const val ACTION_STOP = "com.didyar.app.action.STOP_BATCH"

        fun start(context: Context) {
            val intent = Intent(context, BatchDescriptionService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BatchDescriptionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
