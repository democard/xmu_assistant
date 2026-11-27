<div align="center">

<img src="assets/xmu-assistant-mark.png" width="96" alt="虾大助手图标">

# 虾大助手 · Campus Assistant

一个面向厦门大学学生的 **Android 校园日常工具**，帮助整理课表、查看成绩与考试、下载课件，并提供课堂签到状态监控与提醒。

> **重要变更说明**
> - **因学校方面已拉闸限制相关策略与接口，自动签到等功能已正式下线**：Android 端已彻底移除自动签到、一键签到、数字签到码抓取与课程雷达坐标管理模块（从源码及安装包中完全剥离），不再提供代签或自动提交功能。
> - **软件定位已调整为「课堂监听提醒 + 畅课直达 + 校园日常工具」**：保留前台课堂监听多渠道提醒与签到记录查看，新增应用内**畅课签到网页**（进入前同步已登录会话 Cookie，免手动重复登录即可直达畅课完成签到），并重做单屏「今日概览」首页；保留课表、桌面小组件、成绩与专业排名、课件下载及考试提醒功能。
> - **Windows 桌面端已声明废弃（停止维护）**：后续仅维护 Android 端，不再对 Windows 客户端提供功能更新或接口适配。

课程、课件、课表、成绩、考试、课堂监听提醒与畅课直达，集中在一个轻量客户端中。

**当前版本：Android v1.9.0（versionCode 48） · Windows 桌面端已废弃（历史归档 v1.8.6）**

[下载 Android 安装包（v1.9.0）](https://github.com/democard/xmu_assistant/releases/download/v1.9.0/xmu-assistant-release.apk) · [发布说明](https://github.com/democard/xmu_assistant/releases/tag/v1.9.0)

</div>

## 平台状态

| 平台 | 当前状态 | 说明 |
| --- | --- | --- |
| Android | **维护中（推荐）** | 重做「今日概览」首页，新增应用内畅课签到网页；保留课堂监听提醒、签到记录、课表、成绩与排名、课件、考试提醒 |
| Windows | **已废弃（停止维护）** | 原 `xmu-rollcall-cli` 代码仅作历史归档，不再更新或适配新接口 |

[安装与使用](#安装与使用) · [Android 功能概览](#android-功能概览) · [数据与隐私](#数据与隐私) · [开发与验证](#开发与验证) · [问题反馈](#问题反馈)

## 安装与使用

### 下载

| 平台 | 状态 / 版本 | 文件与运行要求 |
| --- | --- | --- |
| Android | 1.9.0（versionCode 48） | [xmu-assistant-release.apk](https://github.com/democard/xmu_assistant/releases/download/v1.9.0/xmu-assistant-release.apk)，Android 8.0 及以上，覆盖安装保留本地账号与缓存 |
| Windows（已废弃） | 历史归档 1.8.6 | 不再维护或推荐，历史发布文件见 [v1.8.6 Releases](https://github.com/democard/xmu_assistant/releases/tag/v1.8.6) |
| 对应源码 | 仓库 `main` 分支 | 通过 GitHub 源码构建最新版本 |

本版发布文件校验值见 [v1.9.0 SHA256SUMS.txt](https://github.com/democard/xmu_assistant/releases/download/v1.9.0/SHA256SUMS.txt)。在下载目录打开 PowerShell，可用以下命令计算 SHA-256，与对应版本校验文件中的同名条目核对：

```powershell
Get-FileHash .\xmu-assistant-release.apk -Algorithm SHA256
```

**Android 更新请从本仓库发布页下载。** 当前 APK 沿用仓库内公开的固定调试签名，支持覆盖同签名旧版；该签名不能证明安装包来自维护者，任何持有公开密钥的人都能制作同签名安装包。卸载应用会清除应用私有数据，正常更新请使用覆盖安装。

### 第一次使用

1. 打开应用，用自己的虾大统一认证账号登录。网络需要能访问课程平台；课表、成绩和考试查询还需要能访问教务服务。
2. 按需查看学习记录、课程课件，或在 Android 端查看课表和成绩。登录过期时重新登录。
3. 需要提醒时，先配置通知渠道和个人策略。Android 请按照内置教程检查通知权限和后台电池限制。

后台功能依赖应用运行、有效登录态和网络连接。Windows 端需保持程序运行；Android 使用前台服务，仍可能受系统或厂商后台策略影响。提醒不保证始终及时。

## Android 功能概览

| 功能 | 说明 |
| --- | --- |
| 今日概览首页 | 单屏展示当前课程 / 下一节课与教室、近期考试倒计时、课堂监听状态开关、进行中签到快捷条及常用功能直达入口 |
| 登录态保存与恢复 | 本地加密保存账号与会话，支持会话过期安全续登，并在打开畅课网页前同步统一认证 Cookie |
| 课堂监听与畅课直达 | 后台监听新发布的签到并推送系统 / PushPlus / QQ 邮箱提醒；保留进行中类型标注、实时进度与近期出勤记录，支持应用内一键直达畅课完成签到（已彻底移除自动签到、一键签到、数字码抓取与雷达坐标） |
| 课程课件浏览与批量下载 | 支持课程搜索、学年学期筛选与断点续传 |
| 全学期课表与日历导出 | 周课表 / 日程视图、本地微调上课安排、导出 `.ics` 日历 |
| 今日课程桌面小组件 | 预投影未来 14 天课程摘要，跨日可读本地缓存 |
| 成绩、模拟与排名 | GPA / 加权均分 / 学分统计、实时模拟计算、长图分享与专业排名证明 |
| 考试安排与考前提醒 | 按学期展示考试，支持精确闹钟与锁屏全屏提醒 |
| 常驻与快捷操作 | 快捷设置「签到监控」磁贴、桌面图标长按直达、浅色 / 深色 / 跟随系统主题 |

### 课堂监听、畅课网页与课件

因学校已对相关策略与接口采取限制，本版彻底移除自动提交签到、一键签到、数字签到码抓取与雷达定位模块，仅保留课堂状态监听提醒、进行中签到类型标注（数字 / 雷达 / 二维码）与近期出勤记录查看。收到监听提醒或首页检测到进行中签到时，可通过首页进行中签到卡片、签到页或「更多 → 畅课网页」直接打开应用内**畅课签到**页面完成签到（打开前会预检并同步本地已登录会话 Cookie，无需重复输密码）。

课件可按课程浏览并批量下载；Android 支持课程搜索和学年学期筛选，无法直接下载时保留平台入口。下载是否成功仍取决于当前账号权限、链接有效性和服务器响应。

续传前会核对资源归属和强 ETag；缺少可靠验证器时从头下载，避免拼接不同资源或版本。旧版没有归属信息的断点保留在原位置，新下载另起文件名。

### Android 课表与教务功能

课表提供每周七天、每天十一节的网格和日程视图，支持查看今天、跳转周次、复制课程详情及导出 `.ics` 日历。今日课程小组件保存未来十四天的课程摘要，跨日可读取本地排课，超过缓存范围需刷新；显示数量和刷新时机也受桌面启动器影响。

成绩页支持统计、模拟成绩计算和长图分享。专业排名需要用户手动发起查询；已有完整结果时可复用，成绩变化只标记待更新。证明 PDF 可导出或清理应用内副本，已导出的副本需自行管理。

考试页按学期展示安排，可设置考前提醒。课表、成绩和考试的缓存用于离线查看，最新安排仍应以学校系统为准。更多入口与构建说明见 [Android 文档](android/README.md)。

## 本版更新

### 1.9.0 · 过渡版：下线自动签到、重做今日概览首页与畅课网页直达

- **功能收口与包体瘦身**：因学校方面对相关接口采取限制，正式移除自动签到、一键签到、数字签到码抓取与课程雷达坐标管理源码及界面；启用严格资源压缩（`shrinkMode="strict"`），剥离未引用的大图与旧资源，Release APK 体积精简回 `~3.86 MB` 档位。
- **重做「今日概览」单屏首页**：采用更紧凑的单屏信息层级展示今日课程、下节教室、近期考试倒计时、课堂监听启停开关与快捷功能矩阵。
- **新增应用内「畅课签到」网页**：支持从首页进行中签到卡片、签到详情页及「更多 → 畅课网页」直达畅课课程/签到页；进入前自动预检登录态并将本地加密存储的会话 Cookie 异步同步到 WebView，减少重复登录。
- **桌面端状态明确**：声明 Windows 桌面端（`xmu-rollcall-cli`）废弃归档，本版仅发布 Android 安装包。

[1.9.0 发布说明](documentation/releases/v1.9.0.md)

### 1.8.6 · 双端稳定性与分享、导出修复（历史版本）

- **Windows**：修复课程切换失败及线程启动异常后的恢复状态；文件导出改用同目录原子替换，写入失败保留旧文件；CSV 文本单元格中和电子表格公式前缀。
- **Android 会话与缓存**：修复请求取消或客户端初始化异常后请求门 / loading 残留，收紧排名、考试 Cookie 与成绩初始化的会话接纳；协调 Academic 缓存清理和异步写入。
- **Android 签到与课表**：前台签到和后台监控共用并发去重门；统一课表修正对周视图、ICS 导出和小组件的投影，修复后台 Worker 与手动保存之间的多种写入时序。
- **Android 成绩分享**：MediaStore 仅在图片写入并发布成功后返回 URI；失败时清理未完成项目，长图生成和系统分享面板遵循请求门、会话与 Activity 生命周期。

[发布说明](documentation/releases/v1.8.6.md) · [发布验证记录](documentation/verification-1.8.6.md)

### 1.8.5 · 双端界面与交互优化（历史版本）

- Android 首页新增一键签到，改善签到分类卡片、人数统计和缺勤标记。
- 优化课表与成绩布局、周次与课程安排弹窗、课程坐标 / 课件选择和考试刷新。
- 改善 Windows 课程 / 课件列表和雷达配置，修复登录取消回滚及下载刷新边界。

[1.8.5 发布说明](documentation/releases/v1.8.5.md)

### 1.8.4 · 双端稳定性与安全更新（历史）

- 修复同账号重新登录后查询或自动任务卡住，阻止手动连点及自动 / 手动重叠提交；暂停监控不影响手动应答。
- 下载绑定资源与版本，支持安全断点恢复、进度和取消；修复长文件名丢失后缀、配置目录探针竞争。
- 修复 Android 登录 Cookie 作用域、账号切换与历史缓存写回竞态，完善雷达地点保存与坐标提交边界。
- 邮件强制安全连接和主机名校验，提交回执不明时不跨端口重复发送。
- 固定静态检查规则与维护依赖，加入双端自动化验证流程。

[更新与下载](documentation/releases/v1.8.4.md) · [修复记录](documentation/repair8-2026-10-24.md)

### 1.8.3 · Android 历史版本

- 修复同一签到同时出现在进行中与历史记录中导致签到页闪退的问题。
- 首页按老版“监控与提醒”内摘要行的方式恢复“当前签到”和“查看签到详情”；不新增独立模块，也不重复展示签到详情卡片。数据在前台自动刷新，手动刷新仍在签到详情页。
- 沿用现有签名，可覆盖安装；Windows 保持 1.8.1。

此次首页与签到页 16 项界面回归通过；其余模块沿用前一候选的 787 项全量测试证据。详见 [发布说明](documentation/releases/v1.8.3.md)和[验证记录](documentation/verification-1.8.3.md)。

### 1.8.1 · 历史版本

- **Windows 雷达签到**：新增「雷达坐标」页，按课程保存地点并指定首选；自动与手动雷达签到只提交该课程的首选坐标一次，未配置时不提交并提示原因，不再使用固定探测坐标盲提交。预置翔安校区地点随包分发。
- **等待策略（双端）**：Android 策略页补上「达到人数 / 达到比例」输入框并真正写入设置；Windows 将人数与比例改为常显并按模式启用，切换模式后不再出现“改不了”的情况。
- **Android 成绩模拟**：模拟成绩四项并排显示「当前 → 计入后」，平均与加权都会随输入实时变化。
- **Android 课表与坐标**：课表恢复完整 11 节布局并支持横向滑动、课程安排可本地修改；坐标管理只列出本学期课程，旧学期坐标保留但隐藏；现场采点改为 GPS 与网络并行采样并设超时。
- **签到与首页**：签到页移除“最近一次签到”大卡片；首页自动签到模块新增雷达坐标管理入口。

本版安装包已重新构建、核对版本和校验值，APK 签名保持兼容，Windows 隔离启动检查通过。详见 [发布说明](documentation/releases/v1.8.1.md)。

### 1.7.4 · 历史版本

- 改善双端记录详情查询的兼容性。
- 修复跨时区、跨日期记录的时间处理。
- 修复 Windows 后续查询失败或返回空值时沿用旧缓存的问题。
- 统一公开文字与界面展示文案，保留原有图像、接口及旧版兼容标识。
- 补充回归用例，保留现有个人策略与状态核实流程。

该版本安装包已核对版本和校验值，APK 签名保持兼容。详见 [发布说明](documentation/releases/v1.7.4.md)与[验证记录](documentation/verification-1.7.4.md)。

### 1.7.3 · 历史版本

v1.7.3 主要修复下载、账号切换和导出中的边界问题：

- **双端下载**：续传前校验响应范围与实际字节数，错误响应保留断点；识别带查询参数和 fragment 的课件直链。
- **Windows**：账号切换或重新登录后丢弃旧下载事件，章节缓存按会话隔离；修复压缩下载误报、非法邮件端口及 Python 安装包缺少图标。
- **Android**：初始下载请求仅向同源地址发送 Cookie；课表缓存替换失败时保留旧数据，考试缓存可从系统时间回拨恢复。
- **日历与成绩图片**：修复日历事件标识碰撞，以及 Android 8 / 9 连续保存成绩图片时的覆盖和失败残留。

日历事件标识规则已改变：向保留旧版导入事件的日历再次导入，可能出现重复事件，建议先移除旧导入。完整记录见 [v1.7.3 发布页](https://github.com/democard/xmu_assistant/releases/tag/v1.7.3)与[测试报告](TEST_REPORT.md)，旧版本记录见 [Releases](https://github.com/democard/xmu_assistant/releases)。

## 数据与隐私

应用在本机保存配置、登录态和业务缓存。登录、查询和用户启用的操作会连接相应服务；启用 PushPlus 或邮件通知时，通知内容会发送到所选服务。导出的课表、成绩图片、证明和日志也可能包含个人信息，分享前请自行检查。

| 数据 | 保存方式与边界 |
| --- | --- |
| Windows 密码、Cookie、通知密钥 | 使用当前 Windows 用户的 DPAPI 加密；若加密组件不可用或加密失败，当前实现会告警并回退为明文，不能把整个配置目录视为始终加密 |
| Android 账号、Cookie 等凭据 | 使用 EncryptedSharedPreferences 保存；不等于所有业务缓存和导出文件均已加密 |
| 课表、成绩、考试与学习记录缓存 | 用于本地展示；换号和登出时按业务清理或隔离，外部导出副本不随登出自动删除 |
| Android 小组件摘要 | 包含课程名、时间和地点，不包含密码或 Cookie；课程安排本身仍可能涉及个人隐私 |
| Android 本地课表修正 | 持久设置，按课程匹配，沿用跨账号保留行为，不随登出删除；共用设备时请在对应管理页检查和删除不再需要的设置 |

Windows 默认配置目录为当前用户主目录下的 `.xmu_rollcall`。需要独立测试配置时，可在启动前设置 `XMU_ROLLCALL_CONFIG_DIR`。目录结构、加密限制及开发方式见 [Windows 文档](xmu-rollcall-cli/README.md)。

提交代码或反馈问题时，不要附带真实账号、密码、Cookie、通知密钥、运行配置和未经脱敏的截图。仓库保存源码、测试和校验文件，EXE / APK 通过 GitHub Releases 分发；本地运行数据和构建日志不作为发布内容。

## 开发与验证

### 从源码构建与运行

Android 端使用 Android Studio 打开 `android/`，配置 JDK 17 和 Android SDK 35，使用仓库携带的 Gradle 8.7 Wrapper 构建与测试：

```powershell
git clone https://github.com/democard/xmu_assistant.git
cd xmu_assistant\android
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

详细测试与安装命令见 [Android 构建说明](android/README.md#构建与测试)。历史已废弃的 Windows 桌面端（`xmu-rollcall-cli`）仅作归档，运行与打包方式见 [Windows 开发说明](xmu-rollcall-cli/README.md)。

### 1.8.6 验证记录（历史）

本次发布的完整回归、Lint、Windows EXE 与 Android Release APK 检查结果见 [1.8.6 验证记录](documentation/verification-1.8.6.md)。该记录区分本次实测结果与历史维护报告；真实校园端到端及设备验收范围也在其中说明。

### 1.8.4 验证记录（历史）

Python 全量 800 项测试、294 个子测试通过；Android 全量 822 项通过，其中 19 项邮件回归通过。Ruff、编译检查与 Android Lint 通过。以上数字只对应 1.8.4，详见 [1.8.4 验证记录](documentation/verification-1.8.4.md)；历史发布记录见 [TEST_REPORT.md](TEST_REPORT.md)。

### 项目结构

[开发文档导航](documentation/README.md) · [贡献与目录约定](CONTRIBUTING.md) · [模块定位](documentation/architecture.md)

```text
xmu_assistant/
├── android/                 # Kotlin + Compose 原生客户端及 JVM 测试（当前主维护端）
├── xmu-rollcall-cli/        # [已废弃] 历史 Windows 桌面端源码（停止维护）
├── tests/                   # [已废弃] 历史 Windows 端 Python 测试
├── assets/                  # 图标与品牌资源
├── scripts/                 # 构建、模拟器启动及维护脚本
├── documentation/           # 公开维护文档与历史开发记录
├── xmu-assistant.spec        # Windows PyInstaller 打包配置
├── release/SHA256SUMS.txt    # 发布文件校验值；二进制不入库
├── TEST_REPORT.md           # 扫描、修复与发布验证记录
└── LICENSE                  # Apache-2.0
```

桌面端使用 Python、PySide6、requests 和 xmulogin；Android 端使用 Kotlin、Jetpack Compose、OkHttp、WorkManager 与 AlarmManager。测试使用 pytest，以及 Android 的 JUnit、MockWebServer 和 Robolectric。具体依赖以 [Python 包配置](xmu-rollcall-cli/pyproject.toml)和 [Android 版本目录](android/gradle/libs.versions.toml)为准。

发布时核对双端源码版本、Android versionCode 与签名，完成测试和安装包检查后计算 SHA-256。`release/` 仅提交校验文件；安装包上传到对应版本的 Release。涉及运行数据的日志或截图应先脱敏再分享。

## 问题反馈

请在 [Issues](https://github.com/democard/xmu_assistant/issues) 中提供应用版本、系统版本、复现步骤、预期和实际表现。涉及网络问题时可说明使用校园网、校外网络或隧道，但不要公开账号和会话信息。截图请遮挡姓名、学号、课程安排等个人信息，日志请检查请求头、查询参数和本地路径。

本项目是非官方的个人学习工具。请仅使用本人账号及有权访问的数据，遵守课程与平台的使用规则；课程安排、成绩和出勤结果以原平台记录为准。

## 致谢与许可

- 部分记录处理与会话管理实现参考 [KrsMt-0113/XMU-Rollcall-Bot](https://github.com/KrsMt-0113/XMU-Rollcall-Bot)。
- 课件下载实现参考 [KrsMt-0113/XMUFD](https://github.com/KrsMt-0113/XMUFD)。
- 感谢 requests、PySide6、xmulogin、OkHttp、AndroidX、Jetpack Compose、pytest、JUnit 和 Robolectric 等开源项目。

本项目采用 [Apache-2.0 许可证](LICENSE)。第三方依赖遵循各自许可证。
