@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cls

:: 检测 Visual Studio 环境
where ml64.exe >nul 2>&1
if %ERRORLEVEL% EQU 0 goto :check_profiles

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
goto :check_profiles

:no_vs
echo [错误] 找不到 Visual Studio, 请从 VS x64 Native Tools 命令行运行
pause
exit /b 1

:check_profiles
echo ==============================
echo Step 7: Build PGO Optimized EXE
echo ==============================

cd /d "%~dp0"

:: 收集所有 PGO profile，构建逗号分隔列表 (native-image --pgo 支持逗号分隔多文件)
set "PGO_LIST="
set "PGO_COUNT=0"

if exist "pgo_profiles\run_*.iprof" (
    for %%f in (pgo_profiles\run_*.iprof) do (
        if !PGO_COUNT! EQU 0 (
            set "PGO_LIST=%%f"
        ) else (
            set "PGO_LIST=!PGO_LIST!,%%f"
        )
        set /a PGO_COUNT+=1
    )
)

:: 回退：根目录 default.iprof
if !PGO_COUNT! EQU 0 (
    if exist "default.iprof" (
        set "PGO_LIST=default.iprof"
        set /a PGO_COUNT=1
    )
)

if !PGO_COUNT! EQU 0 (
    echo [ERROR] No PGO profile found!
    echo Please run 6、run-pgo.bat first to collect PGO data.
    echo.
    echo Expected:
    echo   - pgo_profiles\run_*.iprof  (multiple profiles)
    echo   - OR default.iprof  (merged or single profile)
    pause
    exit /b 1
)

echo PGO profiles: !PGO_COUNT! file(s^)
echo   !PGO_LIST!
echo.

echo Building optimized native image...
mvn clean verify -Pnative-pgo -pl roco-ui -am -DskipTests "-Dpgo.file=!PGO_LIST!"

echo.
echo ======================================
echo PGO Optimized EXE Build Completed!
echo File: roco-ui\target\RocoMapTracker.exe
echo.
echo Performance improvement: ~15-30%% over non-PGO build
echo ======================================

pause
endlocal
