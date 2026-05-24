@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cls

set "COUNTER_FILE=%~dp0.pgo_run_count"
if not exist "%COUNTER_FILE%" echo 0 > "%COUNTER_FILE%"
set /p RUN_COUNT=<"%COUNTER_FILE%"

set "PROFILE_DIR=%~dp0pgo_profiles"
if not exist "%PROFILE_DIR%" mkdir "%PROFILE_DIR%"

cd /d "%~dp0"

if not exist "roco-ui\target\RocoMapTracker-instrumented.exe" (
    echo [ERROR] Instrumented EXE not found!
    echo Please run 5、build-native-instrument.bat first.
    pause
    exit /b 1
)

:loop
set /a RUN_COUNT+=1
echo !RUN_COUNT! > "%COUNTER_FILE%"

set "PROFILE_FILE=%PROFILE_DIR%\run_!RUN_COUNT!.iprof"

echo 统计已有数据
set TOTAL_SIZE=0
for %%A in ("%PROFILE_DIR%\*.iprof") do set /a TOTAL_SIZE+=%%~zA
set /a TOTAL_KB=%TOTAL_SIZE% / 1024

echo ==============================================
echo Step 6: PGO Data Collection - Run #!RUN_COUNT!
echo ==============================================
echo.
if !TOTAL_SIZE! GTR 0 (
    echo Accumulated PGO data: !TOTAL_KB! KB  (!RUN_COUNT! profiles)
) else (
    echo No PGO data collected yet.
)
echo Output: !PROFILE_FILE!
echo.
echo Tips:
echo   - Use core features: map matching, OCR, route editing
echo   - Close the app normally (Alt+F4) to flush profile data
echo   - Run 3-5 times for best coverage
echo ==============================================
echo.
echo Starting instrumented binary...
echo.

if exist "default.iprof" del /q "default.iprof"

roco-ui\target\RocoMapTracker-instrumented.exe -XX:ProfilesDumpFile="!PROFILE_FILE!"

echo.
echo ==============================================
echo Run #!RUN_COUNT! finished.
echo ==============================================

echo 兼容不支持 -XX:ProfilesDumpFile 的版本
if exist "default.iprof" (
    echo [INFO] Moving default.iprof to: !PROFILE_FILE!
    move /y "default.iprof" "!PROFILE_FILE!" >nul
)

if exist "!PROFILE_FILE!" (
    for %%A in ("!PROFILE_FILE!") do (
        set /a SIZE_KB=%%~zA / 1024
        echo [OK]  %%~nxA: !SIZE_KB! KB
    )
) else (
    echo [WARN] Profile file NOT generated!
    echo Make sure you closed the app normally (Alt+F4).
)

echo.
echo ==============================================
echo Profiles collected so far:
dir /b "%PROFILE_DIR%\run_*.iprof" 2>nul
echo ==============================================
echo.
choice /c ynm /m "Continue collecting? (Y=Yes, N=No, M=Merge now)"
if errorlevel 3 goto merge
if errorlevel 2 goto merge
echo.
goto loop

:merge
echo.
echo ==============================================
echo Merging !RUN_COUNT! profiles into default.iprof...
echo ==============================================

set "INPUT_ARGS="
for %%f in ("%PROFILE_DIR%\run_*.iprof") do (
    set "INPUT_ARGS=!INPUT_ARGS! --input-file=%%f"
    echo   %%~nxf
)

echo Using native-image-configure merge...
    native-image-configure merge !INPUT_ARGS! --output-file="%~dp0default.iprof" 2>&1
    if !ERRORLEVEL! EQU 0 (
        if exist "%~dp0default.iprof" (
            echo [OK] Merged to: default.iprof
            for %%A in ("%~dp0default.iprof") do echo Size: %%~zA bytes
            goto done
        )
    )
    echo [WARN] merge tool failed, profiles will be passed individually to build.

echo 回退：保留独立文件，build 脚本会合并传参
echo.
echo [INFO] Profiles remain in %PROFILE_DIR%\
echo Build script will pass all files to native-image via --pgo=file1,file2,...

:done
echo.
echo ==============================================
echo PGO data collection complete (!RUN_COUNT! runs).
echo.
echo Next: Run 7、build-native-pgo.bat to build optimized EXE
echo ==============================================
pause
endlocal
