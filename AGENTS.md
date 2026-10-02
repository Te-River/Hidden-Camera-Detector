# AGENTS.md — 业务上下文与协作规范

> 本文件是项目的**业务上下文单一事实源**，由 lead 维护，随重大变更同步更新。
> 任何 agent（或人）加入项目先读此文件。

## 1. 项目定位

**隐藏摄像头探测器** —— Android APK，帮助用户发现酒店/民宿/出租屋内的隐藏摄像头。
仓库：`github.com/Te-River/Hidden-Camera-Detector`（公开）。用户本人安装测试（侧载）。

## 2. 业务目标与验收

5 种探测模式，全部可用：

| 模式 | 原理 | 权限 |
|---|---|---|
| 磁场 | 磁力计检测电子设备磁场异常（本底校准+阈值告警） | 无 |
| 红外 | 前摄（无 IR-cut）看红外补光灯亮斑，原生库检测标记 | CAMERA |
| 反光 | 后摄+闪光灯常亮，捕捉镜头反光点 | CAMERA |
| 局域网 | /24 网段 TCP 并发扫描常见摄像头端口 + mDNS 发现 | INTERNET 等 |
| 蓝牙 | BLE+经典双通道扫描，可疑名称标记 | BLUETOOTH_* |

硬性验收：一键构建出签名 APK ≤500KB · Android 16 实机安装 · 5 模式可运行 · 沉浸式+高刷生效。

## 3. 技术底座（全部实证过，勿重新调研）

- **构建环境**：Termux（aarch64 Android，无 root）。官方 SDK 二进制是 x86_64 跑不了 —— 全链用 Termux 原生包：`aapt2 2.20 / openjdk-21 / d8 / apksigner`。
- **构建命令**：`bash build.sh` → 产物 `build/HiddenCameraDetector.apk`（约 60KB）。android.jar 首次自动下载（platform-36_r02），缓存于 `build/`。
- **构建链**：clang(.so, 16KB页对齐) → aapt2 compile → aapt2 link → javac(--release 11) → d8(--min-api 26) → python zipfile 注入 → apksigner(V3)。
- **硬约束**：纯 framework API，**禁止 AndroidX/任何第三方库**；minSdk 26 / targetSdk 35 / compileSdk 36；包名 `com.hcd.detector`。
- **已验证事实**：/proc/net 在 Android 10+ 禁读（扫描走 TCP connect）；`NsdManager.resolve` 在 SDK 36 已移除（34+ 用 resolveService，26-33 反射）；持有相机会话时 Torch 必须用 `FLASH_MODE_TORCH`（与 setTorchMode 互斥）；Termux 无法触发系统安装器（用户手动装）。

## 4. 金标联盟（ITGSA）适配

用户要求：**能适配多少适配多少**。规范文档全量收录在 `docs/itgsa/`（24 PDF + 7 MD）。

| 规范 | 状态 |
|---|---|
| 沉浸式适配指导书 | ✅ 已按三段式实现（b42de3a） |
| 16KB Page Size | ✅ irscan.so 链接对齐（readelf 验证） |
| 高刷新率 | ✅ preferredDisplayModeId |
| 内存管理 T/TAF 358 | 🔄 待做：onTrimMemory 配合（设备侧规范，app 义务=配合回收不泄漏） |
| 无障碍标准 | 🔄 待做：contentDescription + 触摸目标 |
| 功耗标准 | 🔄 待做：onPause 停扫描（后台 CPU 克制） |
| 安全/个人信息保护 | ✅ 合规（allowBackup=false、无明文、最小权限、不采集数据） |
| Picker 系列/地震预警 | N/A（无对应功能） |

## 5. 协作规范

- **每次 commit 必须 push**（用户硬性要求）。
- 构建产物只进 `build/`（gitignored）；临时文件放 OS tmp（`$PREFIX/tmp/opencode/`），不进仓库。
- `docs/` 始终被跟踪。
- 代码改动走 implementer → tester → reviewer 管线；修复循环直到零 Critical/Major。
- 已知未修问题见 `CHANGELOG.md` 的 Known Issues。

## 6. 当前状态（2026-10-03）

- v1.0 源码全量交付（087aa7e）+ 沉浸式重做（b42de3a），APK 60KB 构建通过。
- **待修**（reviewer 发现，见黑板 p4-review）：M1 相机 openCamera 竞态泄漏 / M2 acquireLatestImage 崩溃 / M3 蓝牙权限拒绝 NPE / M4 zipalign 缺失（改 res 可能安装失败）。
- **待做**：ITGSA 适配批（§4 的 🔄 项）。
- 用户实测反馈「所有功能都不行」—— 具体症状待用户补充，M1-M3 大概率是主因。
