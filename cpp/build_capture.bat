@echo off
chcp 65001 >nul
echo ========================================
echo   Building capture.exe (Socket Mode)
echo ========================================

:: Setup MSVC environment
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat" >nul
set _VC_ERR=%ERRORLEVEL%
cd /d "%~dp0"
if %_VC_ERR% neq 0 (
    echo [ERROR] vcvars64.bat failed
    exit /b 1
)

set "OUTPUT=RocoMapTracker-capture.exe"

echo.
echo Compiling capture_main.cpp ...
echo Output: %OUTPUT%
echo.

rc /nologo /fo resource.res resource.rc

cl /std:c++17 /O2 /EHsc /arch:AVX2 ^
   /Fe:"%OUTPUT%" ^
   capture_main.cpp resource.res ^
   d3d11.lib dxgi.lib windowsapp.lib runtimeobject.lib user32.lib ws2_32.lib ^
   /link /SUBSYSTEM:CONSOLE

if %ERRORLEVEL% equ 0 (
    echo.
    echo ========================================
    echo   BUILD SUCCESS
    echo ========================================
) else (
    echo.
    echo ========================================
    echo   BUILD FAILED
    echo ========================================
)

pause
exit /b 0
