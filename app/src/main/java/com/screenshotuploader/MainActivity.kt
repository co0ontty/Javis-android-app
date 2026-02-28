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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.screenshotuploader.service.FloatingWindowService
import com.screenshotuploader.ui.theme.ScreenshotUploaderTheme
import com.screenshotuploader.util.PreferencesManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkOverlayPermissionAndStartService()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val intent = Intent(this, FloatingWindowService::class.java).apply {
                action = FloatingWindowService.ACTION_START
                putExtra(FloatingWindowService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(FloatingWindowService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScreenshotUploaderTheme {
                MainScreen(
                    onStartService = { startServiceWithPermissions() },
                    onStopService = { stopFloatingService() }
                )
            }
        }
    }

    private fun startServiceWithPermissions() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
        }
        
        if (permissions.isEmpty()) {
            checkOverlayPermissionAndStartService()
        } else {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun checkOverlayPermissionAndStartService() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }

    private fun stopFloatingService() {
        val intent = Intent(this, FloatingWindowService::class.java).apply {
            action = FloatingWindowService.ACTION_STOP
        }
        stopService(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var serverUrl by remember { mutableStateOf(PreferencesManager.getServerUrl(context)) }
    var authToken by remember { mutableStateOf(PreferencesManager.getAuthToken(context)) }
    var isServiceRunning by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("截图上传助手") }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { 
                        serverUrl = it
                        scope.launch {
                            PreferencesManager.saveServerUrl(context, it)
                        }
                    },
                    label = { Text("服务器地址") },
                    placeholder = { Text("http://192.168.1.100:8898") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = authToken,
                    onValueChange = { 
                        authToken = it
                        scope.launch {
                            PreferencesManager.saveAuthToken(context, it)
                        }
                    },
                    label = { Text("访问令牌 (Token)") },
                    placeholder = { Text("输入你的Bearer Token") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (!isServiceRunning) {
                    Button(
                        onClick = {
                            if (serverUrl.isBlank() || authToken.isBlank()) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("请先设置服务器地址和Token")
                                }
                            } else {
                                onStartService()
                                isServiceRunning = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("启动悬浮窗服务")
                    }
                } else {
                    Button(
                        onClick = {
                            onStopService()
                            isServiceRunning = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("停止悬浮窗服务")
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "使用说明：\n" +
                           "1. 设置服务器地址和访问令牌\n" +
                           "2. 点击"启动悬浮窗服务"\n" +
                           "3. 授予所需权限\n" +
                           "4. 点击悬浮按钮即可截图上传",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}