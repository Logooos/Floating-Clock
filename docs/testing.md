# 验证记录

最后核对日期：2026-09-29。

## v0.6.0-home.1 首页视觉预览

本轮仅实现 A「静谧卡片」首页。基线代码为 `a9ac7b9a70f01164f250221919ad08c82a84ea3c`，
基线文档 main 为 `8ad56a7607e0e3262e0270c5c5093eee60781267`。保留原 129 项测试，新增 6 项：
core 72、app 34、仪器 29，共 **135 项独立用例**，模拟器矩阵不重复计数。

新增覆盖首页状态映射、实际来源标签、偏移与锚点门控、虚拟单调时钟推进／停止清空、
主按钮启动／停止原服务，以及浅／深色 × 停止／正常／失效的真实 Compose 渲染。
截图测试使用注入的固定时钟和真实 TimeEngine，显式标注“离线截图样例 · 模拟校准”；
截图不证明访问了公共时间源，也不证明误差或帧率。可安装 APK 继续使用用户配置的真实来源。

本地使用现有 WSL、隔离工具链和 API 35 无界面模拟器，命令：
`./gradlew testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest --continue --max-workers=2 --console=plain --no-daemon`。
首轮四项全部通过。检查实际截图后收紧首页间距，最终复验返回 BUILD SUCCESSFUL（102 个任务，20 执行、82 up-to-date）。
JUnit XML 确认 core 72、app 34、API 35 仪器 29 项全部通过，失败／错误／跳过均 0；lint 与 APK 构建通过。
最终受测源文件 SHA-256 与提交前快照一致。逐张检查六张实际 API 35 PNG，失效提示及主操作可见。
本地 API 31/33 本轮 NOT RUN，待对应代码提交的 CI 矩阵核对。

CI 保留 API 31/33/35；API 35 通过 MediaStore 保存截图，测试后拉取六张 PNG，逐个检查存在且非空。
`home-api35-screenshots` 是截图产物，`floating-clock-debug` 是 Debug APK，测试报告仍单独上传。
本地与 CI 的测试均不要求真实公网。API 31 的通知拒绝用例不适用，预期执行 28 项。

本轮未修改校时算法、网络适配器、悬浮服务、DataStore、Room Schema 或迁移。首页是现有会话的只读消费者，
不会创建新校准会话，停止时不显示历史基准。版本为预览版本而非完整 v0.6.0。
真机视觉确认、TalkBack、200% 字体完整页面检查、厂商系统适配与覆盖安装：NOT RUN。
不声称真机 120FPS 或 50ms 精度通过；完整预设迁移、其他页面和悬浮标签重构等待用户确认。

## v0.5.0 验证

基线 main 为 `33d48d7675e55d9c6ea030b57f913afa3016797c`（v0.4.0 验证文档）；
对应已验证代码为 `3538286a7e15b52d9bdbd180df42299939058c91`。
保留原 100 项测试，目前新增 29 项：core 72、app 31、仪器 26，共 129 项独立用例。
这些是源码用例数，最终执行数量以 JUnit XML 为准，API 矩阵不重复计数。

本轮发现现有 WSL Ubuntu 22.04 和可用 KVM，工具链由代理下载到被忽略的 `.tools/`，
不要求用户安装 Android Studio。使用官方 JDK 17、SDK 36、Gradle Wrapper；模拟器无界面运行。
工具链下载校验后运行；没有提交 SDK 路径、构建输出、测试数据库或导出文件。

首轮代码生成已通过；完整检查的首轮 Debug APK 和测试 APK 构建成功，但整体失败：
- 既有 HTTP 超时测试强制等待 accept，而 200ms 总超时可在冷 JVM 建连前结束；已将
  无响应超时与握手后取消分别验证，仍保持原测试的超时／取消覆盖，不使用 sleep。
- lint 拦截 Compose 直接读取 StateFlow.value；已改为 collectAsState。
- 上述失败不记为验收通过，修复后重新执行全部检查。

新增覆盖包括配置文件重开、并发事务、损坏恢复、平台约束、单位／时区／共享来源恢复、
四风格三模式、运行中原窗口改样式、大字体警告区域不缩小、7 天边界及上限、Room 实际迁移与重开、分页导出边界、
JSON/CSV 转义、未知值、取消、空间／权限／关闭失败、禁用备份及重启不自动校时。
Schema 1 为开发基线，迁移测试确实创建该版本并通过 Room 校验升级到 2，未伪称旧版本已发布。

第二轮 72 项 core、31 项 app 单元测试、lint 和 APK 构建通过。API 31 实际执行 24 项，
其中 22 项通过，2 项失败：Room MigrationTestHelper 的序列化运行时被一致性解析降为
1.7.3，以及 Android 写入器在 flush 失败后未关闭底层流。已对齐序列化 BOM 1.8.1，
并为原始输出流增加独立 use；最终复验两项均通过。
本地 API 33/35 镜像准备因官方 SDK 包解压错误失败，未执行该两版本测试；云端矩阵保留。

最终本地通过 WSL 执行 Wrapper，命令为
`./gradlew testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest --continue --max-workers=2 --console=plain --no-daemon`，
返回 `BUILD SUCCESSFUL`（102 个任务，100 执行、2 个 up-to-date）。

| 检查 | 结果 |
| --- | --- |
| `testDebugUnitTest`（含 `:core:time:test`） | PASS：core 72、app 31；失败/错误/跳过均 0 |
| `lintDebug` | PASS |
| `assembleDebug` | PASS，Debug APK 已生成 |
| API 31 `connectedDebugAndroidTest` | PASS：25 项，失败/错误/跳过均 0；API 33+ 通知拒绝用例不适用 |
| 本地 API 33/35 | NOT RUN：镜像准备失败；由保留的云端矩阵验证 |

JUnit XML 确认新增大字体绘制测试实际执行，最终源文件哈希与构建期间快照一致。
原 100 项测试名与基线逐项比对，无缺失。提交前检查 diff、机器路径、凭据模式和构建产物，PRD 未修改。
代码及版本提交 `a9ac7b9a70f01164f250221919ad08c82a84ea3c` 已推送 main，对应
[Android CI 36541244158](https://github.com/Logooos/Floating-Clock/actions/runs/36541244158)
整体和全部四个任务均为 **success**。逐项核对工作流的 JUnit 公告：

| 云端环境 | 检查与结果 |
| --- | --- |
| build / JDK 17 / SDK 36 | `testDebugUnitTest`：core 72、app 31，全部通过；`lintDebug`、`assembleDebug`、提交的 Room Schema 一致性检查通过 |
| API 31 | `connectedDebugAndroidTest`：25 项通过，通知拒绝用例不适用 |
| API 33 | 同上：26 项通过 |
| API 35 | 同上：26 项通过 |

各报告失败、错误、跳过均为 0；129 项独立用例全部在适用环境得到验证。
产物：floating-clock-debug（11020922092，归档 10,913,571 字节）、android-check-reports
（11020612809）、API 31/33/35 报告（11020418450、11020823553、11020963090）。
从上述 CI 页面下载。API 31 另做了已构建 APK 的设置页外观核对；没有启动公共网络同步。

后续验证记录提交只修改此文档并使用 `[skip ci]`；上述 SHA 才是受测代码及版本提交。
工作流对现有 Action v4 给出 Node 20/setup-java v4 弃用警告，本轮运行通过，后续应单独升级验证。
无真机验证、OEM 设备迁移验收、真实文档提供器满盘验收、120FPS 或 50ms 结论。

## v0.4.0 验证

基线 main 为 ae741ea，v0.3.0 代码 00aee5d 的 CI 全部通过。
保留原 63 项，新增 37 项，累计 100 项独立用例：71 项 core JVM、17 项 app 单元测试、
12 项仪器测试。API 31/33/35 运行同一组仪器测试，不重复计数；API 31 不执行通知权限拒绝用例。

新增确定性覆盖：API 来源排序、系统时钟不可用/读取区间/跳变、NTP 四时间戳、时代边界、
模式/来源/状态/时间戳/延迟过滤、HTTP 缓存及秒级量化、初始备选、运行中保留锚点和同源恢复、
手动请求合并、请求预算并发、智能间隔、取消/晚到结果保护、明显跳变复核及标签独立。
回环 UDP/HTTP 测试只使用 127.0.0.1 随机端口和固定时间；没有公网 DNS、Thread.sleep 或当前日期依赖。
真实 I/O 超时用较短超时及事件握手验证，不把运行耗时当作精度；同步调度使用虚拟时间。
模拟器沿用演示模式，新增真实服务锁屏取消挂起假源及 API 来源目录检查，不向公网发包。

本地实际尝试 `./gradlew.bat testDebugUnitTest`、`lintDebug`、`assembleDebug`、
`connectedDebugAndroidTest`：全部 **NOT RUN**，当前环境无 JDK/JAVA_HOME，Wrapper 无法启动。
XML/YAML/TOML 解析与 `git diff --check` 仅为静态检查，不等同于编译通过。

首轮 b3075af 的 CI 36457131979 编译失败：最后成功时间显示的 floorMod 返回 Int，
已在 84da25a 改用 Long 纳秒重载；该失败不能记为测试通过，模拟器当轮未运行。
修复提交 `84da25a4a936afb4fc52b618907447e5dd370133` 的
[CI 36457436627](https://github.com/Logooos/Floating-Clock/actions/runs/36457436627) 已全部成功：
单元测试（包含 core:time）、lint、Debug APK，以及 API 31/33/35 模拟器均 PASS。
完整实现验收后更新为 0.4.0 / versionCode 4，并强化 URL 校验、测试边界与 CI 报告计数。
最终代码/版本提交 `3538286a7e15b52d9bdbd180df42299939058c91` 的
[Android CI 36488523850](https://github.com/Logooos/Floating-Clock/actions/runs/36488523850)
已完成，整体及全部任务均为 **success**。从工作流解析的 JUnit XML 公告核对实际数量：

| 环境 | 实际命令 | 结果 |
| --- | --- | --- |
| GitHub Actions / JDK 17 / SDK 36 | `./gradlew testDebugUnitTest --stacktrace` | PASS：core 71、app 17；失败、错误、跳过均为 0 |
| 同上 | `./gradlew lintDebug --stacktrace` | PASS |
| 同上 | `./gradlew assembleDebug --stacktrace` | PASS |
| API 31 模拟器 | `./gradlew connectedDebugAndroidTest --stacktrace` | PASS：11 项；通知权限拒绝用例不适用，被 SDK 过滤器排除，不计为执行通过 |
| API 33 模拟器 | 同上 | PASS：12 项，失败/错误/跳过均为 0 |
| API 35 模拟器 | 同上 | PASS：12 项，失败/错误/跳过均为 0 |

全部 100 项独立用例在适用环境中得到验证；多 API 重复执行不叠加独立用例数。
产物已上传：floating-clock-debug（ID 10999909964，归档 9,739,229 字节）、
android-check-reports（11001245249）、api-31-smoke-reports（11001220801）、
api-33-smoke-reports（11000976107）、api-35-smoke-reports（11000735983）。
APK 与报告可从上述运行页面下载。

后续验证记录提交仅修改本文件，使用 `[skip ci]`；受测代码、版本及工作流未变化。
已检查提交文件、静态格式及工作区，未纳入机器 SDK 路径、密钥、构建输出；PRD 未改。
未创建分支、正式 Release、标签或签名密钥。
CI 尚有既有 Actions 的 Node 20 / setup-java v4 弃用警告，不影响本次成功结果，后续维护时升级。

NOT RUN：真实公共 NTP/HTTPS 端点连通性与限频实测、UDP 123 受限的真实运营商网络、
OEM 系统网络时间缓存/恢复、真机跨 App/120FPS/耗电、独立参考下的时间误差、API 32/34/36。
无真机或独立可信参考源；模拟器与回环测试不证明 50ms 或官方购物活动时间准确度。

## v0.3.0 验证

基线 bf2aefc，工作区干净；上一次代码 b4d7d90 的 CI 成功。
保留 39 项核心 JVM 测试及 2 项首页仪器测试；新增 14 项 app 单元测试、8 项仪器测试，
累计 63 个独立测试用例。API 31/35 重复运行同一组仪器测试不重复计入数量。

本地实际尝试 gradlew.bat testDebugUnitTest、lintDebug、assembleDebug、
connectedDebugAndroidTest：全部 NOT RUN，无 JDK/JAVA_HOME，Wrapper 无法启动。
最终代码提交 `00aee5d08a8b917cd4b638b21fc50398024c8424` 的
[Android CI 36419903183](https://github.com/Logooos/Floating-Clock/actions/runs/36419903183)
已完成，整体结果为 success。实际执行结果：

| 环境 | 命令 | 结果 |
| --- | --- | --- |
| GitHub Actions / JDK 17 / SDK 36 | `./gradlew testDebugUnitTest --stacktrace` | PASS，包含 39 项 core 和 14 项 app 单元测试 |
| 同上 | `./gradlew lintDebug --stacktrace` | PASS |
| 同上 | `./gradlew assembleDebug --stacktrace` | PASS |
| API 31 模拟器 | `./gradlew connectedDebugAndroidTest --stacktrace` | PASS；通知运行时权限拒绝用例由 `SdkSuppress(minSdkVersion = 33)` 排除，不适用于 Android 12 |
| API 35 模拟器 | `./gradlew connectedDebugAndroidTest --stacktrace` | PASS，包含通知拒绝后的首页停止用例 |

保留原有 41 项，新增 22 项，总计 63 项：53 项单元测试、10 项仪器测试。
模拟器只验证功能与生命周期，不证明真机帧率、耗电或时间精度。
窗口/手势测试使用公开 WindowInspector 检查实际窗口数量、附着和移除，
通过同步 UiAutomation 触摸注入验证点击与长按，不依赖无障碍窗口标题。

早期运行 36417361722、36418012629、36418949951 的模拟器任务曾失败；
修正了窗口查找方式、通知用例 SDK 前提及初次显示时的触摸同步后，上述最终运行通过。
没有把这些早期失败记为通过，也没有跳过 API 31 手势测试。

最终运行的产物：`floating-clock-debug`（ID 10969315690，归档 9,697,171 字节）、
`android-check-reports`（10969210807）、`api-31-smoke-reports`（10968933568）、
`api-35-smoke-reports`（10969606010），可从运行页面下载。
此验证记录为后续仅文档提交，使用 `[skip ci]`；受测代码、版本及工作流保持不变。
已检查工作区和提交文件，未提交机器 SDK 路径、密钥或构建产物；没有创建正式 Release。

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
