# 验证记录

记录日期：2026-09-28；开发里程碑 v0.1.0。

## 当前执行结果

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
| GitHub Actions | 未验证 / NOT RUN | 已配置用户提供的远端；推送因缺少 GitHub 凭据失败，API 返回无工作流运行记录 |
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
实际尝试 `git push -u origin codex/bootstrap-v0.1.0`，非交互重试返回
`could not read Username for 'https://github.com': terminal prompts disabled`。
认证恢复后重新推送该分支，查看 [Actions](https://github.com/Logooos/Floating-Clock/actions)，
并以实际结果更新此表。本次没有向 main 推送，没有发布 Release。

## 未完成的设备及后续验证

- 小米 14 Pro / HyperOS 3：安装、启动、旋转、大字体、无障碍与长时间表现，NOT RUN。
- API 32、33、34、35、36 的扩展兼容矩阵，NOT RUN；本阶段仅配置 API 31 冒烟。
- 后续悬浮窗：跨 App、拖动、权限撤回、厂商限制，尚未实现，NOT RUN。
- 后续会话：锁屏完全停止、解锁不重启、重启丢弃锚点，尚未实现，NOT RUN。
- 真实源失败保留锚点、同源重试及恢复，尚未实现，NOT RUN。
- 刷新率、帧耗时、耗电及独立官方时间误差，NOT RUN；不能从 JVM 测试推断。
- 正式签名、安装升级与发布校验和，未授权且本轮不实现。

## 交付检查

PRD 保持原样。只维护两个模块，版本为 0.1.0 / versionCode 1。
提交前检查 `git diff --check`、Wrapper 校验和及暂存文件列表。
不得纳入 local.properties、机器 SDK 路径、签名密钥、凭据、APK、缓存或 build 目录。
