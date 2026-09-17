@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

REM ============================================================
REM  SelfFace - backend launcher (Windows)
REM
REM  Private settings (DB password, JWT secret ...) are NOT stored
REM  in this file. They are read from backend\.env.local, which is
REM  listed in .gitignore and therefore never committed.
REM
REM  First run:  copy .env.local.example  ->  .env.local
REM              then fill in DB_PASSWORD
REM ============================================================

REM ---- 1) load .env.local (key=value lines, '#' starts a comment) ----
if exist "%~dp0.env.local" (
    for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%~dp0.env.local") do (
        if not "%%~a"=="" set "%%~a=%%~b"
    )
)

REM ---- 2) optional untracked overrides, highest priority ----
if exist "%~dp0run.local.cmd" call "%~dp0run.local.cmd"

REM ---- 3) defaults ----
if not defined DB_HOST set "DB_HOST=localhost"
if not defined DB_PORT set "DB_PORT=3306"
if not defined DB_NAME set "DB_NAME=selfface"
if not defined DB_USERNAME set "DB_USERNAME=root"

REM ---- 4) port is pinned on purpose ----
REM  Some terminals inject SERVER_PORT into child processes, and Spring
REM  Boot would then grab that port instead of 8081. Setting it here
REM  explicitly overrides any inherited value.
set "SERVER_PORT=8081"

REM ---- 5) fail fast with a readable message ----
if not defined DB_PASSWORD (
    echo.
    echo   [ERROR] DB_PASSWORD is not set.
    echo.
    echo   Fix: copy backend\.env.local.example to backend\.env.local
    echo        and put your MySQL password in it:
    echo.
    echo          DB_PASSWORD=your_password
    echo.
    pause
    exit /b 1
)

REM ---- 6) locate maven ----
set "MVN_CMD=mvn.cmd"
if exist "D:\apache-maven-3.9.6\apache-maven-3.9.16\bin\mvn.cmd" (
    set "MVN_CMD=D:\apache-maven-3.9.6\apache-maven-3.9.16\bin\mvn.cmd"
)

echo.
echo   SelfFace - backend
echo   API   : http://localhost:8081/api/health
echo   DB    : %DB_USERNAME%@%DB_HOST%:%DB_PORT%/%DB_NAME%
echo.
echo   Press Ctrl+C to stop.
echo.

call "%MVN_CMD%" spring-boot:run

endlocal
