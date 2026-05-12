@echo off
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

%MAVEN_BIN% clean native:compile -Pnative-instrument -pl roco-ui -am -DskipTests

echo.
echo ======================================
echo Instrumented EXE Build Completed!
echo File: roco-ui\target\RocoMapTracker-instrumented.exe
echo Next: Run 6、run-pgo.bat to collect PGO data
echo ======================================

pause
