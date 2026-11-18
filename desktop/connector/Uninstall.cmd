@echo off
chcp 65001 >nul
if not exist "%~dp0connector-control.ps1" (
  echo Run this shortcut from the installed connector folder.
  pause
  exit /b 1
)
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0connector-control.ps1" -Action Uninstall
if errorlevel 1 (
  pause
  exit /b 1
)
pause
del "%~f0" & exit /b 0
