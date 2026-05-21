@echo off
chcp 65001 >nul
echo ========================================
echo   Building sift_match.exe
echo ========================================

:: Setup MSVC environment
call "C:\Program Files\Microsoft Visual Studio\18\Insiders\VC\Auxiliary\Build\vcvars64.bat" >nul
set _VC_ERR=%ERRORLEVEL%
cd /d "%~dp0"
if %_VC_ERR% neq 0 (
    echo [ERROR] vcvars64.bat failed
    exit /b 1
)

set "OPENCV_ROOT=D:\Documents\environment\opencv-4.10.0\opencv\build"
set "ZLIB_ROOT=D:\Documents\environment\zlib-1.3.1"
set "OUTPUT=RocoMapTracker-sift_match.exe"

echo.
echo Compiling sift_match_main.cpp ...
echo OpenCV: %OPENCV_ROOT%
echo Output: %OUTPUT%
echo.

rc /nologo /fo resource.res resource.rc

cl /std:c++17 /utf-8 /O2 /EHsc /arch:AVX2 ^
   /I"%OPENCV_ROOT%\include" ^
   /I"%ZLIB_ROOT%\include" ^
   /Fe:"%OUTPUT%" ^
   sift_match_main.cpp resource.res ^
   /link ^
   /LIBPATH:"%OPENCV_ROOT%\x64\vc16\lib" ^
   /LIBPATH:"%ZLIB_ROOT%\lib" ^
   opencv_world4100.lib ^
   zlib.lib ^
   ws2_32.lib ^
   /SUBSYSTEM:CONSOLE

if %ERRORLEVEL% equ 0 (
    echo.
    echo ========================================
    echo   BUILD SUCCESS
    echo ========================================
    echo Copying to resources...
    copy /y "%OUTPUT%" "..\roco-ui\src\main\resources\sift\%OUTPUT%" >nul
    copy /y "%OPENCV_ROOT%\x64\vc16\bin\opencv_world4100.dll" "..\roco-ui\src\main\resources\sift\opencv_world4100.dll" >nul
    echo Done.
    echo ========================================
    exit /b 0
) else (
    echo.
    echo ========================================
    echo   BUILD FAILED
    echo ========================================
    exit /b 1
)

pause
exit /b 0
