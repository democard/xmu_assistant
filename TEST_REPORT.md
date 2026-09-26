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

## 2026-09-25 四轮全量维护验收

起始提交：`27d7b44`。本节记录本次维护，保留上文历史发布记录及工作区原有两份交接文档。由主代理统一验收，三个独立代理分别参与实现、复现和交叉审查。四轮均运行完整 Windows 测试目录及 Android Debug 单元测试；定向红测和修复复验不单独计为一轮。起始 Windows 基线为 507 项测试、154 个子测试通过。

### 依赖安装与运行环境

复用 Python 3.14.6、requests 2.34.2、PySide6 6.11.2、PyInstaller 6.22.2、项目 JDK 17、Gradle 8.7 与现有 Android SDK/依赖缓存。没有升级项目库版本。Android 本机 `local.properties` 中仍有旧 SDK 路径，构建通过已有 `ANDROID_HOME` 定位有效 SDK；未改全局环境或用户本机配置。网络基准变体补充引用项目已在使用的 Compose 测试 manifest 依赖。

### 四轮功能验证

| 轮次 | 全量执行方式与额外检查 | Windows 结果 | Android 结果 |
| --- | --- | --- | --- |
| 1 | 标准全量；下载异常、旧会话回执、通知权限与投递；编译与静态检查 | 520 项 + 160 子测试通过 | Debug 644 项通过；Lint 0 错误、6 警告 |
| 2 | 全量；Windows 按固定种子 20260925 打乱顺序；Cookie 作用域、401、异常周次与缓存、ICS | 534 项 + 198 子测试通过 | Debug 655 项通过；Lint 0 错误、6 警告 |
| 3 | 全量；Windows 反向顺序；Android 两个测试进程及 Asia/Shanghai 时区；真实 Qt 与本地 HTTP 集成 | 546 项 + 202 子测试通过 | Debug 663 项通过；Lint 0 错误、6 警告 |
| 4 | 新的独立桌面配置目录；全量；Android 强制重跑全部构建任务、额外运行网络基准变体全套测试、Debug/Release Lint、Release 打包 | 546 项 + 202 子测试通过；EXE/wheel 构建及安装目录冒烟通过 | Debug 663 项、networkBenchmark 666 项通过；均 0 失败/错误/跳过；Release 构建通过；两种 Lint 均 0 错误、6 警告 |

每轮 Windows 致命语法/名称检查与编译检查通过。各轮数字是当轮完整执行结果，不相加当作独立用例数量。Android 数字由实际生成的 JUnit XML 汇总，第四轮最终构建执行 122 个任务，没有复用测试结果冒充重跑。

### 修复记录

| 编号 | 修复及行为验收 |
| --- | --- |
| M-01 | 双端失效断点收到 416 后只额外尝试一次无 Range 完整下载；重下响应校验成功才替换原断点。重复 416、401/403/500、JSON/HTML、短流和错误 206 不损坏原断点；响应及临时片段清理。 |
| M-02 | Windows 应答结果和应答过期错误携带来源会话，在消费前核验；换号、同账号重登、结果排队和取消时序均不能用旧结果改写新会话或发送旧错误提醒。 |
| M-03 | Android 常驻通知保持 LOW，业务提醒使用独立 DEFAULT 通道；尊重本机开关、系统权限和通道禁用。未接受投递不写通知去重，二维码可在权限恢复后提醒；自动签到不受通知关闭阻断。 |
| M-04 | Cookie 克隆/合并独立复制完整元数据，缓存升级为版本 2 JSON 并保留 DPAPI、锁和原子写入。同名多域/多路径不会合并，错误缓存不会部分替换现有会话；旧平面缓存收窄到 HTTPS LNT。 |
| M-05 | Windows 签到明细 HTTP 401 上抛会话过期，403/404/429/500 保持资源失败；传输故障可降级，程序错误不再被吞成空结果。 |
| M-06 | Android 单双周标记只影响所属范围，独立后缀才全局应用；兼容括号简写。合法空周次集合保持为空，不再扩展成全学期课程。 |
| M-07 | 日历缓存拒绝异常总周数、逆序日期和不匹配的学期槽，只剔除坏日历；ICS 跳过零/负时长，统一转义 CR、LF、CRLF。 |
| M-08 | Windows 事件表按 ID 复用单元格，只写入改变的值；保持选中事件和视口。插入、删除、重排、空态、主题切换及渲染异常后的状态恢复均经真实 Qt 验证。 |
| M-09 | Android 列表/详情/数字提交识别 HTTP 200 已知登录表单；数字提交不再假成功。缺失或错误类型的 rollcalls 数组报错，正常空数组仍合法。 |
| M-10 | Windows 打包脚本支持已装 PyInstaller 的普通 Python/py，保留虚拟环境和 Conda，修正带空格路径引用及 Conda 返回码检查。原脚本在本机拒绝已有可用 Python，修复后实际构建成功。 |
| M-11 | networkBenchmark 的 initWith(debug) 不继承 debugImplementation，导致 30 项 Compose UI 测试无法启动。补齐该变体的测试 Activity manifest 依赖后，666 项完整测试全部通过。 |

新增 Python 真实 HTTP 集成只使用 127.0.0.1/127.0.0.2 随机端口，验证超过两个下载块的流、真实截断连接、失败响应关闭及跨主机重定向不泄漏带域 Cookie。测试服务器、会话和线程均在退出时收尾。

性能证据：同机同脚本、500 行连续刷新 100 次，原实现 0.662 秒且当前行变为 -1；修复后 0.431 秒且当前行仍为 200。约减少 35% 耗时，仅代表该局部微基准。真实 Qt 回归另断言未改变的单元格不触发 dataChanged；独立代理额外验证了 1000 轮随机增删/重排及选择保持。

### 红测与回归证据

- 下载修前：Windows 恢复用例失败，Android 22 项定向测试中 5 项失败；见 `build/maintenance-pc-download-before.log`、`build/maintenance-android-download-before.log`。
- 第二轮：Windows 401/异常传播出现 3 个预期失败；Cookie 临时红测复现域与属性丢失；Android 三类数据测试 63 项中 12 项失败。见 `build/maintenance-round2-detail-before.log`、`build/cookie_probe/`、`build/maintenance-round2-android-before.log`。
- 第三轮：真实 Qt 新增用例修前 5 失败/3 通过；Android 18 项定向测试中 4 项预期失败。见 `build/maintenance-round3-android-before.log` 和 `build/maintenance-table-before.log` / `maintenance-table-after.log`。
- 第四轮首次网络基准变体 666 项中 30 项 UI 测试失败；修复后完整重跑成功。首次失败日志和 XML 保留在 `build/maintenance-round4-android.log`、`build/maintenance-round4-network-before-results/`。
- 四轮完整日志：`build/maintenance-round1-python.log` 至 `maintenance-round4-python.log`；Android 前三轮为 `maintenance-round1-android.log` 至 `maintenance-round3-android.log`，第四轮最终结果为 `maintenance-round4-android-final.log`。
- 每轮 Android XML 汇总保存在 `build/maintenance-roundN-android-counts.json`，对应 XML 目录也分别留档，后续运行不会覆盖这些副本。

复验命令：Windows `python -m pytest tests -q`、`python -m ruff check xmu-rollcall-cli tests scripts --select E9,F63,F7,F82`、`python -m compileall -q xmu-rollcall-cli tests scripts`。乱序和反序入口为 `python build/maintenance_pytest_runner.py 20260925` / `reverse`，先设置 `QT_QPA_PLATFORM=offscreen`。Android 最终在仓库根执行 `android/gradlew.bat -p android :app:testDebugUnitTest :app:testNetworkBenchmarkUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease --rerun-tasks --offline -I gradle/init.gradle --console=plain`，JAVA_HOME 指向项目 JDK 17。第三轮另用 `build/maintenance-parallel-tests.gradle` 配置两个测试进程与上海时区。

### 构建、界面与服务启动

- Windows 实际运行修复后的 `scripts/build_dashboard_exe.bat`，PyInstaller EXE 构建成功。检查 171 个归档成员，应用模块、图标、qico 与 DPAPI 在位，确认打入新的 Cookie 缓存逻辑，没有错误根目录 ICU DLL 或已知运行配置文件。没有对 EXE 本体执行窗口验收。
- wheel 使用 `pip wheel --no-deps --no-build-isolation` 构建，并安装到独立 `build/maintenance-wheel-install/`，未覆盖全局安装。直接从该目录加载真实 Qt 窗口，空配置、禁止网络请求，六个页面和三种主题均通过；图标非空。offscreen 环境显式注册系统中文字体，首页和课件页截图已人工检查，六页截图存于 `build/maintenance-ui-pages/`。
- Android Release 完整编译/R8/资源压缩成功，APK 签名校验通过；包名 com.xmu.assistant，minSdk 26、targetSdk 35。归档内 SMTPTransport 仍存在，没有已知运行配置文件。签名沿用原有项目签名。
- 所有新增网络用例均为离线替身或回环服务；没有调用真实校园账号、签到提交或通知服务。没有启动需要用户维护的常驻服务。Gradle 可复用构建守护进程；测试持有的回环线程、Qt 窗口和请求会话均关闭。
- 本次只生成本地验证安装包，版本仍为 1.7.3/30；未更新发布校验表，未发布、提交或推送 Git，也未覆盖桌面安装位置。原交接文档保留不变。

构建与包检查证据：`build/maintenance-round4-windows-build.log`、`maintenance-round4-wheel.log`、`maintenance-round4-wheel-install.log`、`maintenance-round4-ui-smoke.log`、`maintenance-round4-package-check.log`、`maintenance-round4-apk-signature.log`、`maintenance-round4-apk-metadata.log`。

### 已知问题与验证边界

- Debug/Release Lint 各保留原有 6 条警告：1 条同步偏好写入、3 条第三方库 TrustManager、2 条 monochrome 图标；本轮没有新增 Lint 错误。
- 没有 Android 真机、真实校园接口、真实通知投递或第三方日历导入验收；Robolectric、真实 Qt 与回环 HTTP 不能替代这些场景。
- 下载尚未用 ETag/If-Range 校验同尺寸远端文件更换的一致性；本次解决 416 恢复与失败保留。
- 旧 Cookie 字典中已经丢失的跨域信息不可恢复，少数旧缓存可能需重新登录。requests 默认 CookiePolicy 对 host-only 子域的行为保持原样；本轮验证的是域/路径/secure 保真与对无关域的隔离。
- 远程通知去重表示任务已接受，不等于服务已送达；远程发送失败仍采用原来的记录日志、不重复发送策略。
- Activity 销毁时启动探测/自动登录令牌收尾仍是后续候选，未完成生命周期级复现和修复；其它历史审查项继续以原交接文档为线索，修复前仍需按当前代码复核。

## 2026-09-25 第二批四轮全量与自主模拟验收

承接上节已完成的维护继续执行，没有把定向测试分拆成四轮。四轮分别重跑整个 Windows `tests/` 和 Android Debug 单元测试集，第四轮还重跑网络基准变体、构建 Release。所有新增问题先保存失败复现，修复后再回归。保留原有交接文档和上一批证据。

### 依赖安装

复用上一批 Python 3.14.6、PySide6、pytest、JDK 17、Android SDK 与 Gradle 缓存；未升级依赖、版本号或修改全局环境。测试使用独立配置目录，Android 通过已有有效 `ANDROID_HOME` 定位 SDK，`local.properties` 的旧路径提示仍存在。wheel 以 `pip wheel --no-deps --no-build-isolation` 构建，并以 `pip install --no-deps --target build/maintenance2-wheel-install` 安装到本项目隔离目录。

### 功能验证

| 轮次 | 完整执行与场景变化 | Windows | Android |
| --- | --- | --- | --- |
| 1 | 标准全套基线；开始新一批故障和时序模拟 | 546 项、202 子测试通过 | Debug 663 项通过 |
| 2 | 随机顺序种子 92602；真实 worker/Qt 操作、8 线程 HTTP、生命周期和重复事件 | 559 项、202 子测试通过 | Debug 681 项通过 |
| 3 | Windows 反向顺序；Android 两个进程、纽约时区、en-US；异常配置、缓存及成绩保存倒序 | 563 项、207 子测试通过 | Debug 686 项通过 |
| 4 | 独立配置；最终修改后重跑；Android 禁用构建缓存并强制全部任务执行；Debug/Release Lint、Release、EXE、wheel、完整窗口保存及重开模拟 | 563 项、207 子测试通过 | Debug 691 项、networkBenchmark 694 项通过；123 个任务全部实际执行 |

最终 Android 两个变体均 0 失败、0 错误、0 跳过。数字为每轮完整执行结果，不把累计重复执行次数当作独立用例数。各轮 Android 都禁用了测试构建缓存；首轮发现一次 FROM-CACHE 后立即禁用缓存重跑，该缓存读数不计作一轮。第四轮首次全量 686/689 通过后，独立审查补出两个成绩交错问题，再增补红测并重新执行最终全量与构建。

真实模拟及可观察断言：

- PC 真实 MonitorWorker 线程：首个明细用屏障阻塞，第二个事件仍先通知；暂停阻止后续请求，401 终止，临时超时恢复不重复通知。
- 真实 Qt 表格/按钮与生产处理器：点击跳过发生在进度到达前、任务排队后、延迟中、明细核验中；都没有发出模拟 PUT，界面保留用户选择；明确手动提交仍可覆盖跳过。传输为内存替身。
- 真实回环 HTTP：8 个并发课件线程、16 条倒序活动、连续刷新 3 次、无效 JSON、401 和空课程。输出顺序正确，源 Cookie 不被 Set-Cookie 污染，独立子会话在全部 worker 退出后各关闭一次。另有独立复核屏障测试确保一个线程失败时不提前关闭仍在读取的兄弟连接。
- 临时文件与真实配置读写：null/布尔/数字/数组/对象凭据不会变成伪密码；合法字符串 `"None"` 保留。模拟原子替换失败后旧配置可读、临时文件清理、下一次保存恢复；4 个线程的 80 次加锁事务全部保留。
- Android 内存传输、显式时刻 0/5/10 秒：重复 ID、A 拒绝/B 短截止、A 超时/下一轮状态未知、旧 token 晚到、登录页、通知拒绝后恢复、首错误优先及取消立即中止。
- Android 固定种子 8675309：200 门课程、每门 3 段周次、25 周逐周核对解析/筛选/索引；极大周数有界。无效考试日期/时间不产生提醒，跨午夜时间正确；NaN/Infinity 损坏成绩不会污染正常统计。
- Robolectric 真实 Activity 与实际共用协程 runner：调度前销毁、IO 挂起时销毁、阻塞登录取消、旧 owner 回填；任务真正结束才释放门。考试切学期、清空后迟到缓存均受阻。成绩通过真实查询客户端配内存 HTTP，控制多次刷新和保存任务的执行顺序，验证最终存储 Cookie、JSON 和时间戳。

### 修复记录

| 编号 | 复现与最终修改 |
| --- | --- |
| S-01 | PC 首条明细慢请求阻塞其它新事件通知；改为先发出本轮所有新事件，再逐项读详情，每阶段保留停止检查。 |
| S-02 | PC 手动跳过被下一轮进度重新触发自动提交；复用会话内已处理集合、作废旧任务 token，并在提交前复核，迟到取消回执不覆盖跳过。 |
| S-03 | 课件线程池结束后子 Session 未显式关闭；ExitStack 在 executor 完全退出后清理独立会话，成功、异常与重复刷新均验证。 |
| S-04 | 错误 JSON 凭据类型被 str() 变成 `None`、`False` 等伪凭据；读写与通知归一化只接纳真实字符串，保留原加密与原子替换。 |
| S-05 | Android 启动探测/自动登录取消后全局门占用；共用 Job 最终完成回调收尾，阻塞 IO 未返回前不放行重叠登录。同族手动登录也复用完成回调，接线另有契约锁定。 |
| S-06 | Android 同轮重复事件 ID 多次消耗提交预算；按 ID 去重，同轮最多处理一次。 |
| S-07 | 一条签到被拒绝/超时阻断后续独立事件；处理完其它事件再上报首个普通错误，会话失效/取消/中断/严重错误立即终止。 |
| S-08 | 考试初始化缓存 A 晚到后显示到新选学期 B；回填增加选中学期匹配，保留清空代际保护。 |
| S-09 | 成绩保存倒序覆盖新快照；统一 revision 串行写入。失败快照保留最后有效成绩/原时间，最新小 Cookie 在请求完成前同步交接，后台仅保存成绩和时间。清空等待已进入的写入结束再失效队列，换号先过屏障再清持久化，覆盖已在写及尚未开始两类交错。 |

失败证据：`build/maintenance2-monitor-red.log`（3 失败）、`maintenance2-gui-skip-red.log`（5 失败）、`maintenance2-pool-red.log`（3 失败）、`maintenance2-persistence-red.log`（1 主用例和 5 子例失败）、`maintenance2-startup-red.log`（3 失败）、`maintenance2-startup-exam-red.log`（考试 1 失败、启动已全绿）、`maintenance2-scenarios-red.log`（3 失败）、`maintenance2-score-red.log`（1 失败）。交叉审查补测 `maintenance2-score-boundaries-red.log`（13 项中 4 失败，含 2 行为及 2 接线约束）、`maintenance2-score-cookie-red.log`（10 项中仅“立即再刷新”失败）。新增线程检查曾因 Android 编译路径没有 management 接口而失败，改为观察真实 BLOCKED/WAITING 状态后编译及行为验证通过，无新依赖；失败日志 `maintenance2-score-cookie-test-compile.log` 留存。

完整日志：`build/maintenance2-round1-python.log` 至 `maintenance2-round4-python.log`，对应 `maintenance2-roundN-android.log`、`maintenance2-roundN-android-counts.json` 及按轮保留的 JUnit XML 目录。Android Debug/Release Lint 各 0 错误、6 条既有警告。Python 致命 Ruff、compileall 和 `git diff --check` 通过。

复验入口：`python build/maintenance2_run.py 1 python` / `1 android`；第二轮 Python 追加 `92602`；第三轮 Python 追加 `reverse`，Android 追加 `-I build/maintenance2-parallel-tests.gradle`；第四轮 Android 追加 `:app:testNetworkBenchmarkUnitTest :app:lintRelease :app:assembleRelease --rerun-tasks`。runner 为每次调用设置隔离配置及 hash seed，并保存完整日志与 XML 汇总。

### 服务启动与打包

- 实际执行 Windows 项目打包脚本，EXE 构建通过；wheel 构建和隔离安装通过。最终 Android Release 构建、APK 签名验证通过；EXE 归档确认新配置归一化及连接清理逻辑已打入，图标、DPAPI 在位；wheel 含 GUI 与图标，APK 保留 SMTPTransport，未混入已知运行配置文件。证据为 `maintenance2-round4-windows-build.log`、`maintenance2-round4-wheel.log`、`maintenance2-round4-wheel-install.log`、`maintenance2-round4-package-check.log`、`maintenance2-round4-apk-signature.log`。
- 直接从源码及新安装 wheel 分别创建完整 DashboardWindow，禁止外网请求，隔离配置并拦截开机注册表写入/托盘通知。实际切换六页、三种主题；连续点击两次“保存通知设置”，等待后台队列完成，读取文件确认最后一次生效；关闭后重新构造窗口，主题与通知字段恢复正确。未点击发送测试通知。
- 实际截图位于 `build/maintenance2-ui/figures/` 和 `build/maintenance2-installed-ui/figures/`；重开后的通知页已视觉核验。日志为 `maintenance2-ui-simulation.log`、`maintenance2-round4-installed-ui.log`，可用 `python build/maintenance2_ui_simulation.py --installed` 重现。
- 回环 HTTP 服务器通过本次持有的线程和 server 句柄关闭，测试 worker、临时配置和 Qt 窗口清理完毕；没有遗留需用户维护的常驻服务。Gradle 正常复用构建守护进程。
- 包版本仍为 1.7.3/30，仅更新本地验证产物；没有修改 release 校验表、签名密钥或桌面安装，没有提交、推送或发布。

### 已知问题与验证边界

新增测试为 Windows 17 项及 5 个子例、Android 28 项（26 项行为模拟、2 项接线约束）。独立代理复核并用额外线程交错验证了 Python 修复；Android 交叉审查实际找出遗漏的成绩组合场景，再补入最终回归。

本次是离线模拟与自动化验收：没有真实校园账号/接口、真实签到写入、推送投递、Android 真机/厂商后台限制或真实第三方日历验收。Activity 测试覆盖共用生产 runner，未完整驱动 Compose 登录页面和 CAS；换号清理的接线另有源码顺序约束，不冒充真实登录端到端测试。EXE 构建与归档已检查，没有启动 EXE 本体窗口。

成绩清空和小 Cookie 交接可能等待正在执行的本地加密写入；锁内没有网络操作。已开始发送的请求不能撤回。配置并发覆盖同进程加锁事务，未承诺多进程写入互斥。上一批的 ETag/If-Range、默认 CookiePolicy 与远程通知接收/送达差异仍保留；上一批列为候选的启动生命周期问题已由本节 S-05 复现并修复。

## 2026-09-25 追加一轮全量与故障模拟验收

承接第二批四轮之后，按“再来一轮”执行一次追加的完整回归；专项复现、修复复验及构建不拆算成多轮。保留所有先前工作区修改、交接文档与历史日志。本轮由主代理统一运行全量及构建，三个独立代理分工复现、修复和交叉复核。

### 依赖安装

复用项目 Python、PySide6、pytest、JDK 17、Gradle 8.7、Android SDK 与现有依赖缓存，未升级依赖或版本。测试使用新的合成配置目录。wheel 通过 `pip wheel --no-deps --no-build-isolation` 构建，并安装到 `build/maintenance3-wheel-install`，未覆盖全局包。Android SDK 沿用已有有效环境变量；本机 local.properties 旧路径提示仍存在。

### 功能验证

| 完整验收 | 实际结果与证据 |
| --- | --- |
| Windows 全套随机顺序，种子 92503 | 604 项、207 子测试通过，24.80 秒；`build/maintenance3-final-python.log` |
| Android Debug 全套 | 709 项，0 失败/错误/跳过；`build/maintenance3-final-android.log` 与对应 XML 副本 |
| Android networkBenchmark 全套 | 712 项，0 失败/错误/跳过；不是校园网络实测 |
| Android 构建与检查 | 124 个任务全部实际执行；Debug/Release Lint 各 0 错误、6 警告、12 提示；Release 构建及签名通过 |
| Windows 构建与静态检查 | EXE、wheel 构建和隔离安装通过；致命 Ruff、compileall、diff 空白检查通过 |

Android 最终运行两个测试进程、Asia/Kathmandu 时区和 en-GB 语言区域，禁用构建缓存并强制所有任务执行。复验入口：`python build/maintenance3_run.py python final`；Android 为 `python build/maintenance3_run.py android final :app:cleanTestDebugUnitTest :app:cleanTestNetworkBenchmarkUnitTest :app:testDebugUnitTest :app:testNetworkBenchmarkUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease --rerun-tasks -I build/maintenance3-parallel-tests.gradle`。runner 保留日志、各变体 JUnit XML 和按次计数，未把历史重复执行次数累计为用例数。

真实行为及故障模拟：

- 下载：真实 requests.Response 流/临时文件和 Android 回环 MockWebServer，分别验证首次下载、已有断点、416 恢复遇到非文件状态；断言旧字节不变、不生成成品、响应关闭，合法空 200 文件仍成功。另模拟最后重命名失败后重试、已校验分段追加中途磁盘满后准确续传，以及普通文件名边界。
- 配置/Cookie：损坏缓存的 CR/LF 名称和值、新旧缓存结构、截断与解密失败恢复、原子替换失败、序列化中途磁盘满。断言旧文件/原 CookieJar 保留，临时文件清理，下一次保存恢复；没有向网络发送这些合成数据。
- Qt：真实表格、勾选框、课程选择框与下载按钮，验证重排/删除后选择仍按文件身份匹配、切课在旧请求未返回时清空旧行、拒绝跨课下载。独立复核以固定种子 934271 额外执行 400 次重排/插入/删除，勾选、选中行、焦点均与期望交集一致。
- 保存队列：文件替换失败后第二次点击仍按顺序保存；账户字段不被覆盖；关闭到托盘后排队保存继续完成。通知只捕获参数，未发送真实通知。
- Android：用真实 SectionState、Robolectric Activity、可控调度器与线程屏障复现课表启动缓存迟到、校准设置/移除倒序保存、旧 Activity 写入中取消后新 owner 清理、排名最后一次查询换号、课件 A/B 列表倒序、切课及换号后的下载回调。后台传输全部为内存替身或本机回环。

### 修复记录

| 编号 | 复现与修复 |
| --- | --- |
| T-01 | 两端下载把 202/204 等响应当成文件，覆盖断点或提升空文件；现在只有 200/206 可以写入/提升，原身份失效分类和 416 恢复保留。 |
| T-02 | 损坏 Cookie 中的 CR/LF 会替换有效会话并使后续请求头构造失败；恢复前统一拒绝，保留原 jar，诊断不包含凭据。 |
| T-03 | 桌面课件重排把旧行勾选转给别的文件；表项保存稳定 key，恢复勾选/选中行/焦点，已删除文件不选其替代行。 |
| T-04 | 桌面切课期间旧课件可被下载到新课程目录；读取互斥门前先清旧行，派发再复核课程归属。 |
| T-05 | Android 课表启动缓存、周次校准与后台持久化的时序问题；捕获启动会话与清空代际；周次校准同步更新进程快照；所有 Activity 通过同一 revision 门串行保存，清理等待旧写入结束。小 Cookie 同步交接，后台仅保存文件；旧迁移再次核验会话后才清偏好，写失败保留唯一旧缓存。 |
| T-06 | Android 排名最后一轮旧查询在换号后写回等待警告；每次查询返回后再次检查取消与会话世代，覆盖末轮没有后续延迟的分支。 |
| T-07 | Android 切课后旧下载回调污染当前列表/进度；按当前课程与条目双重身份接纳可见更新，文件仍正常保存，B 课程缓存不变；沿用下载状态不写 AcademicCache 的原有行为。 |
| T-08 | EXE spec 在 Analysis 应用源码路径前扫描全局旧包，产生三个不存在的项目 hidden imports；现在先将项目源码放入扫描路径。实际重新构建后旧模块错误消失，应用模块、图标和 DPAPI 归档检查通过。 |

红测证据：Python 数据模拟修前 20 失败/11 通过，`maintenance3-data-red.log`；Qt 选择修前 7 失败/1 通过，`maintenance3-desktop-selection-red.log`；Android 下载 5 项中 4 失败，`maintenance3-download-red-android.log`；Android 首批时序 11 项中 7 失败，`maintenance3-timeline-red-android.log`。修复后相应专项均重新验证，最后执行完整回归。独立复核追加迁移用例：`maintenance3-migration-red-android.log` 为 8 项中 2 失败；修复后时序与接线共 40 项全绿，见 `maintenance3-timeline-green-android.log`。

Windows 首次全量 601 通过/1 失败来自旧 `_RecordingTable` 测试桩缺少新调用的 Qt 选择接口；补齐桩接口后保留一次预留全部行、零 insertRow 的断言，最终全套 604+207 通过。首次日志保留为 `maintenance3-initial-python.log`。Android 下载测试首次编译缺少注入构造参数，修正测试后才运行上述行为红测；编译日志单独保留，未计为产品缺陷。

曾探测登录保存失败回滚，但其 mock 强制抛出了生产 save_session 实际会捕获的异常，复核后撤回该候选和临时测试，没有改登录生产逻辑；`maintenance3-desktop-login-red.log` 标为 INVALID PROBE，其失败不计入成果。

### 服务启动

实际从本轮新安装 wheel 构造完整 DashboardWindow，隔离配置并拦截外部请求、开机注册表和托盘通知，切换六页/三种主题、连续点击两次保存，再关闭重建窗口确认最终字段恢复。真实截图 `build/maintenance3-installed-ui/figures/shot_maintenance3_reopened.png` 已视觉检查，日志 `maintenance3-installed-ui-command.log`。

另外尝试以桌面工具检查原生窗口，隔离测试进程 PID 27952 已启动，但工具窗口列表未识别到目标，因此未执行桌面点击，也不计作原生交互通过。通过本次停止文件让该进程自行退出，日志确认 CLOSED，临时配置已清理；最终界面断言来自上述真实 Qt 自动化。EXE 仅构建与归档验收：预检发现直接启动测试 EXE 会重配现有开机启动项，本次没有执行该路径。

所有临时 HTTP 服务、线程、Activity 与 Qt 窗口由测试句柄收尾；无新增需用户维护的常驻服务。Gradle 正常复用构建守护进程。包检查日志 `maintenance3-package-check-command.log`，签名日志 `maintenance3-apk-signature.log`。版本保持 1.7.3/30；未提交、推送、发布或覆盖桌面安装，未更新 release 校验文件。

### 已知问题

首次强制全量使用 tr-TR：两个变体各 139 项因 Windows 上测试依赖 Conscrypt 的本地库加载失败而未通过，均为 UnsatisfiedLinkError；库名中的 windows 被转换为带无点小写字母的 wındows。失败发生在 Robolectric 初始化阶段，记录保存于 `maintenance3-turkish-android.log`、对应 XML 和 `maintenance3-turkish-failure-types.json`。没有修改或跳过测试来掩盖该失败；最终改用 en-GB 保留同一新时区与并行设置完整重跑。tr-TR 与该 Windows 测试依赖的组合仍未完成验收，不能据此判断 Android 真机语言行为。

本轮新增 Windows 41 项、Android 18 项正式测试；额外 400 次 Qt 状态转换是同一随机模拟中的操作次数，不计为 400 项新增测试。交叉复核记录为 `build/maintenance3-data-review.md`、`maintenance3-desktop-review.md` 与 `maintenance3-android-review.md`。

没有验证真实校园登录/接口、真实签到/通知投递、Android 真机、厂商后台限制或第三方日历。磁盘满/文件锁为可控异常注入，并非耗尽用户磁盘。关闭模拟覆盖关闭到托盘，不能证明系统强杀或明确退出时后台保存必已落盘。既有 6 条 Android Lint 警告保留；PyInstaller 仍有第三方 pycparser 两个可选表模块警告。下载 ETag/If-Range、一致性与多进程写入边界沿用之前记录。课表清理和 Cookie 交接可能短暂等待正在执行的本地文件写入；锁内没有网络请求。课表保护覆盖 SectionState 的刷新、校准与启动迁移通道，不宣称其它后台模块或跨进程共用该门。

## 2026-09-25 再追加一轮全量与后台场景验收（maintenance4）

按“再来一轮”完成一次完整回归：增加组合故障模拟，先留存失败证据，再修复、交叉复核，并统一执行两端全量和打包。专项、复验、构建均属于这一轮。保留之前所有工作区修改、历史报告及交接文档。

### 依赖安装

复用已有 Python、PySide6、pytest、JDK 17、Android SDK、Gradle 和离线依赖缓存，未升级依赖或产品版本。新 wheel 安装在 `build/maintenance4-wheel-install`，没有覆盖全局安装；所有测试使用合成配置。Android 使用有效的环境变量 SDK 路径，本机 local.properties 的旧路径提示仍存在。

### 功能验证

| 全量验收 | 实测结果 |
| --- | --- |
| Windows 全套，随机顺序种子 92504 | 631 项、207 子测试全部通过，23.93 秒；`build/maintenance4-final-python.log` |
| Android Debug 全套 | 730 项，0 失败/错误/跳过 |
| Android networkBenchmark 全套 | 733 项，0 失败/错误/跳过；不是校园网络实测 |
| Android 发布验收 | 124 个任务实际执行，Debug/Release Lint 各 0 错误、6 警告、12 提示；Release APK 构建、签名验证通过 |
| Windows 发布验收 | EXE、wheel 构建、隔离安装、包内容检查、致命 Ruff、compileall、diff 空白检查通过 |

Android 使用 zh-CN、Australia/Lord_Howe 时区、两个测试进程，每 25 个测试类重建进程，禁用构建缓存并强制执行任务。最终日志为 `build/maintenance4-final-android.log`，计数为 `maintenance4-final-android-counts.json`，两个变体均保存独立 XML 副本。本机日志使用被忽略的 `build/maintenance4_run.py` 隔离配置、打乱顺序并调用 Gradle；克隆仓库后可直接运行 `python -m pytest tests -q`，以及 `android/gradlew.bat -p android :app:testDebugUnitTest :app:testNetworkBenchmarkUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease` 做标准全量复验。

本轮新增 Windows 27 项、Android 21 项正式测试；Android 包含 19 项行为模拟、2 项入口/清理接线约束。没有把重复执行次数累加为新增用例。

### 修复记录

| 编号 | 确认问题、修复及模拟证据 |
| --- | --- |
| U-01 | 并发同名下载、入口文件及正式文件与另一任务断点文件的交叉命名会冲突。现在对规范化的正式文件和 `.part` 两条路径同时占位，网络/写盘期间不持分配锁，退出时释放。两个真实本机 HTTP 请求通过屏障控制交错，逐个检查文件字节和路径。 |
| U-02 | 编号下载失败后的 `(2).part` 被跳过，重试从 `(3)` 重新下载。现在编号路径与原名一致复用空闲断点；真实短流后验证准确 Range、最终仍为 `(2)`、原成品不变。 |
| U-03 | x-gzip/zstd 自动解压后被错误地与压缩传输长度比较，合法文件被报不完整。补齐压缩格式识别；压缩 206 仍禁止拼接。覆盖跨主机重定向、Cookie 不跨主机、416 全量恢复、坏压缩体与请求前失败。 |
| U-04 | 桌面通知排队后退出/同账号重登仍外发，迟到回执污染新会话；延迟读取设置还会把旧事件发给新收件人。队列固定会话身份和设置快照，发送前及回执接收时复核。主动测试通知独立于教务登录，可在未登录时正常显示成功/失败反馈。实际调用生产 worker 和结果处理器，仅替换外部发送。 |
| U-05 | CSV 顺序与页面分组、筛选及核实后保留的行序不一致。导出按表格稳定键读取最新记录，真实 Qt 表格逐行对照临时 CSV，并覆盖取消导出及原位状态更新。 |
| U-06 | 考试提醒错误判断全屏特殊访问、授权入口不正确，已排队广播忽略最新开关/通知权限；乱序大量考试还会漏掉近期场次。改用平台特殊访问检查和专用设置页，广播接收时重查权限/开关，按提醒时间排序后保留最近 100 场。测试覆盖 API 33/34、授权撤销、普通通知回退及 110 场逆序考试。 |
| U-07 | 后台课表请求期间返回前台仍可自动 CAS，同账号同 Cookie 重登可重新授权旧请求，旧后台结果覆盖新前台结果。Worker 捕获只读会话代际、凭据与课表版本，重登回调重查前台/取消/版本；共享写入门内通过版本 CAS 提交，旧请求不能抢走最新版本。 |
| U-08 | 退出清理与后台文件/Cookie 写入并发时会恢复旧数据；阻塞读取在任务取消后正常返回也继续写入。后台 Cookie、文件、Widget 共用课表写入门，清理等待本地提交结束并最终清 Cookie；取数返回及入锁后检查取消。真实临时缓存、测试偏好和 Widget 存储均验证清空，另核对实际退出/登录调用方顺序。 |

U-06 的 API 选择核对了 [Android 14 官方行为变更文档](https://developer.android.com/about/versions/14/behavior-changes-14)。接收器仍保留框架需要的公开无参构造器，独立复核已用 javap 确认。

失败与复验记录：下载首批 14 项中 8 失败，追加交叉命名 1 项失败；最终新增 15 项全绿，相关 71 项及 14 子项全绿。桌面首批 11 项中 9 失败，最终新增 12 项全绿，相关 102 项及 44 子项全绿。考试提醒 10 项中 7 失败，修复后相关 43 项全绿。Worker 最终有效红测 11 项中 8 失败，修复后 6 类 69 项全绿；所有这些项目均再次纳入最终全量。

证据分别为 `maintenance4-data-{red,crosspath-red,green,regression}.log`、`maintenance4-desktop-{red,green,targeted}.log`、`maintenance4-reminders-{red,green}-android.log`、`maintenance4-worker-{barrier-red,cookie-red,green}-android.log`。Worker 首次测试仅观察线程 BLOCKED，误把其它锁当成课表门，曾产生一项假通过；已改为只认可明确课表门栈帧并重跑红测，原始日志保留，不将假通过作为验证成果。

交叉复核实际补出了正式路径与断点路径交叉冲突，以及 Cookie setter 已进入后的退出清理窗口。复核记录为 `build/maintenance4-data-review.md`、`maintenance4-desktop-review.md`、`maintenance4-android-review.md`。新回环 HTTP 服务去掉绑定地址时不必要的反向 DNS 查询，保留真实请求/重定向覆盖，新增下载模拟由约 63 秒降到 0.98 秒。

### 服务启动

从本轮新安装 wheel 创建完整 DashboardWindow，实际切换六页、三种主题、连续点击两次保存，关闭并重新构造窗口确认最终设置恢复。外网请求、开机注册表写入和托盘通知被拦截，未点击真实发送。日志 `build/maintenance4-installed-ui-command.log`；重开截图 `build/maintenance4-installed-ui/figures/shot_maintenance4_reopened.png` 已视觉检查。

EXE 完成构建和归档内容检查，wheel 同时核验本轮新实现与图标资源；APK 完成构建、归档和签名检查，签名沿用项目既有 Android Debug 测试证书（本地验证），证据见 `maintenance4-windows-build-command.log`、`maintenance4-package-check-command.log`、`maintenance4-apk-signature.log`。EXE 本体窗口没有启动，避免已知启动路径重配现有开机项；界面验收来自隔离安装的同版 Python 包。

临时 HTTP 服务、线程、Qt 窗口及 Activity 都由测试句柄收尾，无新增常驻服务。版本保持 1.7.3/30，未提交、推送、发布或覆盖桌面安装，未改 release 校验表与签名配置。

### 已知问题

没有真实校园登录/签到写入、外部通知投递、Android 真机或厂商后台调度测试。Worker 测试执行生产共用 runner 并校验接线，不等同于真实 WorkManager 定时唤醒；Worker 沿用不更新正在运行 Activity 内存课表的行为。提醒测试的自定义 Robolectric shadow 仅模拟特殊访问查询，保留框架通知存储，不能证明真机锁屏/系统设置页表现。

文件占位仅覆盖当前进程；仍未实现 URL/ETag/If-Range 断点归属及多进程目录互斥。已开始的网络通知无法撤回。清理可能短暂等待已进入的本地文件或偏好写入，锁内没有网络请求。已有 Android Lint 警告与两个第三方 pycparser 可选模块打包警告保留。上一轮 Windows+tr-TR 的 Conscrypt 加载问题仍属于环境限制，本轮没有宣称已解决。

## 2026-09-25 测试包交付与桌面覆盖

按用户要求交付本轮已验证的 Windows 单文件 EXE 与 Android APK。`build/maintenance4-delivery/xmu-assistant-test.exe`、`xmu-assistant-test.apk` 均逐字节核对 SHA-256，校验清单位于同目录 `SHA256SUMS.txt`。两者分别来自本轮 `dist/xmu-assistant.exe` 和 `android/app/build/outputs/apk/release/app-release.apk`，不是 `release/` 中较早的同版本文件。

先将原 桌面上的 `xmu助手.exe` 备份为 `build/maintenance4-delivery/desktop-previous-xmu助手.exe`，校验备份后覆盖同名桌面文件，再核对桌面文件 SHA-256 为 `13dc37c24101c0425dae46e24a3d0953b5c8b74279dfd0671d02ffd9d3675cd2`。使用隔离空白配置直接启动桌面 EXE，7 秒后仍有运行进程；结束本次测试进程，确认没有残留实例，开机启动注册表路径保持原值。真实账号配置未参与该启动验证。

APK SHA-256 为 `74dec792d76fe9cba736c90f12ee1762be3e2f264e80d427e8ffe13aef594061`，`zipalign -c -v 4` 与之前的 v2 签名检查通过。包名 `com.xmu.assistant`，版本 `1.7.3` / `versionCode 30`，沿用项目固定测试证书，支持同包名、同签名且已安装版本号不高于 30 的覆盖安装。本机未连接用户手机执行真机安装，APK 供用户直接测试。本次测试二进制未上传 GitHub Release。
