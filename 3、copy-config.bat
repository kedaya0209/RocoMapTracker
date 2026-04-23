@echo off
chcp 65001 >nul
cls

echo ==============================
echo Step 3: Copy config to project
echo ==============================

mkdir src/main/resources/META-INF/native 2>nul
xcopy /y /e target/native-agent\* src/main/resources/META-INF\native-image\

echo.
echo Config copied successfully!
pause