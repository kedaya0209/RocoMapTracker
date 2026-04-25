@echo off
chcp 65001 >nul
cls

set GRAALVM_HOME=D:\Documents\environment\java\graalvm-win\graalvm-jdk-21.0.11+9.1
set JAVA_HOME=%GRAALVM_HOME%
set PATH=%GRAALVM_HOME%\bin;%PATH%

set VCVARS_PATH="C:\Program Files\Microsoft Visual Studio\18\Insiders\VC\Auxiliary\Build\vcvarsall.bat"
call %VCVARS_PATH% x64

set MAVEN_BIN=%~dp0temp-maven\apache-maven-3.9.6\bin\mvn.cmd

echo ==============================
echo Step 1: Build JAR file
echo ==============================

%MAVEN_BIN% clean package -DskipTests

echo.
echo JAR build completed!
pause