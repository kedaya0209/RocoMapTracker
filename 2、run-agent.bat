@echo off
chcp 65001 >nul
cls

set GRAALVM_HOME=D:\Documents\environment\java\graalvm-win\graalvm-jdk-25.0.2+10.1
set JAVA_HOME=%GRAALVM_HOME%
set PATH=%GRAALVM_HOME%\bin;%PATH%

echo ==============================================
echo  正在启动程序并收集反射配置
echo  使用完成后，请正常关闭程序窗口
echo  不要直接关闭黑窗口！
echo ==============================================

java -agentlib:native-image-agent=config-output-dir=target/native-agent,experimental-class-loader-support -jar target/RocoMapTracker-1.0.0-jar-with-dependencies.jar

echo.
echo 程序已退出，正在清理残留进程...
taskkill /f /im java.exe >nul 2>&1
taskkill /f /im javaw.exe >nul 2>&1

echo 配置已生成！
pause