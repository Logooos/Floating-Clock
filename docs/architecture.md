# v0.2.0 时间引擎架构

本阶段实现可测试的时间引擎和演示首页，不实现真实来源、悬浮窗、存储或重试调度。
不声称已满足 PRD S0 的来源审计与初始公共时间路径退出条件。PRD.md 保持不变。

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
该版本组合已在 GitHub Actions 运行 36384811167 通过单元测试、lint 和 Debug APK 构建，
具体环境及模拟器／真机验证边界见 testing.md。
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
主界面注入 `SystemClock.elapsedRealtimeNanos()` 和 `DemoTimeSource`，展示固定
2026-01-01 UTC 起点推进的演示时间；设置按钮仍禁用，没有权限申请或真实网络请求。

- `ClockProvider` 只提供单调纳秒，可注入测试虚拟时钟。Android 实现使用
  `SystemClock.elapsedRealtimeNanos()`，包括休眠时间，不能以墙上时间或帧数替代。
- `TimeSource` 提供来源 ID、来源类别和可挂起校准接口，未实现网络适配器。
- `CalibrationResult` 沿用成功／失败类型，扩展有符号估计偏移（纳秒）和不确定度证据。
  估计偏移不参与显示叠加、不等于实测误差；不确定度默认为 null，非空值必须附证据说明，
  提供方仍有责任验证证据，字符串本身不是精度证明。当前没有独立参照测量，
  `accuracyVerified` 恒为 false，`measuredErrorNanos` 恒为 null。
- `PlatformId` 只有 TAOBAO_TMALL、JD、MEITUAN、PDD、DOUYIN 五个入口，与来源类别分离。
  `PlatformTimeState` 保存平台、选定来源、状态、最近成功样本、失败原因和平台偏移。
  最近成功校准 UTC 指最后成功锚点的服务器 UTC，不读取本机墙上时钟。
- `TimeAnchor` 的 UTC epoch 纳秒与同一瞬间的 monotonic 纳秒构成内存锚点。
  引擎计算 `UTC = anchorUTC + (nowMono - anchorMono)`，不读取系统墙上时间。
- 显示时叠加有符号的全局及平台毫秒偏移，原始锚点不可变。
  精确算术遇到 Long 溢出会抛异常，调用方不能接受绕回后的伪时间。
- 时区使用 `ZoneId`，默认 `Asia/Shanghai`，只参与格式化；`HH:mm:ss.SSS`
  保留三位毫秒并截断更细部分。格式器可复用，未来帧绘制仍需性能实测。

Long epoch 纳秒的范围约为 1677–2262 年，足够当前需求；越界必须拒绝。
锚点只属于当前手动启动会话，不能跨重启、锁屏后的新会话恢复。
引擎记录最后观察到的单调时间；发生回退（即使仍大于锚点）会使所有锚点不可用并标记
STALE，保留历史但不输出时间，必须重新校准。跨重启仍不能仅依赖数值识别旧会话，
后续会话拥有者必须创建新引擎，不能恢复持久化锚点。

## 校准与状态

`calibrate(platform, source)` 或 `calibrate(platforms, source)` 显式调用 TimeSource 一次。
同源平台可以批量校准，共享同一个不可变样本。首次选择后来源 ID 与类别固定；
任何改变都必须显式创建新会话，不因失败换源。本阶段不实现后台源调度或自动重试。

| 事件 | 状态／结果 |
| --- | --- |
| 新引擎 | STOPPED，无锚点；读取标记无时间，直接取值抛异常 |
| 首次校准开始 | INITIALIZING，isCalibrating=true |
| 已有样本时校准开始 | 保留原状态和警告，isCalibrating=true |
| 成功且合法的新样本 | SYNCED，替换基准并清除失败原因；不代表精度已验证 |
| 失败且有旧基准 | RETRYING，保留旧基准；若原本 STALE 则仍为 STALE |
| 首次失败 | STALE，无可信基准，不产生伪服务器时间 |
| markStale | STALE，保留旧基准并显式警告，不虚构自动过期阈值 |
| 时钟回退 | STALE，保留历史但禁止使用旧锚点 |
| 同源恢复 | SYNCED，应用新锚点，可显式向前／向后跳变，不隐藏于平滑算法 |

样本来源必须匹配请求，单调锚点必须位于本次请求开始与完成之间，UTC 推演不能溢出。
源异常转为失败；协程取消保留历史、结束校准中状态，并继续向调用者传播取消。
每个平台的请求序号阻止晚到的旧请求覆盖新校准或显式失效。

## 并发、读取与显示

引擎用一把短锁保护五个平台状态与偏移。调用可挂起的 TimeSource 在锁外，
不在锁内等待网络，也不把网络请求放到读取路径。未来真实适配器负责切换合适的 I/O
执行上下文、超时及取消；`suspend` 本身不是自动切换到后台线程。

`setOffsets(globalMillis, platformMillisMap)` 原子替换完整配置，未提供的平台偏移为零。
输入 Map 不保留引用，合并偏移预先换算为纳秒；溢出时拒绝整次更新，不发布一半配置。
完成异步校准时从最新状态复制，因此不会把采样期间的偏移修改覆盖掉。

调用者创建并复用 `TimeReadings`，`readInto` 在一次锁内只读取一次单调时钟，计算所有
平台。结果含同一版本的配置和不可变元数据引用；正常读取不创建列表、Map 或结果对象。
每个读取线程使用自己的缓冲区，不并发共享可变缓冲区。非法时钟或数值溢出拒绝读取，
缓冲区的可用标记清空；调用方不能把失败后的缓存值当成当前时间。

毫秒转换采用 floorDiv，负 epoch 的不足一毫秒也正确落在前一毫秒；格式化采用 java.time，
正确处理时区及夏令时，毫秒位截断而不四舍五入。格式器可复用，但字符串和 Instant
仍会分配；没有宣称已实测 120FPS、耗电或零分配渲染。

Compose 示例约每 16ms 请求重新读取并格式化，delay 只是展示节奏，不累加时间。
Activity 停止时暂停演示更新；这不是悬浮服务，也没有实现 Choreographer 循环。
模拟校准起点通过 DemoTimeSource 构造参数注入，UI 不包含推演公式。

## 后续实现约束

悬浮层仍采用单个 `WindowManager` 窗口和 Custom View，帧驱动使用 Choreographer；
网络采样独立于绘制。锁屏停止采样、帧回调、窗口和服务，解锁不自动重启。
API 31–32 不调用 API 33 的系统网络时钟；来源失效不能静默切换。
Proto DataStore / Room 在实际实现配置与诊断时引入，本轮不添加空存储层和依赖。
