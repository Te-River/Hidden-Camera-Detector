# Changelog

本项目的显著变更记录。格式参照 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### Known Issues（待修）
- M1 CameraHelper.close() 无法取消在途 openCamera → 快速退出相机模式后相机被永久占用（reviewer）
- M2 IRActivity/TorchActivity 的 acquireLatestImage 无异常保护 → 间歇崩溃（reviewer）
- M3 蓝牙权限拒绝路径 tvStatus NPE 崩溃（reviewer）
- M4 无 zipalign，resources.arsc 对齐靠侥幸 → 改 res 后可能安装失败（reviewer）
- 用户实测：全面屏适配差（已修 b42de3a 待复测）、各模式功能异常（症状待补充）

## [1.0.0] - 2026-10-02

### Added
- 5 种探测模式：磁场（磁力计+仪表盘动画）/ 红外（前摄+原生亮斑检测）/ 反光（后摄+闪光灯常亮）/ 局域网（TCP 并发端口扫描+mDNS）/ 蓝牙（BLE+经典双通道）
- 纯 framework API 实现（无 AndroidX），arm64 原生库 irscan（16KB 页对齐）
- 手工构建链 build.sh（aapt2→javac→d8→zipfile→apksigner），产物约 60KB
- 金标联盟 ITGSA 文档库收录（docs/itgsa/，24 PDF + 7 MD）
- 高刷新率适配（preferredDisplayModeId）、深色主题

### Fixed（后续补丁，见 git log）
- 2026-10-03 沉浸式按金标联盟指导书重做：边到边三段式，系统栏可见透明，insets 避让，挖孔 SHORT_EDGES（b42de3a）
