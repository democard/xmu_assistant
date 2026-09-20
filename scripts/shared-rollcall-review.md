# 共有功能对照与修复交接

日期：2026-09-20；基线 `d3f73d4`；工作分支 `feature/attendance-progress`。

范围：对照原项目已有的会话、签到读取、状态判定和提醒行为，修复本项目确定的问题。没有增加签到类型、接入人数 UI 或门槛，也没有改变原有界面布局。本轮没有打包或发布。

## 对照结果与处理

| 共有功能 | 核对结果 | 本轮处理 |
| --- | --- | --- |
| 登录态保存、恢复 | KrsMt 使用本地 Cookie；我们已有加密、原子落盘和取消登录保护 | 保留现有存储机制，补齐 PC 恢复失败事件的登录代数，旧请求不得释放新登录的门或改写 UI |
| 本人签到状态 | vintcessun 明细模型使用 `on_call` / `absent`；另一参考使用 `on_call_fine` | PC、Android 历史与进行中状态明确识别这两个已签值，避免把正常已签归为原始未知 |
| 已签与缺勤区分 | 原项目按明确状态计数，更新时间不是签到成功证据 | 删除 PC 两处时间戳优先捷径；`absent + updated_at` 仍为未签，仅时间戳不产生本人已签结论 |
| 会话失效 | 原项目也通过账号 profile 校验登录；资源无权限不能与整个会话失效混用 | Android 历史请求的 profile 403 触发原有会话失效处理，课程/明细 403 保持局部失败 |
| 通知结果 | 核查现有通知发现两端都接受 HTTP 200 的异常页面或缺失业务码 | 按 PushPlus 官方响应契约，必须有明确业务码 200 才确认请求受理；不自动重发，避免重复通知 |
| 说明与实际行为 | README 声称支持人数门槛、手动确认，但当前执行链未接入 | 修正功能表与说明，保留现有轮询配置和类型开关 |

用户原先选择的 Android 历史“未知在界面显示已签”回退保持不变；它只影响显示，不参与统计或本人状态确认。此次显式识别正常 `on_call` 后，对应记录将直接保存为已签。

## 代码与验收入口

- PC `desktop_qt/core.py`：`classify_rollcall_status`、`infer_signed_status`、`verify_own_status`。
- PC `desktop_qt/app.py` / `events.py`：所有 `restore_failed` 带 `login_epoch`，处理器先判定代数，保留旧事件格式兼容。
- Android `RollcallHistoryClient.kt` / `RollcallModels.kt`：状态词、按端点区分 403。
- PC `notifications.py` / Android `NotificationSenders.kt`：异常回执不再默认为成功。
- 回归覆盖：状态附带三个时间戳、只有时间没有明确状态、精确状态词、旧恢复任务迟到、profile/course/detail 403、异常通知回执与正常数字/字符串业务码。

PC 完整 unittest：411 项通过。Android 完整单元测试：543 项通过，0 失败、0 错误、0 跳过；Lint：0 错误、6 条已有警告。两端测试均未新增失败。

测试通过模拟响应、本地 MockWebServer 和假的会话对象完成；没有读取个人凭据、请求学校服务、发送真实通知或提交签到。PushPlus 的 `code=200` 仅说明服务受理请求，最终送达依赖渠道，本轮未新增送达查询。

## 参考证据

- [KrsMt 会话保存与校验](https://github.com/KrsMt-0113/XMU-Rollcall-Bot/blob/c7de02b11da3433a047bab29dbbbbbb4c855a484/xmu-rollcall-cli/xmu_rollcall/utils.py)
- [vintcessun 个人状态定义](https://github.com/vintcessun/xmu_assistant_sign_bot/blob/a9b0c2c1162f5a4fe503c6d76186a0b201e2d4c8/src/api/xmu_service/lnt/student_rollcalls.rs)
- [hot-YUser 状态统计](https://github.com/hot-YUser/auto-rollcall-thu-tronclass/blob/8eb463382a40526e3e491440e2d33a1123b8240d/troTHU/rollcall_progress.py)
- [PushPlus 官方响应说明](https://www.pushplus.plus/doc/guide/api.html)

原有保护（账号隔离、Cookie 加密、请求超时、监控去重和停止信号）继续使用，不以原项目较简单的实现替换。第三方文件未被复制到本项目。
