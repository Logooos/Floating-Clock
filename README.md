# Floating Clock

免费开源的 Android 悬浮时钟项目，产品基线见 [PRD.md](PRD.md)。
当前开发版本 **v0.3.0**（versionCode 3），支持 Android 12（API 31）及以上。

本阶段复用纯 Kotlin 时间引擎，加入单个 WindowManager 悬浮容器、Choreographer
帧绘制、点击平台菜单、长按拖动及前台服务。五个入口最多选三个、纵向排序，
淘宝／天猫共用一项；支持完整、紧凑、极简模式，所有模式明确标注演示和未验证。
模拟时钟从固定的 2026-01-01 UTC 起点推进，默认 Asia/Shanghai。
**没有真实网络来源或购物平台官方同步，显示毫秒不证明精度。**

在首页申请悬浮窗权限，返回后手动点击“启动悬浮窗”；授权不会自动启动。
通知权限可选，拒绝后仍可从首页停止。锁屏／息屏停止服务，解锁不会恢复；
没有开机自启。每次手动启动建立新模拟校准会话。
首页显示实际绘制 FPS；120Hz 是向系统提交的偏好，未验证真机 120FPS 或 50ms。
部分应用可以隐藏第三方悬浮窗，本应用不绕过其策略。
本版配置与位置仅在内存中，尚无持久化、数据库、真实校时、购物账号、后端或遥测。
不提供自动下单、自动点击、倒计时或抢购自动化。

## 云端构建（推荐）

不需要本地安装 Android Studio、Android SDK 或 Gradle。

1. 当前初始开发阶段直接推送到 GitHub 的 `main`；工作流也支持 PR 检查。
2. 在 Actions 查看 **Android CI**；也可在工作流进入默认分支后手动运行。
3. `build` 安装 JDK 17 与 SDK 36，执行单元测试、lint 和 Debug APK 构建。
4. 成功后下载 `floating-clock-debug` 产物，解压得到 APK。
5. `overlay-smoke` 在 API 31、35 模拟器上检查首页、权限、窗口、手势和锁屏生命周期。

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

- `app/`：Compose 首页、悬浮服务与自绘时钟、会话单元测试和 Android 冒烟测试。
- `core/time/`：既有接口、校准状态、偏移与批量时间读取；39 个纯 JVM 测试。
- `gradle/`：版本目录与完整 Wrapper（包括 JAR）。
- `.github/workflows/android-ci.yml`：构建、报告、APK 与模拟器测试。
- `.agents/skills/`：时间来源、Android 验证与版本维护规则。

工具链版本与选型依据见 [架构文档](docs/architecture.md)，未执行检查及设备限制见
[测试文档](docs/testing.md)。使用 Conventional Commits；当前初始里程碑按维护者要求在 main 开发。
源代码使用 [MIT License](LICENSE)；Gradle Wrapper 保留其 Apache-2.0 声明。
