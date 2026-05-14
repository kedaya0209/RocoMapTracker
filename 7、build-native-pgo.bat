@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cls

set GRAALVM_HOME=D:\Documents\environment\java\graalvm-win\graalvm-jdk-25.0.2+10.1
set JAVA_HOME=%GRAALVM_HOME%
set PATH=%GRAALVM_HOME%\bin;%PATH%

set VCVARS_PATH="C:\Program Files\Microsoft Visual Studio\18\Insiders\VC\Auxiliary\Build\vcvarsall.bat"
call %VCVARS_PATH% x64

set MAVEN_BIN=D:\Documents\environment\apache\apache-maven-3.9.4\bin\mvn.cmd

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
%MAVEN_BIN% clean package -Pnative-pgo -pl roco-ui -am -DskipTests "-Dpgo.file=!PGO_LIST!"

echo.
echo ======================================
echo PGO Optimized EXE Build Completed!
echo File: roco-ui\target\RocoMapTracker.exe
echo.
echo Performance improvement: ~15-30%% over non-PGO build
echo ======================================

pause
endlocal
