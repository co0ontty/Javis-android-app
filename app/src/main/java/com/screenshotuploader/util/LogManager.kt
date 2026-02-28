package com.screenshotuploader.util

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object LogManager {
    private const val TAG = "LogManager"
    private const val LOG_FILE_NAME = "screenshot_uploader.log"
    private const val MAX_LOG_LINES = 1000
    private const val NORMAL_LOG_LINES = 200

    private var logFile: File? = null
    private val logBuffer = ConcurrentLinkedQueue<String>()
    private var sessionStartTime: Long = 0
    private var debugMode = false

    fun init(context: Context) {
        sessionStartTime = System.currentTimeMillis()
        debugMode = PreferencesManager.isDebugMode(context)

        val logDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        logFile = File(logDir, LOG_FILE_NAME)

        // 清空旧日志文件
        try {
            if (logFile?.exists() == true) {
                logFile?.writeText("")
            } else {
                logFile?.parentFile?.mkdirs()
                logFile?.createNewFile()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear log file", e)
        }

        // 记录会话开始 - 立即刷新
        i(TAG, "=== 新会话开始 ===")
        i(TAG, "启动时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(sessionStartTime))}")
        i(TAG, "Debug模式: ${if (debugMode) "开启" else "关闭"}")
        i(TAG, "设备信息: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
        i(TAG, "最大日志行数: ${if (debugMode) MAX_LOG_LINES else NORMAL_LOG_LINES}")
        flush()
    }

    fun setDebugMode(enabled: Boolean) {
        debugMode = enabled
        i(TAG, "Debug模式已${if (enabled) "开启" else "关闭"}")
        flush()
    }

    fun isDebugMode(): Boolean = debugMode

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        addLog("D", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        addLog("I", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        addLog("W", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val fullMessage = if (throwable != null) {
            val stackTrace = Log.getStackTraceString(throwable)
            "$message\n$stackTrace"
        } else {
            message
        }
        addLog("E", tag, fullMessage)
    }

    fun v(tag: String, message: String) {
        if (debugMode) {
            Log.v(tag, message)
            addLog("V", tag, message)
        }
    }

    fun trace(tag: String, methodName: String, enter: Boolean = true) {
        if (debugMode) {
            val action = if (enter) "→→→ 进入" else "←←← 退出"
            addLog("T", tag, "$action $methodName")
        }
    }

    fun step(tag: String, step: Int, message: String) {
        val logMsg = "[$step] $message"
        Log.i(tag, logMsg)
        addLog("S", tag, logMsg)
    }

    fun captureException(tag: String, e: Throwable) {
        val stackTrace = Log.getStackTraceString(e)
        addLog("E", tag, "💥 异常捕获: ${e.javaClass.simpleName}\n$stackTrace")
    }

    private fun addLog(level: String, tag: String, message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date())
        val logLine = "[$timestamp] $level/$tag: $message"

        // 添加到内存缓冲区
        logBuffer.offer(logLine)

        // 限制日志行数
        val maxLines = if (debugMode) MAX_LOG_LINES else NORMAL_LOG_LINES
        while (logBuffer.size > maxLines) {
            logBuffer.poll()
        }

        // 写入文件
        try {
            logFile?.appendText("$logLine\n")
            // Debug模式或错误日志立即刷新
            if (debugMode || level == "E" || level == "W" || level == "S") {
                flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
        }
    }

    private fun flush() {
        try {
            logFile?.let { file ->
                val fos = java.io.FileOutputStream(file, true)
                fos.channel.force(true)
                fos.close()
            }
        } catch (e: Exception) {
            // 忽略
        }
    }

    fun getSessionLog(): String {
        flush()

        val sb = StringBuilder()
        sb.append("=== 截图上传助手日志 ===\n")
        sb.append("会话开始时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(sessionStartTime))}\n")
        sb.append("当前时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}\n")
        sb.append("运行时长: ${formatDuration(System.currentTimeMillis() - sessionStartTime)}\n")
        sb.append("Debug模式: ${if (debugMode) "开启" else "关闭"}\n")
        sb.append("日志条数: ${logBuffer.size}\n")
        sb.append("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n")
        logFile?.let {
            sb.append("日志文件: ${it.absolutePath}\n")
        }
        sb.append("========================\n\n")

        logBuffer.forEach { line ->
            sb.append(line)
            sb.append("\n")
        }

        return sb.toString()
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "${hours}小时${minutes % 60}分${seconds % 60}秒"
            minutes > 0 -> "${minutes}分${seconds % 60}秒"
            else -> "${seconds}秒"
        }
    }

    @Deprecated("Use getSessionLog() instead", ReplaceWith("getSessionLog()"))
    fun getLogContent(): String = getSessionLog()

    fun getLogFile(): File = logFile ?: File(Environment.getExternalStorageDirectory(), LOG_FILE_NAME)
}
