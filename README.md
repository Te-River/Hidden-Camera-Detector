# Hidden-Camera-Detector · 隐藏摄像头探测器

帮助你在酒店、民宿、出租屋等场所发现隐藏摄像头的 Android 应用。

**纯原生实现 · APK 仅约 60KB · 无任何第三方库**

## 探测模式

| 模式 | 原理 | 需要权限 |
|---|---|---|
| 🧲 磁场探测 | 磁力计检测电子设备引起的磁场异常，10 秒本底校准 + 阈值告警（变红/震动） | 无 |
| 🔴 红外探测 | 多数前置摄像头无 IR-cut 滤镜，红外补光灯在预览中显示为紫白亮点，原生库实时检测标记 | 相机 |
| 🔦 反光探测 | 闪光灯常亮 + 后摄预览，捕捉镜头反光点（缓慢移动手机观察闪烁） | 相机 |
| 🌐 局域网扫描 | /24 网段 64 线程并发 TCP 扫描常见摄像头端口（RTSP 554/海康 8000/雄迈 34567…）+ mDNS 发现 | 网络 |
| 📶 蓝牙扫描 | BLE + 经典蓝牙双通道，可疑设备名（CAM/IPC/DVR…）标红 | 蓝牙 |

## 构建

在 **Termux**（Android 设备上的 Linux 环境）中一键构建，无需 PC、无需 Android Studio、无需官方 SDK：

```bash
pkg install -y openjdk-21 aapt2 d8 apksigner   # 一次性
bash build.sh                                   # 产物: build/HiddenCameraDetector.apk
```

构建链：`clang(arm64 .so, 16KB页对齐) → aapt2 → javac → d8 → apksigner(V3)`，首次构建自动下载 android.jar（platform-36）。

安装：用文件管理器打开 `build/HiddenCameraDetector.apk` 侧载（需允许「安装未知应用」）。

## 技术特点

- **零依赖**：纯 Android framework API，无 AndroidX / 无第三方库 / 无 Kotlin
- **极小体积**：约 60KB（同等功能 AndroidX 应用通常 3-5MB+）
- **原生加速**：亮斑检测（红外/反光共用）由 C 实现（`jni/irscan.c`），clang 编译为 arm64-v8a，16KB page size 对齐
- **金标联盟（ITGSA）适配**：沉浸式三段式（边到边+insets 避让）、高刷新率（preferredDisplayModeId）、16KB Page Size、最小权限、无数据采集
- **实机验证**：小米14 Pro / Android 16 (API 36) 构建与测试

## 项目结构

```
├── build.sh                  # 一键构建脚本
├── AndroidManifest.xml
├── jni/irscan.c              # 原生亮斑检测（JNI）
├── src/com/hcd/detector/
│   ├── MainActivity.java     # 模式入口 + 沉浸式/高刷适配
│   ├── magnetic/             # 磁场（含自绘仪表盘 GaugeView）
│   ├── camera/               # 红外/反光 + Camera2 封装 + 亮斑叠加层
│   ├── network/              # 端口扫描引擎 + 局域网模式
│   └── bluetooth/            # 蓝牙模式
├── res/                      # 布局/主题/文案（深色主题）
└── docs/itgsa/               # 金标联盟规范文档库（24 PDF + 7 MD）
```

## 说明

- 红外探测依赖前置摄像头无 IR-cut 滤镜（多数手机成立，个别机型不适用）
- 局域网扫描无 root 权限，采用 TCP connect 方式（Android 10+ 禁读 /proc/net），/24 网段约 15-30 秒
- 本工具提供辅助判断，不能保证检出所有隐藏摄像头

## License

见 [LICENSE](LICENSE)。
