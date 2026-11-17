package com.aibox.domain.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** 单次下载的进度快照。 */
data class DownloadProgress(
    val percent: Int,
    val bytesDownloaded: Long,
    /** -1 = 服务器没给总大小 */
    val totalBytes: Long,
    val bytesPerSecond: Long
)

/** 用户主动暂停（不是失败）。 */
class DownloadPausedException : IOException("paused")

/**
 * 下载管理器：支持断点续传、暂停、停止。
 *
 * - 续传：读取目标文件已有长度，带 `Range: bytes=N-` 请求；
 * - 若服务器忽略 Range 返回 200，会清空文件重新下载，避免写坏文件；
 * - 暂停 = 取消当前 Call 并保留已下载的临时文件；
 * - 停止 = 取消 Call，由调用方删除文件。
 */
class DownloadManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress: StateFlow<Map<String, Int>> = _progress.asStateFlow()

    /** 正在跑的请求：key = 目标文件绝对路径。 */
    private val activeCalls = ConcurrentHashMap<String, Call>()

    /** 暂停某个下载（保留临时文件，后续可续传）。 */
    fun pause(destFile: File) {
        activeCalls.remove(destFile.absolutePath)?.cancel()
    }

    /** 取消某个下载（不删文件，文件清理由调用方决定）。 */
    fun cancel(destFile: File) {
        activeCalls.remove(destFile.absolutePath)?.cancel()
    }

    suspend fun download(
        url: String,
        destFile: File,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val key = destFile.absolutePath
        var call: Call? = null
        try {
            var downloaded = if (destFile.exists()) destFile.length() else 0L
            var req = Request.Builder().url(url).apply {
                if (downloaded > 0) header("Range", "bytes=$downloaded-")
            }.build()
            call = client.newCall(req)
            activeCalls[key] = call
            var resp = call.execute()

            // 服务器不支持 Range：必须清空重下，否则会从偏移追加导致文件损坏
            if (downloaded > 0 && resp.code == 200) {
                resp.close()
                destFile.delete()
                downloaded = 0L
                req = Request.Builder().url(url).build()
                call = client.newCall(req)
                activeCalls[key] = call
                resp = call.execute()
            }
            if (!resp.isSuccessful) error("HTTP ${resp.code}")

            val body = resp.body ?: error("响应为空")
            val total = body.contentLength().takeIf { it > 0 }?.let { it + downloaded } ?: -1L
            var sum = downloaded
            RandomAccessFile(destFile, "rw").use { raf ->
                raf.seek(downloaded)
                body.byteStream().use { ins ->
                    val buf = ByteArray(64 * 1024)
                    var windowStart = System.nanoTime()
                    var windowBytes = 0L
                    var speed = 0L
                    while (true) {
                        val read = try {
                            ins.read(buf)
                        } catch (e: IOException) {
                            if (call.isCanceled()) throw DownloadPausedException() else throw e
                        }
                        if (read == -1) break
                        raf.write(buf, 0, read)
                        sum += read
                        windowBytes += read
                        val now = System.nanoTime()
                        if (now - windowStart >= 1_000_000_000L) {
                            speed = windowBytes * 1_000_000_000L / (now - windowStart)
                            windowStart = now
                            windowBytes = 0L
                        }
                        val p = if (total > 0) {
                            (sum * 100 / total).toInt().coerceIn(0, 100)
                        } else 0
                        _progress.value = _progress.value + (destFile.name to p)
                        onProgress(DownloadProgress(p, sum, total, speed))
                    }
                }
            }
            Result.success(destFile)
        } catch (e: DownloadPausedException) {
            Result.failure(e)
        } catch (e: Exception) {
            if (call?.isCanceled() == true) Result.failure(DownloadPausedException())
            else Result.failure(e)
        } finally {
            activeCalls.remove(key)
        }
    }

    companion object {
        const val CHANNEL_ID = "aibox_download"
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
            .setContentTitle("AIBox")
            .setContentText("正在下载模型...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        startForeground(DownloadManager.NOTIF_ID, notif)
        return START_STICKY
    }
}
