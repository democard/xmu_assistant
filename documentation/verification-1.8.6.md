# 1.8.6 发布验证记录

**验证日期：2026-11-21。** Windows 1.8.6；Android 1.8.6 / versionCode 47。源基线为 v1.8.5（`2e0738a`）；本次发布 tag：[v1.8.6](https://github.com/democard/xmu_assistant/releases/tag/v1.8.6)，提交哈希以 tag 指向的 GitHub commit 为准。

[发布说明](releases/v1.8.6.md) · [项目首页](../README.md) · [第一轮维护](debug-2026-11-21.md) · [第二轮维护](debug-2026-11-21-round2.md) · [第三轮维护](debug-2026-11-21-round3.md)

## 自动化与静态检查

| 检查 | 本次实测结果 |
| --- | --- |
| Python 全量测试 | `python -m pytest tests -q --tb=short`：**835 passed，372 subtests passed**，60.55 秒 |
| Ruff | `python -m ruff check xmu-rollcall-cli tests scripts`：通过 |
| Python `compileall` | `python -m compileall -q xmu-rollcall-cli tests scripts`：通过 |
| Android `:app:testDebugUnitTest` | **130 个测试套件、897 项通过，0 失败 / 0 错误 / 0 跳过** |
| Android Debug / Release Lint | 两个变体均 **0 Error / Fatal**；报告共 **7 Warning、14 Information** |
| Android Release 构建 | Release APK 构建成功；`zipalign` 检查通过 |
| `git diff --check` 与本次新增发布文档链接 | `git diff --check` 通过；新建的 v1.8.6 发布说明 / 验证记录及入口链接目标均存在。未做全仓历史链接审计 |

## 安装包核验

| 检查 | 本次实测结果 |
| --- | --- |
| Windows EXE | 构建完成；内部 `xmu_rollcall.__version__` 为 `1.8.6`，确认包含 `desktop_qt.exporting`。文件大小 **36,500,626 字节**；启动冒烟按保护规则跳过：检测到已有用户自启动注册，为保留原设置未启动 GUI 验收 |
| Android APK | Release APK `xmu-assistant-release.apk`，**4,081,346 字节**；包名 `com.xmu.assistant`，versionName `1.8.6`，versionCode `47`，minSdk `26` / targetSdk `35` |
| Android 签名与升级兼容 | 证书 SHA-256：`f10efd770965d6ba76aefe6ab5c7b3caf6d6ba474cf3fdc453040cdce1abb8e8`；与既有公开固定调试签名相同，可覆盖同签名旧版 |
| SHA-256 | EXE：`bd6e98675c0d3080b18eaa3cefc7e619c2edc957595b423370da676b5cee82e7`；APK：`eb7009cfd019ee5d3a6b5c51bf022e219516a447b04b7e91e80698954122e9b7` |
| GitHub Release 资产 | 发布页：[v1.8.6](https://github.com/democard/xmu_assistant/releases/tag/v1.8.6)。附件为上述 EXE、APK 与 `SHA256SUMS.txt`；发布流程会回取远端附件并与本地 SHA-256 核对后确认发布 |

SHA-256 值对应主代理本次构建的本地 1.8.6 产物，并写入 `release/SHA256SUMS.txt`。发布流程在发布前下载回取附件并比对校验值。历史 Debug APK、1.8.5 校验值及三轮维护报告里的测试数字均不替代本次 Release 产物证据。

## 验证边界

自动化测试中的 HTTP、ContentProvider 和文件系统行为以本地假传输、模拟 Provider、Robolectric、Compose / Qt 控件为主。没有证据表明本次执行过真实校园账号或真实校园服务端到端操作，也没有真机 MediaStore / 系统分享面板或设备厂商后台策略验收。Android APK 延用既有公开固定调试密钥签名；它保持同签名覆盖安装兼容，不证明发布者身份。
