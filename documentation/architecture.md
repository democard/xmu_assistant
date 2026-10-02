# 模块定位

Windows 和 Android 是同一仓库内独立运行的两个客户端。以下按现有目录定位代码；路径均相对仓库根目录，源码包名与构建入口保持原有约定。

## Windows

| 职责 | 主要入口 |
| --- | --- |
| 桌面启动与页面装配 | [desktop.py](../xmu-rollcall-cli/xmu_rollcall/desktop.py)、[desktop_qt/app.py](../xmu-rollcall-cli/xmu_rollcall/desktop_qt/app.py) |
| 页面、后台事件、托盘与主题 | [desktop_qt/](../xmu-rollcall-cli/xmu_rollcall/desktop_qt/) |
| 配置与凭据保存 | [config.py](../xmu-rollcall-cli/xmu_rollcall/config.py)、[secrets.py](../xmu-rollcall-cli/xmu_rollcall/secrets.py) |
| 签到轮询、校验与统计 | [engine.py](../xmu-rollcall-cli/xmu_rollcall/engine.py)、[verify.py](../xmu-rollcall-cli/xmu_rollcall/verify.py)、[rollcall_progress.py](../xmu-rollcall-cli/xmu_rollcall/rollcall_progress.py) |
| 课件与通知 | [courseware.py](../xmu-rollcall-cli/xmu_rollcall/courseware.py)、[notifications.py](../xmu-rollcall-cli/xmu_rollcall/notifications.py) |
| 包依赖与 EXE 打包 | [pyproject.toml](../xmu-rollcall-cli/pyproject.toml)、[xmu-assistant.spec](../xmu-assistant.spec) |
| 回归测试 | [tests/](../tests/) |

`xmu-rollcall-cli/` 是沿用的源码目录名，当前应用入口为桌面界面。安装后的包内图标位于 `xmu_rollcall/assets/`，根目录 `assets/` 保存品牌源文件；两处用途不同。

## Android

主源码目录为 [`android/app/src/main/java/com/xmu/assistant/`](../android/app/src/main/java/com/xmu/assistant/)。

| 职责 | 主要文件或入口 |
| --- | --- |
| 页面入口与导航 | `MainActivity.kt`、`MainScreen.kt`、`AppNavigation.kt` |
| 登录、会话和请求隔离 | `TronclassLogin.kt`、`SessionRecovery.kt`、`SessionEpoch.kt`、`RequestGate.kt` |
| 签到、人数和后台监控 | `RollcallEngine.kt`、`StudentRollcallProgress.kt`、`RollcallMonitorService.kt` |
| 课表与小组件 | `XmuScheduleClient.kt`、`SchedulePlanner.kt`、`ScheduleWidgetProvider.kt` |
| 成绩与排名 | `XmuScoreAutoQueryClient.kt`、`ScoreShare.kt`、`XmuRankClient.kt` |
| 考试与课件 | `XmuExamClient.kt`、`ExamReminder.kt`、`CoursewareClient.kt` |
| 依赖和应用配置 | [版本目录](../android/gradle/libs.versions.toml)、[应用构建配置](../android/app/build.gradle.kts) |
| 单元测试和样例 | [app/src/test/](../android/app/src/test/) |

Android 的 `networkBenchmark` 开发变体及其测试位于 `app/src/networkBenchmark/` 与 `app/src/testNetworkBenchmark/`，与普通发布构建分开维护。

## 共享维护入口

- [scripts/](../scripts/)：Windows 打包、Android 安装启动、图标生成及离线模拟。
- [历史开发记录](history/README.md)：跨端行为约定的来由。
- [测试报告](../TEST_REPORT.md)：历史发布检查与未验证边界。

构建命令见各端 README；运行前确认命令要求的工作目录和环境。
