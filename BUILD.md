# PhoneMirror 构建指南

## 项目结构

```
phone-mirror/
├── desktop/          # 电脑端 (Electron)
│   ├── src/
│   │   ├── main.js              # 主进程
│   │   ├── webrtc-server.js     # WebRTC 服务器
│   │   ├── signaling-server.js  # 信令服务器
│   │   └── renderer/            # 渲染进程
│   │       ├── index.html
│   │       └── app.js
│   └── package.json
│
└── mobile/           # 手机端 (Android)
    └── android/
        └── app/
            ├── build.gradle
            ├── src/main/
            │   ├── AndroidManifest.xml
            │   ├── java/com/phonemirror/app/
            │   │   ├── MainActivity.java
            │   │   ├── webrtc/
            │   │   │   └── WebRTCManager.java
            │   │   └── service/
            │   │       └── ScreenCaptureService.java
            │   └── res/
            │       ├── layout/activity_main.xml
            │       └── values/colors.xml
            └── ...
```

## 电脑端构建

### 环境要求
- Node.js 18+
- npm 或 yarn

### 安装依赖
```bash
cd desktop
npm install
```

### 开发模式
```bash
npm run dev
```

### 构建应用
```bash
# Windows
npm run build:win

# macOS
npm run build:mac

# Linux
npm run build:linux

# 全部平台
npm run build
```

## Android 端构建

### 环境要求
- Android Studio Hedgehog (2023.1.1) 或更高版本
- Android SDK 34
- JDK 17

### 构建步骤

1. 打开 Android Studio
2. 选择 "Open an existing Android Studio project"
3. 选择 `phone-mirror/mobile/android` 目录
4. 等待 Gradle 同步完成
5. 连接 Android 设备或启动模拟器
6. 点击 "Run" 按钮或按 `Shift + F10`

### 生成 APK
```bash
# 调试版本
./gradlew assembleDebug

# 发布版本
./gradlew assembleRelease
```

APK 文件将生成在：
- 调试版：`app/build/outputs/apk/debug/app-debug.apk`
- 发布版：`app/build/outputs/apk/release/app-release.apk`

## 使用说明

### 首次使用

1. **电脑端**
   - 运行 PhoneMirror 应用
   - 等待显示二维码和配对码

2. **手机端**
   - 安装并打开 PhoneMirror App
   - 授予必要的权限（屏幕录制、网络等）
   - 扫描电脑上的二维码或手动输入配对码
   - 确认屏幕录制权限

3. **连接成功**
   - 手机屏幕将实时显示在电脑上
   - 可以使用鼠标和键盘控制手机

### 性能优化建议

1. **网络环境**
   - 确保手机和电脑在同一 WiFi 网络
   - 使用 5GHz WiFi 频段获得更低延迟
   - 避免网络拥塞时段使用

2. **视频设置**
   - 根据网络状况调整分辨率和码率
   - 游戏场景建议使用 60fps
   - 普通使用 30fps 即可

3. **延迟优化**
   - 关闭电脑上的防火墙或添加例外
   - 优先使用有线网络连接电脑
   - 关闭手机上不必要的后台应用

## 故障排除

### 无法连接
- 检查手机和电脑是否在同一网络
- 确认防火墙没有阻止端口 8080
- 尝试手动输入 IP 地址

### 画面卡顿
- 降低分辨率或帧率设置
- 检查网络信号强度
- 关闭其他占用带宽的应用

### 延迟过高
- 使用 5GHz WiFi
- 降低视频码率
- 确保电脑性能充足

## 技术参数

| 参数 | 默认值 | 范围 |
|------|--------|------|
| 分辨率 | 1080p | 480p - 2K |
| 帧率 | 60fps | 15 - 60fps |
| 码率 | 8 Mbps | 1 - 15 Mbps |
| 延迟 | ~70ms | 30 - 150ms |
| 协议 | WebRTC | - |
| 编解码 | H.264 | H.264/VP8/VP9 |

## 许可证

MIT License
