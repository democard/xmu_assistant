# 虾大助手 Windows 源码

本目录包含 `xmu_rollcall` Python 包：PySide6 桌面界面、登录与会话、学习记录、课件下载和通知逻辑。当前公开版本 **1.8.4**。虽然目录沿用 `xmu-rollcall-cli` 名称，当前应用入口是桌面界面。

[直接下载 EXE](https://github.com/democard/xmu_assistant/releases/download/v1.8.4/xmu-assistant.exe) · [发布说明](https://github.com/democard/xmu_assistant/releases/tag/v1.8.4) · [项目首页](../README.md) · [测试报告](../TEST_REPORT.md) · [开发文档](../documentation/README.md)

## 从源码运行

需要 Windows 和 Python 3.11 或以上。以下 PowerShell 命令均从**仓库根目录**执行，使用独立虚拟环境，无需激活脚本或安装 Conda：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r .\xmu-rollcall-cli\requirements.txt
$env:PYTHONPATH = (Resolve-Path .\xmu-rollcall-cli).Path
.\.venv\Scripts\python.exe -m xmu_rollcall.desktop
```

显式设置 `PYTHONPATH` 可确保运行当前仓库源码，避免误用其他环境中安装的旧副本。也可以执行 `.\.venv\Scripts\python.exe -m pip install -e .\xmu-rollcall-cli` 安装可编辑包，安装后通过 `.\.venv\Scripts\xmu-dashboard.exe` 启动。

## 本地配置

默认配置目录为当前 Windows 用户主目录下的 `.xmu_rollcall`；不可写时依次尝试 `%LOCALAPPDATA%\xmu_rollcall`、`%APPDATA%\xmu_rollcall`。没有可写位置时提示设置独立目录，不向程序启动目录或系统目录写入。`config.json` 保存账号与策略，账号对应的 JSON 文件保存 Cookie。

需要隔离测试配置时，在启动前显式设置目录。例如以下配置只影响当前 PowerShell 会话：

```powershell
$env:XMU_ROLLCALL_CONFIG_DIR = Join-Path $env:LOCALAPPDATA 'xmu-assistant-dev'
```

Windows 下密码、Cookie、SMTP 授权码和 PushPlus Token 使用当前用户的 DPAPI 加密。加密模块缺失或加密失败时，当前实现会告警并回退明文；学号、策略、业务缓存及导出文件也不能视为全部加密。不要上传整个配置目录；隔离测试时请使用虚构数据。

## 下载与邮件

取消课件下载后，已完成文件保留，可以重新下载。同一资源且强 ETag 匹配时才续传；资源版本改变或没有可靠验证器时从头下载。旧版无归属的 `.part` 不自动认领，原文件保留，新下载使用编号名；新断点的 `.resume.json` 是归属/版本元数据，请与断点一起保留或清理。附件最终签名地址刷新不改变业务归属。文件名长度裁剪会保留有效扩展名。

邮件在连接、TLS 或认证失败且尚未提交时仍可尝试备用端口；提交后回执不明会提示检查收件箱，不再自动重投。已确认受理后的连接关闭异常不触发重发。桌面手动应答不依赖监控开启，暂停监控只取消自动等待任务。

更新详情见 [1.8.4 发布说明](../documentation/releases/v1.8.4.md)，修复依据见 [八项修复记录](../documentation/repair8-2026-10-24.md)。

## 测试

安装应用依赖后，在仓库根目录执行：

```powershell
.\.venv\Scripts\python.exe -m pip install -e './xmu-rollcall-cli[dev]'
$env:PYTHONPATH = (Resolve-Path .\xmu-rollcall-cli).Path
$env:QT_QPA_PLATFORM = 'offscreen'
.\.venv\Scripts\python.exe -m pytest tests -q
.\.venv\Scripts\python.exe -m ruff check xmu-rollcall-cli tests scripts
.\.venv\Scripts\python.exe -m compileall -q xmu-rollcall-cli tests scripts
```

`offscreen` 供无界面的 Qt 测试使用；同一终端恢复正常启动应用前，执行 `Remove-Item Env:QT_QPA_PLATFORM`。

仓库 `ruff.toml` 固定致命语法、名称、控制流及未用局部变量检查；`pytest.ini` 固定源码路径，避免导入全局旧包。pytest 9 原生支持子测试，无需另外安装 `pytest-subtests`。独立运行的旧测试仍保留原有路径入口。

要复用本轮 Windows / Python 3.14.6 的依赖版本，在空白虚拟环境中运行 `python -m pip install -r requirements-ci.txt`。它按 `constraints-windows-py314.txt` 固定应用与测试依赖；打包工具也有对应约束，可用 `python -m pip install -c constraints-windows-py314.txt -e './xmu-rollcall-cli[dev,build]'` 安装。其他 Python 版本仍使用包的原有依赖下界；约束文件不承诺安装包字节级一致。

GitHub Actions 分别运行 Windows 测试 / 静态检查和 Android 测试 / Lint，并保存报告。[查看工作流](https://github.com/democard/xmu_assistant/actions/workflows/verify.yml)。最新源码修复与本地验证见 [八项修复记录](../documentation/repair8-2026-10-24.md)。

1.8.4 的 Python 全量 800 项测试、294 个子测试通过，Ruff 和编译检查通过。安装包检查见 [1.8.4 验证记录](../documentation/verification-1.8.4.md)。

## 打包 EXE

在仓库根目录执行，产物为 `dist/xmu-assistant.exe`：

```powershell
.\.venv\Scripts\python.exe -m pip install pyinstaller
.\.venv\Scripts\python.exe -m PyInstaller --noconfirm --clean .\xmu-assistant.spec
```

也可运行 `scripts/build_dashboard_exe.bat`。该脚本优先使用根目录 `.venv`，否则尝试名为 `xmu-rollcall-dashboard` 的 Conda 环境。构建配置统一维护在 [xmu-assistant.spec](../xmu-assistant.spec)，包含图标、品牌资源及 DPAPI 模块，并裁剪未用的 Qt 组件。

构建后应实际检查 EXE 启动、版本、资源及凭据加密模块，再更新发布校验值。EXE 通过 GitHub Releases 分发，不提交二进制或本地运行数据。

## 代码定位

| 位置 | 职责 |
| --- | --- |
| `xmu_rollcall/desktop.py` | 桌面启动入口 |
| `xmu_rollcall/desktop_qt/` | PySide6 页面、后台任务、事件处理、托盘与主题 |
| `xmu_rollcall/config.py`、`secrets.py` | 配置与凭据保存 |
| `xmu_rollcall/engine.py`、`verify.py` | 签到轮询与处理 |
| `xmu_rollcall/desktop_qt/core.py` | 桌面数据模型与 LNT 查询 |
| `xmu_rollcall/assets/` | 安装包内图标资源，供仓库外运行时回退加载 |
| `../tests/` | 回归测试 |

包元数据与依赖见 [pyproject.toml](pyproject.toml)。项目采用 [Apache-2.0 许可证](../LICENSE)。
