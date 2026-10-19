# Android 1.8.2 签到修复验证

日期：2026-10-19。包名 com.xmu.assistant，versionCode 43；Windows 保持 1.8.1。[发布与下载](releases/v1.8.2.md)。

## 修复内容

1. 签到页的进行中事件与最近记录可能包含同一个签到 ID。原先两个区块直接把该 ID 用作同一个 LazyColumn 的 key，导致 IllegalArgumentException。现在分别使用 active: 与 history: 前缀，保留两处记录及原始业务 ID。
2. 首页重新接入共享的 RollcallSectionState，显示当前签到、数字码、雷达首选地点、加载和失败状态。支持手动刷新、签到详情和对应课程的坐标管理入口。首页和签到页在登录就绪且处于前台时按原有 15 秒新鲜度窗口检查是否需要刷新；离开页面或进入后台停止轮询。自动刷新不反复弹出提示，手动刷新保留原有反馈。
3. 构建检查发现现场采点的 runCatching 权限异常处理无法被 Lint 识别。改成显式捕获 SecurityException，沿用原来的错误提示与收尾行为，未关闭或屏蔽检查。

## 验证证据

- 先在未修复的实现上运行新增的重复 ID 回归用例，复现 Key was already used 异常；修复后同一用例通过。
- Debug 全量测试：787 项，0 失败、0 错误、0 跳过。新增用例覆盖重复 ID、首页数字与雷达展示、活动结束清空、刷新与跳转、加载禁用、登出隐藏、失败保留旧数据，以及静默刷新只发送 GET。
- 首页使用 Robolectric 原生图形渲染，检查 320×640 布局，数字码 001204 保留前导零，刷新、详情和坐标管理操作均已测试。
- Lint：0 错误、36 警告、13 条信息。
- Release 经 R8 压缩构建成功。APK 元数据为 1.8.2 / 43，v2 签名验证通过，签名证书与仓库原有固定签名密钥一致。

检查入口：在 android 目录运行：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug assembleRelease
```

测试报告在 android/app/build/reports/tests/testDebugUnitTest/；Lint 报告在 android/app/build/reports/lint-results-debug.html。首页渲染图在 android/app/build/rollcall-ui/home-current-rollcalls.png。

## 安装与设备确认

下载公开版 xmu-assistant-release.apk 后覆盖安装，不要先卸载。当前未连接真机；实际课堂签到时，需在设备上确认首页显示当前签到，并能打开签到详情、返回首页。此次验证使用虚构数据和模拟传输，没有向学校提交签到。
