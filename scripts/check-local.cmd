@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-local.ps1" %*
exit /b %ERRORLEVEL%
