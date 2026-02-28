package com.screenshotuploader

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.screenshotuploader.service.FloatingWindowService
import com.screenshotuploader.ui.theme.ScreenshotUploaderTheme
import com.screenshotuploader.util.LogExportHelper
import com.screenshotuploader.util.LogManager
import com.screenshotuploader.util.PreferencesManager
import com.screenshotuploader.util.UpdateManager
import com.screenshotuploader.data.model.Result as ApiResult
import kotlinx.coroutines.launch

/**
 * 权限状态数据类
 */
data class PermissionStatus(
    val name: String,
    val description: String,
    val granted: Boolean,
    val action: PermissionAction
)

sealed class PermissionAction {
    data object None : PermissionAction()
    data object Overlay : PermissionAction()
    data object Notifications : PermissionAction()
    data object MediaProjection : PermissionAction()
}

class MainActivity : ComponentActivity() {

    // 服务运行状态 - 真实状态
    private var actualServiceRunning = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        LogManager.step("MainActivity", 1, "权限请求回调")
        LogManager.i("MainActivity", "Permissions result: $permissions")
        permissions.forEach { (permission, granted) ->
            LogManager.v("MainActivity", "  $permission -> $granted")
        }
        val allGranted = permissions.entries.all { it.value }
        LogManager.step("MainActivity", 2, "所有权限已授予: $allGranted")

        if (allGranted) {
            LogManager.step("MainActivity", 3, "权限全部授予，继续启动流程")
            checkOverlayPermissionAndStartService()
        } else {
            val denied = permissions.entries.filter { !it.value }.map { it.key }
            LogManager.e("MainActivity", "以下权限被拒绝: $denied")
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        LogManager.step("MainActivity", 4, "悬浮窗权限回调")
        LogManager.d("MainActivity", "Result code: ${result.resultCode}")
        val hasPermission = Settings.canDrawOverlays(this)
        LogManager.step("MainActivity", 5, "当前悬浮窗权限状态: $hasPermission")

        if (hasPermission) {
            LogManager.i("MainActivity", "悬浮窗权限已授予")
            requestMediaProjection()
        } else {
            LogManager.e("MainActivity", "悬浮窗权限被拒绝")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        LogManager.d("MainActivity", "通知权限回调: granted=$granted")
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        LogManager.step("MainActivity", 6, "MediaProjection权限回调")
        LogManager.d("MainActivity", "Result code: ${result.resultCode}, RESULT_OK: $RESULT_OK")

        if (result.resultCode == RESULT_OK) {
            LogManager.i("MainActivity", "MediaProjection权限已授予")
            LogManager.d("MainActivity", "ResultData: ${result.data}")

            try {
                LogManager.step("MainActivity", 7, "准备启动FloatingWindowService")
                val intent = Intent(this, FloatingWindowService::class.java).apply {
                    action = FloatingWindowService.ACTION_START
                    putExtra(FloatingWindowService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(FloatingWindowService.EXTRA_RESULT_DATA, result.data)
                }
                LogManager.step("MainActivity", 8, "Intent已创建: action=${intent.action}, resultCode=${result.resultCode}")

                actualServiceRunning = true
                ContextCompat.startForegroundService(this, intent)
                LogManager.step("MainActivity", 9, "✓ FloatingWindowService启动调用成功")
            } catch (e: Exception) {
                LogManager.e("MainActivity", "启动服务失败", e)
                LogManager.captureException("MainActivity", e)
                actualServiceRunning = false
            }
        } else {
            LogManager.e("MainActivity", "MediaProjection权限被拒绝, resultCode: ${result.resultCode}")
            actualServiceRunning = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置全局异常处理器
        setupExceptionHandler()

        LogManager.i("MainActivity", "╔══════════════════════════════════════╗")
        LogManager.i("MainActivity", "║      MainActivity.onCreate        ║")
        LogManager.i("MainActivity", "╚══════════════════════════════════════╝")
        LogManager.d("MainActivity", "设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        LogManager.d("MainActivity", "Android SDK: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        LogManager.d("MainActivity", "包名: $packageName")

        try {
            setContent {
                ScreenshotUploaderTheme {
                    MainScreen(
                        onStartService = { startServiceWithPermissions() },
                        onStopService = { stopFloatingService() },
                        onExportLog = { exportLog() },
                        onGrantOverlay = { grantOverlayPermission() },
                        onGrantNotification = { grantNotificationPermission() },
                        onCheckUpdate = { checkForUpdate() },
                        isServiceRunning = { actualServiceRunning },
                        setServiceRunning = { actualServiceRunning = it }
                    )
                }
            }
            LogManager.i("MainActivity", "UI设置完成")
        } catch (e: Exception) {
            LogManager.e("MainActivity", "设置UI失败", e)
            LogManager.captureException("MainActivity", e)
            throw e
        }
    }

    override fun onResume() {
        super.onResume()
        LogManager.v("MainActivity", "onResume")
    }

    override fun onPause() {
        super.onPause()
        LogManager.v("MainActivity", "onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        LogManager.i("MainActivity", "onDestroy")
    }

    private fun setupExceptionHandler() {
        LogManager.i("MainActivity", "设置全局异常处理器")
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogManager.e("UncaughtException", "╔══════════════════════════════════════╗")
            LogManager.e("UncaughtException", "║          💥 发生未捕获异常            ║")
            LogManager.e("UncaughtException", "╚══════════════════════════════════════╝")
            LogManager.e("UncaughtException", "线程: ${thread.name} (id: ${thread.id})")
            LogManager.e("UncaughtException", "线程状态: ${thread.state}")
            LogManager.e("UncaughtException", "异常类型: ${throwable.javaClass.name}")
            LogManager.e("UncaughtException", "异常消息: ${throwable.message}")
            LogManager.captureException("UncaughtException", throwable)

            // 调用默认处理器
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun exportLog() {
        LogManager.i("MainActivity", "导出日志")
        try {
            LogExportHelper.exportLog(this)
            LogManager.i("MainActivity", "日志导出成功")
        } catch (e: Exception) {
            LogManager.e("MainActivity", "导出日志失败", e)
            LogManager.captureException("MainActivity", e)
        }
    }

    private fun startServiceWithPermissions() {
        LogManager.i("MainActivity", "═══════════════════════════════════════")
        LogManager.step("MainActivity", 0, "点击【启动悬浮窗服务】按钮")
        LogManager.i("MainActivity", "开始启动服务流程")
        LogManager.i("MainActivity", "═══════════════════════════════════════")

        // 检查悬浮窗权限
        val hasOverlay = Settings.canDrawOverlays(this)
        LogManager.d("MainActivity", "悬浮窗权限检查: $hasOverlay")

        if (!hasOverlay) {
            LogManager.w("MainActivity", "需要悬浮窗权限，跳转到设置")
            grantOverlayPermission()
            return
        }

        // 检查其他权限
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            val hasNotif = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            LogManager.d("MainActivity", "通知权限: $hasNotif")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
        }

        LogManager.d("MainActivity", "需要请求的权限: $permissions")

        if (permissions.isEmpty()) {
            LogManager.d("MainActivity", "无需额外权限，直接启动")
            checkOverlayPermissionAndStartService()
        } else {
            LogManager.d("MainActivity", "请求运行时权限")
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun checkOverlayPermissionAndStartService() {
        LogManager.d("MainActivity", "再次检查悬浮窗权限")
        if (Settings.canDrawOverlays(this)) {
            LogManager.i("MainActivity", "悬浮窗权限已确认，请求MediaProjection")
            requestMediaProjection()
        } else {
            LogManager.w("MainActivity", "悬浮窗权限仍被拒绝")
            grantOverlayPermission()
        }
    }

    private fun requestMediaProjection() {
        LogManager.i("MainActivity", "请求MediaProjection权限")
        try {
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            LogManager.d("MainActivity", "ScreenCaptureIntent已创建")
            mediaProjectionLauncher.launch(intent)
        } catch (e: Exception) {
            LogManager.e("MainActivity", "创建MediaProjection Intent失败", e)
            LogManager.captureException("MainActivity", e)
            actualServiceRunning = false
        }
    }

    private fun stopFloatingService() {
        LogManager.i("MainActivity", "停止悬浮窗服务")
        try {
            val intent = Intent(this, FloatingWindowService::class.java).apply {
                action = FloatingWindowService.ACTION_STOP
            }
            stopService(intent)
            actualServiceRunning = false
            LogManager.i("MainActivity", "服务停止请求已发送")
        } catch (e: Exception) {
            LogManager.e("MainActivity", "停止服务失败", e)
            LogManager.captureException("MainActivity", e)
        }
    }

    private fun grantOverlayPermission() {
        LogManager.i("MainActivity", "打开悬浮窗权限设置页面")
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            LogManager.e("MainActivity", "打开悬浮窗权限设置失败", e)
            LogManager.captureException("MainActivity", e)
        }
    }

    private fun grantNotificationPermission() {
        LogManager.i("MainActivity", "请求通知权限")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } catch (e: Exception) {
            LogManager.e("MainActivity", "请求通知权限失败", e)
            LogManager.captureException("MainActivity", e)
        }
    }

    /**
     * 检查版本更新
     */
    private fun checkForUpdate() {
        LogManager.i("MainActivity", "检查版本更新")
        val updateManager = UpdateManager(this)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        scope.launch {
            try {
                when (val result = updateManager.checkForUpdates()) {
                    is ApiResult.Success -> {
                        val versionInfo = result.data
                        if (versionInfo != null) {
                            LogManager.i("MainActivity", "发现新版本: ${versionInfo.version}")
                            showUpdateDialog(versionInfo, updateManager)
                        } else {
                            LogManager.i("MainActivity", "已是最新版本")
                            showNoUpdateDialog()
                        }
                    }
                    is ApiResult.Error -> {
                        LogManager.e("MainActivity", "检查更新失败: ${result.message}")
                        showUpdateErrorDialog(result.message)
                    }
                }
            } catch (e: Exception) {
                LogManager.e("MainActivity", "检查更新异常", e)
                LogManager.captureException("MainActivity", e)
                showUpdateErrorDialog(e.message ?: "未知错误")
            }
        }
    }

    private fun showUpdateDialog(versionInfo: UpdateManager.VersionInfo, updateManager: UpdateManager) {
        setContent {
            ScreenshotUploaderTheme {
                UpdateVersionDialog(
                    versionInfo = versionInfo,
                    onDismiss = { recreate() },
                    onConfirm = {
                        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
                        scope.launch {
                            when (val result = updateManager.downloadAndInstall(versionInfo, this@MainActivity)) {
                                is ApiResult.Success -> {
                                    LogManager.i("MainActivity", "下载成功")
                                }
                                is ApiResult.Error -> {
                                    LogManager.e("MainActivity", "下载失败: ${result.message}")
                                    showDownloadErrorDialog(result.message)
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    private fun showNoUpdateDialog() {
        setContent {
            ScreenshotUploaderTheme {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { recreate() },
                    title = { Text("检查更新") },
                    text = { Text("当前已是最新版本") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { recreate() }) {
                            Text("确定")
                        }
                    }
                )
            }
        }
    }

    private fun showUpdateErrorDialog(message: String) {
        setContent {
            ScreenshotUploaderTheme {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { recreate() },
                    title = { Text("检查更新失败") },
                    text = { Text(message) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { recreate() }) {
                            Text("确定")
                        }
                    }
                )
            }
        }
    }

    private fun showDownloadErrorDialog(message: String) {
        setContent {
            ScreenshotUploaderTheme {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { recreate() },
                    title = { Text("下载失败") },
                    text = { Text(message) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { recreate() }) {
                            Text("确定")
                        }
                    }
                )
            }
        }
    }
}

/**
 * 检查所有权限状态
 */
@Composable
fun checkAllPermissions(): List<PermissionStatus> {
    val context = LocalContext.current
    LogManager.v("PermissionCheck", "检查所有权限状态")

    val permissions = mutableListOf<PermissionStatus>()

    // 悬浮窗权限
    val hasOverlay = Settings.canDrawOverlays(context)
    permissions.add(
        PermissionStatus(
            name = "悬浮窗权限",
            description = "允许在其他应用上方显示悬浮按钮",
            granted = hasOverlay,
            action = if (hasOverlay) PermissionAction.None else PermissionAction.Overlay
        )
    )
    LogManager.v("PermissionCheck", "  悬浮窗: $hasOverlay")

    // 通知权限 (Android 13+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        permissions.add(
            PermissionStatus(
                name = "通知权限",
                description = "显示后台服务运行通知",
                granted = granted,
                action = if (granted) PermissionAction.None else PermissionAction.Notifications
            )
        )
        LogManager.v("PermissionCheck", "  通知: $granted")
    }

    // 前台服务权限 (Android 14+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        permissions.add(
            PermissionStatus(
                name = "前台服务权限",
                description = "允许后台屏幕录制服务",
                granted = granted,
                action = PermissionAction.None
            )
        )
        LogManager.v("PermissionCheck", "  前台服务: $granted")
    }

    return permissions
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onExportLog: () -> Unit,
    onGrantOverlay: () -> Unit = {},
    onGrantNotification: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    isServiceRunning: () -> Boolean = { false },
    setServiceRunning: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(PreferencesManager.getServerUrl(context)) }
    var authToken by remember { mutableStateOf(PreferencesManager.getAuthToken(context)) }
    var debugMode by remember { mutableStateOf(PreferencesManager.isDebugMode(context)) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }

    // 同步服务运行状态
    val serviceRunning = isServiceRunning()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("截图上传助手") }
            )
        }
    ) { paddingValues ->
        androidx.compose.material3.Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 服务器地址
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = {
                        serverUrl = it
                        scope.launch {
                            try {
                                PreferencesManager.saveServerUrl(context, it)
                                LogManager.d("MainScreen", "服务器地址: $it")
                            } catch (e: Exception) {
                                LogManager.e("MainScreen", "保存服务器地址失败", e)
                            }
                        }
                    },
                    label = { Text("服务器地址") },
                    placeholder = { Text("http://192.168.1.100:8898") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Token
                OutlinedTextField(
                    value = authToken,
                    onValueChange = {
                        authToken = it
                        scope.launch {
                            try {
                                PreferencesManager.saveAuthToken(context, it)
                                LogManager.d("MainScreen", "Token已更新")
                            } catch (e: Exception) {
                                LogManager.e("MainScreen", "保存Token失败", e)
                            }
                        }
                    },
                    label = { Text("访问令牌 (Token)") },
                    placeholder = { Text("输入你的Bearer Token") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Debug模式开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Debug模式", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "开启后记录详细日志",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = debugMode,
                        onCheckedChange = {
                            debugMode = it
                            LogManager.setDebugMode(it)
                            scope.launch {
                                try {
                                    PreferencesManager.saveDebugMode(context, it)
                                    LogManager.d("MainScreen", "Debug模式: $it")
                                } catch (e: Exception) {
                                    LogManager.e("MainScreen", "保存Debug模式失败", e)
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 启动/停止服务按钮
                if (!serviceRunning) {
                    Button(
                        onClick = {
                            LogManager.i("MainScreen", "点击【启动悬浮窗服务】按钮")
                            if (serverUrl.isBlank() || authToken.isBlank()) {
                                LogManager.w("MainScreen", "服务器地址或Token为空")
                            } else {
                                onStartService()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("启动悬浮窗服务")
                    }
                } else {
                    Button(
                        onClick = {
                            LogManager.i("MainScreen", "点击【停止悬浮窗服务】按钮")
                            onStopService()
                            setServiceRunning(false)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("停止悬浮窗服务")
                    }
                }

                // 检查权限按钮
                Button(
                    onClick = {
                        LogManager.i("MainScreen", "点击【检查权限】按钮")
                        refreshTrigger++
                        showPermissionDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text("检查权限")
                }

                // 导出日志按钮
                Button(
                    onClick = {
                        LogManager.i("MainScreen", "点击【导出日志】按钮, Debug: $debugMode")
                        onExportLog()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("导出日志${if (debugMode) " (详细)" else ""}")
                }

                // 检查更新按钮
                Button(
                    onClick = {
                        LogManager.i("MainScreen", "点击【检查更新】按钮")
                        onCheckUpdate()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3)
                    )
                ) {
                    Text("检查更新")
                }

                Spacer(modifier = Modifier.weight(1f))

                // 使用说明
                Text(
                    text = "使用说明：\n" +
                            "1. 设置服务器地址和访问令牌\n" +
                            "2. 点击「启动悬浮窗服务」\n" +
                            "3. 授予所需权限\n" +
                            "4. 点击悬浮按钮即可截图上传\n" +
                            "5. 遇到问题时请开启Debug模式，然后导出日志",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // 权限检查对话框
    if (showPermissionDialog) {
        PermissionDialog(
            refreshTrigger = refreshTrigger,
            onDismiss = {
                LogManager.d("MainScreen", "关闭权限对话框")
                showPermissionDialog = false
            },
            onGrantOverlay = {
                LogManager.d("MainScreen", "从对话框授予悬浮窗权限")
                onGrantOverlay()
            },
            onGrantNotification = {
                LogManager.d("MainScreen", "从对话框授予通知权限")
                onGrantNotification()
            }
        )
    }
}

@Composable
fun PermissionDialog(
    refreshTrigger: Int,
    onDismiss: () -> Unit,
    onGrantOverlay: () -> Unit,
    onGrantNotification: () -> Unit
) {
    // refreshTrigger用于在重新进入时刷新状态
    val permissions = checkAllPermissions()
    val allGranted = permissions.all { it.granted }

    LogManager.v("PermissionDialog", "显示权限对话框, 全部授予: $allGranted")

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(20.dp)
        ) {
            Text(
                text = "权限检查",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                permissions.forEach { permission ->
                    PermissionItem(
                        name = permission.name,
                        description = permission.description,
                        granted = permission.granted,
                        onGrant = {
                            LogManager.d("PermissionDialog", "授予权限: ${permission.name}")
                            when (permission.action) {
                                is PermissionAction.Overlay -> onGrantOverlay()
                                is PermissionAction.Notifications -> onGrantNotification()
                                else -> {}
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 底部按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("关闭")
                }

                if (!allGranted) {
                    Button(
                        onClick = {
                            LogManager.d("PermissionDialog", "点击去授权")
                            onDismiss()
                            onGrantOverlay()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("去授权")
                    }
                } else {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("全部已授予")
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionItem(
    name: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (granted)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    contentDescription = null,
                    tint = if (granted) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!granted) {
            TextButton(onClick = onGrant) {
                Text("授权")
            }
        }
    }
}

/**
 * 版本更新对话框
 */
@Composable
fun UpdateVersionDialog(
    versionInfo: com.screenshotuploader.util.UpdateManager.VersionInfo,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(20.dp)
        ) {
            Text(
                text = "发现新版本",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2196F3)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 版本信息
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "新版本",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = versionInfo.version,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "大小",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatFileSize(versionInfo.fileSize),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "发布日期",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = versionInfo.releaseDate.take(10),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 更新说明
            Text(
                text = "更新说明",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = versionInfo.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 强制更新提示
            if (versionInfo.forceUpdate) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Cancel,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "此为强制更新，请立即升级",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 底部按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!versionInfo.forceUpdate) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("稍后")
                    }
                }
                Button(
                    onClick = onConfirm,
                    modifier = if (versionInfo.forceUpdate) Modifier.fillMaxWidth() else Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3)
                    )
                ) {
                    Text("立即更新")
                }
            }
        }
    }
}

/**
 * 格式化文件大小
 */
fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
        else -> "${bytes / (1024 * 1024 * 1024)}GB"
    }
}
