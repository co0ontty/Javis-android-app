package com.screenshotuploader.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.google.gson.Gson
import com.screenshotuploader.data.model.ApiError
import com.screenshotuploader.data.model.Result
import com.screenshotuploader.data.model.UploadResponse
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
        try {
            val serverUrl = PreferencesManager.getServerUrl(context)
            val authToken = PreferencesManager.getAuthToken(context)

            if (serverUrl.isBlank() || authToken.isBlank()) {
                return@withContext Result.Error(400, "请先在设置中配置服务器地址和Token")
            }

            val tempFile = saveBitmapToTempFile(bitmap)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    tempFile.name,
                    tempFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("$serverUrl/api/v1/images/upload")
                .header("Authorization", "Bearer $authToken")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            tempFile.delete()

            val responseBody = response.body?.string()
            
            if (response.isSuccessful && responseBody != null) {
                val uploadResponse = gson.fromJson(responseBody, UploadResponse::class.java)
                Result.Success(uploadResponse)
            } else {
                val errorMessage = if (responseBody != null) {
                    try {
                        val error = gson.fromJson(responseBody, ApiError::class.java)
                        error.message
                    } catch (e: Exception) {
                        responseBody
                    }
                } else {
                    "上传失败: ${response.code}"
                }
                Result.Error(response.code, errorMessage)
            }
        } catch (e: IOException) {
            Result.Error(500, "网络错误: ${e.message}")
        } catch (e: Exception) {
            Result.Error(500, "发生错误: ${e.message}")
        }
    }

    private fun saveBitmapToTempFile(bitmap: Bitmap): File {
        val tempFile = File.createTempFile("screenshot_", ".jpg", context.cacheDir)
        FileOutputStream(tempFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return tempFile
    }

    companion object {
        @Volatile
        private var instance: ImageRepository? = null

        fun getInstance(context: Context): ImageRepository {
            return instance ?: synchronized(this) {
                instance ?: ImageRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}