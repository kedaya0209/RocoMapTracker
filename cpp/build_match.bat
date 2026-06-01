@echo off
chcp 65001 >nul
echo ========================================
echo   Building match.exe
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

:: Dependencies: use local copies, auto-download and build from source if missing
set "OPENCV_VERSION=4.10.0"
set "OPENCV_SRC=%~dp0opencv-%OPENCV_VERSION%"
set "OPENCV_ROOT=%~dp0opencv-%OPENCV_VERSION%\install"
set "ZLIB_ROOT=%~dp0zlib-1.3.1"
set "OUTPUT=RocoMapTracker-match.exe"

:: ---- OpenCV (build from source) ----
if exist "%OPENCV_ROOT%\include\opencv2\opencv.hpp" (
    echo OpenCV found: %OPENCV_ROOT%
    goto :opencv_ok
)
echo [Auto] OpenCV not found, downloading opencv-%OPENCV_VERSION% source ...
curl -fSL --ssl-no-revoke -o "%~dp0opencv-%OPENCV_VERSION%.zip" "https://ghfast.top/https://github.com/opencv/opencv/archive/refs/tags/%OPENCV_VERSION%.zip"
if not exist "%~dp0opencv-%OPENCV_VERSION%.zip" (
    echo [ERROR] Download failed. Please download manually:
    echo   https://github.com/opencv/opencv/archive/refs/tags/%OPENCV_VERSION%.zip
    echo   and extract to: %OPENCV_SRC%
    exit /b 1
)
echo [Auto] Extracting OpenCV source ...
powershell -Command "Expand-Archive -Path '%~dp0opencv-%OPENCV_VERSION%.zip' -DestinationPath '%~dp0' -Force" >nul
del /q "%~dp0opencv-%OPENCV_VERSION%.zip" 2>nul

echo [Auto] Building OpenCV with CMake + NMake (this takes 5-10 minutes) ...
pushd "%OPENCV_SRC%"
if not exist build mkdir build
cd build
cmake -G "NMake Makefiles" -DCMAKE_BUILD_TYPE=Release ^
    -DCMAKE_INSTALL_PREFIX="%OPENCV_ROOT%" ^
    -DBUILD_SHARED_LIBS=ON ^
    -DBUILD_LIST=core,imgproc,features2d,flann,calib3d ^
    -DBUILD_opencv_python2=OFF -DBUILD_opencv_python3=OFF ^
    -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_EXAMPLES=OFF ^
    -DBUILD_DOCS=OFF -DBUILD_opencv_apps=OFF ^
    -DWITH_FFMPEG=OFF -DWITH_GTK=OFF -DWITH_V4L=OFF ^
    -DWITH_CUDA=OFF -DWITH_OPENCL=OFF -DWITH_QUIRC=OFF -DWITH_IPP=OFF ^
    -DBUILD_PNG=OFF -DBUILD_JPEG=OFF -DBUILD_TIFF=OFF -DBUILD_WEBP=OFF ^
    -DBUILD_OPENJPEG=OFF -DBUILD_ZLIB=OFF ^
    ..
if errorlevel 1 (
    echo [ERROR] CMake configure failed
    popd
    exit /b 1
)
nmake /NOLOGO
if errorlevel 1 (
    echo [ERROR] OpenCV build failed
    popd
    exit /b 1
)
nmake install
if errorlevel 1 (
    echo [ERROR] OpenCV install failed
    popd
    exit /b 1
)
popd
if not exist "%OPENCV_ROOT%\include\opencv2\opencv.hpp" (
    echo [ERROR] Build succeeded but install failed: %OPENCV_ROOT%
    exit /b 1
)
echo [Auto] OpenCV ready: %OPENCV_ROOT%
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
echo Compiling match_main.cpp ...
echo OpenCV: %OPENCV_ROOT%
echo zlib  : %ZLIB_ROOT%
echo Output: %OUTPUT%
echo.

rc /nologo /fo resource.res resource.rc

cl /std:c++17 /utf-8 /O2 /EHsc /arch:AVX2 ^
   /I"%OPENCV_ROOT%\include" ^
   /I"%ZLIB_ROOT%\include" ^
   /Fe:"%OUTPUT%" ^
   match_main.cpp sift_matcher.cpp match_common.cpp resource.res ^
   /link ^
   /OPT:REF ^
   /LIBPATH:"%OPENCV_ROOT%\lib" ^
   /LIBPATH:"%ZLIB_ROOT%\lib" ^
   opencv_core4100.lib ^
   opencv_imgproc4100.lib ^
   opencv_features2d4100.lib ^
   opencv_flann4100.lib ^
   opencv_calib3d4100.lib ^
   zlib.lib ^
   ws2_32.lib ^
   /SUBSYSTEM:CONSOLE

if %ERRORLEVEL% equ 0 (
    echo.
    echo ========================================
    echo   BUILD SUCCESS
    echo ========================================
    echo Copying to resources...
    copy /y "%OUTPUT%" "..\roco-ui\src\main\resources\match\%OUTPUT%" >nul
    for %%D in (core imgproc features2d flann calib3d) do (
        copy /y "%OPENCV_ROOT%\bin\opencv_%%D4100.dll" "..\roco-ui\src\main\resources\match\opencv_%%D4100.dll" >nul
    )
    if defined VCToolsRedistDir (
        for %%D in (concrt140 msvcp140 msvcp140_1 msvcp140_2 msvcp140_atomic_wait vcruntime140 vcruntime140_1 vcruntime140_threads) do (
            if exist "%VCToolsRedistDir%x64\Microsoft.VC145.CRT\%%D.dll" (
                copy /y "%VCToolsRedistDir%x64\Microsoft.VC145.CRT\%%D.dll" "..\roco-ui\src\main\resources\match\%%D.dll" >nul
            )
        )
    )
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
