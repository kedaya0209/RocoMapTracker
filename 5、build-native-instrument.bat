@echo off
chcp 65001 >nul
cls

set "VCVARS_PATH=C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat"
if not exist "%VCVARS_PATH%" (
    echo [错误] 找不到 vcvarsall.bat: %VCVARS_PATH%
    pause
    exit /b 1
)
call "%VCVARS_PATH%" x64
if %ERRORLEVEL% neq 0 (
    echo [错误] VS 环境初始化失败
    pause
    exit /b 1
)

echo ==============================
echo Step 5: Build PGO Instrumented EXE
echo ==============================

mvn clean package -Pnative-instrument -pl roco-ui -am -DskipTests

echo.
echo ======================================
echo Instrumented EXE Build Completed!
echo File: roco-ui\target\RocoMapTracker-instrumented.exe
echo Next: Run 6、run-pgo.bat to collect PGO data
echo ======================================

pause
