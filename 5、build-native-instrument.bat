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
echo Step 5: Build PGO Instrumented EXE
echo ==============================

%MAVEN_BIN% clean native:compile -Pnative-instrument -DskipTests

echo.
echo ======================================
echo Instrumented EXE Build Completed!
echo File: target/RocoMapTracker-instrumented.exe
echo Next: Run this EXE to collect PGO data
echo ======================================

pause
