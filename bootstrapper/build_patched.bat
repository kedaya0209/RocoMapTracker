@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

rem build_patched.bat - PE post-processing for single-file distribution (v3)
rem Usage: build_patched.bat [engine-dir]
rem   engine-dir : dir with RocoMapTracker.engine.exe and classes/ subdirs
rem                (default: ..\roco-ui\target)
rem Output: <engine-dir>\RocoMapTracker.exe

set "SCRIPT_DIR=%~dp0"
set "ENGINE_DIR=%~1"
if "%ENGINE_DIR%"=="" set "ENGINE_DIR=%SCRIPT_DIR%..\roco-ui\target"
pushd "%SCRIPT_DIR%" || exit /b 1

echo [1] Staging files...
if not exist "%ENGINE_DIR%\RocoMapTracker.engine.exe" (
    echo ERROR: Engine not found at "%ENGINE_DIR%\RocoMapTracker.engine.exe"
    popd
    exit /b 1
)

rem Verify DLL source directories exist
set "JVFXDLL=%ENGINE_DIR%\classes\javafx-dll"
set "SHDLL=%ENGINE_DIR%\classes\dll"
if not exist "%JVFXDLL%\glass.dll" (
    echo ERROR: JavaFX DLLs not found at "%JVFXDLL%"
    echo        Run 'mvn -Pnative package -pl roco-ui -am' first
    popd
    exit /b 1
)
if not exist "%SHDLL%\jvm.dll" (
    echo ERROR: JVM shim DLLs not found at "%SHDLL%"
    echo        Run 'mvn -Pnative package -pl roco-ui -am' first
    popd
    exit /b 1
)

copy /Y "%ENGINE_DIR%\RocoMapTracker.engine.exe" "engine.exe" >nul || exit /b 1
echo Engine staged.
echo.

rem ---- Detect Visual Studio environment ----
where ml64.exe >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo [2] MSVC tools already in PATH, using existing environment
    goto :compile
)

echo [2] Looking for Visual Studio...
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" goto :no_vs

for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -property installationPath`) do set "VS_DIR=%%i"
if not defined VS_DIR goto :no_vs

echo Found VS at: !VS_DIR!
call "!VS_DIR!\VC\Auxiliary\Build\vcvarsall.bat" x64
if !ERRORLEVEL! NEQ 0 (
    echo ERROR: VS environment initialization failed
    popd
    exit /b 1
)
goto :compile

:no_vs
echo ERROR: Cannot find Visual Studio installation
echo Please run from a Visual Studio x64 Native Tools Command Prompt
popd
exit /b 1

:compile
echo VS environment ready.
echo.

rem ---- Compile loader_stub.asm -> loader_stub.obj ----
echo [3] Compiling loader_stub.asm...
ml64.exe /nologo /c /Fo loader_stub.obj loader_stub.asm
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: MASM compilation failed
    popd
    exit /b %ERRORLEVEL%
)
echo loader_stub.obj compiled.
echo.

rem ---- Compile pe_patch.c -> pe_patch.exe ----
echo [4] Compiling pe_patch.c...
cl.exe /nologo /O1 /GS- /Fepe_patch.exe pe_patch.c
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: pe_patch compilation failed
    popd
    exit /b %ERRORLEVEL%
)
echo pe_patch.exe created.
echo.

rem ---- Run pe_patch to produce final exe ----
echo [5] Running PE patcher...

rem Build --embed arguments for all DLLs
set "EMBED_ARGS="

rem VC++ runtime (from javafx-dll/ — pe_patch auto-detects IAT patching)
set "EMBED_ARGS=!EMBED_ARGS! --embed vcruntime140.dll=%JVFXDLL%\vcruntime140.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed vcruntime140_1.dll=%JVFXDLL%\vcruntime140_1.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed msvcp140.dll=%JVFXDLL%\msvcp140.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed msvcp140_1.dll=%JVFXDLL%\msvcp140_1.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed msvcp140_2.dll=%JVFXDLL%\msvcp140_2.dll"

rem JavaFX DLLs (from javafx-dll/) — prism_common first (other prism DLLs depend on it)
set "EMBED_ARGS=!EMBED_ARGS! --embed prism_common.dll=%JVFXDLL%\prism_common.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed prism_d3d.dll=%JVFXDLL%\prism_d3d.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed prism_sw.dll=%JVFXDLL%\prism_sw.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed glass.dll=%JVFXDLL%\glass.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed decora_sse.dll=%JVFXDLL%\decora_sse.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed javafx_font.dll=%JVFXDLL%\javafx_font.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed javafx_iio.dll=%JVFXDLL%\javafx_iio.dll"

rem JVM shim DLLs (from dll/) — jvm.dll + java.dll first
set "EMBED_ARGS=!EMBED_ARGS! --embed jvm.dll=%SHDLL%\jvm.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed java.dll=%SHDLL%\java.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed awt.dll=%SHDLL%\awt.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed jawt.dll=%SHDLL%\jawt.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed fontmanager.dll=%SHDLL%\fontmanager.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed freetype.dll=%SHDLL%\freetype.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed javaaccessbridge.dll=%SHDLL%\javaaccessbridge.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed javajpeg.dll=%SHDLL%\javajpeg.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed lcms.dll=%SHDLL%\lcms.dll"
set "EMBED_ARGS=!EMBED_ARGS! --embed jniframe.dll=%SHDLL%\jniframe.dll"

echo   DLL sources:
echo     VC++:   %JVFXDLL%
echo     JavaFX: %JVFXDLL%
echo     JVM:    %SHDLL%
echo.

.\pe_patch.exe --engine engine.exe --output "%ENGINE_DIR%\RocoMapTracker.exe" --stub loader_stub.obj !EMBED_ARGS!

if %ERRORLEVEL% NEQ 0 (
    echo ERROR: PE patching failed
    popd
    exit /b %ERRORLEVEL%
)
echo.

echo [6] PE patching completed successfully.

rem ---- Cleanup staged files ----
del engine.exe pe_patch.exe pe_patch.obj loader_stub.obj 2>nul

echo.
echo DONE: "%ENGINE_DIR%\RocoMapTracker.exe" created
popd
exit /b 0
