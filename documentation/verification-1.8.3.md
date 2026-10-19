# Android 1.8.3 验证记录

日期：2026-10-19。包名 com.xmu.assistant，versionCode 44。[安装包与更新说明](releases/v1.8.3.md)。

## 首页恢复老版的信息结构

对照 v1.7.4 的 HomePage：原先在“监控与提醒”内部使用 StatusRow 展示签到摘要，再用“查看签到详情”进入签到页。本版沿用这一结构，将当前签到接入已有自动签到快捷开关区域。

- 移除 1.8.2 初版候选新增的独立标题卡、刷新按钮和各项签到详情卡片。
- 只显示当前课程、签到类型及有多项时的总数；点击“查看签到详情”查看完整信息并手动刷新。
- 自动刷新、失败保留已有数据、活动结束清空和登出隐藏继续生效。
- 保留签到页重复 key 闪退修复，以及原有策略、课程坐标管理入口。

## 本轮有效检查

- HomePageUiTest：11 项通过，覆盖摘要展示、独立控件移除、结束清空、失败保留、登出隐藏、查看详情与既有坐标管理入口，以及既有登录和监控控件。
- RollcallCardsUiTest：5 项通过，含同一 ID 同时出现在进行中与历史记录的闪退回归。
- 合计 16 项，0 失败、0 错误、0 跳过。其余未改动模块复用 1.8.2 候选的 787 项全量测试证据，记录见 [1.8.2 候选验证](verification-1.8.2.md)。
- Robolectric 原生图形渲染为 320×640，已检查摘要位于已有快捷开关区域内，无新增独立模块。
- Lint：0 错误、36 警告、13 条信息。
- R8 Release 构建成功；实际 APK 为 1.8.3 / 44，v2 签名验证通过，签名配置与既有版本一致。

复现入口：在 android 目录运行：

```powershell
.\gradlew.bat testDebugUnitTest --tests com.xmu.assistant.HomePageUiTest --tests com.xmu.assistant.RollcallCardsUiTest
.\gradlew.bat lintDebug assembleRelease
```

测试报告：android/app/build/reports/tests/testDebugUnitTest/。渲染图：android/app/build/rollcall-ui/home-current-rollcalls.png。

## 设备确认

未连接真机。安装后需在实际课堂签到期间确认首页摘要与详情页切换。此次验证使用虚构数据，没有向学校提交签到。
