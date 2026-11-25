@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
if not exist "%~dp0tools\brush\brush_app.exe" (
    echo 找不到 Brush 查看器，请把这个提示告诉我。
    pause
    exit /b 1
)
if not exist "%~dp0runs\furniture-470cc7e52c525dbe\model.ply" (
    echo 找不到椅子模型，请把这个提示告诉我。
    pause
    exit /b 1
)
"%~dp0tools\brush\brush_app.exe" "%~dp0runs\furniture-470cc7e52c525dbe\model.ply" --with-viewer
if errorlevel 1 pause
