@echo off
cls

echo ==============================
echo Step 3: Copy config to project
echo ==============================

:: 确保目标目录存在
if not exist "roco-ui\src\main\resources\META-INF\native-image" (
    mkdir "roco-ui\src\main\resources\META-INF\native-image"
)

:: 复制 agent 生成的配置到 roco-ui 模块
xcopy /y /e "target\native-agent\*" "roco-ui\src\main\resources\META-INF\native-image\"

echo.
echo Config copied successfully!
echo Target: roco-ui\src\main\resources\META-INF\native-image\
pause
