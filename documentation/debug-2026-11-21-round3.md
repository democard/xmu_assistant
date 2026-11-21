# 2026-11-21 第三轮调试与优化

> 后续发布：三轮维护统一汇总进入 [v1.8.6](releases/v1.8.6.md)，正式安装包与验证结果见 [v1.8.6 验证记录](verification-1.8.6.md)。下文的 1.8.5 / 46、未发布状态及 Debug 包校验值均保留为第三轮完成时的历史证据。

本轮接续[第一轮](debug-2026-11-21.md)与[第二轮](debug-2026-11-21-round2.md)，保留已有工作树改动，重点检查成绩刷新 / 分享和桌面文件导出的异常路径。源码仍基于 v1.8.5（`2e0738a`），本轮未发布新版本。

## 问题与修复

| 模块 | 已复现问题 | 处理 |
| --- | --- | --- |
| Android 成绩刷新 | 请求门已取得、loading 已设置后，客户端同步初始化抛错，异常逃出入口且刷新永久被门挡住 | 初始化纳入异常边界，恢复 loading / 请求门并显示错误 |
| Android 相册保存 | MediaStore 插入记录在原 try 之外，存储 / 权限异常直接逃出后台分享任务；图片未写完已可见 | 插入、写入、关闭流和发布共用失败边界；用 IS_PENDING 隐藏未完成图片，成功发布才返回 URI，失败清理已创建记录 |
| Android 长图分享 | 登出后仍拉起旧成绩分享面板；快速重复操作会反复生成大位图；系统分享 Activity 缺失时异常逃出协程 | 用 `score_share` 请求门做单次生成，任务结束统一释放；绑定会话世代及 Activity 生命周期；位图 `finally` 回收，系统面板失败转为可见提示；保留现有离线缓存导出 |
| Windows CSV / 日志导出 | 覆盖旧文件先截断，中途写入失败会丢失原内容 | 同目录临时文件完整写入、flush / fsync 后原子替换；失败保留旧文件并清理临时文件 |
| Windows CSV 文本 | 课程名等文本直接进入电子表格，公式前缀可能按公式解析 | 字符串单元格去掉前导空白后若以 `= + - @` 起始则加单引号中和；数值字段与原始记录不变 |

## 回归证据

- 第三轮开始重新执行 Python 基线：**825 passed，300 subtests passed**，46.49 秒。Ruff 与 `compileall` 通过；这不是最终桌面修复后的全量结果。
- 成绩初始化新增一项行为测试：原实现 **6 项中 1 项失败**；修复后 **6 项全部通过**。
- 相册新增六项：校正 Robolectric 一参数 `openOutputStream` 吞异常的兼容夹具后，原实现 **6 项中 3 项失败**，分别断言插入异常逃逸、未设置 pending、发布失败却返回 URI；修复后 **6 项全部通过**。
- 分享生命周期新增七项：原实现相关 **5 项中 3 项失败**，复现登出后仍启动分享面板、系统分享异常逃出协程、已有分享请求时仍重复渲染写入；修复后与成绩刷新、旧版兼容和长图渲染联合 **27 项全部通过**。另验证 Activity 结束、离线缓存导出、取消清门及连点只生成一个图。
- 桌面导出新增 **10 项测试 / 72 个子测试**：旧实现 **5 项全红**；定向覆盖导出写入 / 替换失败时保留旧文件，以及 CSV 公式前缀中和。修复后相关回归 **40 项通过、112 个子测试通过**，Ruff 通过。
- 第三轮 Python 全量：**835 passed，372 subtests passed**，39.43 秒；Ruff 与 `compileall` 均通过。
- Android 最终全量实际执行 **130 个测试套件、897 项测试，0 失败 / 错误 / 跳过**；成绩刷新 6、长图渲染 8、相册保存 6、分享生命周期 7 均实际执行并通过。
- Android Lint：**0 Error / Fatal、7 Warning、14 Information**，保留现有告警。`:app:assembleDebug` 成功，强制执行 **57 个任务**，耗时 **2 分 56 秒**。
- 本地汇总脚本在清理前校验 8 份文档共 **77 个本地链接**，无缺失；Android wrapper 行尾为 LF。Debug APK 在清理前生成，大小 17,566,988 字节，SHA-256：`c111afd6270927738a2e480a280623e0405a27e24401a74d3a422a6f392ce982`；日志、测试 XML、Lint 报告及 APK 均已随构建产物清理删除，复跑可重新生成。

相关新增测试使用本地 ContentProvider / ContentResolver、Robolectric 与真实 Qt 控件；没有实际访问校园服务或操作系统相册。测试夹具显式提供真实 PNG 输出流，并校正 Robolectric 的旧版流异常兼容行为，避免把夹具缺陷当产品问题。

## 验证边界

本轮开始时 Android 基线为 883 项；最终全量为 897 项。最后一次 Android 强制全量执行 57 个任务并成功；本地报告汇总确认 130 套件 / 897 项测试全通过，Lint 无错误，77 个文档本地链接有效。`diff --check` 通过。独立只读复核对成绩分享与相册改动未发现问题；桌面导出实现及测试已逐项复核。

本轮源码和本地 Debug 验证包均未发布，版本号仍为 1.8.5 / 46。真实校园账号、真机 MediaStore / 系统分享面板及设备厂商行为仍需设备环境验证；没有创建 Git commit、推送或修改发布校验值。

## 复跑

```powershell
python -m pytest tests -q --tb=short
python -m ruff check xmu-rollcall-cli tests scripts
python -m compileall -q xmu-rollcall-cli tests scripts
cd android
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --rerun-tasks --console=plain
```

Android 使用 JDK 17 与 SDK 35；依赖已完整缓存时可加 `--offline`。本轮已在 Android 目录强制运行全部任务（含 `--rerun-tasks`）；执行日志与测试 XML 位于本机 `build/` 和 Gradle 报告目录。
