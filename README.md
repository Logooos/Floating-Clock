# Floating Clock

免费开源的 Android 悬浮时钟项目，产品基线见 [PRD.md](PRD.md)。
当前开发版本 **v0.2.0**（versionCode 2），支持 Android 12（API 31）及以上。

本阶段提供可独立测试的纯 Kotlin 时间引擎：单调纳秒推演、模拟校准与状态、
全局／平台偏移、时区和毫秒格式化，以及并发一致的多平台读取。
首页展示版本、持续变化的演示时钟、模拟平台与来源、悬浮占位和禁用设置入口。
演示时钟从固定的 2026-01-01 UTC 起点推进，明确标为演示数据，默认 Asia/Shanghai。
**尚未实现悬浮窗、网络校时和购物平台适配，精度未经验证。**
不申请网络、悬浮窗或通知权限；没有后台服务、购物账号、后端或遥测。

PRD 规划淘宝／天猫、京东、美团、拼多多、抖音五个入口，覆盖六个品牌。
这些是后续需求，当前没有任何可用平台时间源。公共网络时间不等于平台官方时间；
显示毫秒不证明精度，50ms 是有独立参照时的工程目标，不是保证值。
不提供自动下单、自动点击、倒计时或抢购自动化。

## 云端构建（推荐）

不需要本地安装 Android Studio、Android SDK 或 Gradle。

1. 当前初始开发阶段直接推送到 GitHub 的 `main`；工作流也支持 PR 检查。
2. 在 Actions 查看 **Android CI**；也可在工作流进入默认分支后手动运行。
3. `build` 安装 JDK 17 与 SDK 36，执行单元测试、lint 和 Debug APK 构建。
4. 成功后下载 `floating-clock-debug` 产物，解压得到 APK。
5. `smoke-api-31` 在 API 31 模拟器上检查首页，并用注入的虚拟时钟验证显示变化。

工作流文件存在不代表构建通过；当前验证记录见 [docs/testing.md](docs/testing.md)。
Debug APK 仅供开发测试，使用 runner 临时调试签名，不保证不同运行产物可直接覆盖安装。
遇到签名不一致需卸载旧测试版（会清除其数据）。本轮没有正式 Release、发布签名或签名密钥。

## 可选本地构建

仅在已有 JDK 17 和 Android SDK 36 的环境中使用。SDK 路径通过 `ANDROID_HOME`
或不提交的 `local.properties` 提供，Gradle 由完整 Wrapper 自动下载。

```sh
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew :core:time:test
# 仅在已有模拟器或连接设备时：
./gradlew connectedDebugAndroidTest
```

Windows 使用 `./gradlew.bat`。`testDebugUnitTest` 显式依赖 `:core:time:test`，
不会遗漏 JVM 模块测试。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 工程

- `app/`：Compose 首页和 Android 启动冒烟测试；后续系统集成放在这里。
- `core/time/`：既有接口、校准状态、偏移与批量时间读取；39 个纯 JVM 测试。
- `gradle/`：版本目录与完整 Wrapper（包括 JAR）。
- `.github/workflows/android-ci.yml`：构建、报告、APK 与模拟器测试。
- `.agents/skills/`：时间来源、Android 验证与版本维护规则。

工具链版本与选型依据见 [架构文档](docs/architecture.md)，未执行检查及设备限制见
[测试文档](docs/testing.md)。使用 Conventional Commits；当前初始里程碑按维护者要求在 main 开发。
源代码使用 [MIT License](LICENSE)；Gradle Wrapper 保留其 Apache-2.0 声明。
