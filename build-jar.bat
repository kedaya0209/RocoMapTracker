@echo off
chcp 65001 >nul
cls

set GRAALVM_HOME=D:\Documents\environment\java\graalvm-win\graalvm-jdk-25.0.2+10.1
set MAVEN_HOME=C:\Users\tangh\Desktop\code\RocoMapTracker\temp-maven\apache-maven-3.9.6
set JAVA_HOME=%GRAALVM_HOME%
set PATH=%MAVEN_HOME%\bin;%PATH%

mvn clean package -DskipTests

pause