@echo off
chcp 65001 >nul
echo ========================================
echo   Building wgc_capture.dll (C++ WGC)
echo ========================================

:: Setup MSVC environment
call "C:\Program Files\Microsoft Visual Studio\18\Insiders\VC\Auxiliary\Build\vcvars64.bat" >nul
set _VC_ERR=%ERRORLEVEL%
cd /d "%~dp0"
if %_VC_ERR% neq 0 (
    echo [ERROR] vcvars64.bat failed
    exit /b 1
)

:: Output to source file directory
set "OUTPUT=RocoMapTracker-wgc_capture.dll"

echo.
echo Compiling wgc_capture.cpp ...
echo Output: %OUTPUT%
echo.

cl /std:c++17 /O2 /LD /EHsc ^
   /Fe:"%OUTPUT%" ^
   wgc_capture.cpp ^
   d3d11.lib dxgi.lib windowsapp.lib runtimeobject.lib user32.lib

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
