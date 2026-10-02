# Changelog

本项目的显著变更记录。格式参照 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

## [0.0.1] - 2026-10-03（预览版，版本方案：0.0.1 → 0.0.2 → 0.0.3 递增）

首个预览版。Release：https://github.com/Te-River/Hidden-Camera-Detector/releases/tag/v0.0.1

### Added
- 5 种探测模式：磁场（磁力计+仪表盘动画）/ 红外（前摄+原生亮斑检测）/ 反光（后摄+闪光灯常亮）/ 局域网（TCP 并发端口扫描+mDNS）/ 蓝牙（BLE+经典双通道）
- 纯 framework API 实现（无 AndroidX），arm64 原生库 irscan（16KB 页对齐）
- 手工构建链 build.sh（aapt2→javac→d8→zipfile→python zipalign→apksigner），产物约 60KB
- 金标联盟 ITGSA 文档库收录（docs/itgsa/，24 PDF + 7 MD）
- 高刷新率适配（preferredDisplayModeId）、深色主题

### Fixed
- 沉浸式按金标联盟指导书重做：边到边三段式，系统栏可见透明，insets 避让，挖孔 SHORT_EDGES（b42de3a）
- M1 相机 openCamera 竞态泄漏：volatile released 标志全路径拦截（6dff963）
- M2 acquireLatestImage 竞态崩溃：catch ISE + close 前摘除监听（6dff963）
- M3 蓝牙权限拒绝路径 NPE 崩溃（6dff963）
- M4 zipalign：build.sh 内嵌 python 对齐步骤，resources.arsc 4 字节对齐实证（6dff963）
- m1 手电筒状态同步 / m2 无磁力计提示 / m4 NSD resolve 超时兜底（6dff963）
- build.sh 版本号硬编码覆盖 manifest 的问题（6dff963）

### 金标联盟适配
- 沉浸式适配指导书：三段式边到边（b42de3a）
- 16KB Page Size：irscan.so 链接对齐（readelf 验证）
- 高刷新率：preferredDisplayModeId
- 内存管理 T/TAF 358：6 个 Activity onTrimMemory/onLowMemory 释放资源
- 功耗标准：onPause 停扫描/释放相机（后台零耗电）
- 无障碍标准：contentDescription 补齐，装饰性图标 importantForAccessibility=no
- 安全/个人信息保护：合规（allowBackup=false、无明文、最小权限、不采集数据）
