package com.screenshotuploader.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.screenshotuploader.data.model.Result
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Request.Builder
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 版本更新管理器
 */
class UpdateManager(private val context: Context) {

    data class VersionInfo(
        val version: String,
        val versionCode: Int,
        val downloadUrl: String,
        val releaseDate: String,
        val description: String,
        val forceUpdate: Boolean,
        val fileSize: Long
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var currentVersionCode = 0
    private var currentVersionName = ""

    /**
     * 获取当前APP版本
     */
    init {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            currentVersionCode = packageInfo.versionCode
            currentVersionName = packageInfo.versionName
        } catch (e: Exception) {
            LogManager.e("UpdateManager", "获取APP版本失败", e)
        }
    }

    /**
     * 检查是否有新版本
     */
    suspend fun checkForUpdates(): Result<VersionInfo?> {
        LogManager.i("UpdateManager", "检查更新...")
        return try {
            val serverUrl = PreferencesManager.getServerUrl(context)
            if (serverUrl.isBlank()) {
                return Result.Error(400, "请先设置服务器地址")
            }

            val request = Request.Builder()
                .url("$serverUrl/api/v1/version")
                .header("Authorization", "Bearer ${PreferencesManager.getAuthToken(context)}")
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val code = json.getInt("code")
                    if (code == 200) {
                        val data = json.getJSONObject("data")
                        val latestVersion = VersionInfo(
                            version = data.getString("version"),
                            versionCode = data.getInt("versionCode"),
                            downloadUrl = data.getString("downloadUrl"),
                            releaseDate = data.getString("releaseDate"),
                            description = data.getString("description"),
                            forceUpdate = data.getBoolean("forceUpdate"),
                            fileSize = data.getLong("fileSize")
                        )

                        LogManager.i("UpdateManager", "最新版本: ${latestVersion.version} (code: ${latestVersion.versionCode}), 当前版本: $currentVersionName (code: $currentVersionCode)")

                        if (latestVersion.versionCode > currentVersionCode) {
                            LogManager.i("UpdateManager", "有新版本可用！")
                            return Result.Success(latestVersion)
                        } else {
                            LogManager.i("UpdateManager", "已是最新版本")
                            return Result.Success(null)
                        }
                    } else {
                        return Result.Error(code, json.getString("message"))
                    }
                } else {
                    return Result.Error(response.code, "检查更新失败: ${response.code}")
                }
            }
        } catch (e: IOException) {
            LogManager.e("UpdateManager", "网络请求失败", e)
            Result.Error(500, "网络错误: ${e.message}")
        } catch (e: Exception) {
            LogManager.e("UpdateManager", "检查更新失败", e)
            LogManager.captureException("UpdateManager", e)
            Result.Error(500, "检查更新失败: ${e.message}")
        }
    }

    /**
     * 下载并安装新版本APK
     */
    suspend fun downloadAndInstall(versionInfo: VersionInfo, activity: Activity? = null): Result<String> {
        return try {
            LogManager.i("UpdateManager", "开始下载APK...")

            val serverUrl = PreferencesManager.getServerUrl(context)
            val downloadUrl = if (versionInfo.downloadUrl.startsWith("http")) {
                versionInfo.downloadUrl
            } else {
                "$serverUrl${versionInfo.downloadUrl}"
            }

            // 创建临时下载目录
            val downloadDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates")
            downloadDir.mkdirs()

            val apkFile = File(downloadDir, "update_${versionInfo.version}.apk")

            // 下载APK
            val request = Request.Builder()
                .url(downloadUrl)
                .header("Authorization", "Bearer ${PreferencesManager.getAuthToken(context)}")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.Error(response.code, "下载失败: ${response.code}")
                }

                response.body?.byteStream()?.use { input ->
                    apkFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: return Result.Error(500, "下载失败: 响应体为空")
            }

            LogManager.i("UpdateManager", "APK下载完成: ${apkFile.absolutePath}, 大小: ${apkFile.length() / 1024}KB")

            // 安装APK
            installApk(apkFile, activity)
            Result.Success("下载并安装成功")
        } catch (e: Exception) {
            LogManager.e("UpdateManager", "下载或安装失败", e)
            LogManager.captureException("UpdateManager", e)
            Result.Error(500, "下载失败: ${e.message}")
        }
    }

    /**
     * 安装APK
     */
    private fun installApk(apkFile: File, activity: Activity?) {
        LogManager.i("UpdateManager", "开始安装APK...")

        if (activity != null) {
            // 使用 FileProvider 安装
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                }

                activity.startActivity(intent)
                LogManager.i("UpdateManager", "启动安装界面")
            } catch (e: Exception) {
                LogManager.e("UpdateManager", "启动安装失败", e)
                // 如果启动安装失败，尝试通过Intent.ACTION_VIEW
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                activity?.startActivity(intent)
            }
        } else {
            // 如果没有Activity，尝试广播Intent
            LogManager.w("UpdateManager", "无Activity，无法自动安装")
        }
    }

    /**
     * 格式化文件大小
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
            else -> "${bytes / (1024 * 1024 * 1024)}GB"
        }
    }

    companion object {
        const val TAG = "UpdateManager"
    }
}
