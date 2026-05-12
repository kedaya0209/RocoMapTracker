@echo off
cls

set GRAALVM_HOME=D:\Documents\environment\java\graalvm-win\graalvm-jdk-25.0.2+10.1
set JAVA_HOME=%GRAALVM_HOME%
set PATH=%GRAALVM_HOME%\bin;%PATH%

set VCVARS_PATH="C:\Program Files\Microsoft Visual Studio\18\Insiders\VC\Auxiliary\Build\vcvarsall.bat"
call %VCVARS_PATH% x64

set MAVEN_BIN=D:\Documents\environment\apache\apache-maven-3.9.4\bin\mvn.cmd

echo ==============================
echo Step 4: Build Native EXE
echo ==============================

%MAVEN_BIN% clean native:compile -Pnative -pl roco-ui -am -DskipTests

echo.
echo ======================================
echo EXE Build Completed!
echo File: roco-ui\target\RocoMapTracker.exe
echo ======================================

pause
