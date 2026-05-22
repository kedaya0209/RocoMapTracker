@echo off
chcp 65001 >nul
echo ========================================
echo   Building sift_match.exe
echo ========================================

:: Ensure vswhere.exe is on PATH (needed by vcvars64.bat)
set "PATH=%PATH%;C:\Program Files (x86)\Microsoft Visual Studio\Installer"

:: Setup MSVC environment
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat" >nul
set _VC_ERR=%ERRORLEVEL%
cd /d "%~dp0"
if %_VC_ERR% neq 0 (
    echo [ERROR] vcvars64.bat failed
    exit /b 1
)

:: Dependencies: use local copies, auto-download if missing
set "OPENCV_ROOT=%~dp0opencv-4.10.0\opencv\build"
set "ZLIB_ROOT=%~dp0zlib-1.3.1"
set "OUTPUT=RocoMapTracker-sift_match.exe"

:: ---- OpenCV ----
if exist "%OPENCV_ROOT%\include\opencv2\opencv.hpp" (
    echo OpenCV found: %OPENCV_ROOT%
    goto :opencv_ok
)
echo [Auto] OpenCV not found, downloading opencv-4.10.0-windows.exe ...
curl -fSL --ssl-no-revoke -o "%~dp0opencv-4.10.0-windows.exe" "https://ghfast.top/https://github.com/opencv/opencv/releases/download/4.10.0/opencv-4.10.0-windows.exe"
if not exist "%~dp0opencv-4.10.0-windows.exe" (
    echo [ERROR] Download failed. Please download manually:
    echo   https://github.com/opencv/opencv/releases/download/4.10.0/opencv-4.10.0-windows.exe
    echo   and extract to: %~dp0opencv-4.10.0
    exit /b 1
)
echo [Auto] Extracting OpenCV (this may take a moment) ...
"%~dp0opencv-4.10.0-windows.exe" -o"%~dp0opencv-4.10.0" -y >nul
del /q "%~dp0opencv-4.10.0-windows.exe" 2>nul
if not exist "%OPENCV_ROOT%\include\opencv2\opencv.hpp" (
    echo [ERROR] Extraction failed: %OPENCV_ROOT%
    exit /b 1
)
echo [Auto] OpenCV ready.
:opencv_ok

:: ---- zlib ----
if exist "%ZLIB_ROOT%\include\zlib.h" (
    echo zlib found: %ZLIB_ROOT%
    goto :zlib_ok
)
echo [Auto] zlib not found, downloading zlib-1.3.1 ...
curl -fSL --ssl-no-revoke -o "%~dp0zlib-1.3.1.tar.gz" "https://ghfast.top/https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz"
if not exist "%~dp0zlib-1.3.1.tar.gz" (
    echo [ERROR] Download failed. Please download manually:
    echo   https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz
    echo   and extract to: %~dp0zlib-1.3.1
    exit /b 1
)
echo [Auto] Extracting zlib ...
cd /d "%~dp0"
tar -xzf zlib-1.3.1.tar.gz
del /q zlib-1.3.1.tar.gz 2>nul

echo [Auto] Compiling zlib with MSVC ...
pushd "%ZLIB_ROOT%"
nmake -f win32\Makefile.msc zlib.lib >nul
if errorlevel 1 (
    echo [ERROR] zlib compilation failed
    popd
    exit /b 1
)
:: Create include/lib layout expected by the build
if not exist include mkdir include
if not exist lib mkdir lib
copy /y zlib.h include\ >nul
copy /y zconf.h include\ >nul
copy /y zlib.lib lib\ >nul
popd
echo [Auto] zlib ready.
:zlib_ok

echo.
echo Compiling sift_match_main.cpp ...
echo OpenCV: %OPENCV_ROOT%
echo zlib  : %ZLIB_ROOT%
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
