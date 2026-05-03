@echo off
chcp 65001 >nul
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

if not exist "default.iprof" (
    echo [ERROR] default.iprof NOT found in project root!
    echo Please run 6^^、run-pgo.bat first to generate PGO data.
    echo.
    pause
    exit /b 1
)

echo PGO profile found. Building optimized native image...
%MAVEN_BIN% clean native:compile -Pnative-pgo -DskipTests

echo.
echo ======================================
echo PGO Optimized EXE Build Completed!
echo File: target/RocoMapTracker.exe
echo.
echo Performance improvement: ~15-30%% over non-PGO build
echo ======================================

pause
