package com.screenshotuploader.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.google.gson.Gson
import com.screenshotuploader.data.model.ApiError
import com.screenshotuploader.data.model.Result
import com.screenshotuploader.data.model.UploadResponse
import com.screenshotuploader.util.LogManager
import com.screenshotuploader.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class ImageRepository private constructor(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun uploadImage(bitmap: Bitmap): Result<UploadResponse> = withContext(Dispatchers.IO) {
        val TAG = "ImageRepository"
        LogManager.trace(TAG, "uploadImage", enter = true)

        try {
            val serverUrl = PreferencesManager.getServerUrl(context)
            val authToken = PreferencesManager.getAuthToken(context)

            LogManager.d(TAG, "服务器地址: $serverUrl")
            LogManager.v(TAG, "Token长度: ${authToken.length}")
            LogManager.d(TAG, "图片尺寸: ${bitmap.width}x${bitmap.height}, 大小: ${bitmap.byteCount / 1024}KB")

            if (serverUrl.isBlank()) {
                LogManager.e(TAG, "服务器地址为空")
                return@withContext Result.Error(400, "请先在设置中配置服务器地址")
            }

            if (authToken.isBlank()) {
                LogManager.e(TAG, "Token为空")
                return@withContext Result.Error(400, "请先在设置中配置Token")
            }

            LogManager.d(TAG, "保存图片到临时文件")
            val tempFile = saveBitmapToTempFile(bitmap)
            LogManager.v(TAG, "临时文件: ${tempFile.absolutePath}, 大小: ${tempFile.length() / 1024}KB")

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    tempFile.name,
                    tempFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()

            val uploadUrl = "$serverUrl/api/v1/images/upload"
            LogManager.d(TAG, "上传URL: $uploadUrl")

            val request = Request.Builder()
                .url(uploadUrl)
                .header("Authorization", "Bearer $authToken")
                .post(requestBody)
                .build()

            LogManager.v(TAG, "开始网络请求")
            val startTime = System.currentTimeMillis()

            val response = client.newCall(request).execute()

            val elapsed = System.currentTimeMillis() - startTime
            LogManager.d(TAG, "请求完成, 耗时: ${elapsed}ms, 状态码: ${response.code}")

            val responseBody = response.body?.string()
            LogManager.v(TAG, "响应体长度: ${responseBody?.length ?: 0}")

            tempFile.delete()
            LogManager.v(TAG, "临时文件已删除")

            if (response.isSuccessful && responseBody != null) {
                LogManager.d(TAG, "上传成功, 解析响应")
                LogManager.v(TAG, "响应内容: $responseBody")
                val uploadResponse = gson.fromJson(responseBody, UploadResponse::class.java)
                LogManager.i(TAG, "图片上传成功")
                Result.Success(uploadResponse)
            } else {
                LogManager.e(TAG, "上传失败, 状态码: ${response.code}")
                val errorMessage = if (responseBody != null) {
                    LogManager.v(TAG, "错误响应: $responseBody")
                    try {
                        val error = gson.fromJson(responseBody, ApiError::class.java)
                        error.message
                    } catch (e: Exception) {
                        responseBody
                    }
                } else {
                    "上传失败: ${response.code}"
                }
                LogManager.e(TAG, "错误消息: $errorMessage")
                Result.Error(response.code, errorMessage)
            }
        } catch (e: IOException) {
            LogManager.e(TAG, "网络IO异常", e)
            LogManager.captureException(TAG, e)
            Result.Error(500, "网络错误: ${e.message}")
        } catch (e: Exception) {
            LogManager.e(TAG, "上传异常", e)
            LogManager.captureException(TAG, e)
            Result.Error(500, "发生错误: ${e.message}")
        } finally {
            LogManager.trace(TAG, "uploadImage", enter = false)
        }
    }

    private fun saveBitmapToTempFile(bitmap: Bitmap): File {
        val TAG = "ImageRepository"
        LogManager.trace(TAG, "saveBitmapToTempFile", enter = true)

        val tempFile = File.createTempFile("screenshot_", ".jpg", context.cacheDir)
        LogManager.v(TAG, "创建临时文件: ${tempFile.absolutePath}")

        FileOutputStream(tempFile).use { out ->
            val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            LogManager.v(TAG, "压缩${if (compressed) "成功" else "失败"}")
        }

        LogManager.v(TAG, "文件大小: ${tempFile.length()} bytes")
        LogManager.trace(TAG, "saveBitmapToTempFile", enter = false)
        return tempFile
    }

    companion object {
        @Volatile
        private var instance: ImageRepository? = null

        fun getInstance(context: Context): ImageRepository {
            return instance ?: synchronized(this) {
                instance ?: ImageRepository(context.applicationContext).also {
                    instance = it
                    LogManager.d("ImageRepository", "创建实例")
                }
            }
        }
    }
}
