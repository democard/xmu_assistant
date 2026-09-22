# xmu助手 Android

面向厦门大学 LNT / TronClass 与教务系统的原生客户端，使用 Kotlin 和 Jetpack Compose。与 Windows 端独立运行，登录态和业务缓存保存在设备本地。

当前版本 **1.7.3 / versionCode 30**，支持 **Android 8.0（API 26）及以上**，compileSdk / targetSdk 为 35。

[下载 APK](https://github.com/democard/xmu_assistant/releases/download/v1.7.3/xmu-assistant-release.apk) · [发布说明](https://github.com/democard/xmu_assistant/releases/tag/v1.7.3) · [项目首页](../README.md) · [测试报告](../TEST_REPORT.md)

## 使用入口

底部导航为「首页 / 课表 / 成绩 / 签到 / 更多」。课件、考试、通知、策略和教程集中在「更多」。

| 入口 | 可以做什么 |
| --- | --- |
| 首页 | 登录、启停签到监控、查看近期状态与自动处理策略 |
| 课表 | 周课表 / 日程视图、查看今天、跳转周次、复制详情、导出日历 |
| 成绩 | 成绩与学分统计、GPA / 均分、模拟成绩计算、长图分享、专业排名与证明 |
| 签到 | 查看进行中签到与最近十次历史，核实本人明细，显示人数、比例和数字码 |
| 更多 | 课件批量下载、考试安排与提醒、通知渠道、策略、使用教程 |

应用支持浅色、深色和跟随系统主题。切页、旋转时保留部分页面状态；登出或切换账号时隔离账号相关状态。快捷设置磁贴可启停监控，长按应用图标可直达签到、课表和成绩。

## 功能边界

- **签到监控**：使用前台服务轮询，需要有效登录态、网络和后台运行条件。数字 / 雷达自动处理受策略开关及人数门槛控制；二维码只提醒。刷新和复制签到码本身不会提交签到，人数未获取时不视为达到门槛。
- **课表**：支持七天、十一节的周网格和日程列表，重叠课程分别展示；课表右上角菜单可导出 `.ics`，按单双周和分段周次编排。
- **今日课程小组件**：保存未来十四天课程摘要，优先展示尚未结束的课程；跨日可读本地排课，超出缓存范围需刷新。尺寸和后台刷新受系统、桌面启动器影响。
- **专业排名**：手动查询或申请计算，已有完整记录可复用；成绩变化仅标记待更新。证明 PDF 支持系统导出和应用内清理，导出副本需自行删除。该接口尚未用真实校园账号完成联调。
- **考试提醒**：精确闹钟和全屏提醒受系统权限限制，请按内置教程配置；不能保证系统限制后台时仍按时触发。
- **课件下载**：支持课程搜索、学年学期筛选和批量下载；不能直接下载的条目保留平台入口。续传会校验响应范围与长度，异常时保留断点。

v1.7.3 更新了日历事件标识。向已有旧版导入的日历再次导入可能形成重复事件，建议先移除旧导入再导入新版。

## 数据与更新

账号、Cookie 等凭据使用 EncryptedSharedPreferences 保存。业务缓存和课件不等于加密凭据；小组件摘要包含课程名、时间和地点，虽不含密码或 Cookie，仍可能涉及个人安排。历史展示缓存登出时删除；通知 / 应答去重存储保留，并按账号 Cookie 指纹隔离。

查询和签到连接学校系统；开启 PushPlus 或邮件通知会向所选服务发送通知。成绩长图、课表日历、证明 PDF 和日志可能包含个人信息，分享前需要检查。应用内清理不代替删除已经导出的副本。

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

单元测试使用 JUnit、MockWebServer 和 Robolectric，无需启动模拟器。v1.7.3 发布前共 610 项 Debug 测试通过，Lint 0 错误、6 警告；release 构建、签名及模拟器覆盖升级已验证。真实校园功能、真实通知与 Android 真机全流程不在本次验收范围，详见[测试报告](../TEST_REPORT.md)。

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
