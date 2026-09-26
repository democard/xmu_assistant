@echo off
REM ===== exe 构建（统一走 xmu-assistant.spec，勿在此重复参数）=====
REM 2026-08-27：本脚本曾自带一份无二进制过滤的参数列表，用它构建的 exe
REM 比 spec 版本大 ~5.4MB（38.4 vs 33MB，Qt6 未用 DLL/翻译资源全数打入）。
REM 瘦身逻辑（a.binaries 过滤/翻译裁剪/assets 精简）只维护在 spec 一处。
REM 体积记录：51.5MB（无优化）→ 33MB（spec 过滤）→ ~31MB（+翻译/assets 裁剪）
REM =====
setlocal

set "APP_NAME=xmu-assistant"

cd /d "%~dp0\.."

if exist "%CD%\.venv\Scripts\python.exe" (
  set PYTHON_EXE="%CD%\.venv\Scripts\python.exe"
  goto build
)

REM 支持 README 中的普通 Python 安装；只选择具备打包工具的解释器。
python -c "import sys, PyInstaller; assert sys.version_info >= (3, 11)" >nul 2>nul
if not errorlevel 1 (
  set "PYTHON_EXE=python"
  goto build
)
py -3 -c "import sys, PyInstaller; assert sys.version_info >= (3, 11)" >nul 2>nul
if not errorlevel 1 (
  set "PYTHON_EXE=py -3"
  goto build
)
where conda >nul 2>nul
if errorlevel 1 goto missing_interpreter
set "PYTHON_EXE=conda run -n xmu-rollcall-dashboard python"

:build
echo Building %APP_NAME%.exe with: %PYTHON_EXE%
echo Project root: %CD%

REM call 也兼容作为批处理入口的 conda；否则不会返回检查构建退出码。
call %PYTHON_EXE% -m PyInstaller --noconfirm --clean "%CD%\xmu-assistant.spec"

if errorlevel 1 (
  echo Build failed.
  exit /b 1
)

echo.
echo Build complete:
echo %CD%\dist\%APP_NAME%.exe
exit /b 0

:missing_interpreter
echo [错误] 未找到可用的 Python 解释器，无法打包：
echo   - 项目根不存在 .venv\Scripts\python.exe
echo   - python / py -3 中没有同时可用的 Python 3.11+ 和 PyInstaller
echo   - PATH 中也没有 conda（回退方案依赖 conda 环境 xmu-rollcall-dashboard）
echo 请先完成 README「开发与验证」的环境准备，并在所选 Python 中安装 PyInstaller。
exit /b 1
