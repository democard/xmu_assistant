<div align="center">

<img src="assets/xmu-assistant-mark.png" width="96" alt="虾大助手图标">

# 虾大助手 · Campus Assistant

一个支持 Windows 和 Android 的个人学习工具，帮助整理课程信息、查看学习记录与管理日常安排。

课程、课件、课表、成绩与提醒，集中在一个轻量客户端中。

**当前版本：Windows 1.7.4 · Android 1.7.4（versionCode 31）**

1.7.4 改善记录查询的兼容性与时间处理，安装包已按最新源码构建。Android 同版部分流程已有用户实测反馈，完整验证范围见下方说明。

[下载 Windows 程序](https://github.com/democard/xmu_assistant/releases/download/v1.7.4/xmu-assistant.exe) · [下载 Android 安装包](https://github.com/democard/xmu_assistant/releases/download/v1.7.4/xmu-assistant-release.apk) · [发布说明](https://github.com/democard/xmu_assistant/releases/tag/v1.7.4)

</div>

两端独立登录、独立运行，无需配对。Windows 端适合在电脑上查看学习记录、批量下载课件；Android 端还提供周课表、桌面小组件、成绩统计和考前提醒。

[安装与使用](#安装与使用) · [功能对照](#功能对照) · [本版更新](#本版更新) · [数据与隐私](#数据与隐私) · [开发与验证](#开发与验证) · [问题反馈](#问题反馈)

## 安装与使用

### 下载

| 平台 | 版本 | 文件与运行要求 |
| --- | --- | --- |
| Windows | 1.7.4 | [xmu-assistant.exe](https://github.com/democard/xmu_assistant/releases/download/v1.7.4/xmu-assistant.exe)，下载后运行，无需另装 Python |
| Android | 1.7.4（versionCode 31） | [xmu-assistant-release.apk](https://github.com/democard/xmu_assistant/releases/download/v1.7.4/xmu-assistant-release.apk)，Android 8.0 及以上 |
| 对应源码 | v1.7.4 | [源码 ZIP](https://github.com/democard/xmu_assistant/archive/refs/tags/v1.7.4.zip) |

文件校验值见 [SHA256SUMS.txt](https://github.com/democard/xmu_assistant/releases/download/v1.7.4/SHA256SUMS.txt)。在下载目录打开 PowerShell，可用以下命令计算 SHA-256，与校验文件中的同名条目核对：

```powershell
Get-FileHash .\xmu-assistant.exe -Algorithm SHA256
Get-FileHash .\xmu-assistant-release.apk -Algorithm SHA256
```

**Android 更新请从本仓库发布页下载。** 当前 APK 沿用仓库内公开的固定调试签名，支持覆盖同签名旧版；该签名不能证明安装包来自维护者，任何持有公开密钥的人都能制作同签名安装包。卸载应用会清除应用私有数据，正常更新请使用覆盖安装。

### 第一次使用

1. 打开应用，用自己的虾大统一认证账号登录。网络需要能访问课程平台；课表、成绩和考试查询还需要能访问教务服务。
2. 按需查看学习记录、课程课件，或在 Android 端查看课表和成绩。登录过期时重新登录。
3. 需要提醒时，先配置通知渠道和个人策略。Android 请按照内置教程检查通知权限和后台电池限制。

后台功能依赖应用运行、有效登录态和网络连接。Windows 端需保持程序运行；Android 使用前台服务，仍可能受系统或厂商后台策略影响。提醒不保证始终及时。

## 功能对照

| 功能 | Windows | Android |
| --- | --- | --- |
| 登录态保存与恢复、会话过期检测 | 支持 | 支持 |
| 学习记录与提醒 | 查询、统计、导出与个人策略 | 近期记录、状态查看与个人策略 |
| 课程课件浏览与批量下载 | 支持 | 支持 |
| 系统通知、PushPlus、QQ 邮箱通知 | 支持 | 支持 |
| 全学期课表、周 / 日程视图、日历导出 | — | 支持 |
| 今日课程桌面小组件 | — | 支持 |
| 成绩、GPA / 加权均分 / 学分统计、长图分享 | — | 支持 |
| 专业排名、绩点证明 PDF 导出 | — | 手动申请或读取已有结果 |
| 考试安排与考前提醒 | — | 支持 |
| 常驻与快捷操作 | 系统托盘、开机自启、页面快捷键 | 快捷设置磁贴、图标长按入口 |

### 记录与课件

支持查看课程相关记录与状态，提醒及其他操作按用户设置启用，具体结果以原平台记录为准。

课件可按课程浏览并批量下载。Windows 按平台章节组织列表、按课程目录保存，重名文件不直接覆盖；Android 支持课程搜索和学年学期筛选，无法直接下载时保留平台入口。下载是否成功仍取决于当前账号权限、链接有效性和服务器响应。

### Android 课表与教务功能

课表提供每周七天、每天十一节的网格和日程视图，支持查看今天、跳转周次、复制课程详情及导出 `.ics` 日历。今日课程小组件保存未来十四天的课程摘要，跨日可读取本地排课，超过缓存范围需刷新；显示数量和刷新时机也受桌面启动器影响。

成绩页支持统计、模拟成绩计算和长图分享。专业排名需要用户手动发起查询；已有完整结果时可复用，成绩变化只标记待更新。证明 PDF 可导出或清理应用内副本，已导出的副本需自行管理。**专业排名接口尚未用真实校园账号完成联调。**

考试页按学期展示安排，可设置考前提醒。课表、成绩和考试的缓存用于离线查看，最新安排仍应以学校系统为准。更多入口与构建说明见 [Android 文档](android/README.md)。

## 本版更新

### 1.7.4 · 当前公开版本

- 改善双端记录详情查询的兼容性。
- 修复跨时区、跨日期记录的时间处理。
- 修复 Windows 后续查询失败或返回空值时沿用旧缓存的问题。
- 统一公开文字与界面展示文案，保留原有图像、接口及旧版兼容标识。
- 补充回归用例，保留现有个人策略与状态核实流程。

本版安装包已重新构建、核对版本和校验值，APK 签名保持兼容，Windows 隔离启动检查通过。用户已反馈此前同版 Android 测试包部分流程成功；不将该反馈等同于新发布包全功能验收。详见 [发布说明](documentation/releases/v1.7.4.md)与[验证记录](documentation/verification-1.7.4.md)。

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

Windows 默认配置目录为当前用户主目录下的 `.xmu_rollcall`。需要独立测试配置时，可在启动前设置 `XMU_ROLLCALL_CONFIG_DIR`。目录结构、加密限制及开发方式见 [Windows 文档](xmu-rollcall-cli/README.md)。

提交代码或反馈问题时，不要附带真实账号、密码、Cookie、通知密钥、运行配置和未经脱敏的截图。仓库保存源码、测试和校验文件，EXE / APK 通过 GitHub Releases 分发；本地运行数据和构建日志不作为发布内容。

## 开发与验证

### 从源码运行

先获取源码，再在项目根目录执行环境准备和启动命令。以下示例使用 PowerShell，需要 Python 3.11 或以上；独立虚拟环境无需 Conda：

```powershell
git clone https://github.com/democard/xmu_assistant.git
cd xmu_assistant
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r .\xmu-rollcall-cli\requirements.txt
$env:PYTHONPATH = (Resolve-Path .\xmu-rollcall-cli).Path
.\.venv\Scripts\python.exe -m xmu_rollcall.desktop
```

Android 可用 Android Studio 打开 `android/`，配置 JDK 17 和 Android SDK 35，再使用仓库的 Gradle 8.7 Wrapper 构建。测试与产物路径见 [Android 构建说明](android/README.md#构建与测试)；Windows 的测试、打包及本地配置见 [Windows 开发说明](xmu-rollcall-cli/README.md)。

### 1.7.4 本地验证

| 检查 | 本地结果 |
| --- | --- |
| Windows 自动化回归 | 703 项测试、225 个子测试通过；静态检查与编译检查通过 |
| Android Debug 回归 | 758 项测试通过；Lint 0 错误、36 警告（含 30 条依赖版本提示） |
| Windows 安装包 | EXE 构建、业务模块比对及隔离启动检查通过；完整实际流程仍待验证 |
| Android 安装包 | release 构建、签名与版本检查通过；此前同版测试包部分实际流程已有用户反馈，新发布包未单独完成真机全功能验收 |

自动化测试使用虚构数据和模拟传输，不代表所有设备、网络或实际业务场景均已验证。维护侧没有执行真实业务写入；用户反馈仅覆盖其实际测试的 Android 流程，真实通知、长期后台运行及 Windows 完整流程仍待验收。详见 [1.7.4 验证记录](documentation/verification-1.7.4.md)，历史发布记录见 [TEST_REPORT.md](TEST_REPORT.md)。

### 项目结构

[开发文档导航](documentation/README.md) · [贡献与目录约定](CONTRIBUTING.md) · [模块定位](documentation/architecture.md)

```text
xmu_assistant/
├── android/                 # Kotlin + Compose 原生客户端及 JVM 测试
├── xmu-rollcall-cli/        # Python 包、PySide6 桌面界面与业务逻辑
├── tests/                   # Python 回归测试
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
