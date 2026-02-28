package com.screenshotuploader.data.model

data class UploadResponse(
    val code: Int,
    val message: String,
    val data: Map<String, Any>? = null
)
