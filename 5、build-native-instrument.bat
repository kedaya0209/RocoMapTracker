@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cls

:: 检测 Visual Studio 环境
where ml64.exe >nul 2>&1
if %ERRORLEVEL% EQU 0 goto :build

set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" goto :no_vs

for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -property installationPath`) do set "VS_DIR=%%i"
if not defined VS_DIR goto :no_vs

call "!VS_DIR!\VC\Auxiliary\Build\vcvarsall.bat" x64
if !ERRORLEVEL! neq 0 (
    echo [错误] VS 环境初始化失败
    pause
    exit /b 1
)
goto :build

:no_vs
echo [错误] 找不到 Visual Studio, 请从 VS x64 Native Tools 命令行运行
pause
exit /b 1

:build
echo ==============================
echo Step 5: Build PGO Instrumented EXE
echo ==============================

mvn clean verify -Pnative-instrument -pl roco-ui -am -DskipTests

echo.
echo ======================================
echo Instrumented EXE Build Completed!
echo File: roco-ui\target\RocoMapTracker-instrumented.exe
echo Next: Run 6、run-pgo.bat to collect PGO data
echo ======================================

pause
endlocal
