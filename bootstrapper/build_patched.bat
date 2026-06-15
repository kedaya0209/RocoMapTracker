@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

rem build_patched.bat - PE post-processing for single-file distribution
rem Usage: build_patched.bat <engine-dir>
rem   engine-dir : dir with RocoMapTracker.engine.exe + classes\javafx-dll\*.dll
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
if not exist "%ENGINE_DIR%\classes\javafx-dll\vcruntime140.dll" (
    echo ERROR: vcruntime140.dll not found at "%ENGINE_DIR%\classes\javafx-dll\"
    popd
    exit /b 1
)

copy /Y "%ENGINE_DIR%\RocoMapTracker.engine.exe" "engine.exe" >nul || exit /b 1
copy /Y "%ENGINE_DIR%\classes\javafx-dll\vcruntime140.dll" "vcruntime140.dll" >nul || exit /b 1
copy /Y "%ENGINE_DIR%\classes\javafx-dll\vcruntime140_1.dll" "vcruntime140_1.dll" >nul || exit /b 1
echo Files staged.
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
echo loader_stub.obj created.
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
.\pe_patch.exe ^
    --engine engine.exe ^
    --output "%ENGINE_DIR%\RocoMapTracker.exe" ^
    --vcr140 vcruntime140.dll ^
    --vcr140_1 vcruntime140_1.dll ^
    --stub loader_stub.obj

if %ERRORLEVEL% NEQ 0 (
    echo ERROR: PE patching failed
    popd
    exit /b %ERRORLEVEL%
)
echo.

echo [6] PE patching completed successfully.

rem ---- Cleanup staged files ----
del engine.exe vcruntime140.dll vcruntime140_1.dll pe_patch.exe pe_patch.obj loader_stub.obj 2>nul

echo.
echo DONE: "%ENGINE_DIR%\RocoMapTracker.exe" created
popd
exit /b 0
