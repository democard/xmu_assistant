# 虾大助手 Android

虾大助手的 Android 原生客户端，使用 Kotlin 和 Jetpack Compose，提供今日课表概览、全学期课表、成绩与排名、课程课件下载、考试安排与签到监控提醒等功能。

> **说明**：因原签到提交接口已不可用，已下线自动签到、一键签到、数字签到码抓取与课程雷达坐标功能；保留**签到监控提醒**（系统通知 / PushPlus / QQ 邮箱）与**签到情况查看**（保留数字 / 雷达 / 二维码类型标注、实时进度与近期出勤记录）。

## 使用入口

底部导航为「首页 / 课表 / 成绩 / 签到 / 更多」。课件、考试、通知、策略和教程集中在「更多」。

| 入口 | 可以做什么 |
| --- | --- |
| 首页 | 登录、查看今日课程与近期考试倒计时、控制签到监控与当前签到摘要、快捷跳转常用功能 |
| 课表 | 周课表 / 日程视图、查看今天、跳转周次、本地微调课程安排、导出日历 |
| 成绩 | 成绩与学分统计、GPA / 均分、模拟成绩计算、长图分享、专业排名与证明 |
| 签到 | 查看进行中签到类型与进度、回顾近期签到记录与本人出勤状态 |
| 更多 | 课件批量下载、考试安排与提醒、通知渠道、监控间隔与外观策略、使用教程 |

应用支持浅色、深色和跟随系统主题。切页、旋转时保留部分页面状态；登出或切换账号时隔离账号相关状态。快捷设置磁贴可启停签到监控，长按应用图标可直达签到情况、课表和成绩。

## 功能边界

- **记录与提醒**：需要有效登录态、网络和后台运行条件，相关操作按个人策略启用，状态以原平台记录为准。
- **课表**：支持七天、十一节的周网格和日程列表，重叠课程分别展示；课表右上角菜单可导出 `.ics`，按单双周和分段周次编排。
- **今日课程小组件**：保存未来十四天课程摘要，优先展示尚未结束的课程；跨日可读本地排课，超出缓存范围需刷新。尺寸和后台刷新受系统、桌面启动器影响。
- **专业排名**：手动查询或申请计算，已有完整记录可复用；成绩变化仅标记待更新。证明 PDF 支持系统导出和应用内清理，导出副本需自行删除。
- **考试提醒**：精确闹钟和全屏提醒受系统权限限制，请按内置教程配置；不能保证系统限制后台时仍按时触发。
- **课件下载**：支持课程搜索、学年学期筛选和批量下载；不能直接下载的条目保留平台入口。续传校验响应范围、长度、资源身份与版本，说明见下节。

v1.7.3 更新了日历事件标识。向已有旧版导入的日历再次导入可能形成重复事件，建议先移除旧导入再导入新版。

### 1.8.6 稳定性与会话修复

- 请求取消、客户端初始化失败和账号切换后，及时恢复 loading / 请求门状态，旧任务的 Cookie 与缓存写回受会话及修订顺序保护。
- 修复排名、考试、签到和课件任务的取消 / 异常边界；前台签到与监控共享提交门，避免同一记录并发重复提交。
- 课表本地修正统一作用于周视图、ICS 和桌面小组件；修复手动修改、后台 Worker、进程快照和持久化文件之间的交错覆盖。
- 成绩长图保存只有在 MediaStore 完整写入并发布后才返回分享 URI；失败清理未完成项目，分享任务受会话与 Activity 生命周期管理。

完整内容见 [1.8.6 发布说明](../documentation/releases/v1.8.6.md)；构建、回归和包检查结果见 [1.8.6 验证记录](../documentation/verification-1.8.6.md)。三轮维护文档保留排查、红绿回归与时序分析过程。

### 1.8.5 界面与交互优化（历史）

新增一键签到，改善数字 / 雷达 / 二维码卡片、实时人数、成绩与课表布局；周次、课程安排和选课使用可滚动弹窗。完整已发布内容见 [1.8.5 发布说明](../documentation/releases/v1.8.5.md)。

### 1.8.4 下载、缓存与邮件修复（历史）

下载只在资源归属与可靠版本验证器匹配时续传；缺少可靠验证器则重新全量下载。旧版无归属的 `.part` 保留，新下载另起文件名，不与其它同名课件拼接。邮件渠道要求有效 TLS 和服务器身份校验，提交后回执不明不再盲目切换端口重发。历史缓存的会话有效性检查与实际写入共用提交屏障，登出后的旧任务不得写回。

更新详情见 [1.8.4 发布说明](../documentation/releases/v1.8.4.md)，修复依据见 [八项修复记录](../documentation/repair8-2026-10-24.md)。

## 数据与更新

账号、Cookie 等凭据使用 EncryptedSharedPreferences 保存。业务缓存和课件不等于加密凭据；小组件摘要包含课程名、时间和地点，虽不含密码或 Cookie，仍可能涉及个人安排。历史展示缓存登出时删除；通知 / 应答去重存储保留，并按账号 Cookie 指纹隔离。

课程雷达地点与本地课表修正是按课程匹配的持久设置，沿用跨账号保留行为，登出不删除。共用设备或切换账号后，请先在对应管理页检查设置；无需保留的地点和修正应主动删除。

查询及用户启用的操作会连接相应服务；开启 PushPlus 或邮件通知会向所选服务发送通知。成绩长图、课表日历、证明 PDF 和日志可能包含个人信息，分享前需要检查。应用内清理不代替删除已经导出的副本。

**当前 release APK 使用仓库内公开的固定调试密钥签名。** 它保持旧版覆盖升级兼容，但不能证明发布者身份；任何持有该密钥的人都能签出同签名 APK。请从本仓库发布页下载并核对校验值，不要安装来源不明的同签名更新。卸载会清除应用私有数据。

## 构建与测试

需要 JDK 17、Android SDK Platform 35 及对应构建工具。用 Android Studio 打开本目录，设置 Gradle JDK 为 17，并配置本机 SDK；也可以通过 `JAVA_HOME`、`ANDROID_HOME` 指定环境。`local.properties` 中的 `sdk.dir` 只供本机使用，不应提交。

仓库携带 Gradle 8.7 Wrapper。以下 PowerShell 命令从**仓库根目录**开始执行；首次构建需联网下载 Gradle 和依赖：

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleRelease
```

| 产物 | 相对 `android/` 的位置 |
| --- | --- |
| Release APK | `app/build/outputs/apk/release/app-release.apk` |
| 单元测试报告 | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Lint 报告 | `app/build/reports/lint-results-debug.html` |

1.8.4 的 Debug 全量 822 项测试通过，其中 19 项邮件回归通过；Lint 0 错误、6 警告。这些是历史发布数据，不代表当前源码验证。当前 1.8.6 的构建和测试结果见 [1.8.6 验证记录](../documentation/verification-1.8.6.md)。

仓库另带 `gradle/init.gradle` 作为可选镜像配置，可用 `-I gradle/init.gradle` 显式加载。依赖未缓存完整时，不要添加 `--offline`。

### 安装到设备或模拟器

在 `android/` 目录、已有设备连接且 `adb` 可用时：

```powershell
adb install -r .\app\build\outputs\apk\release\app-release.apk
adb shell am start -n com.xmu.assistant/.MainActivity
```

也可从仓库根目录执行 `scripts/start_android_test.ps1`。该脚本只负责安装和启动已构建的 APK，优先复用运行中的模拟器，否则尝试启动名为 `xmu_assistant_api30` 的 AVD；使用前需准备该 AVD。它不构建 APK，也不运行 JVM 单元测试。

## 开发定位

源码位于 `app/src/main/java/com/xmu/assistant/`，测试位于 `app/src/test/`。

| 模块 | 相关文件 |
| --- | --- |
| 签到监控与入口 | `RollcallMonitorService.kt`、`MonitorControlTileService.kt` |
| 登录与会话 | `TronclassLogin.kt`、`SessionHealth.kt`、`SessionRecovery.kt` |
| 并发与旧请求隔离 | `RequestGate.kt`、`MonitorRunGate.kt`、`SessionEpoch.kt` |
| 课表与小组件 | `XmuScheduleClient.kt`、`ScheduleWidgetProvider.kt` |
| 成绩与图片分享 | `XmuScoreAutoQueryClient.kt`、`ScoreShare.kt` |
| 考试与提醒 | `XmuExamClient.kt`、`ExamReminder.kt` |
| 课件下载 | `CoursewareClient.kt`、`CoursewareDownloadBatch.kt` |

依赖版本集中在 [gradle/libs.versions.toml](gradle/libs.versions.toml)，应用版本、SDK、签名及构建类型在 [app/build.gradle.kts](app/build.gradle.kts)。`networkBenchmark` 是用于只读网络测量的开发变体，不是发布安装包。

功能修复发布前递增 `versionCode`，同步 `versionName`、根 README 与校验文件，核对签名兼容性。公开反馈和提交中不要包含真实账号、Cookie、通知密钥、运行缓存或未脱敏截图。
