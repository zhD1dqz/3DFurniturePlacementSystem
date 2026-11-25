@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
if exist "D:\Python\python.exe" (
    "D:\Python\python.exe" -u "%~dp0start_reconstruction.py"
) else (
    python -u "%~dp0start_reconstruction.py"
)
pause
