package com.screenshotuploader.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.screenshotuploader.MainActivity
import com.screenshotuploader.R
import com.screenshotuploader.data.model.Result
import com.screenshotuploader.data.repository.ImageRepository
import com.screenshotuploader.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class FloatingWindowService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private lateinit var imageRepository: ImageRepository

    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private var isTakingScreenshot = false
    private var isDestroyed = false

    // 用于显示Toast的Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val TAG = "FloatingWindowService"
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "screenshot_service_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        LogManager.i(TAG, "╔══════════════════════════════════════╗")
        LogManager.i(TAG, "║    FloatingWindowService.onCreate    ║")
        LogManager.i(TAG, "╚══════════════════════════════════════╝")
        LogManager.v(TAG, "SDK版本: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        LogManager.v(TAG, "设备制造商: ${Build.MANUFACTURER}")
        LogManager.v(TAG, "设备型号: ${Build.MODEL}")

        try {
            LogManager.step(TAG, 1, "获取WindowManager")
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            LogManager.v(TAG, "WindowManager获取成功")

            LogManager.step(TAG, 2, "初始化ImageRepository")
            imageRepository = ImageRepository.getInstance(this)
            LogManager.v(TAG, "ImageRepository初始化成功")

            initScreenParams()
            createNotificationChannel()
            LogManager.step(TAG, 3, "✓ Service创建完成")
        } catch (e: Exception) {
            LogManager.e(TAG, "Service创建失败", e)
            LogManager.captureException(TAG, e)
        }
    }

    private fun initScreenParams() {
        LogManager.v(TAG, "获取屏幕参数...")
        try {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
            LogManager.i(TAG, "屏幕: ${screenWidth}x${screenHeight}, 密度: $screenDensity")
            LogManager.v(TAG, "XDPI: ${metrics.xdpi}, YDPI: ${metrics.ydpi}")
        } catch (e: Exception) {
            LogManager.e(TAG, "获取屏幕参数失败", e)
            throw e
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogManager.i(TAG, "═══════════════════════════════════════")
        LogManager.i(TAG, " onStartCommand")
        LogManager.i(TAG, "═══════════════════════════════════════")
        LogManager.step(TAG, 10, "action: ${intent?.action}, flags: $flags, startId: $startId")

        when (intent?.action) {
            ACTION_START -> {
                handleStartAction(intent)
            }
            ACTION_STOP -> {
                LogManager.i(TAG, "收到停止指令")
                stopSelf()
            }
            null -> {
                LogManager.w(TAG, "action为null")
            }
            else -> {
                LogManager.w(TAG, "未知的action: ${intent.action}")
            }
        }

        LogManager.d(TAG, "onStartCommand结束, 返回START_NOT_STICKY")
        return START_NOT_STICKY
    }

    private fun handleStartAction(intent: Intent) {
        LogManager.step(TAG, 11, "处理ACTION_START")
        try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -999)
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

            LogManager.step(TAG, 12, "接收参数: resultCode=$resultCode")
            LogManager.d(TAG, "  RESULT_OK常量: ${Activity.RESULT_OK}")
            LogManager.d(TAG, "  resultData: ${resultData ?: "null"}")

            if (resultCode == -999) {
                LogManager.e(TAG, "错误：resultCode未传递！")
                showToastSafe("启动参数错误，请重新启动")
                stopSelf()
                return
            }

            if (resultCode != Activity.RESULT_OK) {
                LogManager.e(TAG, "resultCode无效: $resultCode != ${Activity.RESULT_OK}")
                showToastSafe("屏幕录制权限被拒绝")
                stopSelf()
                return
            }

            if (resultData == null) {
                LogManager.e(TAG, "resultData为null")
                showToastSafe("启动参数缺失")
                stopSelf()
                return
            }

            LogManager.step(TAG, 13, "参数验证通过，开始初始化")

            initMediaProjection(resultCode, resultData)
            startForegroundService()
            showFloatingWindow()

            LogManager.step(TAG, 20, "✓ 服务启动成功")
        } catch (e: SecurityException) {
            LogManager.e(TAG, "SecurityException - 权限被拒绝", e)
            LogManager.captureException(TAG, e)
            showToastSafe("权限被拒绝，请重新授权")
            stopSelf()
        } catch (e: Exception) {
            LogManager.e(TAG, "服务启动异常", e)
            LogManager.captureException(TAG, e)
            showToastSafe("服务启动失败: ${e.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        LogManager.v(TAG, "创建通知渠道...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = android.app.NotificationChannel(
                    CHANNEL_ID,
                    "截图服务通知",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "截图上传助手悬浮窗服务通知"
                }
                val notificationManager = getSystemService(android.app.NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
                LogManager.v(TAG, "通知渠道创建成功")
            } catch (e: Exception) {
                LogManager.e(TAG, "创建通知渠道失败", e)
            }
        }
    }

    private fun startForegroundService() {
        LogManager.step(TAG, 15, "启动前台服务...")
        try {
            val notificationIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("截图上传助手正在运行")
                .setContentText("点击悬浮按钮即可截图上传")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()

            startForeground(NOTIFICATION_ID, notification)
            LogManager.step(TAG, 16, "✓ 前台服务已启动, ID: $NOTIFICATION_ID")
        } catch (e: Exception) {
            LogManager.e(TAG, "启动前台服务失败", e)
            throw e
        }
    }

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        LogManager.step(TAG, 14, "初始化MediaProjection...")
        try {
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)

            if (mediaProjection == null) {
                LogManager.e(TAG, "获取MediaProjection返回null")
                throw IllegalStateException("无法获取MediaProjection")
            }
            LogManager.step(TAG, 15, "✓ MediaProjection初始化成功")
        } catch (e: Exception) {
            LogManager.e(TAG, "MediaProjection初始化失败", e)
            LogManager.captureException(TAG, e)
            throw e
        }
    }

    private fun showFloatingWindow() {
        LogManager.step(TAG, 17, "显示悬浮窗...")
        LogManager.d(TAG, "当前floatingView: $floatingView")
        LogManager.d(TAG, "isDestroyed: $isDestroyed")

        if (isDestroyed) {
            LogManager.w(TAG, "Service已销毁，跳过创建悬浮窗")
            return
        }

        if (floatingView != null) {
            LogManager.w(TAG, "悬浮窗已存在")
            return
        }

        try {
            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 200
            }
            LogManager.v(TAG, "LayoutParams创建完成, type: ${layoutParams.type}")

            floatingView = ImageView(this).apply {
                setImageResource(android.R.drawable.ic_menu_camera)
                setBackgroundResource(android.R.drawable.ic_menu_crop)
                elevation = 8f

                setOnClickListener {
                    LogManager.d(TAG, "悬浮窗被点击")
                    takeScreenshot()
                }

                setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            LogManager.v(TAG, "触摸开始: (${event.rawX}, ${event.rawY})")
                            initialX = layoutParams.x
                            initialY = layoutParams.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val newX = initialX + (event.rawX - initialTouchX).toInt()
                            val newY = initialY + (event.rawY - initialTouchY).toInt()
                            layoutParams.x = newX
                            layoutParams.y = newY
                            try {
                                windowManager.updateViewLayout(this, layoutParams)
                                LogManager.v(TAG, "位置更新: ($newX, $newY)")
                            } catch (e: Exception) {
                                LogManager.e(TAG, "更新悬浮窗位置失败", e)
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            LogManager.v(TAG, "触摸结束")
                            false
                        }
                        else -> false
                    }
                }
            }
            LogManager.v(TAG, "悬浮窗View创建完成")

            windowManager.addView(floatingView, layoutParams)
            floatingParams = layoutParams
            LogManager.step(TAG, 18, "✓ 悬浮窗显示成功, 位置: (${layoutParams.x}, ${layoutParams.y})")
        } catch (e: Exception) {
            LogManager.e(TAG, "显示悬浮窗失败", e)
            LogManager.captureException(TAG, e)
            showToastSafe("悬浮窗创建失败: ${e.message}")
            floatingView = null
        }
    }

    private fun takeScreenshot() {
        LogManager.step(TAG, 100, "开始截图...")

        if (isTakingScreenshot) {
            LogManager.w(TAG, "正在截图中，忽略本次请求")
            return
        }

        val projection = mediaProjection
        if (projection == null) {
            LogManager.e(TAG, "MediaProjection未初始化")
            showToastSafe("截图服务未初始化")
            return
        }

        isTakingScreenshot = true

        floatingView?.visibility = View.GONE
        LogManager.v(TAG, "悬浮窗已隐藏")

        mainHandler.postDelayed({
            try {
                performScreenshot(projection)
            } catch (e: Exception) {
                LogManager.e(TAG, "执行截图失败", e)
                LogManager.captureException(TAG, e)
                showToastSafe("截图失败: ${e.message}")
                isTakingScreenshot = false
                mainHandler.postDelayed({
                    floatingView?.visibility = View.VISIBLE
                }, 200)
            }
        }, 300)
    }

    private fun performScreenshot(projection: MediaProjection) {
        LogManager.step(TAG, 101, "执行截图 - 目标: ${screenWidth}x${screenHeight}")

        try {
            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                android.graphics.PixelFormat.RGBA_8888,
                2
            )
            LogManager.v(TAG, "ImageReader创建完成")

            virtualDisplay = projection.createVirtualDisplay(
                "ScreenshotDisplay",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
            LogManager.v(TAG, "VirtualDisplay创建完成")

            val handler = mainHandler
            imageReader?.setOnImageAvailableListener({ reader ->
                LogManager.step(TAG, 102, "图像可用回调")
                var image: Image? = null
                try {
                    image = reader.acquireLatestImage()

                    if (image != null) {
                        LogManager.d(TAG, "图像获取成功, planes: ${image.planes.size}")
                        val bitmap = imageToBitmap(image)
                        LogManager.d(TAG, "Bitmap转换: ${bitmap.width}x${bitmap.height}")
                        uploadScreenshot(bitmap)
                    } else {
                        LogManager.w(TAG, "图像为空")
                    }
                } catch (e: Exception) {
                    LogManager.e(TAG, "处理图像失败", e)
                    LogManager.captureException(TAG, e)
                    showToastSafe("截图处理失败")
                } finally {
                    image?.close()
                    LogManager.v(TAG, "图像资源已释放")
                    cleanupScreenshot()
                    isTakingScreenshot = false
                }
            }, handler)

            LogManager.v(TAG, "图像监听器已设置")

        } catch (e: Exception) {
            LogManager.e(TAG, "截图失败", e)
            LogManager.captureException(TAG, e)
            showToastSafe("截图失败")
            cleanupScreenshot()
            isTakingScreenshot = false
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        LogManager.v(TAG, "pixelStride: $pixelStride, rowStride: $rowStride, padding: $rowPadding")

        val width = screenWidth + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(
            width,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return bitmap
    }

    private fun uploadScreenshot(bitmap: Bitmap) {
        LogManager.step(TAG, 103, "开始上传截图...")
        serviceScope.launch {
            try {
                val result = imageRepository.uploadImage(bitmap)

                when (result) {
                    is Result.Success -> {
                        LogManager.step(TAG, 104, "✓ 上传成功")
                        withContext(Dispatchers.Main) {
                            showToastSafe("上传成功")
                        }
                    }
                    is Result.Error -> {
                        LogManager.e(TAG, "上传失败: ${result.message}")
                        withContext(Dispatchers.Main) {
                            showToastSafe("上传失败: ${result.message}")
                        }
                    }
                    else -> {
                        LogManager.w(TAG, "未知上传结果")
                    }
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "上传异常", e)
                LogManager.captureException(TAG, e)
                withContext(Dispatchers.Main) {
                    showToastSafe("上传异常: ${e.message}")
                }
            }
        }
    }

    private fun cleanupScreenshot() {
        LogManager.v(TAG, "清理截图资源...")
        try {
            virtualDisplay?.release()
            imageReader?.close()
            LogManager.v(TAG, "资源清理完成")
        } catch (e: Exception) {
            LogManager.e(TAG, "清理资源失败", e)
        } finally {
            virtualDisplay = null
            imageReader = null
        }

        mainHandler.postDelayed({
            try {
                floatingView?.visibility = View.VISIBLE
                LogManager.v(TAG, "悬浮窗已重新显示")
            } catch (e: Exception) {
                LogManager.e(TAG, "重新显示悬浮窗失败", e)
            }
        }, 500)
    }

    private fun showToastSafe(message: String) {
        mainHandler.post {
            try {
                if (!isDestroyed) {
                    Toast.makeText(this@FloatingWindowService, message, Toast.LENGTH_SHORT).show()
                    LogManager.v(TAG, "Toast已显示: $message")
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "显示Toast失败", e)
            }
        }
    }

    private fun hideFloatingWindow() {
        LogManager.v(TAG, "隐藏悬浮窗...")
        try {
            if (floatingView != null) {
                windowManager.removeView(floatingView)
                LogManager.v(TAG, "悬浮窗已移除")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "移除悬浮窗失败", e)
        } finally {
            floatingView = null
            floatingParams = null
        }
    }

    override fun onDestroy() {
        LogManager.i(TAG, "╔══════════════════════════════════════╗")
        LogManager.i(TAG, "║    FloatingWindowService.onDestroy   ║")
        LogManager.i(TAG, "╚══════════════════════════════════════╝")
        isDestroyed = true

        serviceScope.cancel()
        hideFloatingWindow()
        cleanupScreenshot()

        try {
            mediaProjection?.stop()
            LogManager.v(TAG, "MediaProjection已停止")
        } catch (e: Exception) {
            LogManager.e(TAG, "停止MediaProjection失败", e)
        } finally {
            mediaProjection = null
        }

        super.onDestroy()
        LogManager.i(TAG, "Service已销毁")
    }

    override fun onBind(intent: Intent?): IBinder? {
        LogManager.v(TAG, "onBind被调用")
        return null
    }
}
