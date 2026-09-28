# Changelog

采用 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) 风格，
版本遵循 [Semantic Versioning](https://semver.org/spec/v2.0.0.html)。

## [Unreleased]

## [0.1.0] - 2026-09-28

### Added

- Kotlin 原生 Android 框架，Compose 演示首页，Android 12+ 支持。
- `:app` 与纯 Kotlin `:core:time` 两个模块及明确单位的时间领域接口。
- 单调纳秒推演、全局／平台手动偏移、时区与毫秒格式化的确定性测试。
- AGP 9.x 内置 Kotlin、版本目录和含校验和的完整 Gradle Wrapper。
- GitHub Actions 单元测试、lint、Debug APK 构建与 API 31 模拟器启动冒烟任务。
- 架构、测试、贡献规则、可复用项目技能及 MIT 许可证。

### Known limitations

- 此版本号标记开发里程碑，未发布正式 Release；云端运行状态尚未验证。
- 真实校时、悬浮窗、平台适配、持久化与发布签名不在本阶段范围。
- 尚未进行真机验证，不提供官方时间或实际精度声明。
