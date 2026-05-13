@echo off
chcp 936 >nul
title PhoneMirror Windows Build Tool
echo ==========================================
echo    PhoneMirror Windows Build Tool
echo ==========================================
echo.

REM Check Node.js
node -v >nul 2>&1
if errorlevel 1 (
    echo [Error] Node.js not found. Please install Node.js 18+
    echo.
    echo Download: https://nodejs.org/
    pause
    exit /b 1
)

echo [1/5] Checking Node.js version...
node -v
echo.

REM Enter project directory
cd /d "%~dp0"

echo [2/5] Cleaning old files...
if exist node_modules rmdir /s /q node_modules
if exist package-lock.json del package-lock.json
if exist dist rmdir /s /q dist
echo [Done] Cleaned
echo.

echo [3/5] Setting npm mirror and installing...
call npm config set registry https://registry.npmmirror.com
call npm install
if errorlevel 1 (
    echo [Error] npm install failed
    echo.
    echo Try: npm install --legacy-peer-deps
    pause
    exit /b 1
)
echo [Done] Dependencies installed
echo.

echo [4/5] Building Windows EXE...
call npm run build:win
if errorlevel 1 (
    echo.
    echo [Error] Build failed!
    echo.
    echo Common fixes:
    echo   1. Run as Administrator
    echo   2. Check network connection
    echo   3. Delete node_modules and retry
    pause
    exit /b 1
)

echo.
echo [5/5] Build completed!
echo.
echo ==========================================
echo    Output files:
echo ==========================================
echo.
if exist "dist\PhoneMirror Setup 1.0.0.exe" (
    echo [Installer] dist\PhoneMirror Setup 1.0.0.exe
)
if exist "dist\PhoneMirror-Portable-1.0.0.exe" (
    echo [Portable]  dist\PhoneMirror-Portable-1.0.0.exe
)
echo.
pause
