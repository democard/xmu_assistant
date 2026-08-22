<div align="center">

# 🎓 xmu助手（XMU Assistant）

**厦门大学 LNT / TronClass 双端助手 —— Windows 桌面端 + Android 原生端，签到、课件、课表、成绩、考试安排一站搞定。**

Windows 桌面端 · PySide6 　|　 Android 原生端 · Kotlin + Jetpack Compose

[特性总览](#-特性总览) · [快速开始](#-快速开始) · [二次开发](#-二次开发) · [项目结构](#-项目结构) · [接口实现说明](#-接口实现说明)

</div>

---

## 🤔 为什么有它

厦大教学用的是 TronClass（LNT 学习平台）和教务系统：签到总是悄悄出现、课件散落在章节里、课表成绩考试各在一个系统。xmu助手把这些**碎片化的学生日常全部收进一个工具**：

- **桌面端**替你在电脑上 7×24 盯住签到，识别数字 / 雷达 / 二维码签到，数字码自动填、雷达自动答，再也不会错过签到；
- **Android 端**把教务系统搬进手机：全学期课表（支持桌面小卡片）、成绩统计与长图分享、考试安排与考前强提醒；
- 两端都能像本地应用一样**直接下载课件**，平台版权保护的附件也能识别并下载。

---

## 📜 参考与致谢

- 签到监控、数字签到与雷达签到处理思路参考 [KrsMt-0113/XMU-Rollcall-Bot](https://github.com/KrsMt-0113/XMU-Rollcall-Bot)（签到轮询接口、`number_code` 递归提取、基于距离估算候选位置、Cookie 保存恢复）；
- 课件下载参考 [KrsMt-0113/XMUFD](https://github.com/KrsMt-0113/XMUFD)；
- Android 端课表、成绩、考试查询为本项目自行实现并真机 / 模拟器调试；
- 依赖开源项目：[requests](https://github.com/psf/requests)、[PySide6](https://doc.qt.io/qtforpython/)、[OkHttp](https://github.com/square/okhttp)、Jetpack Compose（Material 3）、WorkManager、[AndroidX Security（EncryptedSharedPreferences）](https://developer.android.com/jetpack/androidx/releases/security)、MockWebServer / Robolectric / JUnit4。

---

## ✨ 特性总览

| 能力 | Windows 桌面端 | Android 原生端 |
| --- | :-: | :-: |
| 账号登录（学号 + 密码，厦大统一认证） | ✅ | ✅ |
| 登录态 Cookie 保存 / 恢复 / 会话健康探测 | ✅ | ✅ |
| 签到事件轮询监控（数字 / 雷达 / 二维码识别） | ✅ | ✅ |
| 数字签到自动填码、雷达签到自动应答 | ✅ | ✅ |
| 防抖策略（随机延迟、应答门槛、手动确认） | ✅ | ✅ |
| 通知：系统通知 / PushPlus 微信 / QQ 邮箱 | ✅ | ✅ |
| 签到历史查询（按学年 / 学期 / 时间范围 / 未签筛选） | ✅ | — |
| 课程课件浏览与批量下载 | ✅ | ✅ |
| 课表（教务系统全学期排课，周 / 日程视图） | — | ✅ |
| 桌面小卡片（今日课程 Widget） | — | ✅ |
| 成绩统计（GPA / 加权 / 学分）与长图分享 | — | ✅ |
| 考试安排查询与考前提醒（精确闹钟 + 全屏强提醒） | — | ✅ |
| 深链接直达（`xmurollcall://`） | — | ✅ |
| 系统托盘、开机自启 | ✅ | — |

---

## 🖥️ Windows 桌面端

由 `/xmu-rollcall-cli` Python 包驱动，`PySide6` 实现，多页签工作台 + 系统托盘常驻。

### 首页 · 签到监控

- 登录、退出登录、**自动恢复登录态**（Cookie 存本地，重启免登录）；
- 轮询当前账号可见的签到事件，识别**数字签到 / 雷达签到 / 二维码签到**；
- 数字签到自动读取并显示签到码，支持手动处理选中签到或标记跳过；
- 「检测后自动签到」可开可选：数字签到作答前**随机延迟 10–30 秒**、支持应答门槛与手动确认，避免被平台判定为脚本行为；
- 二维码签到**只提醒、不自动处理**（需要扫码，无法代做）；
- 三种通知渠道：系统通知、PushPlus 微信、QQ 邮箱（SMTP）。

### 签到情况

- 拉取账号的全部课程与签到记录，按学年 / 第一 / 第二 / 第三学期筛选；
- 时间范围筛选：今天 / 本周 / 本学期，默认本学期，支持只看未签到；
- 按日期聚合展示，状态区分：未签 / 未知 / 已签 / 无记录。

### 课程课件

- 按学年、学期、课程筛选，课程顺序保持平台接口原始顺序；
- 课件严格按**平台章节树**排序展示（而非活动接口顺序），活动详情**并发拉取**减少等待；
- 显示文件名、类型、大小、发布时间、处理状态与下载权限；
- Ctrl 多选批量下载 / 下载整个课程；下载按课程分目录保存，重名不覆盖；
- `allow_download=false` 但程序可下载的课件标记为「可下载（平台版权保护）」并允许下载。

### 策略与日志

- 策略页：轮询间隔（1–300 秒可调）、自动签到开关与延迟参数；
- 日志页：登录、刷新、轮询、异常全程留痕，方便排查。

---

## 📱 Android 原生端

位于 `/android`，`Kotlin + Jetpack Compose (Material 3)`，独立登录、独立后台监控，与桌面端互不依赖。

### 签到监控（移动端）

- 前台服务 `RollcallMonitorService` 常驻轮询，检测到签到后推送本地通知（含课程、类型、剩余时间、状态）；
- 数字 / 雷达签到可开关自动处理，二维码签到只提醒；
- 通知携带深链接 `xmurollcall://rollcall/{id}`，点击直达处理页。

### 教务三件套（本端独立实现）

- **课表**：从教务系统读取全学期排课，周 / 日程双视图；格子显示课程名、节次、当周教室，点击查看老师、完整周次与平行教学班详情；
- **成绩**：百分制成绩 + 统计面板（平均绩点 / 加权绩点 / 平均分数 / 加权分数 / 已修总学分），一键渲染成**竖版长图**分享；
- **考试安排**：当前学期 + 历史学期查询，未排考课程识别；每场未完成考试在开考前指定分钟数触发**精确闹钟提醒**（可选锁屏全屏强提醒，Android 13+ 自动引导通知授权）。

### 桌面小卡片（Widget）

- 桌面「今日课程」卡片：当天课程、时间、地点一目了然；
- 策略页一键添加、可开关；卡片只读取非敏感摘要，不含学号 / 密码 / Cookie。

### 内置教程

- 每个功能页内置图文使用说明，含国产系统后台限制与电池策略提示，开箱即用不迷路。

---

## 🔐 安全与隐私设计

这是本项目工程化最深的部分，不止是"能用"：

- **数据只在本机**：账号、Cookie、通知配置、课表 / 成绩 / 考试缓存全部只存本地，无任何云端中转；
- **加密存储**：Android 端账号与 Cookie 使用 `EncryptedSharedPreferences`（AndroidX Security）加密保存；
- **会话健康探测**：`SessionHealthProbe` 通过状态码、身份页跳转（`c-identity.xmu.edu.cn` / `ids.xmu.edu.cn`）与登录页特征识别会话是否过期，绝不在失效会话上空跑签到；
- **请求门控**：`RequestGate` / `MonitorRunGate` 以互斥区串行化登录、登出、轮询、刷新，杜绝竞态；换号登录自动清空成绩 / 课表 / 考试缓存与提醒闹钟，**防串号、防串提醒**；
- **有界并发**：批量请求走 `BoundedParallel` 有界并发池；课件详情并发拉取但受窗口限制，不给平台造成压力；
- **只读网络基准**：专门的 `networkBenchmark` 构建变体只做只读探测并输出网络时延统计（`NetworkTiming`），用于评估校园网 / 隧道环境，不产生任何写操作；
- **缓存版本化**：课表缓存带格式版本号，旧格式自动失效重拉；数据刷新带 `SessionEpoch` 防过期结果覆盖新数据；
- **覆盖安装不丢数据**：Android 固定调试签名（keystore 入库），`install -r` 覆盖升级保留本地登录态；
- **桌面端可定位配置目录**：默认 `~/.xmu_rollcall`，可用环境变量 `XMU_ROLLCALL_CONFIG_DIR` 重定向；写失败自动回退到当前目录。

---

## 🚀 快速开始

### 开箱即用（推荐）

构建产物随仓库提交在 `release/`，同时发布到 GitHub Releases：

```text
release/xmu-assistant.exe           # Windows 桌面端（含 Python 运行时 + PySide6，免安装；v1.1.2，含 DPAPI 凭据加密）
                                    #   复现构建：scripts/build_dashboard_exe.bat
release/xmu-assistant-release.apk   # Android 端（release 构建，R8 压缩；沿用固定签名，覆盖安装不丢登录数据）
release/SHA256SUMS.txt              # 全部产物 SHA-256 校验值
```

- **Windows**：下载 `xmu-assistant.exe` 双击运行。首次登录后在「首页」输入学号密码，程序自动恢复 / 保存 TronClass Cookie、刷新签到情况、读取课件列表并预加载默认课程课件；账号等敏感信息经 DPAPI 加密落盘。
- **Android**：通过 ADB 或系统安装器安装 `xmu-assistant-release.apk`；系统通知 / 电池策略 / 小卡片按内置教程配置即可。

> 仅需要 Windows 系统 + 可访问厦大统一认证与 LNT/TronClass 的网络环境。

### 从源码运行（二次开发）

```powershell
# 环境准备（推荐 Conda）
conda create -n xmu-rollcall-dashboard python=3.11
conda run -n xmu-rollcall-dashboard python -m pip install -r .\xmu-rollcall-cli\requirements.txt pyinstaller

# 运行桌面端
$env:PYTHONPATH=(Resolve-Path .\xmu-rollcall-cli).Path
conda run -n xmu-rollcall-dashboard python -m xmu_rollcall.desktop
```

Android 端用 Android Studio 打开 `android/` 直接构建（要求 JDK 17 + Android SDK，`compileSdk 35`、`minSdk 26`）。

---

## 🛠️ 技术栈

| 端 | 技术 |
| --- | --- |
| Windows 桌面端 | Python 3.11+、PySide6、requests、xmulogin、pywin32（DPAPI 加密账号/授权码/Token）、PyInstaller 打包 |
| Android 端 | Kotlin、Jetpack Compose（Material 3）、OkHttp、EncryptedSharedPreferences、WorkManager、AlarmManager、AppWidget |
| 测试 | pytest（桌面引擎 / 课件 / 通知 / 配置）、MockWebServer + Robolectric + Compose UI Test（Android JVM 测试，无需模拟器） |

---

## 📁 项目结构

```text
xmu_assistant/
├── README.md
├── LICENSE                        # Apache-2.0
├── assets/                        # 图标 / Logo（含 SVG 源文件与多尺寸 PNG）
├── release/                       # 已构建的 EXE / APK 与 SHA-256 校验值
├── scripts/                       # 打包与测试脚本
│   ├── build_dashboard_exe.bat    # 一键打包 Windows EXE
│   ├── start_android_test.ps1/bat # Android 构建测试
│   ├── simulate_rollcall_detection.py
│   └── generate_logo_assets.py
├── tests/                         # Python 侧测试（pytest）
├── xmu-rollcall-cli/              # Windows 桌面端 Python 包
│   ├── pyproject.toml
│   ├── requirements.txt
│   └── xmu_rollcall/
│       ├── config.py              # 配置与默认策略
│       ├── courseware.py          # 课件活动 / 章节树 / 下载
│       ├── engine.py              # 统一签到引擎（双端复用）
│       ├── verify.py              # 数字签到 / 雷达签到作答（含距离估算定位）
│       ├── notifications.py       # 系统 / PushPlus / QQ 邮箱通知
│       ├── proxy_guard.py
│       ├── utils.py               # 重试、请求与基础 URL
│       └── desktop_qt/            # PySide6 桌面界面
│           ├── app.py             # 主窗口 / 托盘 / 事件调度
│           ├── core.py            # 数据模型与 LNT 查询
│           └── theme.py
└── android/                       # Android 原生端（Kotlin + Compose）
    ├── README.md
    ├── app/src/main/java/com/xmu/assistant/
    │   ├── RollcallMonitorService.kt   # 前台服务轮询
    │   ├── TronclassLogin.kt / SessionHealth.kt / SessionRecovery.kt
    │   ├── RequestGate.kt / MonitorRunGate.kt / BoundedParallel.kt
    │   ├── XmuScheduleClient.kt / ScheduleWidgetProvider.kt
    │   ├── XmuScoreAutoQueryClient.kt / ScoreShare.kt
    │   ├── XmuExamClient.kt / ExamReminder.kt / ExamSectionState.kt
    │   ├── CoursewareClient.kt / CoursewareDownloadBatch.kt
    │   └── ReadOnlyBenchmarkRunner.kt  # networkBenchmark 只读基准
    ├── keystore/                  # 固定调试签名（覆盖安装不丢数据，已入库）
    └── app/                       # 模块源码与资源
```

---

## 🔌 接口实现说明

### 数字签到

1. `GET /api/radar/rollcalls` 读取当前账号可见签到事件，取 `rollcall_id`；
2. `GET /api/rollcall/{rollcall_id}/student_rollcalls` 递归查找 `number_code`；
3. `PUT /api/rollcall/{rollcall_id}/answer_number_rollcall` 提交（带随机 deviceId）。

### 雷达签到

- 提交接口 `PUT /api/rollcall/{rollcall_id}/answer`，请求体含经纬度、精度、设备 ID 等；
- 平台无直接"老师位置"接口时：利用两次已知坐标应答返回的 `distance`，做**平面坐标圆相交求解**估算签到中心点，再尝试候选位置（代码见 `verify.py`）。

### 课程课件

- 课程列表：多候选课程接口依次探测（`/api/my-courses`、`/api/courses?...` 等）；
- 章节树：`GET /api/courses/{course_id}/modules`；活动：`GET /api/course/{course_id}/courseware-activities`；详情：`GET /api/activities/{activity_id}` 并发；
- 展示顺序按**章节树合并**；`homework` 类型活动在取详情前过滤，不作为课件展示。

### 会话与登录

- 登录走厦大统一认证（xmulogin），Cookie 本地存取；
- Android 端 `SessionHealthProbe` 判定过期：`401/403`、跳转至已知身份域、响应体含登录表单特征均可识别。

---

## ⚙️ 本地配置

桌面端默认配置目录：当前用户主目录 `~\.xmu_rollcall`

```text
~\.xmu_rollcall\
├── config.json     # 账号与策略配置
└── 1.json、2.json  # 对应账号的 TronClass Cookie
```

可用环境变量改目录：

```powershell
$env:XMU_ROLLCALL_CONFIG_DIR="<config-directory>"
```

---

## 🧪 测试与发布

```powershell
# 桌面端：测试与编译检查
conda run -n xmu-rollcall-dashboard python -m pytest tests
conda run -n xmu-rollcall-dashboard python -m compileall xmu-rollcall-cli\xmu_rollcall

# 打包 EXE
.\scripts\build_dashboard_exe.bat   # 产物 dist\xmu-assistant.exe

# 发布（改版本号 → 测试 → 打包 → 算 SHA256 → 打 tag → 上 GitHub Releases）
gh release create vx.y.z .\dist\xmu-assistant.exe --title "vx.y.z" --notes "Release notes"
```

Android 端：`.\scripts\start_android_test.ps1` 构建并跑 JVM 测试（Robolectric 渲染 Compose 组件，无需模拟器）；测试用例覆盖成绩复制粘贴、Android 源码完备性、缓存格式等。

### 发布版本约定（Android）

- 每次合入功能性修复后发布前：`android/app/build.gradle.kts` 中 `versionCode` +1，`versionName` 按语义化版本递增（修复 +0.0.1，功能 +0.1）。
- 发布产物同步到 `release/` 后，必须更新 `release/SHA256SUMS.txt`：
  `cd release && sha256sum xmu-assistant.exe xmu-assistant-release.apk > SHA256SUMS.txt`

### 发布安全说明（务必读）

- **固定调试签名**：`release` APK 用仓库内固定 `android/keystore/debug.keystore`（默认口令 `android/android`，公开）
  签名，换取 `install -r` 覆盖升级不丢本地登录态（重签会丢加密数据）。**代价：任何能拿到该密钥的人
  可伪造同签名更新覆盖安装**，触及本地加密凭据——这是刻意权衡。建议评估：如发布到公开渠道，改为产线
  独立 keystore 并妥善保管，或在 README/发布说明中向用户明示"仅接受官方来源更新"。
- **桌面 EXE**：`release/xmu-assistant.exe` 已重建为 v1.1.2（含 DPAPI 凭据加密）；复现构建请在有
  PyInstaller 的环境执行 `.\scripts\build_dashboard_exe.bat`（已带 `--hidden-import win32crypt`）并同步 SHA256SUMS。
- 发布前检查：产物与源码 HEAD 一致（`release/` 一起提交）、SHA256SUMS 已更新、APK 无 DEBUGGABLE、versionCode 单调递增。

---

## ⚠️ 免责声明

本项目**仅用于个人学习、研究和本人数据管理**。请遵守学校、课程与平台规则：程序只查询当前登录账号有权访问的数据，不应被用于绕过权限、访问他人信息或干扰平台服务。签到自动化存在被平台判定异常的风险，请合理使用并自行承担后果。

---

本项目基于 Apache-2.0 许可证开源。