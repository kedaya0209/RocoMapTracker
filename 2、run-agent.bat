@echo off
chcp 65001 >nul
cls

:: 1. 环境路径设置
set "GRAALVM_HOME=D:\Documents\environment\java\graalvm-win\graalvm-jdk-25.0.2+10.1"
set "JAVA_HOME=%GRAALVM_HOME%"
set "PATH=%GRAALVM_HOME%\bin;%PATH%"

:: 2. 确保输出目录存在
set "CONFIG_DIR=target\native-agent"
if not exist "%CONFIG_DIR%" mkdir "%CONFIG_DIR%"

echo ==============================================
echo  正在启动 RocoMapTracker 并收集反射配置
echo  [已开启定时刷盘] 每 10 秒自动保存一次
echo ==============================================

:: 使用你发现的正确参数名：config-write-period-secs
java "-agentlib:native-image-agent=config-output-dir=%CONFIG_DIR%,experimental-class-loader-support,config-write-period-secs=10,config-write-initial-delay-secs=5" -jar "target/RocoMapTracker-1.0.0-jar-with-dependencies.jar"

echo.
echo ==============================================
echo 程序已退出，正在执行最后同步...
timeout /t 3 >nul

echo 配置已生成在: %CONFIG_DIR%
pause