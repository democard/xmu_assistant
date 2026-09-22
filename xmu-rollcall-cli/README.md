# xmu助手 Windows 源码

本目录包含 `xmu_rollcall` Python 包：PySide6 桌面界面、登录与会话、签到监控、课件下载和通知逻辑。当前版本 **1.7.3**。虽然目录沿用 `xmu-rollcall-cli` 名称，当前应用入口是桌面界面。

[直接下载 EXE](https://github.com/democard/xmu_assistant/releases/download/v1.7.3/xmu-assistant.exe) · [项目首页](../README.md) · [测试报告](../TEST_REPORT.md)

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

默认配置目录为当前 Windows 用户主目录下的 `.xmu_rollcall`；主目录无法创建时会尝试当前工作目录。`config.json` 保存账号与策略，账号对应的 JSON 文件保存 Cookie。

需要隔离测试配置时，在启动前显式设置目录。例如以下配置只影响当前 PowerShell 会话：

```powershell
$env:XMU_ROLLCALL_CONFIG_DIR = Join-Path $env:LOCALAPPDATA 'xmu-assistant-dev'
```

Windows 下密码、Cookie、SMTP 授权码和 PushPlus Token 使用当前用户的 DPAPI 加密。加密模块缺失或加密失败时，当前实现会告警并回退明文；学号、策略、业务缓存及导出文件也不能视为全部加密。不要上传整个配置目录；隔离测试时请使用虚构数据。

## 测试

安装应用依赖后，在仓库根目录执行：

```powershell
.\.venv\Scripts\python.exe -m pip install pytest pytest-subtests ruff
$env:PYTHONPATH = (Resolve-Path .\xmu-rollcall-cli).Path
$env:QT_QPA_PLATFORM = 'offscreen'
.\.venv\Scripts\python.exe -m pytest tests -q
.\.venv\Scripts\python.exe -m ruff check xmu-rollcall-cli tests scripts --select E9,F63,F7,F82
.\.venv\Scripts\python.exe -m compileall -q xmu-rollcall-cli tests scripts
```

`offscreen` 供无界面的 Qt 测试使用；同一终端恢复正常启动应用前，执行 `Remove-Item Env:QT_QPA_PLATFORM`。

v1.7.3 发布前完成 481 项测试、144 个子测试，以及上述静态与编译检查。EXE 已验证启动、首页和图标；真实校园功能、真实通知和所有页面的人工检查不在本次验收范围。具体回归场景见[测试报告](../TEST_REPORT.md)。

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
