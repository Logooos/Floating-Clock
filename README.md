# Floating Clock

免费开源的 Android 悬浮时钟项目，产品基线见 [PRD.md](PRD.md)。
当前开发版本 **v0.5.0**（versionCode 5），支持 Android 12（API 31）及以上。

复用纯 Kotlin 时间引擎与 WindowManager 悬浮容器，默认使用真实网络时间：
Android 13+ 优先系统网络时钟；Android 12 或首次不可用时尝试 Cloudflare / Google NTP。
高级选项可明确选源或使用公开 HTTPS Date（HTTP_ESTIMATED，秒级信息）。
五个平台入口最多显示三个、纵向排序，淘宝／天猫共用一项；支持完整、紧凑、极简模式。
**公共时间不是购物平台官方时间，显示毫秒不证明精度；实际误差与不确定度未验证。**
逐平台来源证据、访问条件及限制见 [来源审计](docs/time-sources.md)。

首页选择来源、申请悬浮窗权限，返回后手动点击“启动悬浮窗”；授权不会自动启动。
所有显示平台共享所选来源，平台名称只表示分组，未接入需要密钥的官方接口。
初次自动选源允许兜底；运行中失败只重试原源，保留最后成功锚点并在连续失败后告警。
手动同步与自动同步共享限频，间隔为 30–300 秒；每次新会话重新选源，切源需先停止。
菜单和首页提供实际 sourceId、端点、最后成功基准、RTT 等详情，这些指标不是误差保证。
可显式选择离线演示，自动化也使用假源或回环服务，不依赖公共服务器。

通知权限可选，拒绝后仍可从首页停止。锁屏／息屏停止采样和服务，解锁不会恢复；
没有开机自启。每次手动启动建立新校准会话，不恢复旧锚点。
首页显示实际绘制 FPS；120Hz 是向系统提交的偏好，未验证真机 120FPS 或 50ms。
部分应用可以隐藏第三方悬浮窗，本应用不绕过其策略。
设置页保存平台顺序、全局／平台偏移、时区、共享来源、字体、颜色、透明度及悬浮位置。
支持深色、浅色卡片、半透明磨砂外观和无背景数字四种风格，与三种信息模式组合；
警告始终可见，紧凑／极简模式的实际来源可在点击菜单中查看。磨砂外观使用渐变和半透明边框，
不会读取或模糊其他应用画面。样式和偏移修改直接应用，来源更换仍需停止当前会话。
Proto DataStore 保存配置，Room 保存最近 7 天诊断（最多 120,000 条）。诊断页支持筛选、详情、
清除及系统文件选择器 JSON／CSV 导出；CSV 用 rowType 区分一次性元数据与记录行。
配置和诊断存放在 no-backup 目录，同时禁用云备份和设备迁移；卸载会删除这些数据。
不持久化活动校准锚点、服务或请求状态，恢复配置不会自动联网。网络服务方可见请求 IP；
没有秘密 AppKey、购物账号、开发者后端、遥测或日志上传。
不提供自动下单、自动点击、倒计时或抢购自动化。

## 云端构建（推荐）

不需要本地安装 Android Studio、Android SDK 或 Gradle。

1. 当前初始开发阶段直接推送到 GitHub 的 `main`；工作流也支持 PR 检查。
2. 在 Actions 查看 **Android CI**；也可在工作流进入默认分支后手动运行。
3. `build` 安装 JDK 17 与 SDK 36，执行单元测试、lint 和 Debug APK 构建。
4. 成功后下载 `floating-clock-debug` 产物，解压得到 APK。
5. `overlay-smoke` 在 API 31、33、35 模拟器上检查首页、权限、窗口、手势和锁屏生命周期。

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
- `core/time/`：时间引擎、来源适配、协议校验与同步策略；71 个纯 JVM 测试。
- `gradle/`：版本目录与完整 Wrapper（包括 JAR）。
- `.github/workflows/android-ci.yml`：构建、报告、APK 与模拟器测试。
- `.agents/skills/`：时间来源、Android 验证与版本维护规则。

工具链版本与选型依据见 [架构文档](docs/architecture.md)，未执行检查及设备限制见
[测试文档](docs/testing.md)。使用 Conventional Commits；当前初始里程碑按维护者要求在 main 开发。
源代码使用 [MIT License](LICENSE)；Gradle Wrapper 保留其 Apache-2.0 声明。
