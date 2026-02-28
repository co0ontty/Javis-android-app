package com.screenshotuploader.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object LogExportHelper {

    /**
     * 导出日志文件
     * Android 11+ 需要_MANAGE_EXTERNAL_STORAGE权限才能通过File URI分享文件
     * 如果没有权限，则直接分享日志文本内容
     */
    fun exportLog(context: Context) {
        val logManager = LogManager

        // 获取日志文件
        val logFile = logManager.getLogFile()
        if (!logFile.exists()) {
            showToast(context, "日志文件不存在")
            return
        }

        // Android 11+ (API 30+) 需要特殊权限才能分享文件
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // 没有全部文件访问权限，直接分享日志文本内容
                shareLogAsText(context, logManager)
                showToast(context, "正在分享日志文本...")
                return
            }
        }

        // 有权限或Android 10及以下，尝试分享文件
        shareLogFile(context, logFile)
    }

    /**
     * 直接分享日志文本内容（不需要特殊权限）
     */
    private fun shareLogAsText(context: Context, logManager: LogManager) {
        try {
            val logContent = logManager.getSessionLog()
            if (logContent.isBlank()) {
                showToast(context, "日志内容为空")
                return
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "截图上传助手日志（当前会话）")
                putExtra(Intent.EXTRA_TEXT, logContent)
                // 添加时间戳作为标识
                val timestamp = System.currentTimeMillis()
                putExtra(Intent.EXTRA_TITLE, "screenshot_uploader_log_$timestamp.txt")
            }

            context.startActivity(Intent.createChooser(shareIntent, "分享日志"))
        } catch (e: Exception) {
            showToast(context, "分享日志失败: ${e.message}")
            logManager.e("LogExportHelper", "Failed to share log as text", e)
        }
    }

    /**
     * 分享日志文件（需要MANAGE_EXTERNAL_STORAGE权限）
     */
    private fun shareLogFile(context: Context, logFile: File) {
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "截图上传助手日志")
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(shareIntent, "分享日志文件"))
        } catch (e: Exception) {
            // 如果文件分享失败，回退到文本分享
            LogManager.e("LogExportHelper", "Failed to share log file, fallback to text", e)
            shareLogAsText(context, LogManager)
        }
    }

    /**
     * 打开存储权限设置页面
     */
    fun openStorageSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${context.packageName}")
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                // 部分设备可能不支持上述Intent，尝试通用设置页面
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:${context.packageName}")
                context.startActivity(intent)
            } catch (e2: Exception) {
                showToast(context, "无法打开存储权限设置")
            }
        }
    }

    /**
     * 检查是否有全部文件访问权限
     */
    @Suppress("UNUSED_PARAMETER")
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // Android 10及以下不需要MANAGE_EXTERNAL_STORAGE权限
        }
    }

    private fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
