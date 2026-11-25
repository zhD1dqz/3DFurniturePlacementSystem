@echo off
chcp 65001 >nul
cd /d "%~dp0"
if exist ".venv\Scripts\python.exe" (
  ".venv\Scripts\python.exe" -u desktop-server\server.py --open %*
  goto done
)
where py >nul 2>nul
if not errorlevel 1 (
  py -3 -u desktop-server\server.py --open %*
  goto done
)
where python >nul 2>nul
if not errorlevel 1 (
  python -u desktop-server\server.py --open %*
  goto done
)
echo 未找到 Python，请先按照 README.md 创建 .venv 并安装依赖。
:done
pause
