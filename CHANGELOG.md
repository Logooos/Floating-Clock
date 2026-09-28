# Changelog

采用 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) 风格，
版本遵循 [Semantic Versioning](https://semver.org/spec/v2.0.0.html)。

## [Unreleased]

## [0.2.0] - 2026-09-28

### Added

- 单调纳秒时间引擎的校准状态、来源元数据、全局与五平台独立偏移及批量读取。
- 同源模拟校准、失败保留旧锚点、显式失效、恢复与乱序结果保护；未知精度不作数值声明。
- Android 单调时钟接入和固定起点的演示毫秒时钟，不进行网络请求。
- 时间边界、夏令时、并发一致性、非法输入与失败恢复测试，保留 API 31 启动冒烟。

### Changed

- Android 使用 elapsedRealtimeNanos 注入时间核心，UI 只格式化引擎输出。
- 开发和云端构建统一使用 main，删除旧初版远端分支。
- versionCode 从 1 递增为 2；维护架构、测试记录与并发读取规则。

### Known limitations

- 仅验证计算正确性；模拟来源不证明真实网络准确度，不声称达到 50ms。
- 未实现真实网络来源、重试调度、悬浮窗和持久化；未进行真机或 120FPS 验证。
- 未发布正式 Release，未配置正式签名密钥。

## [0.1.0] - 2026-09-28

### Added

- Kotlin 原生 Android 框架，Compose 演示首页，Android 12+ 支持。
- `:app` 与纯 Kotlin `:core:time` 两个模块及明确单位的时间领域接口。
- 单调纳秒推演、全局／平台手动偏移、时区与毫秒格式化的确定性测试。
- AGP 9.x 内置 Kotlin、版本目录和含校验和的完整 Gradle Wrapper。
- GitHub Actions 单元测试、lint、Debug APK 构建与 API 31 模拟器启动冒烟任务。
- 架构、测试、贡献规则、可复用项目技能及 MIT 许可证。

### Known limitations

- 此版本号标记开发里程碑，未发布正式 Release；云端单元测试、lint、Debug 构建和 API 31 模拟器冒烟已通过。
- 真实校时、悬浮窗、平台适配、持久化与发布签名不在本阶段范围。
- 尚未进行真机验证，不提供官方时间或实际精度声明。
