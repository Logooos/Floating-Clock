# 验证记录

记录日期：2026-09-28。

## v0.3.0 验证（进行中）

基线 bf2aefc，工作区干净；上一次代码 b4d7d90 的 CI 成功。
保留 39 项核心 JVM 测试及 2 项首页仪器测试；新增 14 项 app 单元测试、8 项仪器测试，
累计 63 个独立测试用例。API 31/35 重复运行同一组仪器测试不重复计入数量。

本地实际尝试 gradlew.bat testDebugUnitTest、lintDebug、assembleDebug、
connectedDebugAndroidTest：全部 NOT RUN，无 JDK/JAVA_HOME，Wrapper 无法启动。
云端构建及 API 31/35 模拟器结果待记录，不能把代码存在或静态检查视为通过。

新增覆盖：权限缺失、选择与排序、三种模式、重复启动/停止、部分前台服务或窗口失败、
权限撤销、通知拒绝后的首页停止、实际息屏/唤醒不恢复、点击菜单与长按拖动、
隐藏/移除取消帧回调，以及 FPS 的确定性计算。系统长按测试等待原生阈值事件，
纯 Kotlin 测试不使用睡眠、网络或当前日期。

NOT RUN：真机120FPS/功耗、小米/HyperOS、真实购物 App 的遮挡与外部触摸兼容性、
API 32/33/34/36、独立参考下的真实网络精度。本版无真实网络适配器。

## v0.2.0 验证

直接在 main 开发；起始提交 983e76c，开始时工作区干净。
原 tip 带 `[skip ci]`，main 无运行记录；先提交 main 工作流规则，
[基线运行 36386834001](https://github.com/Logooos/Floating-Clock/actions/runs/36386834001)
对应 bba0b3c，单元测试、lint、APK 构建和 API 31 冒烟全部通过。
用户已把 GitHub 默认分支改为 main，旧远端 codex/bootstrap-v0.1.0 已删除。

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| 本地 `./gradlew.bat testDebugUnitTest` | NOT RUN | 无 JDK / JAVA_HOME，Wrapper 启动失败 |
| 本地 `./gradlew.bat lintDebug` | NOT RUN | 同上 |
| 本地 `./gradlew.bat assembleDebug` | NOT RUN | 同上 |
| main 实现 CI | PASS | 实现提交 3349cf1，[运行 36387765939](https://github.com/Logooos/Floating-Clock/actions/runs/36387765939) 全部成功 |
| 最终 v0.2.0 版本 CI | PASS | b4d7d90，[运行 36388496504](https://github.com/Logooos/Floating-Clock/actions/runs/36388496504)；单元测试、lint、APK 构建及 API 31 两项测试通过 |
| XML 解析 / git diff --check | PASS | 静态检查，不等同于编译或测试通过 |
| 真机、实际网络精度、120FPS | NOT RUN | 没有设备、真实网络适配器或独立可信参照 |

实现 CI 实际通过 `./gradlew testDebugUnitTest --stacktrace`、
`./gradlew lintDebug --stacktrace`、`./gradlew assembleDebug --stacktrace` 及
API 31 `./gradlew connectedDebugAndroidTest --stacktrace`，APK 与报告均已上传。
main 首轮实现检查通过后才更新版本号；未创建 Release 或签名密钥。

最终版本产物：`floating-clock-debug`（ID 10955506045，归档 9,662,540 字节）、
`android-check-reports`（ID 10955461433）、`api-31-smoke-reports`（ID 10955635526）。
在上面的最终运行页面下载。验证记录的后续提交只修改此文档并使用 `[skip ci]`；
实际编译测试的 v0.2.0 代码及版本配置提交为 b4d7d90。

现有 9 个 JVM 测试继续保留，新增 30 个，累计 39 个；Android 测试从 1 个扩展为 2 个，
总计 41 个（本轮新增 31 个）。这不是基于模拟网络延迟的精度测试。

- 纳秒／毫秒／秒推进，墙上时间跳变、正负偏移叠加、五入口独立和淘宝／天猫合并配置。
- 日期、分钟、小时、年界线；纽约春秋夏令时转换；负 epoch、毫秒截断、未知精度。
- 首次失败、旧基准保留、显式失效、同源恢复、来源拒绝替换、异常与取消传播。
- 请求区间验证、时钟回退、数值上下界与溢出、旧异步结果不能覆盖新结果或配置。
- 一次时钟读取计算五个平台；复用缓冲区；偏移 Map 防御复制；跨线程读写一致性。
- API 31 保留启动与演示标注断言，并注入虚拟时钟、推进 Compose 测试时钟验证显示变化。

测试只用 FakeClock 和 FakeTimeSource，无 Thread.sleep、真实网络或当前日期依赖。
异步乱序用显式 continuation 控制；并发测试验证同一个不变量，无随机测试数据，
CountDownLatch 建立开始条件，Future 超时只用于防止死锁，不是计时精度断言。

后续仍需独立参照的真实校时验证、API 32–36 扩展矩阵、小米真机、悬浮与锁屏停机、
渲染性能和耗电测量。v0.2.0 不实现这些后续功能，也不声称达到 50ms 精度。

## v0.1.0 历史记录

### 执行结果

| 检查 | 结果 | 证据／限制 |
| --- | --- | --- |
| `./gradlew.bat testDebugUnitTest` | NOT RUN | 本地 Wrapper 启动失败，未安装 JDK，JAVA_HOME 未设置；测试未执行 |
| `./gradlew.bat lintDebug` | NOT RUN | 同上，lint 未执行 |
| `./gradlew.bat assembleDebug` | NOT RUN | 同上，未生成 APK |
| API 31 `connectedDebugAndroidTest` | NOT RUN | 本地无 Android SDK／模拟器，交由 CI 冒烟任务 |
| Wrapper JAR SHA-256 | PASS | 与 Gradle 9.6.0 官方校验和一致，见 architecture.md |
| XML / TOML / CI YAML 静态解析 | PASS | Python XML、tomli、PyYAML 解析；检查 CI 包含三个必需命令 |
| 三份项目 Skill | PASS | skill-creator 的 quick_validate.py；已有 time-source 文件去除 BOM 并规范为 SKILL.md |
| 提交内容扫描 | PASS | 未检测到 SDK 本地路径、常见凭据、密钥或生成构建产物；静态扫描不等同于完整安全审计 |
| Git diff 空白检查 | PASS（本轮代码） | PRD 原有 Markdown 行尾双空格保留；其余暂存文件通过 diff --check |
| GitHub Actions 首轮 | FAIL | SSH 推送成功；SDK setup Action 失败；lintDebug、assembleDebug 通过；单元测试、APK 上传与模拟器跳过 |
| 云端 `./gradlew testDebugUnitTest --stacktrace` | PASS | 第二轮 36384811167，提交 82067ed，包含 core:time:test |
| 云端 `./gradlew lintDebug --stacktrace` | PASS | 同一轮 build job |
| 云端 `./gradlew assembleDebug --stacktrace` | PASS | 同一轮 build job，Debug APK 已上传 |
| 云端 API 31 `./gradlew connectedDebugAndroidTest --stacktrace` | PASS | 同一轮 smoke-api-31 job，启动与首页占位断言通过，报告已上传；仅模拟器 |
| 真机验证 | NOT RUN | 当前没有 Android 真机 |

## 自动化覆盖

`core/time/src/test/.../TimeEngineTest.kt` 包含 9 个纯 JVM 测试：纳秒增量、
正负全局／平台偏移、毫秒三位及截断、时区展示、虚拟时钟推进、墙上时间前后跳变、
未来锚点拒绝、偏移溢出拒绝、无效校准元数据拒绝。无网络访问或真实等待。

`testDebugUnitTest` 显式依赖 `:core:time:test`；单独运行后者也可验证核心。
测试结果在 `core/time/build/reports/tests/test/index.html`。

`LaunchSmokeTest` 用 Compose instrumentation 启动真实 Activity，断言应用名、
版本、演示标签、两种状态占位和禁用设置入口。CI 使用 API 31 Google APIs x86_64
镜像及 KVM；超时、下载或虚拟化故障必须作为基础设施失败记录，不忽略成成功。
这是模拟器启动与界面检查，不是精度测试或物理设备验证。

CI 分别执行 `./gradlew testDebugUnitTest --stacktrace`、
`./gradlew lintDebug --stacktrace`、`./gradlew assembleDebug --stacktrace`，
并上传报告和 Debug APK。模拟器任务执行 `./gradlew connectedDebugAndroidTest --stacktrace`。
查看 Actions 对应提交 SHA、任务结果和报告；任务跳过不算通过。

目标仓库：[Logooos/Floating-Clock](https://github.com/Logooos/Floating-Clock)。
开发分支：`codex/bootstrap-v0.1.0`。本地初始提交：`78ae86e`。
HTTPS 首次推送因缺少凭据失败；改为用户指定的 SSH 地址
`git@github.com:Logooos/Floating-Clock.git` 后推送成功。
[首轮运行 36384486826](https://github.com/Logooos/Floating-Clock/actions/runs/36384486826)
对应 `f869480`：SDK setup Action 失败，但 runner 自带 SDK 支持 lint 与 assemble 成功。
公开日志接口返回 403，未获得该 Action 的具体失败日志，不推断错误根因。
工作流已改用固定 Ubuntu 24.04 runner 自带的 SDK 管理器安装指定组件，移除多余安装 Action。
[修正后运行 36384811167](https://github.com/Logooos/Floating-Clock/actions/runs/36384811167)
对应 `82067ed`：build job 通过，单元测试、lint、assemble 与产物上传均成功。
`floating-clock-debug` 产物 ID 为 `10953694011`，归档大小 9,643,619 字节；
`android-check-reports` 产物 ID 为 `10954655282`。在该运行页面下载，可能需要 GitHub 登录。
API 31 模拟器启动冒烟通过，`api-31-smoke-reports` 已上传。
记录结果的后续提交只修改文档，使用 `[skip ci]` 避免重复构建；被验证的代码提交是 `82067ed`。
本次没有向 main 推送，没有发布 Release。

## 未完成的设备及后续验证

- 小米 14 Pro / HyperOS 3：安装、启动、旋转、大字体、无障碍与长时间表现，NOT RUN。
- API 32、33、34、35、36 的扩展兼容矩阵，NOT RUN；本阶段仅配置 API 31 冒烟。
- 后续悬浮窗：跨 App、拖动、权限撤回、厂商限制，尚未实现，NOT RUN。
- 后续会话：锁屏完全停止、解锁不重启、重启丢弃锚点，尚未实现，NOT RUN。
- 真实源失败保留锚点、同源重试及恢复，尚未实现，NOT RUN。
- 刷新率、帧耗时、耗电及独立官方时间误差，NOT RUN；不能从 JVM 测试推断。
- 正式签名、安装升级与发布校验和，未授权且本轮不实现。

### v0.1.0 交付检查

v0.1.0 交付时 PRD 保持原样。只维护两个模块，版本为 0.1.0 / versionCode 1。
提交前检查 `git diff --check`、Wrapper 校验和及暂存文件列表。
不得纳入 local.properties、机器 SDK 路径、签名密钥、凭据、APK、缓存或 build 目录。
