@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
if exist "D:\Python\python.exe" (
    "D:\Python\python.exe" "%~dp0workflow_desktop.py"
) else (
    python "%~dp0workflow_desktop.py"
)
if errorlevel 1 pause
