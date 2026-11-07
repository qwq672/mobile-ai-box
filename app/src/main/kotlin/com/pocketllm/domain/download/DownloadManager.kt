package com.pocketllm.domain.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

/**
 * 下载管理器：支持断点续传。
 *
 * 流程：
 * 1. UI 调 download(repo, path, destFile, source)
 * 2. 创建 DownloadTask 进 DB
 * 3. 调度执行（OkHttp Range 请求 + RandomAccessFile 续写）
 * 4. 进度通过 StateFlow 推到 UI
 */
class DownloadManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress: StateFlow<Map<String, Int>> = _progress.asStateFlow()

    suspend fun download(
        url: String,
        destFile: File,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            // 断点续传：检查已下载大小
            val downloaded = if (destFile.exists()) destFile.length() else 0L

            val req = Request.Builder().url(url).apply {
                if (downloaded > 0) header("Range", "bytes=$downloaded-")
            }.build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("HTTP ${resp.code}")

            val total = resp.body?.contentLength()?.let { it + downloaded } ?: -1L
            val raf = RandomAccessFile(destFile, "rw")
            raf.seek(downloaded)

            resp.body?.byteStream()?.use { ins ->
                val buf = ByteArray(1 shl 16)  // 64KB
                var read: Int
                var sum = downloaded
                while (ins.read(buf).also { read = it } != -1) {
                    raf.write(buf, 0, read)
                    sum += read
                    if (total > 0) {
                        val p = (sum * 100 / total).toInt().coerceIn(0, 100)
                        _progress.value = _progress.value + (destFile.name to p)
                        onProgress(p)
                    }
                }
            }
            raf.close()
            destFile
        }
    }

    companion object {
        const val CHANNEL_ID = "pocketllm_download"
        const val NOTIF_ID = 1001

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val ch = NotificationChannel(CHANNEL_ID, "模型下载", NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(ch)
            }
        }
    }
}

/** 占位的 Foreground Service，WorkManager 调度下载时挂前台 */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DownloadManager.ensureChannel(this)
        val notif = NotificationCompat.Builder(this, DownloadManager.CHANNEL_ID)
            .setContentTitle("PocketLLM")
            .setContentText("正在下载模型...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        startForeground(DownloadManager.NOTIF_ID, notif)
        return START_STICKY
    }
}
