@echo off
REM ===== exe 体积优化（2026-08-20 实测 51.5MB -> 33MB）=====
REM 推荐构建：pyinstaller xmu-assistant.spec（含 a.binaries 过滤删未用 Qt6 DLL）
REM 本 bat 的 --exclude-module 对 PySide6 hook 主动收集的 Qt6 DLL 无效，仅排除 Python 模块
REM 真正删除未用 Qt6 二进制靠 spec 的 a.binaries 过滤逻辑
REM =====
setlocal

set ENV_NAME=xmu-rollcall-dashboard
set "APP_NAME=xmu-assistant"

cd /d "%~dp0\.."

if exist "%CD%\.venv\Scripts\python.exe" (
  set PYTHON_EXE=%CD%\.venv\Scripts\python.exe
) else (
  set PYTHON_EXE=conda run -n %ENV_NAME% python
)

echo Building %APP_NAME%.exe with: %PYTHON_EXE%
echo Project root: %CD%

%PYTHON_EXE% -m PyInstaller ^
  --noconfirm ^
  --clean ^
  --windowed ^
  --onefile ^
  --name %APP_NAME% ^
  --icon "%CD%\assets\xmu-assistant.ico" ^
  --add-data "%CD%\assets\xmu-assistant.ico;assets" ^
  --add-data "%CD%\assets\xmu-assistant-logo.png;assets" ^
  --add-data "%CD%\assets\xmu-assistant-icon.png;assets" ^
  --add-data "%CD%\assets\xmu-assistant-mark.png;assets" ^
  --paths "%CD%\xmu-rollcall-cli" ^
  --collect-submodules xmu_rollcall ^
  --collect-submodules xmulogin ^
  --hidden-import win32crypt ^
  --exclude-module PySide6.QtPrintSupport ^
  --exclude-module PySide6.QtSql ^
  --exclude-module PySide6.QtNetwork ^
  --exclude-module PySide6.QtTest ^
  --exclude-module PySide6.QtConcurrent ^
  --exclude-module PySide6.QtDBus ^
  --exclude-module PySide6.QtDesigner ^
  --exclude-module PySide6.QtXml ^
  --exclude-module PySide6.QtHelp ^
  --exclude-module PySide6.QtMultimedia ^
  --exclude-module PySide6.QtMultimediaWidgets ^
  --exclude-module PySide6.QtOpenGL ^
  --exclude-module PySide6.QtOpenGLWidgets ^
  --exclude-module PySide6.QtPdf ^
  --exclude-module PySide6.QtPdfWidgets ^
  --exclude-module PySide6.QtPositioning ^
  --exclude-module PySide6.QtLocation ^
  --exclude-module PySide6.QtNetworkAuth ^
  --exclude-module PySide6.QtNfc ^
  --exclude-module PySide6.QtQml ^
  --exclude-module PySide6.QtQuick ^
  --exclude-module PySide6.QtQuick3D ^
  --exclude-module PySide6.QtQuickControls2 ^
  --exclude-module PySide6.QtQuickWidgets ^
  --exclude-module PySide6.QtRemoteObjects ^
  --exclude-module PySide6.QtScxml ^
  --exclude-module PySide6.QtSensors ^
  --exclude-module PySide6.QtSerialPort ^
  --exclude-module PySide6.QtSerialBus ^
  --exclude-module PySide6.QtStateMachine ^
  --exclude-module PySide6.QtTextToSpeech ^
  --exclude-module PySide6.QtCharts ^
  --exclude-module PySide6.QtSpatialAudio ^
  --exclude-module PySide6.QtSvg ^
  --exclude-module PySide6.QtSvgWidgets ^
  --exclude-module PySide6.QtDataVisualization ^
  --exclude-module PySide6.QtGraphs ^
  --exclude-module PySide6.QtGraphsWidgets ^
  --exclude-module PySide6.QtBluetooth ^
  --exclude-module PySide6.QtUiTools ^
  --exclude-module PySide6.QtAxContainer ^
  --exclude-module PySide6.QtWebChannel ^
  --exclude-module PySide6.QtWebEngineCore ^
  --exclude-module PySide6.QtWebEngineWidgets ^
  --exclude-module PySide6.QtWebEngineQuick ^
  --exclude-module PySide6.QtWebSockets ^
  --exclude-module PySide6.QtHttpServer ^
  --exclude-module PySide6.QtWebView ^
  --exclude-module PySide6.Qt3DCore ^
  --exclude-module PySide6.Qt3DRender ^
  --exclude-module PySide6.Qt3DInput ^
  --exclude-module PySide6.Qt3DLogic ^
  --exclude-module PySide6.Qt3DAnimation ^
  --exclude-module PySide6.Qt3DExtras ^
  --exclude-module PySide6.QtCanvasPainter ^
  --exclude-module PySide6.QtQuickTest ^
  --exclude-module pytest ^
  --exclude-module unittest ^
  --exclude-module tkinter ^
  "%CD%\scripts\xmu_dashboard_launcher.py"

if errorlevel 1 (
  echo Build failed.
  exit /b 1
)

echo.
echo Build complete:
echo %CD%\dist\%APP_NAME%.exe
