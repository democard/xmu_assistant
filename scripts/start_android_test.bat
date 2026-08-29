@echo off
setlocal
for %%I in ("%~dp0..") do set "ROOT=%%~fI"
set "PS1=%ROOT%\scripts\start_android_test.ps1"

if not exist "%PS1%" (
  echo Missing launcher:
  echo %PS1%
  pause
  exit /b 1
)

"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%PS1%"
pause
