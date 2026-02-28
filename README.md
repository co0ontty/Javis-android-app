# 截图上传助手 (Screenshot Uploader)

## 项目概述
Android 客户端项目，提供全局截图悬浮窗功能，将截图上传到后端服务器进行AI分析。

## 功能需求

### 1. 全局截图悬浮窗
- 在屏幕任意位置显示悬浮按钮
- 点击按钮即可截图当前屏幕
- 悬浮窗可拖动调整位置

### 2. 截图上传
- 截图后自动上传到后端 `/api/v1/images/upload` 接口
- 支持 Bearer Token 认证
- 显示上传进度和结果

### 3. 后台服务
- 悬浮窗以 Foreground Service 形式运行
- 应用退到后台后悬浮窗依然可用

## 后端接口

### 图片上传
- **URL**: `POST /api/v1/images/upload`
- **认证**: `Authorization: Bearer {token}`
- **Content-Type**: `multipart/form-data`
- **参数**: 
  - `file`: 图片文件 (multipart)
- **成功响应**:
  ```json
  {
    "code": 200,
    "message": "上传成功",
    "data": {
      "filename": "xxx.jpg",
      "path": "/uploads/xxx.jpg",
      "image_id": "uuid"
    }
  }
  ```

## 技术栈
- Kotlin
- Jetpack Compose
- Foreground Service
- MediaProjection API (截图)
- WindowManager (悬浮窗)
- OkHttp (网络请求)

## 权限需求
- `SYSTEM_ALERT_WINDOW` - 悬浮窗权限
- `FOREGROUND_SERVICE` - 前台服务
- `FOREGROUND_SERVICE_MEDIA_PROJECTION` - 媒体投影
- `INTERNET` - 网络访问
- `POST_NOTIFICATIONS` - 通知 (Android 13+)

## 项目结构
```
app/src/main/java/com/screenshotuploader/
├── MainActivity.kt              # 主入口，权限申请和设置
├── ScreenshotUploaderApp.kt     # Application类
├── service/
│   └── FloatingWindowService.kt # 悬浮窗服务+截图+上传
├── data/
│   ├── model/
│   │   └── UploadResponse.kt    # 数据模型
│   └── repository/
│       └── ImageRepository.kt   # 图片上传
├── ui/
│   └── theme/
│       ├── Color.kt
│       ├── Theme.kt
│       └── Type.kt
└── util/
    └── PreferencesManager.kt    # 本地存储
```

## 使用说明

1. 打开应用，设置服务器地址和 Token
2. 点击"启动悬浮窗服务"
3. 授予所需权限（悬浮窗、截图、通知等）
4. 点击屏幕上的悬浮按钮即可截图并自动上传

## 开发历史

### 2026-02-28
- 项目初始化
- 创建 Android 项目基础结构
- 实现悬浮窗服务和截图功能
- 实现图片上传到后端 API

---

**项目位置**: `/vol1/1000/Javies/android-screenshot-uploader`

**最后更新**: 2026-02-28
