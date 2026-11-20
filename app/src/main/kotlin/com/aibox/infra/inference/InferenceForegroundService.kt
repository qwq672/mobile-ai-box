package com.aibox.infra.inference

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.aibox.MainActivity
import com.aibox.util.log.LogSystem

/**
 * 本地推理前台服务。
 *
 * 目的：切到后台 / 息屏后，系统不会因为进程优先级低而杀死推理，
 * 或冻结 CPU 导致流式输出中途停止。推理期间常驻一条通知，用户可见；
 * 推理结束自动 stopSelf。START_STICKY 让被系统回收后有机会重建。
 *
 * 这是一条轻量保活：不常驻、不自启，只在真正推理时存在。
 */
class InferenceForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        startAsForeground()
        return START_STICKY
    }

    private fun startAsForeground() {
        try {
            createChannelIfNeeded()
            // Android 14+（target 34）要求声明类型的前台服务在启动时带上类型，
            // 否则抛 MissingForegroundServiceTypeException。用 ServiceCompat 兼容各版本。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            LogSystem.info(TAG, "推理前台服务已启动")
        } catch (t: Throwable) {
            // 通知权限被拒 / Android 版本限制时不能让推理本身失败。
            LogSystem.warn(TAG, "startForeground 失败，退化为普通 Service: ${t.message}")
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "AI 推理",
                    NotificationManager.IMPORTANCE_LOW   // 低重要性：不弹横幅、不响铃
                ).apply { description = "本地模型正在后台生成回复" }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(this, 0, launchIntent, piFlags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("AI 正在后台回复")
            .setContentText("正在生成，点按返回对话")
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    companion object {
        private const val TAG = "aibox.inference.fgs"
        private const val CHANNEL_ID = "aibox_inference"
        private const val NOTIFICATION_ID = 0x51A0
        private const val ACTION_STOP = "com.aibox.inference.STOP"

        /** 开始推理：显示常驻通知并提升进程优先级。 */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, InferenceForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { LogSystem.warn(TAG, "start 前台服务失败: ${it.message}") }
        }

        /** 推理结束：移除通知并停止服务。 */
        fun stop(context: Context) {
            runCatching {
                val intent = Intent(context, InferenceForegroundService::class.java).setAction(ACTION_STOP)
                context.startService(intent)
            }.onFailure { LogSystem.warn(TAG, "stop 前台服务失败: ${it.message}") }
        }
    }
}
