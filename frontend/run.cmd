@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

if not exist "node_modules\" (
    echo   Installing dependencies, this may take a few minutes...
    call npm install --no-fund --no-audit
)

echo.
echo   SelfFace - frontend
echo   Web   : http://localhost:5273
echo.
echo   Press Ctrl+C to stop.
echo.

call npm run dev

endlocal
