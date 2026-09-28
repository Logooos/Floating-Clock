# 时间来源审计

审计日期：2026-09-29。范围为官方公开文档与本项目可直接使用的无密钥方案。
“未找到合格来源”不是断言该平台没有内部时钟接口；未访问登录、签名、反爬或非公开接口。
文档可读不等于端点已实测可用，以下公网端点本轮均未进行连通性或独立精度验证。

## 五个平台入口

| 平台入口 | 官方证据及时间接口 | 无密钥访问 | 返回分辨率／误差 | 限制、稳定性与接入结论 |
| --- | --- | --- | --- | --- |
| 淘宝／天猫 | [淘宝 taobao.time.get](https://developer.alibaba.com/docs/api.htm?apiId=120&scopeId=381) 明确公开系统时间 API；未据此断言天猫业务活动时钟相同 | 不满足：不要求用户 session，但 app_key、sign_method、sign 必填，官方示例需要 secret | 文档格式 yyyy-MM-dd HH:mm:ss，为秒级；实测误差未知 | 不嵌入 AppKey/secret、不借用示例凭据，不接入。运行稳定性未验证。淘宝／天猫保持单一入口 |
| 京东 | [京东开放平台](https://open.jd.com/)；[官方接入说明](https://help.jd.com/oapihelp/question-460.html)涉及应用与授权。未验证到可匿名调用的独立时间 API | 时间接口的无密钥资格未确认 | 未知 | 门户部分内容依赖脚本；没有足够接口证据，不实现猜测端点，稳定性未知 |
| 美团 | [美团开放平台](https://openapi.meituan.com/)；[官方 SDK 文章](https://tech.meituan.com/2023/01/05/openplatform-sdk-auto-generate.html)说明 developerId/signKey 签名。未确认合格的公开时间 API | 未确认；已核对的业务 SDK 需要签名，不作为匿名时钟依据 | 未知 | 业务 SDK 不是时间精度承诺；不接入需要秘密参数的接口，稳定性未知 |
| 拼多多 | [官方开放平台](https://open.pinduoduo.com/)与[应用入口](https://open.yangkeduo.com/application/)；未取得可验证的匿名时间接口文档 | 未确认 | 未知 | 公开页面访问及脚本内容有限，不以非官方代码或论坛中的 URL 作为证据，不接入，稳定性未知 |
| 抖音 | [官方 API 目录](https://developer.open-douyin.com/docs/resource/zh-CN/dop/develop/openapi/list)、[公共参数](https://developer.open-douyin.com/docs/resource/zh-CN/dop/develop/openapi/common-params)及[凭据安全说明](https://developer.open-douyin.com/docs/resource/zh-CN/dop/develop/sdk/mobile-app/permission/get-permission-token)；未确认匿名独立时间 API | 未确认；需要 client_secret/token 的能力不纳入本地方案 | 未知 | 不获取购物账号、不把 secret 放进 APK、不绕过授权，稳定性未知 |

**实际实现：五个入口都可使用同一公共网络来源，分别保留平台偏移；没有 OFFICIAL_API 适配器。**
UI 的平台名称仅表示显示分组。HTTP Date 也不会因 URL 属于购物平台而改为官方时间。
v0.4.0 首页选择的是会话共用来源，未提供每行不同来源的配置；引擎既有平台模型保持独立。

## 已实现的来源

| 来源 | 证据、数据与访问条件 | 实现与限制 |
| --- | --- | --- |
| Android 系统网络时间 | [SystemClock.currentNetworkTimeClock](https://developer.android.com/reference/android/os/SystemClock#currentNetworkTimeClock())，API 33+ 公开方法，无应用密钥 | 默认优先；在 I/O 上读取，以前后单调时间中点关联毫秒值；超过 100ms 的读取拒绝。DateTimeException 等不可用情况失败。系统缓存年龄、独立精度未知；不假装触发系统主动联网校时。API 31/32 完全不调用该方法 |
| Cloudflare NTP | [官方 NTP 使用说明](https://developers.cloudflare.com/time-services/ntp/usage/)，time.cloudflare.com，UDP 123，无密钥 | API 31/32 默认、API 33+ 初始备用；每次 DNS 解析同一主机，单次 UDP socket 连接到解析出的固定地址/端口并校验回应。原主机恢复时继续采样 |
| Google Public NTP | [官方指南](https://developers.google.com/time/guides)，time.google.com，UDP 123，无密钥 | 仅初始备用或用户明确选择；[闰秒平滑](https://developers.google.com/time/smear)可能与其他 UTC 服务在闰秒附近不同，因此不在运行中混用或自动切换 |
| HTTP_ESTIMATED | [RFC 9110 Date](https://www.rfc-editor.org/rfc/rfc9110.html#name-date)，公开 HTTPS HEAD 响应的 Date 头，无登录或密钥 | 高级选项输入公开 URL，没有内置未经审计的购物平台网页。禁止认证信息、查询参数、片段和明文 HTTP（仅 JVM 回环测试可显式放行）。拒绝重定向、非 200、Date 缺失/重复/非法、Age 非零、Via/Warning、缓存 HIT/STALE、无新鲜度指令；仍无法证明 CDN 或上游时钟准确性 |
| 离线演示 | 固定 2026-01-01 UTC 起点 + 单调时间 | 自动化使用，不访问公网；明确标为演示，不能当真实来源 |

NTP 使用 [RFC 5905](https://www.rfc-editor.org/rfc/rfc5905) 的 48 字节客户端包及四时间戳公式。
校验响应长度、来源地址/端口、版本 3/4、server 模式、leap、stratum/KoD、originate、
reference/receive/transmit、处理时长、root delay/dispersion 和总延迟。
扩展字段及认证包当前拒绝；**未实现 NTS，UDP 来源检查不等于密码学认证**。
NTP era 根据请求时本机日期选择最近 68 年范围，严重错误的系统日期需用户先修正。
一次墙钟读取只用于构造 t1，t4 使用单调增量，途中修改墙钟不会污染 RTT。

HTTP 的 Date 只有整秒，使用 Date + RTT/2 作为收包时刻的粗估基准。
秒级量化、生成位置、传输非对称、隐藏中间层均未被独立测量；三位毫秒仅是插值展示。
不确定度与实际误差始终未知，RTT、来源标称分辨率、校准跳变量都不等于已验证误差。

## 调度、失败与隐私

- 只有用户手动启动服务才采样；首页选项和显示帧不触发网络。
- 自动初选：API 33+ 系统网络 → Cloudflare → Google；API 31/32 Cloudflare → Google。
  手动高级来源仅尝试所选项，不偷偷兜底；所有候选失败后只继续探测最后选定的候选。
- 每主机/URL 在进程内至少间隔 30 秒，自动 30–300 秒。稳定且低延迟时倍增间隔，
  不稳定时回到下限；失败按 30/60/300 秒退避，连续三次失败强制警告并保留旧基准。
  这是工程限频，非平台批准的额度；未来有明确更严格的来源条款时需相应上调。
- 大于 2 秒的锚点跳变先拒绝，需后续同源样本与待复核基准相差不超过 250ms 才接受；
  该异常过滤阈值不是精度保证。初始样本无独立参照，不声称能检出所有系统性误差。
- 每次单请求最长约 2 秒，RTT 超过 2 秒拒绝；无忙重试。手动操作合并且遵守下限。
  锁屏/停止取消协程、关闭 UDP/HTTP 资源和 Android DNS 请求；已进入系统解析或 Binder
  的调用可能到返回/超时才完全退出，但不得发布结果或继续调度。
- 只向所选时间服务发送协议请求，没有账号、遥测、开发者后端或日志上传。服务提供方仍可见
  请求 IP。配置/预算不跨进程持久化；v0.5.0 才处理持久化，不承诺跨强制重启的限频存储。

## 验证边界

CI 仅访问回环模拟 HTTP/NTP 服务和注入时钟，不依赖真实公共时间端点。
公网连通性（包括 UDP 123）、大陆网络可用性、OEM 系统缓存、真实网络误差与真机性能均 NOT RUN。
没有独立可信参照，因此不得声称已验证 50ms、官方业务活动时间或真机 120FPS。
