@echo off
chcp 65001
cls

set VCVARS_PATH="C:\Program Files\Microsoft Visual Studio\18\Insiders\VC\Auxiliary\Build\vcvarsall.bat"
call %VCVARS_PATH% x64

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
