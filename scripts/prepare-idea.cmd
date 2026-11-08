@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0prepare-idea.ps1" %*
exit /b %ERRORLEVEL%
