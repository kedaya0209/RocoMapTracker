@echo off
chcp 65001 >nul
cls

echo 1. 确保输出目录存在
set "CONFIG_DIR=roco-ui\target\native-agent"
if not exist "%CONFIG_DIR%" mkdir "%CONFIG_DIR%"

echo ==============================================
echo  正在启动 RocoMapTracker 并收集反射配置
echo  [已开启定时刷盘] 每 10 秒自动保存一次
echo ==============================================

echo 2. 查找 fat jar（支持版本号变更）
for %%f in (roco-ui\target\roco-ui-*-jar-with-dependencies.jar) do set "JAR_FILE=%%f"

if not defined JAR_FILE (
    echo [ERROR] 未找到 fat jar，请先运行 1、build-jar.bat
    pause
    exit /b 1
)

echo 使用: %JAR_FILE%
java "-agentlib:native-image-agent=config-merge-dir=%CONFIG_DIR%,experimental-class-loader-support,config-write-period-secs=10,config-write-initial-delay-secs=5" -jar "%JAR_FILE%"

echo.
echo ==============================================
echo 程序已退出，正在执行最后同步...
timeout /t 3 >nul

echo 配置已生成在: %CONFIG_DIR%
pause
