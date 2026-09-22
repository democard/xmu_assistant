# 项目扫描与修复验证报告

日期：2026-09-22。起始版本：`e40c5d8`。以下保留第一轮验证记录；第二轮全项目扫描与修复结果见文末。两轮均由主代理规划、审核和统一验证，由三个 `gpt-5.6-luna / medium` 子代理分工执行。

本报告已脱敏。`build/` 和 `android/app/build/` 下的日志为本地验证证据，不随仓库提交；运行配置、账号信息及含个人数据的截图不进入公开仓库或发布附件。

## 依赖安装

- 复用本机 Python、requests、PySide6、pytest、Ruff，无项目依赖版本变更。
- Android 使用项目 `.tools/jdk17/jdk-17.0.20.1+1` 与 `.tools/gradle-8.7/gradle-8.7`，依赖从已有缓存离线读取。
- 初次 Android 验证失败是本机环境变量仍指向迁移前路径：旧缓存缺少 Android 插件，SDK 路径也不存在。最终仅在构建进程中设置正确环境，未修改用户全局环境。
- 实际使用本机已有的 Gradle 缓存和 Android SDK，个人目录路径已脱敏。前期 wrapper 下载过 Gradle 8.7，最终验证使用项目内现有分发。

## 服务启动

本轮验证不需要启动应用后台服务。桌面测试使用 Qt offscreen 模式；Android 测试使用 JVM、Robolectric 和本地 MockWebServer。未使用校园账号、未提交真实签到、未发送真实通知。没有创建需要用户维护的常驻服务。

Android 源码编译、单元测试和 Lint 实际执行完成，构建结果为 `BUILD SUCCESSFUL`。未重新生成发布安装包，未发布或提交 Git 变更。

## 功能验证

| 编号 | 场景与断言 | 结果 |
| --- | --- | --- |
| DL-01 | 双端收到错误起点、缺失/非法范围、只覆盖中间部分的 206 响应时，拒绝把文件标记成功，旧断点保留 | 通过 |
| DL-02 | 206 短响应或字节数不符时，临时片段不污染旧断点；正确 206 正常拼接，服务端忽略 Range 的 200 正常覆盖 | 通过 |
| DL-03 | 接近文件名长度上限的下载可正常续传，新增临时片段成功/失败后清理 | 通过 |
| DL-04 | PC 的真实 gzip 响应经 requests 自动解压后，不再拿压缩长度误判下载失败；压缩续传不写入已有断点 | 通过 |
| DL-05 | Android 下载返回 JSON/XHTML 错误载荷时，不把空文件或已有断点提升为正式文件 | 通过 |
| CACHE-01 | 课表缓存原子替换失败后旧快照仍可读取；正常连续保存能替换旧快照；重试成功即停止 | 通过 |
| CACHE-02 | 考试学期探测时间戳在未来时，允许重新探测，避免系统时钟回拨后长期不刷新 | 通过 |
| MAIL-01 | 非法/越界 SMTP 端口不会发起连接；空配置保留默认端口；混合列表只尝试合法端口 | 通过 |
| TYPE-01 | 桌面单实例模块的前向类型引用可被静态检查解析，运行时不产生循环导入 | 通过 |

最终检查：

- `python -m pytest tests -q --tb=short`：**469 passed，144 subtests passed**。
- `python -m ruff check xmu-rollcall-cli tests scripts --select E9,F63,F7,F82`：通过。
- `python -m compileall -q xmu-rollcall-cli tests scripts`：通过。
- 在 `android/` 执行项目内 Gradle：`-I gradle/init.gradle :app:testDebugUnitTest :app:lintDebug --offline --console=plain`：通过。测试 XML 汇总 **604 项，0 失败、0 错误、0 跳过**；Lint **0 错误、6 警告**。
- `git diff --check`：通过。

证据：

- [桌面完整测试日志](build/project-scan-python-final.log)
- [Android 构建与验证日志](build/project-scan-android-final.log)
- [Android 单元测试报告](android/app/build/reports/tests/testDebugUnitTest/index.html)
- [Android Lint 报告](android/app/build/reports/lint-results-debug.html)
- [原版代码失败复现日志](build/project-scan-before-reproduction.log)

原版复现使用 `build/project-scan-before/` 内从 Git HEAD 提取的隔离源码，配合本轮新增测试，不替换工作区实现。选定的错误续传、压缩下载和非法端口用例出现预期失败（pytest 统计 11 个失败，含子测试；1 项通过），证明回归用例能识别旧行为。

## 修复记录

1. 双端下载新增 Content-Range 和实际片段长度校验，先验证临时片段再接入已有断点，避免错误范围或短流生成损坏成品。
2. Android 调用层与传输层统一拒绝 JSON/XHTML 错误载荷，消除空文件/旧断点被误报成功的路径。
3. PC 区分压缩传输长度与解压后文件长度；通知端拒绝无有效 SMTP 端口的配置。
4. Android 课表缓存改用原子替换，失败保留旧文件，成功后立即停止重试；考试缓存修复系统时间回拨边界。
5. 桌面前向类型通过 `TYPE_CHECKING` 导入，消除两处未定义类型检查错误。

所有实现均经过主代理差异审阅并运行对应回归。未保留会破坏现有下载进度计数的批量去重改动。

## 已知问题

- 本轮覆盖本地行为、模拟网络、编译和静态检查；未进行真实校园网络、Android 真机或发布安装包验证，也未做界面视觉验收。没有界面布局改动。
- 严格续传要求 206 返回可确认总长度并覆盖到末尾的片段；未知总长、压缩续传或仅返回中间段会明确失败并保留断点。临时校验片段会额外占用磁盘空间，约为本次续传片段大小。
- 没有做 ETag/Last-Modified 跨次下载一致性验证；服务端文件内容改变但字节范围仍合法的情形不在本轮验证范围内。
- Lint 仍有 6 个警告，具体位置见报告；不将无错误等同于无警告。构建还报告迁移前 `sdk.dir` 路径告警，实际通过正确 SDK 环境变量完成验证。
- 本轮是针对下载完整性、缓存保留和错误恢复的一轮维护，不代表所有功能已穷尽验证。

## 第二轮：全项目扫描与增量修复

### 范围与方法

在保留第一轮所有改动的基础上，三个 `gpt-5.6-luna / medium` 子代理分别检查桌面端、Android 网络与会话核心、Android 业务功能与资源；成绩分享测试环境的专项排查交给一个 `gpt-5.6-sol / medium` 子代理。分配清单共 154 个代码、资源、配置和配套说明文件：桌面 46 个、Android 核心 43 个、Android 功能 65 个。逐文件阅读或检查结构及调用关系，相关测试另行交叉核对；主代理审阅候选问题、补查打包产物、审核所有修复并统一运行验证。另对当时已跟踪的 80 个 Python 文件与 27 个 XML 文件做了解析检查，均通过。文件扫描不等于所有设备和网络场景都已动态验证。

本轮没有升级依赖。测试继续使用本机依赖、离线 Gradle 缓存、模拟网络和 Qt offscreen；没有真实校园操作或常驻服务。额外构建了用于验证资源打包的本地 Python wheel，未发布。

### 修复与行为回归

| 项目 | 修复后的行为与验证 |
| --- | --- |
| 桌面下载批次归属 | 下载开始时固定账号、源会话和独立批次标记；换号、同账号重登及登出使旧标记失效。旧进度、逐项完成、完成事件和 Cookie 回写均被丢弃，旧批次不能解锁新批次；会话克隆失败带真实错误结束。行为测试实际执行登录切换和事件处理。 |
| 桌面模块缓存隔离 | 缓存按源会话隔离，并以弱引用释放失效会话。相同会话的 worker 克隆仍复用 TTL 缓存；旧请求晚返回或克隆期间换号不能污染新会话缓存。 |
| 双端课件直链识别 | 根据 URL 路径识别扩展名，支持 `.pdf#page=1`、查询参数与 fragment 组合；Android 页面计数和下载分流共用判断，并覆盖含空格、中文的路径。 |
| Android 下载 Cookie | 初始下载请求也比较协议、主机和有效端口；同主机明文地址、异端口和伪装域不携带登录 Cookie，正常 HTTPS 与显式 443 端口仍可携带。使用模拟传输验证。 |
| Android 网络基准变体 | 补齐新增操作枚举的显式拒绝分支，修复 `networkBenchmark` 变体无法编译。保持原有只读测试计划，不增加网络操作。 |
| 日历导出事件标识 | 使用排课字段与周次段的稳定 SHA-256 摘要，避免同日起始节相同的不同课程记录，以及 `Aa`/`BB` 这类字符串 hash 碰撞共享 UID；重复导出和调整输入顺序仍保持标识稳定。 |
| Android 旧系统成绩分享 | 私有图片文件先独占创建，避免快速连续保存覆盖前一张；压缩返回 false 或抛异常时删除本次失败文件并返回失败。 |
| Python 安装包图标 | wheel 带入图标和品牌 PNG，资源查找增加包内回退，资源生成脚本同步副本。在仓库外解包 wheel，实际加载 Qt 图标及品牌 PNG，验证不依赖源码目录。 |

### 统一验证与证据

- 桌面完整测试：**481 passed，144 subtests passed**，见 [最终测试日志](build/scan2/python-final.log)。
- Python 致命语法/名称静态检查及编译检查通过，见 [静态检查日志](build/scan2/python-static.log)。
- Android 完整 Debug 测试：**610 项，0 失败、0 错误、0 跳过**；Lint **0 错误、6 警告、12 条提示**。构建成功，见 [完整验证日志](build/scan2/android-final.log)。
- `networkBenchmark` 变体编译和定向模式/安全测试通过：**4 项，0 失败、0 错误、0 跳过**，见 [变体验证日志](build/scan2/android-benchmark-final.log)。只运行本地测试，没有实际校园网络基准请求。
- `git diff --check`：通过。
- 实际 wheel 资源与图标加载验证通过，见 [打包日志](build/scan2/python-wheel-final.log)及[隔离加载结果](build/scan2/wheel-smoke-final.log)。
- 修复前复现：从本轮开始状态创建隔离源码副本，带入新增回归，缓存隔离、fragment 直链、旧批次完成和进度四项按预期失败；当前源码均通过，见 [复现日志](build/scan2/python-before-reproduction.log)。网络基准变体在修复前有明确编译失败，见 [变体失败日志](build/scan2/android-benchmark.log)。旧 wheel 缺少资源且 Qt 图标为空，见 [旧 wheel 检查](build/scan2/wheel-smoke.log)。
- 三份扫描底稿与覆盖清单： [桌面](build/scan2/desktop-scan.md)、[Android 核心](build/scan2/android-core-scan.md)、[Android 功能](build/scan2/android-features-scan.md)。底稿中的候选问题以本报告的审核结论为准。

### 保留事项与验证边界

- 会话 Cookie 写盘失败目前记录诊断日志，本次登录仍可继续使用；未增加“下次无法自动登录”的界面提醒，列为后续体验改善事项。
- 请求门在协程启动前取消的候选问题，尚未确认当前生命周期中存在可继续操作却被永久占用的路径；本轮不改。CAS 登录中的互斥标记保留到旧请求结束，以免登出清标记后引入两次并发登录。
- 下载请求已发出后不会被强制中断；本轮保证旧事件不污染新账号，并在后续文件开始前检查会话。没有承诺立即撤回已进行中的文件写入。
- ICS UID 规则改变后，再向已导入旧版本日历的同一日历导入，可能形成新旧两组事件；本轮未做旧 UID 迁移，也未实测第三方日历客户端导入。
- 成绩图片存储测试使用 SDK 28 与真实 PNG 编码/文件写入。Windows 下 AndroidX FileProvider 无法匹配反斜杠路径，测试仅模拟该组件的 URI 映射；没有验证真机文件共享组件。压缩失败测试明确断言执行了 `compress=false` 分支。见 [分享专项测试日志](build/scan2/android-share-repair.log)。
- 没有 Android 真机、真实校园响应、Windows 安装包或真实通知发送验收。第一轮列出的下载一致性边界继续适用。
- 第二轮扫描结束时所有改动保留在工作区；后续打包验收记录见下节。

## v1.7.3 发布验收

版本：Windows 1.7.3；Android 1.7.3 / versionCode 30。基于仓库 `democard/xmu_assistant` 的 v1.7.2 提交 `e40c5d8` 与本报告记录的两轮修复构建。

| 检查 | 结果 |
| --- | --- |
| Windows 完整回归 | 481 项测试、144 个子测试通过，致命语法/名称检查与编译检查通过 |
| Windows EXE | PyInstaller 构建成功；归档内版本为 1.7.3，包含应用图标与 DPAPI 模块，没有误收集根目录 ICU DLL；实际首页和图标显示正常 |
| 桌面覆盖 | 保留旧 EXE 备份后覆盖桌面程序，新桌面文件与发布 EXE 的 SHA-256 一致；正式账号配置保留 |
| Android 完整回归 | 610 项测试通过，0 失败/错误/跳过；Lint 0 错误、6 警告 |
| Android Release | release 构建及 R8 压缩通过；包名 `com.xmu.assistant`，版本 1.7.3 / 30，minSdk 26、targetSdk 35，无 debuggable 标记 |
| Android 签名 | apksigner 验证通过；证书与 v1.7.2 相同，可覆盖同签名旧版 |
| 模拟器升级 | 旧 APK 安装、新 APK 覆盖升级和启动成功；本次创建的模拟器已停止 |
| 安装包隐私检查 | 检查 EXE 170 个归档成员、636 个 PYZ 模块及 APK 229 个成员；未打入运行配置、Cookie 或界面快照。与测试运行数据比对，只命中公开的 SMTP 主机和通用状态文案，未命中身份、凭据或个人课程数据 |
| 源码脱敏检查 | 对待提交快照的 320 个跟踪文件检查并与测试运行值比对；额外命中均为通用学年或原有独立课程示例，无账号关联。未发现真实身份、凭据或运行数据文件；公开报告中的个人目录路径已脱敏 |

发布文件为 `xmu-assistant.exe`、`xmu-assistant-release.apk` 和 `SHA256SUMS.txt`。二进制仅作为 GitHub Release 附件；仓库提交源码、测试、说明和校验文件。本地临时文件与敏感截图由忽略规则排除，不上传。

验证日志：`build/release-1.7.3/python-tests.log`、`windows-build.log`、`windows-archive.log`、`android-gradle-1.7.3.log`、`android-device.log`、`android-signature.log`、`android-metadata.log`。实际校园功能、真实通知发送和 Android 真机完整流程不在本次发布验收范围；Windows 页面切换未完成单独人工验收。日历 UID 迁移边界与上述已知事项继续适用。
