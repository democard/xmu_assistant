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
  set PYTHON_EXE=%CD%\.venv\Scripts\python.exe
) else (
  where conda >nul 2>nul
  if errorlevel 1 goto missing_interpreter
  set PYTHON_EXE=conda run -n xmu-rollcall-dashboard python
)

echo Building %APP_NAME%.exe with: %PYTHON_EXE%
echo Project root: %CD%

%PYTHON_EXE% -m PyInstaller --noconfirm --clean "%CD%\xmu-assistant.spec"

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
echo   - PATH 中也没有 conda（回退方案依赖 conda 环境 xmu-rollcall-dashboard）
echo 请先完成环境准备：参见 README「快速开始」-「从源码运行（二次开发）」的环境准备小节，
echo   或手动创建 .venv 后重跑本脚本。
exit /b 1
