# 本次全班签到比例：代码对比与离线模拟

日期：2026-09-20。项目基线：`f71e9a9`（v1.6.9）。
本轮按用户确认仅研究、模拟，不接入界面，不修改实际签到流程或安装包。

## 是否都源自同一个 xmurollcall

现有证据不支持三者同源：

- 我们 README 第 31 行明确致谢 [KrsMt-0113/XMU-Rollcall-Bot](https://github.com/KrsMt-0113/XMU-Rollcall-Bot) 的签到处理思路；该项目发布包名为 `xmu-rollcall-cli`，MIT 许可，与用户说的 xmurollcall 最吻合。当前文件相关历史可追溯至 `fb5b267` 的源码快照，但不能把该快照当作项目绝对起源。
- [hot-YUser](https://github.com/hot-YUser/auto-rollcall-thu-tronclass) 的 GitHub parent/source 和 README Credits 明确指向 `silvercow002/tronclass-script`，并非上述 KrsMt 项目。
- [vintcessun](https://github.com/vintcessun/xmu_assistant_sign_bot) 的 GitHub 元数据为非 Fork；所查 README 致谢和 Cargo 依赖未声明源自 KrsMt。未找到声明不等于能证明从未借鉴，只能说不能据此确认同源。
- 相同 URL、字段名源于同一个 TronClass 后端协议，不能证明代码来自同一个项目。直接复用本仓库现有读取器的决定不依赖这种推断。

## 对比结论

| 项目 | 统计含义 | 数据口径 | 能否直接作为本项目实现 |
| --- | --- | --- | --- |
| 本项目 PC `course_rollcall_stats` | 本人跨多次点名的历史出勤率 | 本人的课程历史记录 | 不能代替全班本次比例 |
| 本项目 Android | 尚无全班比例 | 实时列表 + 最近历史本人核实 | 需要新增纯统计层 |
| vintcessun Rust 项目 | 本次点名全班人数 | `student_rollcalls` 行数，`status=on_call` 已签 | 不能直接粘贴 Rust 实现到双端 |
| hot-YUser Python 项目 | 本次人数、比例及本人核实 | `rollcall_status` 等字段，精确 `on_call_fine` 已签 | 不能仅凭相同接口认定代码相同 |

### 已确认可复用的本项目代码

- PC `desktop_qt/core.py::fetch_student_rollcall_detail`：现成明细 GET，带超时、会话过期识别、错误返回；本轮用模拟 session 直接调用，确认同一读取器能提供两类统计数据，不另写请求层。
- Android `RollcallHistoryClient` 已有同一明细的只读请求和 `QueryHttpTransport` 测试接口；当前解析方法私有。`RollcallEngine.answerNumber` 也读取同一明细，但走另一条网络路径。未来如接入应统一读取，避免重复 GET。
- 单纯统计人数不需要参考项目额外的 answers 请求。

### 不应直接照搬的差异

1. `on_call` 与 `on_call_fine` 是两份源码实际不同的值。我们的历史分类器不能明确识别前者；新增统计不能复用界面“未知显示已签”的显示回退。此次没有修改历史判定。
2. 全员已签不能自动推导本人已签：返回列表可能没有匹配当前账号。统计原型只在身份明确匹配时给出本人结论。
3. 不完整名单、空名单、无权限或读取失败不能显示为确定的全班 0%。模拟的 `roster_complete=True` 只是测试完整名单的假设，不能证明厦大当前接口确实返回全班完整名单。
4. 重复记录、非对象项、冲突状态需要保留不确定性；不能把任何列表长度都直接称为应到人数。
5. 两参考项目都有固定 15% 判断，但 Rust 源码先做整数除法，可能向下取整；例如 7 人班 1 人只有约 14.29%。本轮仅验证比例算术，未实现自动提交门槛。
6. 本项目 README 声称的“应答门槛、手动确认”与当前执行链路有差异：PC 配置中有占位，未消费；Android 自动流程只看两个类型开关。该项记录待后续确认，本轮没有扩展修改范围。
7. 实时事件有去重机制，不能假设同一事件的变化会反复派发。未来接入人数展示需单独考虑刷新、账号切换和旧请求过期；本轮纯函数不保存快照。

## 模拟与验收

```powershell
D:/python/python.exe scripts/simulate_attendance_progress.py
D:/python/python.exe -m unittest discover -s tests -p test_attendance_progress_simulation.py -v
D:/python/python.exe -m unittest discover -s tests -p test_rollcall_verify.py -q
```

- 18 项模拟测试通过，包含两份公开字段结构、5/40、6/40、7/40、小班、空/缺失名单、未知/冲突状态、重复人员、部分名单、本人身份匹配、无快照串号以及复用现有明细 GET 的模拟响应。
- 一个统计不变量测试遍历 1–24 人班级的所有已签人数，共 324 种组合。
- 现有 PC 签到核实回归 24 项通过。
- 脚本输出 8 个可读示例；不导入实际提交逻辑，不读账号、Cookie、缓存或个人数据。GET 复用测试使用 Mock，并拦截真实 requests 网络调用。
- 未运行第三方程序，未提交真实签到；未做学校接口实测或 Android 新功能验收。这些结果只证明离线统计及 PC 读取器接入可行。

## 参考与许可

本项目 LICENSE 为 Apache-2.0；两个参考仓库固定版本的 LICENSE 均为 AGPL-3.0。本轮依据字段含义独立编写模拟代码，没有复制、翻译第三方函数或引入第三方依赖。后续优先复用本仓库已有读取器。

- [vintcessun 人数统计](https://github.com/vintcessun/xmu_assistant_sign_bot/blob/a9b0c2c1162f5a4fe503c6d76186a0b201e2d4c8/src/logic/rollcall/sign_data.rs)
- [vintcessun 字段类型](https://github.com/vintcessun/xmu_assistant_sign_bot/blob/a9b0c2c1162f5a4fe503c6d76186a0b201e2d4c8/src/api/xmu_service/lnt/student_rollcalls.rs)
- [vintcessun 比例判断](https://github.com/vintcessun/xmu_assistant_sign_bot/blob/a9b0c2c1162f5a4fe503c6d76186a0b201e2d4c8/src/logic/rollcall/time_sign.rs)
- [hot-YUser 统计模块](https://github.com/hot-YUser/auto-rollcall-thu-tronclass/blob/8eb463382a40526e3e491440e2d33a1123b8240d/troTHU/rollcall_progress.py)

Git 工作分支：`feature/attendance-progress`。测试代码与本对比文档一并版本管理；尚未合并或发布。
