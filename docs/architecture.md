# v0.1.0 架构

本轮范围以开发任务为准，是 PRD 第一阶段的工程框架子集；不声称已满足
PRD S0 的来源审计与初始公共时间路径退出条件。PRD.md 保持不变。

## 工具链与证据（2026-09-28）

| 项目 | 固定版本 | 依据 |
| --- | --- | --- |
| AGP | 9.4.0 | 官方稳定版兼容表，Google Maven POM 已读取 |
| Gradle | 9.6.0 | AGP 9.4 指定最低及默认版本 |
| JDK / JVM target | 17 | AGP 官方最低及默认版本 |
| Kotlin / Compose compiler plugin | 2.2.10 | AGP 9.4.0 POM 的内置 KGP 版本，统一 JVM 插件与 Compose 编译插件 |
| Compose BOM | 2026.02.01 | Google Maven 稳定 BOM；UI/Foundation 1.10.4、Material3 1.4.0 |
| Activity Compose | 1.12.4 | AndroidX 稳定版 |
| min / compile / target SDK | 31 / 36 / 36 | API 36 稳定，位于 AGP 支持范围内，保留 Android 12 下限 |
| SDK Build Tools | 36.0.0 | AGP 默认；CI 显式安装 |

未发现需要偏离用户首选 AGP / Gradle / JDK 的官方兼容性冲突。
以上是文档及产物元数据核验，不等同于本仓库已构建通过。
选用已有稳定 Compose BOM，不引入 alpha、beta、RC 或 canary。

- [AGP 9.4 兼容表](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
- [AGP 内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [AGP POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.0/gradle-9.4.0.pom)
- [Compose BOM POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.02.01/compose-bom-2026.02.01.pom)
- [Compose compiler 配置](https://developer.android.com/develop/ui/compose/compiler)
- [Activity 发布记录](https://developer.android.com/jetpack/androidx/releases/activity)
- [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html)

`:app` 使用 AGP 默认启用的内置 Kotlin，不应用 `org.jetbrains.kotlin.android`，
不关闭新 DSL。`:core:time` 是 JVM 模块，使用 `org.jetbrains.kotlin.jvm`；
Compose compiler 插件与内置 Kotlin 版本一致。

Wrapper 脚本及 JAR 来自 Gradle 官方 `v9.6.0` 标签。
JAR SHA-256：`497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`，
已与官方 `gradle-9.6.0-wrapper.jar.sha256` 比对。
distribution 校验和固定在 `gradle-wrapper.properties`，CI setup-gradle 也执行 Wrapper 验证。

## 模块与时间契约

依赖方向仅为 `app -> core:time`。不提前创建来源、存储、悬浮模块。
主界面只展示静态占位信息，设置按钮禁用，没有权限申请或真实网络请求。

- `ClockProvider` 只提供单调纳秒，可注入测试虚拟时钟。后续 Android 实现必须使用
  `SystemClock.elapsedRealtimeNanos()`，包括休眠时间，不能以墙上时间或帧数替代。
- `TimeSource` 提供来源 ID、来源类别和可挂起校准接口，未实现网络适配器。
- `CalibrationResult` 区分成功样本与失败。分辨率、估计不确定度使用纳秒；
  `null` 表示未知，不含未经独立验证的“实测误差”。
- `CalibrationStatus` 对齐 PRD 状态名称；本轮只定义类型，不实现重试状态机。
- `TimeAnchor` 的 UTC epoch 纳秒与同一瞬间的 monotonic 纳秒构成内存锚点。
  引擎计算 `UTC = anchorUTC + (nowMono - anchorMono)`，不读取系统墙上时间。
- 显示时叠加有符号的全局及平台毫秒偏移，原始锚点不可变。
  精确算术遇到 Long 溢出会抛异常，调用方不能接受绕回后的伪时间。
- 时区使用 `ZoneId`，默认 `Asia/Shanghai`，只参与格式化；`HH:mm:ss.SSS`
  保留三位毫秒并截断更细部分。格式器可复用，未来帧绘制仍需性能实测。

Long epoch 纳秒的范围约为 1677–2262 年，足够当前需求；越界必须拒绝。
锚点只属于当前手动启动会话，不能跨重启、锁屏后的新会话恢复。
当前引擎仅拒绝负增量，无法单靠数值识别所有旧会话；后续会话拥有者必须丢弃旧锚点。
失败结果不负责更新锚点，未来协调器负责保留旧基准、同源重试和显式告警。

## 后续实现约束

悬浮层仍采用单个 `WindowManager` 窗口和 Custom View，帧驱动使用 Choreographer；
网络采样独立于绘制。锁屏停止采样、帧回调、窗口和服务，解锁不自动重启。
API 31–32 不调用 API 33 的系统网络时钟；来源失效不能静默切换。
Proto DataStore / Room 在实际实现配置与诊断时引入，本轮不添加空存储层和依赖。
