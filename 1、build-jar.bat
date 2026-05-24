@echo off
chcp 65001 >nul
cls
echo ==============================
echo Step 1: Build JAR file
echo ==============================

mvn clean package -pl roco-ui -am -DskipTests

echo.
echo JAR build completed!
pause
