# 1.8.4 打包与验证记录

日期：2026-10-24。Windows 1.8.4；Android 1.8.4 / versionCode 45。

[安装包与更新说明](releases/v1.8.4.md) · [源码修复记录](repair8-2026-10-24.md)

## 自动化回归

| 检查 | 结果 |
| --- | --- |
| Python 完整回归 | 800 项测试、294 个子测试通过 |
| Android Debug 完整回归 | 822 项测试通过，0 失败 / 错误 / 跳过 |
| 最终 Android 邮件定向 | 19 项通过，覆盖隐式 TLS、STARTTLS 和证书身份对照 |
| Ruff 与 Python 编译检查 | 通过 |
| Android Lint | 0 错误、6 警告、13 条提示 |

完整回归对应本轮修复后的功能源码；最终邮件定向验证对应最后调整的 SMTP 夹具，不与全量计数相加。发布版本号调整后，安装包另核对其内嵌版本与资源。

回归日志保存在本地 `build/repair8-20261024/` 及 `build/maintenance4-repair8*`。构建与包检查日志保存于 `build/release-1.8.4/`。

## 安装包

### Android

- 标准 R8 `assembleRelease` 与 `lintVitalRelease` 成功。
- 包名 `com.xmu.assistant`，versionName `1.8.4`，versionCode `45`，minSdk `26`，targetSdk `35`。
- APK Signature Scheme v2 验证、`zipalign` 对齐检查通过；证书与既有同签名安装包一致。
- 产物 `xmu-assistant-release.apk`，4,048,578 字节。
- 本地证据：`build/release-1.8.4/android-package.md`、`build/maintenance4-release184-android.log`。

### Windows

- 使用既有 PyInstaller spec、Python 3.14.6 与 PyInstaller 6.22.2，独立工作目录完成 onefile EXE 构建。
- 从归档模块确认 `xmu_rollcall.__version__ == "1.8.4"`；Qt 的 `qwindows` / `qico` 插件、ICO / PNG 图标、坐标预置数据与 `win32crypt` 均已包含。
- 最终 EXE 在独立空白配置下实际启动，首页与品牌图标正常显示。
- 产物 `xmu-assistant.exe`，36,539,508 字节。
- 本地证据：`build/release-1.8.4/windows-package.md`、`build.log`、`archive-list.txt`、`ui-home.png`。

## 发布文件

- `xmu-assistant.exe`
- `xmu-assistant-release.apk`
- `SHA256SUMS.txt`

源码与校验文件提交到 Git，安装包通过 [GitHub Release v1.8.4](https://github.com/democard/xmu_assistant/releases/tag/v1.8.4) 分发。
