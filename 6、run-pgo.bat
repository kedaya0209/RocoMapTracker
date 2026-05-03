@echo off
chcp 65001 >nul
cls

echo ==============================================
echo Step 6: Run Instrumented EXE to Collect PGO Data
echo ==============================================
echo.
echo IMPORTANT:
echo   1. Use the app normally for 10-15 minutes
echo   2. Cover all major scenarios (map matching, OCR, etc.)
echo   3. Close the app normally (do NOT kill the process)
echo   4. default.iprof will be generated in the EXE directory
echo ==============================================
echo.
echo Starting instrumented binary...
echo.

cd /d "%~dp0"
target\RocoMapTracker-instrumented.exe

echo.
echo ==============================================
echo App exited. Checking for PGO data file...
echo ==============================================

if exist "default.iprof" (
    echo [OK] default.iprof generated: %~dp0default.iprof
    echo Size:
    for %%A in ("default.iprof") do echo %%~zA bytes
) else (
    echo [WARN] default.iprof NOT found!
    echo Make sure you closed the app normally (Alt+F4 / window close button)
    echo The profile file is written on clean shutdown.
)

echo.
echo Next: Run 7^^、build-native-pgo.bat to build the final optimized EXE
pause
